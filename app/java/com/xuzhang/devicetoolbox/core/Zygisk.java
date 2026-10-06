package com.xuzhang.devicetoolbox.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Zygisk 原生伪装 —— 逐应用，做到「进程内 Java 与 native 完全自洽」。
 *
 * 和另外两条路的区别：
 *   · 开机模块（post-fs-data）：全设备一致，但必须重启，且改的是整机；
 *   · Xposed（**2026-10-06 已退役**）：只在目标进程里改 Java 层，
 *     native 用 __system_property_find 直接读内存，绕过不了 —— 这就是退役的原因；
 *   · 这里：目标进程里 Java 静态字段 + 属性区内存一起改，两条路同源。
 *
 * 配置不能放在模块进程里读（它跑在目标应用进程，没有 root，读不到 /data/adb），
 * 所以由本 App 写一份 /data/adb/devicetoolbox/zygisk.conf，模块通过 Zygisk 的
 * companion socket 向 root 侧进程要。
 *
 * 文件格式（每行两种形态）：
 *   包名<TAB>属性键<TAB>值      写入
 *   DEL<TAB>包名<TAB>属性键     删除（独立通道，值域里绝不会出现删除标记）
 */
public final class Zygisk {

    public static final String MODULE_ID = "devicetoolbox_zygisk";
    public static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;
    public static final String CONF = Scripts.DIR + "/zygisk.conf";
    /** 模块 .so 同时也是 App 的自检库（同一个二进制，两条路走同一份代码）。 */
    public static final String LIB = "libdtbprobe.so";

    private Zygisk() { }

    // ------------------------------------------------------------ 配置

    /**
     * 并发写配置会互相覆盖：A 读到列表 → B 改列表并写完配置 → A 才落盘，落下去的是 **A 的旧列表**。
     * 本 App 有两个写入方 —— 启动自愈（{@link Engine#warmBaseline} 修完存的目标顺手 sync）
     * 与界面动作（立即隐藏 / 取消全部隐藏）。实测就会撞上，表现是「列表里勾着应用、
     * zygisk.conf 却只剩头部（共 0 个应用）」——正是缺陷①的现场。
     * 所有写配置的路径都走这一把锁：读列表与落盘之间不再有窗口。
     */
    private static final Object WRITE_LOCK = new Object();

    /** 生成结果：文本 + 计数（列表里几个、真进了配置几个、有没有对不上的）。 */
    private static final class Conf {
        String text = "";
        int listed;      // 勾选列表里的应用数
        int props;       // 属性行数（写入通道）
        int dels;        // 删除行数（DEL 独立通道）
        int noTarget;    // 在列表里、却没有任何目标机型可用的应用数
        int noRealFp;    // 目标机型的指纹族不成立（没有真指纹 / 与身份字段对不上）的应用数
        int patchBumped; // 安全补丁取了较新值（本机基线值更新）的应用数
        String patchNew = "", patchOld = "";   // 便于结果里如实说明（新值 / 目标原值）
        final java.util.List<String> noRealFpPkgs = new java.util.ArrayList<>();
        final java.util.List<String> noRealFpWhy = new java.util.ArrayList<>();
    }

    private static Conf build(Context c) {
        // ★ 先加载基线：它会把宿主真实的补丁级别注入 `Target.devicePatch`，
        // 目标机型派生补丁日期时要优先沿用它（否则会退回「当月」，显得比宿主还新）。
        // 这一步必须**在取任何 Target 之前**做 —— 实测踩到：忘了这一步，
        // 配置里就还是当月那个值。
        Map<String, String> real = Engine.baseline(c);
        Conf r = new Conf();
        StringBuilder sb = new StringBuilder();
        sb.append("# 改机型工具箱 · Zygisk 逐应用伪装\n");
        sb.append("# 每行两种形态（由 App 自动生成，手改请保持制表符分隔）：\n");
        sb.append("#   包名<TAB>属性键<TAB>值      写入（**值域里不会出现删除标记**）\n");
        sb.append("#   DEL<TAB>包名<TAB>属性键     删除（独立通道，模块在目标进程的私有副本里真删）\n");
        List<String> pkgs = AppSpoof.enabled(c);
        r.listed = pkgs.size();
        Target global = new Store(c).target();
        for (String pkg : pkgs) {
            Target t = AppSpoof.targetFor(c, pkg);
            if (t == null) t = global;
            // ★ 没有可用目标机型时**不能悄悄跳过**：那正好写出「列表说在用、配置里没有它」的假一致
            //   （旧版这里直接 continue，而头部还按列表条数写「共 N 个应用」）。改成计数上报，
            //   由 write() 拒绝写入并如实报错 —— 调用方会把列表回滚。
            if (t == null) { r.noTarget++; continue; }
            t = t.copy();
            t.normalize();
            // ★ 指纹族自洽门（2026-10-06 设计定稿）：门只决定**构建身份族写不写**，
            //   **不拦机型族、不拦整份配置** —— 没有真指纹时照常写机型字段，
            //   构建身份族（指纹/id/incremental/display.id/description/版本/补丁/type/tags…）
            //   由 Props 的 identity 门**整族一个键都不碰**（不写、不删、不打删除标记）。
            //   于是目标进程保留真机自己那套构建身份，彼此一致（公开实现同款做法）。
            //   这里只把「谁没有真数据、为什么」记下来，写完之后在结果里**非阻断**地说明。
            // 安全补丁只前进不后退：记一下是否取了较新值（写出去的值由 Props.effectivePatch 决定）
            String patchWant = Props.effectivePatch(t, real);
            if (!patchWant.equals(t.patch)) {
                r.patchBumped++;
                r.patchNew = patchWant;
                r.patchOld = t.patch;
            }
            String conflict = t.familyConflict();
            if (!conflict.isEmpty()) {
                r.noRealFp++;
                r.noRealFpPkgs.add(pkg);
                r.noRealFpWhy.add(conflict);
            }
            // 传 real：appLevel 需要它来判断「哪些分区是共享镜像」从而不去覆盖
            // （真机上 system/vendor/product/system_ext 是 oplus/ossi 通用值，不是机型码）。
            Map<String, String> m = Props.appLevel(t, real);
            // 厂商专有键的同源派生（值里嵌着真机型码的那一批）。**只在指纹族成立时做**：
            // 没有真指纹的目标按定稿只改「型号名 + 市场名」——厂商串的派生/删除会让改动面
            // 从 4 条键扩到 70+ 条（还会在目标进程里留下一批缺键），与「只改型号名」的承诺
            // 不符，且对「两条指纹一致」毫无帮助（这些键都不进检测方的重组串）。
            if (t.familyOk()) m.putAll(Props.vendorFamily(real, t));
            for (Map.Entry<String, String> e : m.entrySet()) {
                String v = e.getValue();
                // ★ 删除走**独立通道**（缺陷①的根治）：值域里永远不出现删除标记 ——
                //   旧格式把哨兵当值写进扁平行，模块的 Java 侧没拦住，于是目标进程里
                //   `Build.FINGERPRINT` 变成了字面量 "__DELETE__"（真机 Momo 实拍原文）。
                if (Props.DELETE.equals(v)) {
                    sb.append("DEL\t").append(pkg).append('\t').append(e.getKey()).append('\n');
                    r.dels++;
                    continue;
                }
                sb.append(pkg).append('\t').append(e.getKey()).append('\t').append(v).append('\n');
                r.props++;
            }
        }
        sb.append("# 共 ").append(pkgs.size()).append(" 个应用 / ").append(r.props)
                .append(" 条属性 / ").append(r.dels).append(" 条删除\n");
        r.text = sb.toString();
        return r;
    }

    /** 有多少个应用会进配置（用于界面显示）。 */
    public static int appCount(Context c) {
        return AppSpoof.enabled(c).size();
    }

    /** 把配置写到 /data/adb/devicetoolbox/zygisk.conf（读列表→落盘之间持锁，不会被并发覆盖）。 */
    public static Engine.Op sync(Context c) {
        synchronized (WRITE_LOCK) { return write(c); }
    }

    /**
     * **原子地**改「应用隐藏」勾选列表 + 按新列表写配置 —— 二者是同一件事的两半，只改一半就是
     * 缺陷①那种状态（列表说在用、配置里没有 / 反过来）。
     *
     * 做法：持锁 → 先落列表（一次 commit）→ 按列表写配置 → **配置写失败就把列表回滚**。
     * 于是只有两种结果：两边都改成功，或两边都是原样（外加一条如实的错误信息）。
     *
     * @param pkgs 新的勾选列表（顺序保留；空列表 = 取消全部隐藏，配置会写成空配置）
     */
    public static Engine.Op applyList(Context c, List<String> pkgs) {
        synchronized (WRITE_LOCK) {
            List<String> before = AppSpoof.enabled(c);
            AppSpoof.setEnabledList(c, pkgs);
            Engine.Op op = write(c);
            if (!op.ok) {
                AppSpoof.setEnabledList(c, before);
                return fail(op.message + "\n\n已回滚：勾选列表恢复为改动前的 " + before.size()
                        + " 个应用，没有留下「列表与配置不一致」的中间状态。");
            }
            return op;
        }
    }

    private static Engine.Op write(Context c) {
        Conf conf = build(c);
        // ★ 「列表里有、却没有任何目标机型可用」→ 这些应用不可能被注入。宁可不写、如实报错，
        //   也不写出一份看起来成功、实际漏掉应用（或头部计数与正文不符）的配置。
        if (conf.noTarget > 0) {
            return fail("有 " + conf.noTarget + " 个应用在隐藏列表里、却没有任何目标机型可用"
                    + "（既没单独设过、全局目标也是空的）—— 它们不可能被注入，写出去就成了"
                    + "「列表说在用、实际没在伪装」，所以这次不写。\n"
                    + "请先到「机型」页挑一台，或取消勾选这些应用。");
        }
        String text = conf.text;
        String local = Runner.writeOnly(c, "zygisk.conf", text);
        String script = "mkdir -p " + Sh.q(Scripts.DIR) + "\n"
                // 目录与配置都收紧到 root-only：zygisk.conf 明列了目标包名与全部伪装值，
                // 不该给同机其它身份读取（公开实现也把这类文件放 /data/adb，但权限更严）。
                + "chmod 700 " + Sh.q(Scripts.DIR) + "\n"
                // 原子写（公开实现用 .tmp/.new/.part + rename）：先落到 .tmp、定好权限，再 mv 覆盖。
                // 否则 companion 可能正好读到「写了一半」的配置 —— 表现为某个应用偶尔漏伪装，
                // 而且极难复现（配置有 150+ 行，窗口不小）。
                + "cp " + Sh.q(local) + " " + Sh.q(CONF + ".tmp") + "\n"
                + "chmod 600 " + Sh.q(CONF + ".tmp") + "\n"
                + "mv -f " + Sh.q(CONF + ".tmp") + " " + Sh.q(CONF) + "\n"
                + "wc -l < " + Sh.q(CONF) + "\n";
        Sh.Result r = Runner.root(c, "zygisk-conf.sh", script, 20000);
        if (!r.ok()) return fail("写配置失败：" + r.text());
        String lines = r.out.trim();
        int apps = conf.listed;
        if (apps == 0) {
            return done("配置已清空（没有勾选任何应用）", 0, "");
        }
        // ★ 上限自检：模块侧 MAX_PROPS 超了会**静默截断**（等于悄悄少伪装一批键）。
        // 这个项目最忌讳「静默失效」，所以在这里就报出来。数的是**刚生成的文本**本身，
        // 不另算一遍 —— 免得两个地方各算一次、日后漂移。
        int worst = maxEntriesPerApp(text);
        if (worst >= MAX_PROPS) {
            return done("⚠ 已达模块上限：单个应用 " + worst + " 条属性 ≥ MAX_PROPS(" + MAX_PROPS
                    + ")，**超出的会被静默丢弃**（等于悄悄少伪装一批键）。\n"
                    + "请减少该应用的键数，或调大 jni/spoof.cpp 的 MAX_PROPS 并重编模块。\n\n"
                    + "配置已写入 " + CONF + "\n应用 " + apps + " 个 · 共 " + lines + " 行",
                    lines.isEmpty() ? 0 : 1, "");
        }
        return done("配置已写入 " + CONF + "\n应用 " + apps + " 个 · 共 " + lines + " 行"
                + "\n\n对已经开着的目标应用不生效，重启（或杀掉）目标应用后才会伪装。"
                + patchNote(conf) + noRealFpNote(conf), lines.isEmpty() ? 0 : 1, "");
    }

    /**
     * 安全补丁取了较新值时的**中性说明**（2026-10-06 需求：补丁只前进、不后退）。
     * 事实描述，不提「伪装 / 构造」：目标是老固件（如 PKJ110 的 2024-06-17）时，
     * 会写成本机基线的较新值（2026-08-01），避免被判「安全更新已过期」。
     */
    private static String patchNote(Conf conf) {
        if (conf.patchBumped <= 0) return "";
        return "\n\n安全补丁已取较新值：" + conf.patchNew + "（目标机型固件里是 " + conf.patchOld
                + "，本机基线更新）—— 只前进不后退，避免被判「安全更新已过期」。";
    }

    /**
     * 目标机型没有真指纹时的**非阻断说明**（2026-10-06 设计定稿）。
     *
     * 行为（不是拒绝）：机型族照写；构建身份族（指纹 / 构建号 / 内部版本号 / display.id /
     * description / 版本 / 补丁 / type/tags…）**一个键都不碰** —— 目标进程保留真机自己那套，
     * Build.FINGERPRINT 与属性区 ro.build.fingerprint 都是真值、彼此一致（公开实现同款做法）。
     * 这句只解释「为什么检测类应用里的系统指纹仍是真机的」，不拦任何操作。
     */
    private static String noRealFpNote(Conf conf) {
        if (conf.noRealFp <= 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n说明：以下 ").append(conf.noRealFp).append(" 个应用的机型**在机型库里没有指纹数据**："
                + "已伪装型号信息（型号 / 品牌 / 制造商 / 产品名 / 代号），"
                + "**系统指纹与构建号保持真机原样**（构建身份族一个都不写、也不删）：\n");
        int shown = 0;
        for (int i = 0; i < conf.noRealFpPkgs.size() && shown < 5; i++, shown++) {
            sb.append("  · ").append(conf.noRealFpPkgs.get(i)).append("：")
                    .append(conf.noRealFpWhy.get(i)).append('\n');
        }
        if (conf.noRealFpPkgs.size() > shown) {
            sb.append("  … 还有 ").append(conf.noRealFpPkgs.size() - shown).append(" 个\n");
        }
        sb.append("检测类应用里「按 Build 字段拼出来的那行指纹」会显示成「目标机型 + 真机构建号」——"
                + "这是只改型号、不动系统构建身份的必然结果（指纹本身仍是真机真值）。"
                + "想要系统指纹也变成目标机型，请换一台机型库里有真指纹的机型。");
        return sb.toString();
    }

    /**
     * 模块侧的 MAX_PROPS（jni/spoof.cpp）—— **必须与那边一致**。
     * companion 读到超出部分会直接不读、没有任何提示，所以这里要主动兜住。
     */
    public static final int MAX_PROPS = 256;

    /** 从生成的配置文本里数出「单个应用最多的条数」（只数写入通道：`DEL` 行不是属性）。 */
    private static int maxEntriesPerApp(String confText) {
        java.util.HashMap<String, Integer> per = new java.util.HashMap<>();
        if (confText != null) {
            for (String line : confText.split("\n")) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (line.startsWith("DEL\t")) continue;      // 删除行走独立通道，不占 MAX_PROPS
                int t1 = line.indexOf('\t');
                if (t1 <= 0) continue;
                String pkg = line.substring(0, t1);
                Integer v = per.get(pkg);
                per.put(pkg, v == null ? 1 : v + 1);
            }
        }
        int max = 0;
        for (Integer v : per.values()) {
            if (v != null && v > max) max = v;
        }
        return max;
    }

    // ------------------------------------------------------------ 模块状态

    public static boolean installed() {
        Sh.Result r = Sh.root("[ -f " + Sh.q(MODULE_DIR + "/module.prop") + " ] "
                + "&& [ -f " + Sh.q(MODULE_DIR + "/zygisk/arm64-v8a.so") + " ] "
                + "&& echo YES || echo NO", 10000);
        return r.out.contains("YES");
    }

    // ------------------------------------------------------------ 安装 / 卸载

    /** 把 assets 里的 .so 解到应用私有目录，再用 root 拷进模块目录。 */
    public static Engine.Op install(Context c) {
        String soPath;
        try {
            soPath = extractLib(c);
        } catch (Throwable t) {
            return fail("释放模块文件失败：" + t);
        }
        String prop = "id=" + MODULE_ID + "\n"
                // 名字与描述一律中性化：module.prop 是任何 root 侧扫描（模块审计/取证脚本）
                // 一眼就能读到的东西，写「机型伪装」「改机型」等于自报家门。
                + "name=System Props Helper\n"
                + "version=1.0\n"
                + "versionCode=1\n"
                + "author=xuzhang\n"
                + "description=Per-process system property normalization\n";
        String propLocal = Runner.writeOnly(c, "zygisk-module.prop", prop);

        String script = "mkdir -p " + Sh.q(MODULE_DIR + "/zygisk") + "\n"
                + "cp " + Sh.q(propLocal) + " " + Sh.q(MODULE_DIR + "/module.prop") + "\n"
                + "cp " + Sh.q(soPath) + " " + Sh.q(MODULE_DIR + "/zygisk/arm64-v8a.so") + "\n"
                + "chmod 644 " + Sh.q(MODULE_DIR + "/module.prop") + " "
                + Sh.q(MODULE_DIR + "/zygisk/arm64-v8a.so") + "\n"
                + "rm -f " + Sh.q(MODULE_DIR + "/disable") + "\n"
                + "ls -la " + Sh.q(MODULE_DIR + "/zygisk/") + "\n";
        Sh.Result r = Runner.root(c, "zygisk-install.sh", script, 30000);
        if (!r.ok()) return fail("安装失败：" + r.text());

        Engine.Op sync = sync(c);
        return done("Zygisk 模块已安装到 " + MODULE_DIR + "\n\n" + r.out.trim()
                + "\n\n重启一次才会被 Zygisk 加载。\n" + sync.message, 1, "");
    }

    public static Engine.Op uninstall(Context c) {
        Sh.Result r = Sh.root("rm -rf " + Sh.q(MODULE_DIR) + " && echo REMOVED", 20000);
        if (!r.out.contains("REMOVED")) return fail("卸载失败：" + r.text());
        return done("已删除 " + MODULE_DIR + "，重启后 Zygisk 不再加载它", 1, "");
    }

    /**
     * 取出模块 .so，落到应用私有目录（root 能读）。
     *
     * 这个 .so 在 APK 里是 native 库（lib/arm64-v8a/），不在 assets 里 ——
     * 安装时系统已经把它解到 nativeLibraryDir，优先从那里取；
     * 万一没解出来，就回读 APK 里的条目。
     */
    public static String extractLib(Context c) throws Exception {
        File out = new File(c.getFilesDir(), LIB);

        File installed = new File(c.getApplicationInfo().nativeLibraryDir, LIB);
        if (installed.isFile()) {
            try (InputStream in = new java.io.FileInputStream(installed)) {
                copy(in, out);
            }
            return out.getAbsolutePath();
        }

        String src = c.getApplicationInfo().sourceDir;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(src)) {
            java.util.zip.ZipEntry e = zip.getEntry("lib/arm64-v8a/" + LIB);
            if (e == null) e = zip.getEntry("lib/" + LIB);
            if (e == null) throw new java.io.FileNotFoundException("APK 里没有 lib/arm64-v8a/" + LIB);
            try (InputStream in = zip.getInputStream(e)) {
                copy(in, out);
            }
        }
        return out.getAbsolutePath();
    }

    private static void copy(InputStream in, File out) throws Exception {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        out.setReadable(true, false);
    }

    private static Engine.Op fail(String msg) { return Engine.Op.fail(msg); }

    private static Engine.Op done(String msg, int n, String log) { return Engine.Op.done(msg, n, log); }
}
