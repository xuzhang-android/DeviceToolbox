package com.xuzhang.devicetoolbox.core;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作层：所有会改动系统状态的动作都从这里走。
 *
 * 三条硬规则写在这里，而不是散落在各个界面里：
 *   1. 改之前一定先自动快照（除非用户显式关闭）；
 *   2. 改完一定读回校验，校验不过就如实报告，不假装成功；
 *   3. 还原只回写「我们改过的键」，不动系统其它属性。
 */
public final class Engine {

    private Engine() { }

    /** 一次操作的结果。 */
    public static final class Op {
        public boolean ok;
        public String message = "";
        public int count;
        public String log = "";

        public static Op fail(String msg) { Op o = new Op(); o.ok = false; o.message = msg; return o; }

        public static Op done(String msg, int n, String log) {
            Op o = new Op(); o.ok = true; o.message = msg; o.count = n; o.log = log == null ? "" : log; return o;
        }
    }

    // ------------------------------------------------------------ 快照

    public static final class Snap {
        public String dir = "";
        public long time = 0;
        public String title = "";
        public int level = 0;

        public String label() { return title + " · " + Props.LEVEL_NAME[Math.max(0, Math.min(2, level))]; }
    }

    /** 目录里现有的快照，新的在前。 */
    public static List<Snap> snapshots() {
        List<Snap> out = new ArrayList<>();
        Sh.Result r = Sh.root("ls -1 " + Sh.q(Scripts.SNAP_DIR) + " 2>/dev/null", 10000);
        if (!r.ok()) return out;
        List<String> dirs = new ArrayList<>();
        for (String line : r.out.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) dirs.add(s);
        }
        if (dirs.isEmpty()) return out;
        StringBuilder sb = new StringBuilder();
        for (String d : dirs) {
            sb.append("echo '@@'").append(Sh.q(d)).append('\n');
            sb.append("cat ").append(Sh.q(Scripts.SNAP_DIR + "/" + d + "/meta.txt")).append(" 2>/dev/null\n");
        }
        Sh.Result meta = Sh.root(sb.toString(), 15000);
        Snap cur = null;
        for (String line : meta.out.split("\n")) {
            if (line.startsWith("@@")) {
                if (cur != null) out.add(cur);
                cur = new Snap();
                cur.dir = line.substring(2).trim();
            } else if (cur != null) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i), v = line.substring(i + 1).trim();
                switch (k) {
                    case "time": try { cur.time = Long.parseLong(v); } catch (Throwable ignored) { } break;
                    case "title": cur.title = v; break;
                    case "level": try { cur.level = Integer.parseInt(v); } catch (Throwable ignored) { } break;
                    default: break;
                }
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    /** 为「即将改动 level 档属性」做一次快照。 */
    public static Op snapshotNow(Context c, Target t, int level) {
        Map<String, String> current = Device.props();
        List<String> keys = new ArrayList<>(Props.build(t, level).keySet());
        String stamp = String.valueOf(System.currentTimeMillis());
        String dir = Scripts.SNAP_DIR + "/" + stamp;

        String restore = Scripts.restoreText(current, keys);
        String local = Runner.writeOnly(c, "restore.sh", restore);

        String meta = "time=" + stamp + "\n"
                + "title=" + t.title() + "\n"
                + "level=" + level + "\n"
                + "count=" + keys.size() + "\n";
        String metaLocal = Runner.writeOnly(c, "meta.txt", meta);
        String valuesLocal = Runner.writeOnly(c, "values.tsv", Scripts.valuesText(current, keys));

        String script = "mkdir -p " + Sh.q(dir) + "\n"
                + "cp " + Sh.q(local) + " " + Sh.q(dir + "/restore.sh") + "\n"
                + "chmod 755 " + Sh.q(dir + "/restore.sh") + "\n"
                + "cp " + Sh.q(metaLocal) + " " + Sh.q(dir + "/meta.txt") + "\n"
                + "cp " + Sh.q(valuesLocal) + " " + Sh.q(dir + "/values.tsv") + "\n"
                + Scripts.snapshot(dir, current, keys)
                + "echo SNAP_OK\n";
        Sh.Result r = Runner.root(c, "snapshot.sh", script, 25000);
        if (r.ok() && r.out.contains("SNAP_OK")) {
            Op o = new Op(); o.ok = true; o.message = dir; o.count = keys.size();
            return o;
        }
        return Op.fail("快照失败：" + r.text().trim());
    }

    // ------------------------------------------------------------ 真值基线

    private static Map<String, String> baselineCache;

    /** 启动时预热：基线不存在就抓一份。此时设备还没被本工具改过，抓到的是真值。 */
    public static void warmBaseline(Context c) {
        // ★ 这里原本是 `catch (Throwable ignored) {}` —— 它把失败原因整个吞掉，
        //   而本轮「存储目标修了却落不了盘」查不出原因，正是被它挡的
        //   （`warmBaseline` 跑在后台线程，异常也不会弹到界面）。
        //   这个项目已经因为「静默失效」栽过好几次，所以改成**把过程写下来**：
        //   跑到哪一步、有没有抛、抛了什么，一条文件看清楚。
        StringBuilder dbg = new StringBuilder();
        try {
            baseline(c);
            dbg.append("baseline ok.devicePatch=").append(Target.devicePatch).append('\n');
        } catch (Throwable e) {
            dbg.append("baseline THREW: ").append(e).append('\n');
        }
        int n = -1;
        try {
            n = repairStoredTargets(c);
            dbg.append("repairStoredTargets n=").append(n).append('\n');
        } catch (Throwable e) {
            dbg.append("repair THREW: ").append(e).append('\n');
        }
        if (n > 0) {
            try {
                Zygisk.sync(c);
                dbg.append("sync ok\n");
            } catch (Throwable e) {
                dbg.append("sync THREW: ").append(e).append('\n');
            }
        } else {
            dbg.append("skip sync (n<=0)\n");
        }
        // ★ 只在**出异常**时落盘（2026-10-06 审计 L1）：正常启动不再每次往 /data/adb 写诊断文件 ——
        //   发行版里留一个「每次冷启都更新」的调试仪表，等于把内部状态常态暴露给任何 root 侧读取。
        //   这里保留的只有「静默失效」那一类：抛了异常才写，供真机排障。
        if (dbg.indexOf("THREW") >= 0) {
            try {
                String local = Runner.writeOnly(c, "repair-debug.txt", dbg.toString());
                Sh.root("cp " + Sh.q(local) + " /data/adb/devicetoolbox/repair-debug.txt"
                        + " && chmod 600 " + Sh.q("/data/adb/devicetoolbox/repair-debug.txt"), 15000);
            } catch (Throwable ignored) { }
        }
    }

    /**
     * 把已存目标（全局目标 + 每个逐应用目标）里**不是真指纹**的指纹，按机型码从机型库补回来。
     * 返回修好的目标个数（&gt;0 时调用方应重生成配置）。
     *
     * 顺带对每个目标跑一次 `healInventedDates()` + `normalize()`：
     * 清掉「凭空编的」日期族（含那个还在全局目标上留着的 `2024-06-05`）。
     */
    public static int repairStoredTargets(Context c) {
        if (c == null) return 0;
        int n = 0;
        try {
            Library.init(c);
            Store s = new Store(c);
            Target g = s.target();
            if (g != null) {
                repairOne(g);
                // ★ **无条件落盘** —— 这是本轮真正的根因：
                //   `Store.target()` 读取时**就地**调了 `healInventedDates()`（内存里已经修好），
                //   所以「读出来 → 修 → 比较有没有变」永远是「没变」，于是**从不落盘**，
                //   文件里的旧值（`patch=2024-06-05`、`user=android-build`、`host=abfarm-release`）
                //   就永远留着 —— 真机上 prefs 的 mtime 一直停在改动之前，正是这个原因。
                s.setTarget(g);
                n++;
            }
            for (String pkg : AppSpoof.enabled(c)) {
                Target t = AppSpoof.targetFor(c, pkg);
                if (t == null) continue;
                repairOne(t);
                AppSpoof.setTargetFor(c, pkg, t);   // 同上：无条件落盘
                n++;
            }
        } catch (Throwable ignored) { }
        return n;
    }

    /**
     * 单个目标的自愈。返回 true = **内容真的变了**（调用方必须落盘，否则白改）。
     *
     * 注意这里用「改完和改前的序列化串是否相同」来判变化 —— 早先我只在
     * 「采到了库里的真指纹」时才算 true，于是像三星那个目标（库里没有它的真指纹）
     * 的 heal/normalize 结果**被丢掉了**：`patch=2024-06-05`、`user=android-build`、
     * `host=abfarm-release` 一直留在存储里（真机实测：prefs 文件 mtime 停在改动之前）。
     */
    private static boolean repairOne(Target t) {
        if (t == null || t.model == null || t.model.isEmpty()) return false;
        String before = t.serialize();
        try {
            Target row = Library.byModel(t.model);
            if (row != null && row.fingerprint != null && row.fingerprint.contains("/")
                    && !row.fingerprint.equals(t.fingerprint)) {
                t.fingerprint = row.fingerprint;
            }
        } catch (Throwable ignored) { }
        // 无论有没有补到指纹，都把「凭空编的」日期族（含 user/host 那两个 Google 标识）清一遍再归一化
        Target.healInventedDates(t);
        t.normalize();
        return !before.equals(t.serialize());
    }

    /** 用当前状态重建基线（用户确认「现在就是原厂状态」时用）。 */
    public static Op resetBaseline(Context c) {
        baselineCache = null;
        Sh.root("rm -f " + Sh.q(Scripts.BASELINE), 10000);
        Map<String, String> m = baseline(c);
        if (m.isEmpty()) return Op.fail("重建失败：没读到任何属性。");
        return Op.done("已用当前状态重建真值基线（" + m.size() + " 项）", m.size(), "");
    }

    /**
     * 取「原厂真值」并在首次使用时落盘。
     *
     * 真值只可能来自只读的 build.prop 文件 —— getprop 读的是内存属性区，
     * 伪装过之后就不再可信。基线只写一次，之后所有还原都从它取。
     */
    public static Map<String, String> baseline(Context c) {
        if (baselineCache != null) return baselineCache;
        Map<String, String> map = new LinkedHashMap<>();

        // 已落盘的基线优先
        Sh.Result disk = Sh.root("[ -f " + Sh.q(Scripts.BASELINE) + " ] && cat " + Sh.q(Scripts.BASELINE), 15000);
        if (disk.ok() && disk.out.contains("\t")) {
            parseBaseline(disk.out, map);
            baselineCache = map;
        oemIdentity(map);
        injectDevicePatch(map);
            return map;
        }

        // 首次：从 prop 文件 + getprop 抓
        Sh.Result r = Sh.root(Scripts.dumpBaseline(), 30000);
        parseBaseline(r.out, map);
        baselineCache = map;
        oemIdentity(map);
        injectDevicePatch(map);

        // 落盘（写成 key<TAB>值）
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            sb.append(e.getKey()).append('\t').append(Target.esc(e.getValue())).append('\n');
        }
        String local = Runner.writeOnly(c, "baseline.tsv", sb.toString());
        Sh.root("mkdir -p " + Sh.q(Scripts.DIR) + " && chmod 700 " + Sh.q(Scripts.DIR)
                + " && cp " + Sh.q(local) + " " + Sh.q(Scripts.BASELINE)
                + " && chmod 600 " + Sh.q(Scripts.BASELINE), 20000);
        return map;
    }

    /**
     * 允许 getprop **覆盖** prop 文件值的键 —— 厂商 init 会在运行时改写它们。
     *
     * 本机实测：`ro.product.model` 在 `/system/build.prop` 里是基础镜像值 `ossi`，
     * 运行时被厂商 init 改成 `PLQ110`；所以这几个键必须信 getprop。
     * **白名单要窄** —— 范围一大，就等于把「基线优先信内存」这件事又请回来了，
     * 而那正是 2026-10-06 那次基线污染（混进 `vivo` / `SM8650`）的通道。
     * 对应的 `ro.product.<分区>.model` 这类**不在**白名单里：本机实测它们**没有**被 init 覆盖
     * （getprop 与文件同为 `ossi`），所以文件优先更安全。
     */
    private static final java.util.Set<String> PROP_OVERRIDE_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList("ro.product.model", "ro.product.device", "ro.product.brand",
                    "ro.product.name", "ro.product.manufacturer"));

    /**
     * 解析基线 dump。**先到先得**，而 dump 的顺序是「prop 文件在前、getprop 在后」，
     * 所以默认**文件优先**（只读镜像挡得住内存伪装）；只有 {@link #PROP_OVERRIDE_KEYS}
     * 里的键允许 getprop 覆盖。
     */
    private static void parseBaseline(String text, Map<String, String> out) {
        boolean inGetprop = false;
        for (String line : text.split("\n")) {
            if (line.contains("---GETPROP---")) { inGetprop = true; continue; }
            String k, v;
            int tab = line.indexOf('\t');
            int eq = line.indexOf('=');
            if (tab > 0) { k = line.substring(0, tab); v = Target.unesc(line.substring(tab + 1)); }
            else if (eq > 0) { k = line.substring(0, eq); v = line.substring(eq + 1); }
            else continue;
            k = k.trim();
            // 保留哪些前缀：`ro.` / `sys.` / `vendor.` 是伪装的主战场；
            // **`persist.` 必须一起保留** —— 厂商族派生要用 `persist.sys.mark_last_upgraded_version`、
            // `persist.sys.oplus.ota_ver_display`，跨生态清理还要遍历 `persist.*oplus*`。
            // 2026-10-06 实测教训：旧基线文件里有 616 个 persist 键，而用**当前代码**重新抓的
            // 基线里 persist 键是 **0** —— 说明这个过滤条件在某次改动中把 persist 漏掉了，
            // 而旧文件是更早的生成器写的，所以一直没暴露。重抓一次立刻就现形了。
            if (k.isEmpty() || !k.startsWith("ro.") && !k.startsWith("sys.")
                    && !k.startsWith("vendor.") && !k.startsWith("persist.")) continue;
            if (inGetprop && out.containsKey(k) && !PROP_OVERRIDE_KEYS.contains(k)) continue;
            out.put(k, v);
        }
    }

    /** 真值库里有没有这个键。 */
    /**
     * 把宿主真实的补丁级别注入给 Target —— 目标机型的补丁日期优先沿用它。
     *
     * 为什么不自己造一个：造「当月」意味着「补丁发布当天就装上」，太激进；
     * 造「按 SDK 年代的旧值」又会被判「安全更新已过期」（这是最初的 bug）。
     * 宿主自己那个值才是**真实存在、正在使用中**的补丁级别，而且补丁级别是
     * AOSP 的月度级别、不区分机型 —— 目标机型沿用完全合理。
     */
    private static void injectDevicePatch(Map<String, String> base) {
        String v = base.get("ro.build.version.security_patch");
        if (v != null && v.matches("\\d{4}-\\d{2}-\\d{2}")) Target.devicePatch = v;
    }

    /** 厂商机型清单文件（OPPO / 一加 等；没有这个文件的机型上就是空操作）。 */
    private static final String OEM_IDENTITY_FILE = "/my_manifest/build.prop";

    /**
     * 用厂商清单里的**构建身份有效值**覆盖基线（2026-10-06 修缺陷②的真值来源）。
     *
     * 背景：OPPO / 一加 的机型身份由厂商 init 在开机时从 `/my_manifest/build.prop` 注入属性区
     * （文件里自带 `# fingerprint prop inject start`）。而 {@link Scripts#dumpBaseline()}
     * 的文件清单只覆盖 AOSP 系的标准 build.prop，**不含这一份**，解析又是「文件优先」——
     * 于是基线把**通用镜像（qssi/ossi）的值**当成了真值。本机实测差得很远：
     *
     *   `ro.build.version.incremental`：/system/build.prop 写 `1785914195466`（13 位毫秒时间戳，
     *   通用镜像的构建号），设备实际是 `B.19fa10c_b26ef2_b26ef5`；`ro.build.fingerprint` 同理。
     *
     * 后果不是「少读一个文件」：`Engine.rollback` 会把这个通用镜像的值**写回设备** ——
     * 设备上正确的内部版本号就这么变成了一个时间戳（缺陷②现场看到的那个值）。
     *
     * 只取清单里那几个**init 确实会注入、且与 /system 冲突**的构建身份键：
     * `ro.build.id` / `ro.build.fingerprint` / `ro.build.version.incremental` 与
     * `ro.odm.build.*` / `ro.bootimage.build.*`（本机实测这四处运行时值 = 清单值）。
     * **不能整份收下**：清单里还有 `ro.vendor.build.fingerprint`（写的是机型指纹），
     * 而设备实际用的是 /vendor/build.prop 的通用镜像值 `oplus/ossi/ossi:…` ——
     * 收下它会把「哪些分区是共享镜像」的判断搞反（见 Props.partIsSharedImage），那是更严重的破绽。
     *
     * 清单是**只读文件**，不受 resetprop / 全局伪装影响 —— 所以这条通道是安全的，
     * 不需要像 getprop 那样防污染（见 parseBaseline 的窄白名单说明）。
     */
    private static void oemIdentity(Map<String, String> map) {
        try {
            String re = "^(ro\\.build\\.(id|fingerprint|version\\.incremental)"
                    + "|ro\\.(odm|bootimage)\\.build\\.)";
            Sh.Result r = Sh.root("[ -f " + Sh.q(OEM_IDENTITY_FILE) + " ] && grep -hE "
                    + Sh.q(re) + " " + Sh.q(OEM_IDENTITY_FILE), 15000);
            if (!r.ok() || r.out.trim().isEmpty()) return;
            for (String line : r.out.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                if (k.isEmpty() || k.charAt(0) == '#') continue;
                map.put(k, line.substring(eq + 1).trim());
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ 应用伪装

    /** 一键伪装：快照 → 应用 → 读回校验。 */
    public static Op apply(Context c, Target t, int level, boolean autoSnapshot) {
        t.normalize();
        String err = validate(t, level);
        if (err != null) return Op.fail(err);

        if (!Device.hasResetprop()) {
            return Op.fail("没找到 resetprop。本工具依赖 Magisk / KernelSU / APatch 提供的 resetprop 来改属性。");
        }

        if (autoSnapshot) {
            Op s = snapshotNow(c, t, level);
            if (!s.ok) return Op.fail("已中止：自动快照没成功（" + s.message + "）。没有退路就不动手。");
        }

        Sh.Result r = Runner.root(c, "apply.sh", Scripts.apply(t, level), 40000);
        if (!r.ok()) {
            return Op.fail("执行失败（退出码 " + r.code + "）：" + r.text().trim());
        }
        if (r.out.contains("NO_RESETPROP")) {
            return Op.fail("执行环境里没有 resetprop 可用。");
        }
        int n = parseCount(r.out, "DONE ");
        Op verify = verify(c, t, level);
        if (!verify.ok) {
            return Op.done("已写入 " + n + " 条，但读回校验不一致：" + verify.message, n, r.out);
        }
        Device.invalidate();
        return Op.done("已伪装为 " + t.title() + "（" + Props.LEVEL_NAME[level] + "档 · " + n + " 条）"
                + noRealFpNote(t), n, r.out);
    }

    /**
     * 目标机型没有真指纹时的如实提示。
     *
     * 没有真指纹 ⇒ 内部版本号无法从真数据派生（见 {@link Target#fingerprint()}）⇒ 构建身份族
     * （指纹 / 内部版本号 / 构建号 / description）整族不写。这必须让用户看见，不能只是「少了几条」。
     */
    private static String noRealFpNote(Target t) {
        if (t == null || !t.familyOk()) {
            if (t == null || t.fingerprint().isEmpty()) {
                return "\n\n说明：这台机型**在机型库里没有指纹数据**：已伪装型号信息"
                        + "（型号 / 品牌 / 制造商 / 产品名 / 代号），**系统指纹与构建号保持真机原样**"
                        + "（构建身份族一个都不写、也不删）。"
                        + "想让系统指纹也变成目标机型，请换一台机型库里有真指纹的机型。";
            }
        }
        if (!t.familyOk()) {
            return "\n\n说明：这台机型的指纹与机型字段对不上（" + t.familyConflict() + "），"
                    + "本次只写型号族，系统构建身份整族保持真机原样（不写、不删）。";
        }
        return "";
    }

    /** 读回校验：把目标表逐键取回，统计生效条数。 */
    public static Op verify(Context c, Target t, int level) {
        Map<String, String> want = Props.build(t, level);
        Sh.Result r = Runner.root(c, "readback.sh", Scripts.readBack(want.keySet()), 30000);
        if (!r.ok()) return Op.fail("读回失败");
        int hit = 0, miss = 0;
        StringBuilder bad = new StringBuilder();
        for (String line : r.out.split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String k = line.substring(0, i);
            String got = line.substring(i + 1);
            String exp = want.get(k);
            if (exp == null) continue;
            if (exp.equals(got)) hit++;
            else {
                miss++;
                if (bad.length() < 240) bad.append(k).append(" 期望[").append(exp).append("] 实际[").append(got).append("] ");
            }
        }
        if (miss == 0) {
            Op o = new Op(); o.ok = true; o.count = hit; o.message = hit + " 条全部生效";
            return o;
        }
        Op o = new Op(); o.ok = false; o.count = hit;
        o.message = miss + " 条未生效：" + bad;
        return o;
    }

    // ------------------------------------------------------------ 还原

    /** 还原到最近一次快照。 */
    public static Op restoreLast(Context c) {
        List<Snap> list = snapshots();
        if (list.isEmpty()) return Op.fail("没有可用快照，无法还原。若你记得原始机型，可以手动填回去再应用一次。");
        Snap newest = list.get(0);
        for (Snap s : list) if (s.time > newest.time) newest = s;
        return rollback(c, newest);
    }

    /**
     * 还原：**优先用原厂真值基线**，快照只作兜底。
     *
     * 为什么不能只用快照：快照读的是 getprop，也就是「当时内存里的值」。
     * 如果上一次伪装没还原干净就又伪装了一次，快照里存的就是伪装值 ——
     * 还原出来自然还是那个伪装机型。真值只在只读的 build.prop 文件里。
     */
    public static Op rollback(Context c, Snap s) {
        Device.invalidate();

        java.util.List<String> keys = snapshotKeys(c, s);
        if (!keys.isEmpty()) {
            Map<String, String> base = baseline(c);
            String script = Scripts.restoreFromBaseline(base, keys);
            String local = Runner.writeOnly(c, "restore-baseline.sh", script);
            Sh.Result rb = Sh.root("sh " + Sh.q(local) + " && echo RB_OK", 60000);
            if (rb.ok() && rb.out.contains("RB_OK")) {
                Device.invalidate();
                Op v = verifyAgainst(c, base, keys);
                if (v.ok) {
                    return Op.done("已按原厂真值还原（" + v.count + " 项一致）", v.count, rb.out);
                }
                return Op.done("已按真值还原，但核对有出入：" + v.message, 0, rb.out);
            }
        }

        // 兜底：用快照自带的 restore.sh
        String path = Scripts.SNAP_DIR + "/" + s.dir + "/restore.sh";
        Sh.Result r = Sh.root("[ -f " + Sh.q(path) + " ] && sh " + Sh.q(path) + " && echo RB_OK", 60000);
        if (!r.ok() || !r.out.contains("RB_OK")) {
            return Op.fail("还原脚本执行失败：" + r.text().trim());
        }
        Device.invalidate();
        Op v = verifyRollback(c, s);
        if (v.ok) return Op.done("已还原到快照：" + s.label() + "（" + v.count + " 项核对一致）", v.count, r.out);
        return Op.done("已执行还原，但核对有出入：" + v.message, 0, r.out);
    }

    /** 从快照的 values.tsv 里取出当初记录的那些键。 */
    public static java.util.List<String> snapshotKeys(Context c, Snap s) {
        java.util.List<String> keys = new ArrayList<>();
        String path = Scripts.SNAP_DIR + "/" + s.dir + "/values.tsv";
        Sh.Result r = Sh.root("[ -f " + Sh.q(path) + " ] && cat " + Sh.q(path), 20000);
        if (!r.ok()) return keys;
        for (String line : r.out.split("\n")) {
            int tab = line.indexOf('\t');
            String k = tab > 0 ? line.substring(0, tab) : (line.indexOf('=') > 0 ? line.substring(0, line.indexOf('=')) : "");
            k = k.trim();
            if (!k.isEmpty() && !keys.contains(k)) keys.add(k);
        }
        return keys;
    }

    /** 拿真值逐项读回。 */
    public static Op verifyAgainst(Context c, Map<String, String> expect, java.util.List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append("printf '%s\\t%s\\n' ").append(Sh.q(k))
              .append(" \"$(getprop ").append(Sh.q(k)).append(")\"\n");
        }
        Sh.Result r = Sh.root(sb.toString(), 40000);
        if (!r.ok()) return Op.fail("读回失败");
        int same = 0, bad = 0;
        StringBuilder detail = new StringBuilder();
        for (String line : r.out.split("\n")) {
            String[] f = line.split("\t", 2);
            if (f.length < 2) continue;
            String key = f[0], got = f[1];
            String want = expect.get(key);
            if (want == null) want = "";
            if (want.equals(got)) same++;
            else {
                bad++;
                if (detail.length() < 200) {
                    detail.append(key).append("（期望 ").append(want.isEmpty() ? "空" : want)
                          .append("，实为 ").append(got.isEmpty() ? "空" : got).append("）");
                }
            }
        }
        Op o = new Op();
        o.ok = bad == 0;
        o.count = same;
        o.message = bad == 0 ? same + " 项一致" : bad + " 项没还原回去：" + detail;
        return o;
    }

    /** 拿快照里的期望值清单逐项读回。 */
    public static Op verifyRollback(Context c, Snap s) {
        String dir = Scripts.SNAP_DIR + "/" + s.dir;
        String script = "if [ -f " + Sh.q(dir + "/values.tsv") + " ]; then\n"
                + Sh.q("") + "\n"
                + "  while IFS='" + "\t" + "' read -r k v; do\n"
                + "    [ -z \"$k\" ] && continue\n"
                + "    printf '%s\\t%s\\t%s\\n' \"$k\" \"$v\" \"$(getprop \"$k\")\"\n"
                + "  done < " + Sh.q(dir + "/values.tsv") + "\n"
                + "fi\n";
        Sh.Result r = Sh.root(script, 40000);
        if (!r.ok()) return Op.fail("读回失败");
        int same = 0, bad = 0;
        StringBuilder detail = new StringBuilder();
        for (String line : r.out.split("\n")) {
            String[] f = line.split("\t", 3);
            if (f.length < 3) continue;
            String key = f[0], want = Target.unesc(f[1]), got = f[2];
            if (want.equals(got)) same++;
            else {
                bad++;
                if (detail.length() < 200) {
                    detail.append(key).append("（期望 ").append(want.isEmpty() ? "空" : want)
                          .append("，实为 ").append(got.isEmpty() ? "空" : got).append("）");
                }
            }
        }
        Op o = new Op();
        o.ok = bad == 0;
        o.count = same;
        o.message = bad == 0 ? same + " 项一致" : bad + " 项没还原回去：" + detail;
        return o;
    }

    /** 一键恢复出厂：还原最近快照 + 卸载模块 + 清理目录。 */
    public static Op factoryReset(Context c) {
        List<Snap> list = snapshots();
        Snap newest = null;
        for (Snap s : list) if (newest == null || s.time > newest.time) newest = s;
        StringBuilder script = new StringBuilder();
        if (newest != null) {
            String path = Scripts.SNAP_DIR + "/" + newest.dir + "/restore.sh";
            script.append("[ -f ").append(Sh.q(path)).append(" ] && sh ").append(Sh.q(path)).append('\n');
        }
        script.append("rm -rf ").append(Sh.q(Scripts.MODULE_DIR)).append('\n');
        script.append("rm -rf ").append(Sh.q(Scripts.DIR)).append('\n');
        script.append("echo FR_OK\n");
        Sh.Result r = Runner.root(c, "factory.sh", script.toString(), 45000);
        if (r.ok() && r.out.contains("FR_OK")) {
            Device.invalidate();
            return Op.done(newest == null ? "已清理模块与目录（没有快照可还原）"
                    : "已还原到 " + newest.label() + "，并卸载持久化模块", 0, r.out);
        }
        return Op.fail("恢复出厂失败：" + r.text().trim());
    }

    // ------------------------------------------------------------ 隐藏 root

    /** 隐藏 root 痕迹：先快照（这样能原样还原），再写锁定状态属性。 */
    public static Op hideRoot(Context c, Target t, int level) {
        if (!Device.hasResetprop()) {
            return Op.fail("没找到 resetprop，无法修改系统属性。");
        }
        Op snap = snapshotKeys(c, Scripts.hideRootKeys(), "隐藏 root 前");
        if (!snap.ok) return Op.fail("已中止：自动快照没成功（" + snap.message + "）。");

        Sh.Result r = Runner.root(c, "hide-root.sh", Scripts.hideRoot(), 40000);
        if (!r.ok()) return Op.fail("执行失败：" + r.text().trim());
        Device.invalidate();
        return Op.done("已把 " + Scripts.HIDE_ROOT_PROPS.length + " 项暴露点写回锁定状态", 
                Scripts.HIDE_ROOT_PROPS.length, r.out);
    }

    /** 为指定的一批键做快照（与按档位快照同一套机制）。 */
    public static Op snapshotKeys(Context c, java.util.List<String> keys, String title) {
        Map<String, String> current = Device.props();
        String stamp = String.valueOf(System.currentTimeMillis());
        String dir = Scripts.SNAP_DIR + "/" + stamp;

        String restore = Scripts.restoreText(current, keys);
        String local = Runner.writeOnly(c, "restore.sh", restore);

        String meta = "time=" + stamp + "\ntitle=" + title + "\nlevel=0\ncount=" + keys.size() + "\n";
        String metaLocal = Runner.writeOnly(c, "meta.txt", meta);
        String valuesLocal = Runner.writeOnly(c, "values.tsv", Scripts.valuesText(current, keys));

        String script = "mkdir -p " + Sh.q(dir) + "\n"
                + "cp " + Sh.q(local) + " " + Sh.q(dir + "/restore.sh") + "\n"
                + "chmod 755 " + Sh.q(dir + "/restore.sh") + "\n"
                + "cp " + Sh.q(metaLocal) + " " + Sh.q(dir + "/meta.txt") + "\n"
                + "cp " + Sh.q(valuesLocal) + " " + Sh.q(dir + "/values.tsv") + "\n"
                + Scripts.snapshot(dir, current, keys)
                + "echo SNAP_OK\n";
        Sh.Result r = Runner.root(c, "snapshot.sh", script, 25000);
        if (r.ok() && r.out.contains("SNAP_OK")) {
            Op o = new Op(); o.ok = true; o.message = dir; o.count = keys.size();
            return o;
        }
        return Op.fail("快照失败：" + r.text().trim());
    }

    // ------------------------------------------------------------ 持久化模块

    public static Op installModule(Context c, Target t, int level) {
        String propLocal = Runner.writeOnly(c, "module.prop", Scripts.moduleProp(t, level));
        String bootLocal = Runner.writeOnly(c, "post-fs-data.sh", Scripts.postFsData(t, level));
        String script = "mkdir -p " + Sh.q(Scripts.MODULE_DIR) + " " + Sh.q(Scripts.DIR) + "\n"
                + "cp " + Sh.q(propLocal) + " " + Sh.q(Scripts.MODULE_DIR + "/module.prop") + "\n"
                + "cp " + Sh.q(bootLocal) + " " + Sh.q(Scripts.MODULE_DIR + "/post-fs-data.sh") + "\n"
                + "chmod 755 " + Sh.q(Scripts.MODULE_DIR + "/post-fs-data.sh") + "\n"
                + "chmod 644 " + Sh.q(Scripts.MODULE_DIR + "/module.prop") + "\n"
                + "echo MOD_OK\n";
        Sh.Result r = Runner.root(c, "install-module.sh", script, 30000);
        if (r.ok() && r.out.contains("MOD_OK")) {
            return Op.done("持久化模块已安装，重启后仍会生效（" + Props.LEVEL_NAME[level] + "档）", 0, r.out);
        }
        return Op.fail("安装模块失败：" + r.text().trim());
    }

    public static Op uninstallModule(Context c) {
        Sh.Result r = Sh.root("rm -rf " + Sh.q(Scripts.MODULE_DIR) + " && echo UN_OK", 20000);
        if (r.ok() && r.out.contains("UN_OK")) return Op.done("持久化模块已卸载", 0, r.out);
        return Op.fail("卸载失败：" + r.text().trim());
    }

    public static boolean moduleInstalled() {
        Sh.Result r = Sh.root("[ -f " + Sh.q(Scripts.MODULE_DIR + "/module.prop") + " ] && echo 1 || echo 0", 10000);
        return r.out.trim().startsWith("1");
    }

    // ------------------------------------------------------------ 三路自检

    public static final class Row {
        public String key;
        public String prop;      // getprop 看到的
        public String build;     // android.os.Build 看到的
        public String expect;    // 目标值
        public Row(String k, String p, String b, String e) { key = k; prop = p; build = b; expect = e; }
    }

    /**
     * 三路一致性：系统属性 / Java Build / 目标值 并排。
     * Build 字段名与属性名不一致的地方在这里做映射。
     */
    public static List<Row> selfCheck(Target t, int level) {
        Map<String, String> props = Device.props();
        Map<String, String> want = Props.build(t, level);
        List<Row> rows = new ArrayList<>();
        String[][] pairs = {
                {"ro.product.model", "MODEL"},
                {"ro.product.brand", "BRAND"},
                {"ro.product.manufacturer", "MANUFACTURER"},
                {"ro.product.device", "DEVICE"},
                {"ro.product.name", "PRODUCT"},
                {"ro.product.board", "BOARD"},
                {"ro.build.id", "ID"},
                {"ro.build.display.id", "DISPLAY"},
                {"ro.build.fingerprint", "FINGERPRINT"},
        };
        for (String[] pr : pairs) {
            String pv = props.get(pr[0]);
            String bv = Device.build(pr[1]);
            String ev = want.get(pr[0]);
            rows.add(new Row(pr[0], pv == null ? "" : pv, bv == null ? "" : bv, ev == null ? "" : ev));
        }
        rows.add(new Row("ro.build.version.release", props.get("ro.build.version.release"),
                Device.build("RELEASE"), want.get("ro.build.version.release")));
        rows.add(new Row("ro.build.version.sdk", props.get("ro.build.version.sdk"),
                Device.build("SDK_INT"), want.get("ro.build.version.sdk")));
        rows.add(new Row("ro.build.version.security_patch", props.get("ro.build.version.security_patch"),
                Device.build("SECURITY_PATCH"), want.get("ro.build.version.security_patch")));
        return rows;
    }

    // ------------------------------------------------------------ 校验

    /** 动手前的自检，返回 null 表示可以继续。 */
    public static String validate(Target t, int level) {
        if (t == null || t.model == null || t.model.trim().isEmpty()) return "还没选目标机型。";
        if (t.model.length() > 64) return "型号名太长（超过 64 字符），系统属性放不下。";
        for (char c : t.model.toCharArray()) {
            if (c == '\n' || c == '\'' || c == '"' || c == '\\' || c == '$' || c == '`') {
                return "型号名里有特殊字符（引号 / 反斜杠 / $ / 反引号），会让脚本出错，请去掉。";
            }
        }
        if (t.brand != null && t.brand.contains(" ")) return "品牌名不能带空格。";
        if (level < Props.LIGHT || level > Props.DEEP) return "档位不合法。";
        // 说明（2026-10-06 设计定稿）：这里**不再因为「没有真指纹」拒绝伪装**。
        // 没有真指纹时：机型族照写，构建身份族由 Props 的 identity 门整族一个键都不碰
        // （不写、不删），设备保留真机自己那套构建身份、来源之间彼此一致。
        // 校验只负责挡住「会让脚本出错」的输入（上面那些）。
        return null;
    }

    private static int parseCount(String out, String tag) {
        int n = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith(tag)) {
                try { n = Integer.parseInt(line.substring(tag.length()).trim()); } catch (Throwable ignored) { }
            }
        }
        return n;
    }

    /** 概览用：当前机型的关键信息。 */
    public static Map<String, String> overview() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("型号", Device.prop("ro.product.model"));
        m.put("品牌", Device.prop("ro.product.brand"));
        m.put("制造商", Device.prop("ro.product.manufacturer"));
        m.put("设备代号", Device.prop("ro.product.device"));
        m.put("主板", Device.prop("ro.product.board"));
        m.put("Android", Device.prop("ro.build.version.release"));
        m.put("SDK", Device.prop("ro.build.version.sdk"));
        m.put("安全补丁", Device.prop("ro.build.version.security_patch"));
        m.put("构建号", Device.prop("ro.build.id"));
        m.put("显示版本", Device.prop("ro.build.display.id"));
        m.put("指纹", Device.prop("ro.build.fingerprint"));
        return m;
    }
}
