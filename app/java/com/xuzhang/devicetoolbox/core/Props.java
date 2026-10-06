package com.xuzhang.devicetoolbox.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三档属性表 —— 本项目的核心设计。
 *
 * 分层的依据是「改完之后有多少地方会露馅」：
 *   轻量(0)：只动「关于手机」直接显示的那几个键，风险最低；
 *   标准(1)：把 6 个分区的机型键全部对齐 + 7 处指纹，绝大多数机型校验都过；
 *   深度(2)：再改 build 的元信息、Android 版本、安全补丁与各分区版本，最彻底。
 *
 * 表是纯数据生成的，条数由 {@link #count(int)} 算出来，UI 直接显示真实值。
 */
public final class Props {

    /**
     * 「删掉这个键」的哨兵（公开实现用的是同一个词）。
     * **必须与模块侧的 `PA_DELETE_SENTINEL` 一字不差**（jni/prop_area.hpp）。
     *
     * 注意我们这条和它们**不是一回事**：它们只在 companion_resetprop（默认关）下生效，
     * 实际是把键名交给 Magisk 的 `resetprop -d` —— 那是全局删除，所有进程都看得见。
     * 我们是模块在**目标进程的 COW 私有副本**里把 trie 节点的 prop 置 0，
     * 原属性区、系统框架、其它进程都不受影响。
     */
    public static final String DELETE = "__DELETE__";

    public static final int LIGHT = 0, STD = 1, DEEP = 2;

    public static final String[] LEVEL_NAME = {"轻量", "标准", "深度"};

    public static final String[] LEVEL_DESC = {
            "只改「关于手机」显示的型号 / 品牌 / 设备名，共 %d 条，最不容易出问题",
            "把 6 个分区的机型键全部对齐，并改写 7 处指纹，共 %d 条，能过绝大多数机型校验",
            "改全 6 个分区的机型键与各分区指纹，并补齐构建信息、Android 版本与安全补丁，共 %d 条 —— 覆盖最全，露馅点最少",
    };

    /** 参与镜像的分区（属性命名空间）。 */
    private static final String[] PART = {"system", "vendor", "odm", "product", "system_ext", "bootimage"};

    /** 每个分区对齐的机型字段。 */
    private static final String[] FIELDS = {"model", "brand", "manufacturer", "name", "device"};

    /** 深度档里带版本信息的分区（范围收窄，避免过度改写）。 */
    private static final String[] DEEP_PART = {"system", "vendor", "odm", "product", "bootimage"};

    /**
     * 该档位的属性总条数。
     * 用一个字段齐全的探针机型来数 —— 用空机型数会把「值为空所以不写」的键漏掉，数字偏小。
     */
    public static int count(int level) {
        Target probe = new Target();
        probe.model = "probe";
        probe.market = "probe";
        // ★ 探针也要带一份「真指纹」：没有真指纹的目标，构建身份族（指纹 / 内部版本号 /
        //   构建号 / description）会整族不写（见 build() 里的说明），数出来的条数就会比
        //   真正写入的少 —— 而这个数字是给界面看的，必须与实际写入一致。
        //   这里只是一份**用于计数的形状**，永远不写进任何设备（探针机型谁也不会选）。
        probe.fingerprint = "google/probe/probe:14/RKQ1.240101.001/240101001:user/release-keys";
        return build(probe, level).size();
    }

    /**
     * 生成属性表。level 为 0/1/2 时返回该档全部条目；level 为 -1 时返回三档合集（用于总览）。
     */
    public static LinkedHashMap<String, String> build(Target t, int level) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (t == null) t = new Target().normalize();
        t.normalize();

        boolean light = level >= LIGHT || level == -1;
        boolean std = level >= STD || level == -1;
        boolean deep = level >= DEEP || level == -1;
        // ★ 构建身份族（`ro.build.id` / `display.id` / 指纹 / 内部版本号 / description）**只有拿到
        //   真指纹才写**（2026-10-06 修缺陷②）。没有真指纹 ⇒ 内部版本号无法从真数据派生
        //   （见 Target.fingerprint()），而「只写一半」会造出 `Build.ID` 不在 `Build.FINGERPRINT`
        //   里这种一行 `contains()` 就能查出的矛盾 —— 比整族缺失更糟。
        //   整族不写 ⇒ 设备保留它自己那套真值（真值之间自洽，只是机型名与指纹对不上，那是
        //   「机型库里这台机器没有真数据」的固有代价，调用方会把这一点如实报给用户）。
        //   ★ 2026-10-06 深夜：identity 从「有指纹」升级成**自洽门**（Target.familyOk）——
        //   指纹还得与本批要写的 brand/product/device/release/buildId/incremental 逐段一致。
        //   理由是真机实测：检测方（Momo）会把 Build 字段重组成一条指纹与 Build.FINGERPRINT
        //   并排显示，只要有一段对不上，屏幕上就是两条互相矛盾的指纹（= 两套值痕迹）。
        boolean identity = t.familyOk();
        // ★ 定稿方案 A（2026-10-06 深夜）：没有真指纹 ⇒ **只写型号名与市场名**，其余一个都不写。
        //   为什么只有 model 能写、brand/product/device 不能：检测方（Momo）第 2 行是按
        //   `BRAND/PRODUCT/DEVICE:RELEASE/ID/INCREMENTAL:TYPE/TAGS` 重组的，**model 不在串里**
        //   —— 写它不改变重组结果；而 brand/name/device/release/id/incremental/type/tags
        //   只要动一个，重组串就与真机指纹逐字不同（真机实测：机型段一改，屏幕上立刻两条不同）。
        //   manufacturer 不在串里，但检测方会把它与 brand 对拍 ⇒ 同样按「不进指纹串、也不引入
        //   新矛盾」的原则不写。市场名不进串 ⇒ 可以写，于是「型号」栏显示目标机型。
        if (!identity) {
            // 与 appLevel 同一套规则：型号族照写（name/device 只在库里有真实值时写），
            // 构建身份族整族不碰。整机档不写分区副本（那是逐应用那条路的职责）。
            put(m, "ro.product.model", t.model);
            put(m, "ro.product.brand", t.brand);
            put(m, "ro.product.manufacturer", t.manufacturer);
            put(m, "ro.product.marketname", t.market);
            if (t.productIsReal()) put(m, "ro.product.name", t.product);
            if (t.deviceIsReal()) put(m, "ro.product.device", t.device);
            return m;
        }

        // 安全补丁只前进不后退：目标真实补丁 vs 本机基线真值取较新（见 newerPatch 的长注释）。
        // 整机档拿不到 real 表，走 Target.devicePatch（Engine.baseline() 注入的本机真值）。
        final String finalPatch = effectivePatch(t, null);
        // ★ 日期族与**最终补丁同源**（2026-10-06 审计 S1 修复）：
        //   补丁被抬升（例如目标固件 2024-06-17 → 本机基线 2026-08-01）时，date/date.utc
        //   必须跟着抬 —— 否则配置里会同时出现「2025-01-01 构建」和「2026-08-01 补丁」，
        //   那是**可机器校验的矛盾**（`ro.build.date` ↔ `security_patch` 一比对就出来）。
        //   补丁没被动过就保持 t.date/t.dateUtc（那本来就是由目标补丁派生的，彼此自洽）。
        final boolean patchRaised = !finalPatch.equals(t.patch) && !finalPatch.isEmpty();
        final String finalDate = patchRaised ? Target.dateOf(finalPatch) : t.date;
        final String finalDateUtc = patchRaised ? String.valueOf(Target.epochOf(finalPatch)) : t.dateUtc;

        if (light) {
            put(m, "ro.product.model", t.model);
            put(m, "ro.product.brand", t.brand);
            put(m, "ro.product.manufacturer", t.manufacturer);
            put(m, "ro.product.name", t.product);
            put(m, "ro.product.device", t.device);
            // 不写 ro.build.product：真机上是「构建系统产品名」（本机 qssi，高通 SSI），
            // 与机型代号无关，我们无法派生 ⇒ 保留设备真值。详见 appLevel 里的长注释。
        }

        if (std) {
            for (String p : PART) {
                for (String f : FIELDS) {
                    put(m, "ro.product." + p + "." + f, field(t, f));
                }
            }
            putPlatform(m, "ro.product.board", t.board, t);
            putPlatform(m, "ro.board.platform", t.platform, t);

            String fp = t.fingerprint();
            put(m, "ro.product.marketname", t.market);       // 市场名属于机型族，照写
            if (identity) {
                put(m, "ro.build.fingerprint", fp);
                put(m, "ro.bootimage.build.fingerprint", fp);
                put(m, "ro.odm.build.fingerprint", fp);
                put(m, "ro.product.build.fingerprint", fp);
                put(m, "ro.system.build.fingerprint", fp);
                put(m, "ro.system_ext.build.fingerprint", fp);
                put(m, "ro.vendor.build.fingerprint", fp);
                put(m, "ro.system_dlkm.build.fingerprint", fp);
                put(m, "ro.vendor_dlkm.build.fingerprint", fp);
                put(m, "ro.odm_dlkm.build.fingerprint", fp);
            }
            // ★ 构建身份族整族原子（见 appLevel 里的长注释）：没有真数据时，连 description /
            //   版本号 / 内部版本号都不写 —— 设备保留真机那套，来源之间彼此一致。
            if (identity) put(m, "ro.build.description", t.description());
            // 不写 ro.build.flavor：真机真值是 `qssi-user`（构建系统风味，与机型无关），
            // 我们无法派生。原先从 t.flavor 写出来的 `品牌-型号-user` 那个形状，
            // 是从**被污染的基线**里学来的（抓那份基线时内存里正跑着全局伪装），真机上根本不存在。
            if (identity) {
                put(m, "ro.build.id", t.buildId);
                put(m, "ro.build.display.id", t.displayId);
            }
            // 内部版本号属于构建身份族：整族只在 identity 成立时写（缺陷②）
            if (identity) put(m, "ro.build.version.incremental", t.incremental);
        }

        if (deep) {
            // 构建元信息 + 版本族：同属构建身份族（指纹第 4 段=release、末两段=type/tags）⇒ 整族原子
            if (identity) {
                put(m, "ro.build.type", t.type);
                put(m, "ro.build.tags", t.tags);
                put(m, "ro.build.user", t.user);
                put(m, "ro.build.host", t.host);
                put(m, "ro.build.date", finalDate);
                put(m, "ro.build.date.utc", finalDateUtc);
                put(m, "ro.build.version.release", t.release);
                put(m, "ro.build.version.sdk", String.valueOf(t.sdk()));
                put(m, "ro.build.version.security_patch", finalPatch);
                put(m, "ro.build.version.preview_sdk", "0");
                put(m, "ro.build.version.codename", "REL");
            }
            putPlatform(m, "ro.hardware", t.hardware, t);
            // 不再写 ro.build.characteristics —— 真机实测真值是 `nosdcard`，这里写死 "default"
            // 等于凭空盖上一个不存在的值；「不改」反而更安全（公开实现也不写这个键）。

            put(m, "ro.vendor.build.version.incremental", t.incremental);
            if (identity) {
                put(m, "ro.system.build.id", t.buildId);
                put(m, "ro.vendor.build.id", t.buildId);
                put(m, "ro.product.build.id", t.buildId);
            }

            if (identity) for (String p : DEEP_PART) {       // 分区副本同样整族原子
                put(m, "ro." + p + ".build.type", t.type);
                put(m, "ro." + p + ".build.tags", t.tags);
                put(m, "ro." + p + ".build.version.release", t.release);
                put(m, "ro." + p + ".build.version.security_patch", finalPatch);
            }
        }

        return m;
    }

    /** 只写非空值 —— 机型库里有的机型不知道平台/主板，写空串反而会把好值覆盖成空。 */
    private static void put(LinkedHashMap<String, String> m, String key, String value) {
        if (value == null || value.isEmpty()) return;
        m.put(key, value);
    }

    /**
     * **平台域**的键（`ro.product.board` / `ro.board.platform` / `ro.hardware`）专用写入。
     *
     * 比 put() 多挡一道：值**等于机型码 / 设备代号 / 产品名**时一律不写。
     * 理由：这几个键在真机上是平台名（`sun` / `qcom`），不是机型代号；一旦值等于代号，
     * 说明数据源把「代号」当成了「平台名」（本项目的机型库是若干外部来源合并的，
     * 这种脏数据迟早会出现，事实上 2026-10-06 就是被归一化的兜底逻辑这样写坏的）。
     * **宁可保留设备真值（自洽），也不要写一个真机上不可能出现的平台值。**
     */
    private static void putPlatform(LinkedHashMap<String, String> m, String key, String value, Target t) {
        if (value == null || value.isEmpty()) return;
        if (value.equals(t.device) || value.equals(t.model) || value.equals(t.product)) return;
        m.put(key, value);
    }

    /** 从指纹里取 product 段（`brand/product/device:rel/…` 的第 2 段）。 */
    private static String fpProduct(String fp) {
        if (fp == null) return "";
        int s = fp.indexOf('/');
        if (s < 0) return "";
        int e = fp.indexOf('/', s + 1);
        if (e < 0) return "";
        return fp.substring(s + 1, e);
    }

    /**
     * 这个分区是不是**共享系统镜像**（值对所有同世代机型都一样，不随机型变）。
     *
     * 判据**完全从真值现算，不写死分区名单**：
     *   1. 首选比指纹 —— `ro.<分区>.build.fingerprint` 的 product 段 != `ro.build.fingerprint`
     *      的 product 段 ⇒ 该分区来自共享镜像。
     *      本机实测：基础/odm/bootimage 是 `OnePlus/PLQ110/…`，而 system/vendor/product/
     *      system_ext/vendor_dlkm 是 `oplus/ossi/ossi:…`（通用 SSI，连 Android 版本都是 15）。
     *   2. 没有指纹键时退回比 `ro.product.<分区>.model` 与 `ro.product.model`。
     *   3. 真值本身缺失 ⇒ **返回 false（照写）**，保持老行为：
     *      判断不了时宁可按原来的做，也不要静默少写一批键。
     *
     * 为什么这件事重要：把共享镜像分区也写成目标机型，会让**每个分区都严格等于机型码**，
     * 而真机上它们本来就不一致 —— 「过分的自洽」本身就是破绽（一行 diff 就能看出来）。
     */
    private static boolean partIsSharedImage(Map<String, String> real, String part) {
        if (real == null || real.isEmpty()) return false;
        String b = fpProduct(real.get("ro.build.fingerprint"));
        String p = fpProduct(real.get("ro." + part + ".build.fingerprint"));
        if (!b.isEmpty() && !p.isEmpty()) return !p.equals(b);
        String pm = real.get("ro.product.model");
        String pv = real.get("ro.product." + part + ".model");
        if (pm != null && !pm.isEmpty() && pv != null && !pv.isEmpty()) return !pv.equals(pm);
        return false;
    }

    /**
     * 「逐应用」伪装用的属性表 —— 只有 23 条，与本机是不是被整机伪装无关。
     *
     * 为什么和上面三档不一样：逐应用伪装是在目标进程里当场替换，
     * 动的是那个进程自己的内存，不可能去补全 6 个分区上百条键，
     * 也没必要 —— 需要的是「这个进程里到处都对得上」。
     * Zygisk 原生模块与本应用共用这一份，两边结果必须一模一样。
     * （原来还有一条 Xposed 路也读这份，2026-10-06 已退役，见 README。）
     */
    /**
     * 「逐应用」伪装用的属性表。
     *
     * @param real 设备原厂真值（可为 null）。给了它就能判出**哪些分区是共享镜像**、
     *             从而不去覆盖它们，见 {@link #partIsSharedImage}。
     */
    public static LinkedHashMap<String, String> appLevel(Target t) {
        return appLevel(t, null);
    }

    /** 带真值的重载（生产路径用这个；判「共享镜像分区」必须要真值）。 */
    public static LinkedHashMap<String, String> appLevel(Target t, Map<String, String> real) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (t == null) t = new Target().normalize();
        t.normalize();
        final String fp = t.fingerprint();
        // ★ 构建身份族：没有真指纹就整族不写，理由同 build() 里那段（缺陷②）。
        //   另外这里用**自洽门**（指纹与身份字段逐段一致才算有真数据）—— 见 Target.familyConflict。
        final boolean identity = t.familyOk();
        // 安全补丁只前进不后退：目标真实补丁 vs 基线里本机真值取较新（见 newerPatch 的长注释）
        final String patch = effectivePatch(t, real);
        // ★ 日期族与最终补丁同源（审计 S1）：补丁被抬升时 date/date.utc 一起抬；
        //   补丁没动就保持 t.date/t.dateUtc（它们本来就由目标补丁派生，自洽）。
        final boolean patchRaised = !patch.equals(t.patch) && !patch.isEmpty();
        final String patchDate = patchRaised ? Target.dateOf(patch) : t.date;
        final String patchDateUtc = patchRaised ? String.valueOf(Target.epochOf(patch)) : t.dateUtc;
        // ★ 没有真指纹 ⇒ **写全型号族**，构建身份族一个键都不写、不删（2026-10-06 需求定稿）。
        //   为什么要写全：像游戏这类软件直接读 `Build.BRAND / MANUFACTURER / PRODUCT / DEVICE`
        //   判定机型 —— 只改 model 的话它们看到的还是真机品牌，用户视角就是「没改成目标机型」。
        //   值只取目标机型自己的元数据（机型库那一行 / 界面选中的那台），**库里没有的字段不写、不编**：
        //     · model / brand / manufacturer / marketname：界面选中的值，照写；
        //     · name(ro.product.name) / device：**只在机型库给了真实值**时才写
        //       （判据见 Target.productIsReal()，slug(model) 那种推导值不算真数据）；
        //     · 各分区副本与裸键同一套字段、同一套分区族逻辑（共享镜像分区仍然不碰）。
        //   构建身份族（fingerprint / build.id / incremental / display.id / description /
        //   release / sdk / security_patch / type / tags / 各分区指纹）**一个都不写、也不删** ——
        //   目标进程保留真机自己那套构建身份（公开实现同款做法）。
        //   注：检测方（Momo）那行「按 Build 字段重组的指纹」会因此显示成
        //   `目标机型/vs 真机构建号`，这是「改机型但不改系统构建身份」的固有代价，
        //   界面会如实说明；构建号/指纹本身仍是真机真值（没有现实中不存在的值、也没有缺键）。
        if (!identity) {
            final boolean realName = t.productIsReal();
            final boolean realDev = t.deviceIsReal();
            put(m, "ro.product.model", t.model);
            put(m, "ro.product.brand", t.brand);
            put(m, "ro.product.manufacturer", t.manufacturer);
            put(m, "ro.product.marketname", t.market);
            put(m, "ro.vendor.oplus.market.name", t.market);
            put(m, "ro.vendor.oplus.market.enname", t.market);
            if (realName) put(m, "ro.product.name", t.product);
            if (realDev) put(m, "ro.product.device", t.device);
            // 分区副本：与 identity 那条路同一套「共享镜像分区不碰」的判据；
            // 整族一起写（不会出现「只写了某个分区的 brand」这种半族）
            for (String part : APP_PARTS) {
                if (partIsSharedImage(real, part)) continue;
                put(m, "ro.product." + part + ".model", t.model);
                put(m, "ro.product." + part + ".brand", t.brand);
                put(m, "ro.product." + part + ".manufacturer", t.manufacturer);
                if (realName) put(m, "ro.product." + part + ".name", t.product);
                if (realDev) put(m, "ro.product." + part + ".device", t.device);
            }
            return m;
        }
        put(m, "ro.product.model", t.model);
        put(m, "ro.product.brand", t.brand);
        put(m, "ro.product.manufacturer", t.manufacturer);
        put(m, "ro.product.name", t.product);
        put(m, "ro.product.device", t.device);
        // ★ 平台域的三个键：**不再写 ro.build.product**，另外两个走 putPlatform 兜底校验。
        //   2026-10-06「全面检查」用「真值 vs 写入值」比对发现，这三处在真机上是**平台/构建域**：
        //       真值  ro.product.board=sun   ro.hardware=qcom   ro.build.product=qssi
        //   我们曾写 ro.product.board / ro.hardware = 设备代号 OP61C1L1，写 ro.build.product=代号。
        //   其中 **ro.hardware 最危险**：native 代码大量据此分支（qcom / mtk），
        //   写成一个不存在的值可能让目标 App 走错代码路径 —— 这已经不是「被检测」的问题了。
        //   ro.build.product 在真机上是「构建系统产品名」（qssi，高通 SSI），与机型无关，
        //   我们无法派生 ⇒ 直接不写，保留设备真值（同生态目标就是同一套，天然自洽）。
        putPlatform(m, "ro.product.board", t.board, t);
        // 说明：原先这里还有一条补漏「ro.board.platform / ro.hardware 逐应用档漏写」。
        // 那条判断的前提是「机型库里有平台字段」—— 但库只有 186/749 行有 soc/platform，
        // 目标 PME110 那行是空的，于是归一化用代号兜底 → 写出了上面那三个错值。
        // 现在改成：**没有平台数据就整族不碰**（保留真值）；有数据时 putPlatform 再挡一道
        // 「值等于机型码/代号就不要」的脏数据校验。
        putPlatform(m, "ro.board.platform", t.platform, t);
        putPlatform(m, "ro.hardware", t.hardware, t);
        // ───────────── 构建身份族：**整族原子**（2026-10-06 设计定稿） ─────────────
        // 有真指纹（且与身份字段逐段自洽，见 Target.familyConflict）⇒ 整族一起写；
        // 没有 ⇒ **一个键都不碰**：不写、不删、不打删除标记，Java Build 静态字段也不改这几项。
        // 目标进程于是保留真机自己那套构建身份 —— Build.FINGERPRINT 与属性区的
        // ro.build.fingerprint 都是真值、彼此一致（公开实现同款做法）。
        // 为什么必须严格原子（真机实测的「两条打架」）：
        //   ① 旧版在没有真指纹时把 ro.build.fingerprint / ro.bootimage.build.fingerprint /
        //      ro.build.display.* 交给跨生态清理去**删**，而 id/incremental 又原样留着
        //      ⇒ 同一族里一部分被删、一部分没动，来源之间立刻不一致（Momo 两条并列就出分歧）；
        //   ② 删除哨兵当时是带内的，模块的 Java 侧把 "__DELETE__" 当值塞进 Build.FINGERPRINT
        //      ⇒ 屏幕上第 1 行是 "__DELETE__"、第 2 行是重组出来的混装指纹。
        //   两条都已修：哨兵走独立通道 + 硬断言；身份族既不写也不删。
        // 族的最小集（写在这里，避免下次又漏一半）：
        //   fingerprint 族 + build.id 族 + display.id 族 + description + incremental 族
        //   + release/sdk 族 + security_patch 族 + **type/tags/user/host/date**（指纹第 4 段
        //   是 release、末两段就是 type/tags —— 只写一部分等于让重组值与真指纹在不该变的地方不一致）。
        if (identity) {
            put(m, "ro.build.fingerprint", fp);
            put(m, "ro.build.id", t.buildId);
            put(m, "ro.build.display.id", t.displayId);
            put(m, "ro.build.description", t.description());
            // 不写 ro.build.flavor（真机是 qssi-user，构建系统风味，我们无法派生）——
            // 详见 build() 里同一处的说明。
            put(m, "ro.build.type", t.type);
            put(m, "ro.build.tags", t.tags);
            put(m, "ro.build.user", t.user);
            put(m, "ro.build.host", t.host);
            put(m, "ro.build.date", patchDate);
            put(m, "ro.build.date.utc", patchDateUtc);
            put(m, "ro.build.version.release", t.release);
            put(m, "ro.build.version.sdk", String.valueOf(t.sdk()));
            put(m, "ro.build.version.incremental", t.incremental);
            put(m, "ro.build.version.security_patch", patch);
            put(m, "ro.build.version.codename", "REL");
            // 真机实测：本机 security_patch 类键只有 4 个，其中 ro.vendor.build.security_patch
            // 是**没有 `.version.` 段**的裸名 —— 原先漏写，一行 `getprop ro.vendor.build.security_patch`
            // 就能读回真值反证伪装（公开实现也专门收了这个键）。
            put(m, "ro.vendor.build.security_patch", patch);
            // 版本族补漏（实测本机这些键都存在，且都能从 Target 直接推导 —— 零成本）：
            //   sdk_full 真机是 "36.0" 这种带小数点的写法；preview_sdk 是 0；
            //   release_or_preview_display 与 release 同值。不写就是一行 getprop 的真值。
            put(m, "ro.build.version.preview_sdk", "0");
            put(m, "ro.build.version.sdk_full", t.sdk() + ".0");
            put(m, "ro.build.version.release_or_preview_display", t.release);
            put(m, "ro.build.version.release_or_codename", t.release);
        }

        // 分区副本同步。**但共享镜像的分区不碰**（2026-10-06 全面检查的结论）：
        // 真机上分区分成两组 —— 一部分用机型码，另一部分用**共享系统镜像的通用值**；
        // 本机实测 system/vendor/product/system_ext 是 `ossi` / `oplus/ossi/ossi:…`，
        // 而 odm/bootimage 才是机型码。把通用分区也写成目标机型，等于**比真机更一致**，
        // 反而是个一眼能看出的破绽（一行 diff 即可）。
        // 注意：旧注释里那句「必须整族同步」的硬经验，依据是**被污染的基线**
        // （污染版里每个分区都写着 PLQ110，看起来像「漏写」）—— 干净数据下它们本就不同。
        for (String part : APP_PARTS) {
            if (partIsSharedImage(real, part)) continue;
            put(m, "ro.product." + part + ".model", t.model);
            put(m, "ro.product." + part + ".brand", t.brand);
            put(m, "ro.product." + part + ".manufacturer", t.manufacturer);
            put(m, "ro.product." + part + ".name", t.product);
            put(m, "ro.product." + part + ".device", t.device);
        }

        // 分区 build 族：同样**跳过共享镜像分区**（否则会把 `oplus/ossi/ossi:15/…`
        // 这种带 Android 15 前缀的通用指纹覆盖成我们的机型指纹）。
        // 只写「有明确定义」的那批；sdk_full / media_performance_class / 16k_page 这类
        // 语义不明或机型特有的不碰（写错值比不写更容易被对拍）。
        // 模块侧对不存在的键只返回「缺失」、绝不会新建，所以整族写上不会造出幽灵键。
        // 整族要么全写、要么一个都不出现（不允许「只写了部分分区」——那正是来源之间不一致）。
        if (identity) for (String part : BUILD_PARTS) {
            if (partIsSharedImage(real, part)) continue;
            String pre = "ro." + part + ".build.";
            put(m, pre + "fingerprint", fp);
            put(m, pre + "id", t.buildId);
            put(m, pre + "type", t.type);
            put(m, pre + "tags", t.tags);
            put(m, pre + "date", patchDate);
            put(m, pre + "date.utc", patchDateUtc);
            put(m, pre + "version.release", t.release);
            put(m, pre + "version.release_or_codename", t.release);
            put(m, pre + "version.sdk", String.valueOf(t.sdk()));
            put(m, pre + "version.incremental", t.incremental);
            put(m, pre + "version.security_patch", patch);
        }

        // OPPO/一加 的专有市场名键。**本机实测 `ro.vendor.oplus.market.name` 真实存在**
        // 且当前值是「一加 Ace 6」（真机名）—— 不写它的话，目标进程里
        // `getprop ro.vendor.oplus.market.name` 一行就能读回真机市场名。
        // 公开实现专门为它单列一条，另一类实现也收了这个键。
        // 只写这一处：`ro.product.*.marketname` 那族本机**一个都不存在**，
        // 写了也是被模块跳过（不产生幽灵键，但也没收益）。
        put(m, "ro.vendor.oplus.market.name", t.market);
        put(m, "ro.vendor.oplus.market.enname", t.market);
        return m;
    }

    // --------------------------------------------- 厂商专有键：同源派生清理

    /**
     * 需要「按词元替换」派生的厂商专有键 —— 它们的**值里**嵌着真机型码 / 真品牌。
     *
     * 本机实测（baseline.tsv）有 **69 个属性**的值里带着真机型码
     * （`PLQ110` / `OP6113L1` / `OnePlus`）。上面那张表只覆盖 `ro.product.*` 与
     * `ro.build.*` 族，剩下这一批全是 OPPO/oplus 厂商专有键 —— 不处理的话，
     * 目标进程里 `getprop ro.build.version.ota` 一行就能读回
     * `PLQ110_11.A.57_0570_202608071758`：机型说是 PME110、OTA 串里写着 PLQ110。
     * 这比「补丁日期看着旧」严重得多 —— 它是**可机器校验**的矛盾，正则一跑就出来。
     *
     * 注意 `ro.build.display.id` 也在这里：OPPO/一加 的这个键**不是**构建号
     * （AOSP 里才是），而是 ROM 版本串 `PLQ110_16.0.10.500(CN01)`。原先表里写的是
     * 库里那行的构建号，对 OPPO 设备格式就不对，还和自己的 `ro.build.id` 打架；
     * 派生出来的 `PME110_16.0.10.500(CN01)` 才是这个键在该生态里的正确形态。
     *
     * 注意 `ro.vendor.product.oem`：本机实测它落在 `u:object_r:vendor_default_prop:s0`
     * 属性区，而 **app 进程根本不映射这个区**（Momo 映射 153 个区、唯独不含它）——
     * 也就是该键对 app 不可见，模块会报「缺失」。留着是**防御性**的：
     * 换设备/换内核若把该区映射进来，这一条就能挡住真机型码。
     * 判据与实测方法：逐分区实测 app 进程能看到的属性区，再与真机 dump 比对。
     */
    private static final String[] VENDOR_DERIVE = {
            "ro.build.version.ota",
            "persist.sys.mark_last_upgraded_version",
            "ro.build.display.id",
            "ro.build.display.id.show",
            "ro.build.display.full_id",
            "ro.build.display.ota",
            "persist.sys.oplus.ota_ver_display",
            "ro.oplus.image.my_manifest.version",
            "ro.oplus.version.my_manifest",
            "ro.oplus.image.my_stock.type",
            "ro.oplus.image.my_preload.type",
            "ro.oplus.image.system_ext.brand",
            "ro.com.google.clientidbase",
            "ro.product.cuptsm",
            "ro.vendor.product.oem",
    };

    /**
     * 厂商专有键的「同源派生」清理。
     *
     * 判据沿用本项目的核心原则 —— **每个值都要能回答「从哪儿来」**：
     * 这里只做**词元替换，不编造任何新数据**：
     *   · 真值里的真机型码 / 真代号 / 真品牌词元 → 目标机型码 / 目标代号 / 目标品牌；
     *   · 其余字符（ROM 分支号、版本号、打包日期、后缀哈希）**原样保留**。
     * 于是派生值 = 「这台设备真实 ROM 的描述，只是机型名换成了目标机型」。
     * 对**同品牌**目标（本机一加 → 目标 OPPO，同一套 oplus 生态）这完全合理：
     * 同一条 ROM 分支上的不同机型。
     *
     * 反向保护：替换后与真值**完全相同** ⇒ 这个键的值里没有可替换词元
     * ⇒ **不写**。没有派生依据就宁可让真值留着，也不凭空编 —— 凭空编只会引入
     * 一个新的矛盾（这个项目里「比不伪装更糟」的教训已经出现两次：
     * 补丁日期、Google 构建农场名）。
     *
     * 明确**不碰**的两类（写它们才是更糟）：
     *   · `ro.config.ringtone*` —— 值指向真实存在的系统音频文件，改写会造出
     *     「声明的铃声文件不存在」这个**新的、更硬的**矛盾（本机没有 OPPO 品牌的铃声文件）;
     *   · `*.camera.privapp.list` / `vendor.camera.aux.packagelist` —— 是**已安装包清单**，
     *     改写会与真实安装状态矛盾；而且真值里本来就同时含 `com.oneplus.camera`
     *     与 `com.oppo.camera`（同生态共用），留着并不突兀。
     *
     * @param real 设备原厂真值（{@link Engine#baseline}）；为空则整族跳过
     */
    public static LinkedHashMap<String, String> vendorFamily(Map<String, String> real, Target t) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (real == null || real.isEmpty() || t == null) return m;

        final String rm = real.get("ro.product.model");   // 真机型码，如 PLQ110
        final String rd = real.get("ro.product.device");  // 真代号，如 OP6113L1
        final String rb = real.get("ro.product.brand");   // 真品牌，如 OnePlus
        if (rm == null || rm.isEmpty() || rb == null || rb.isEmpty()) return m;

        // ── 跨生态：只能删，不能派生 ──
        // OPPO / OnePlus / realme 共用 oplus 命名空间，算**同一生态**（所以我们把一加伪装成
        // OPPO 的 Find 是成立的）。换到别的生态（小米/三星…）时，上面那套词元替换就没意义了 ——
        // 一台小米手机身上出现 OPPO 的 ROM 串、OTA 串、oplus 专有键，是**原理性矛盾**，
        // 补键永远盖不住。这正是过去判定「跨品牌不可能」的原因。
        // 现在模块有了 __DELETE__（进程内真删），这条路就通了：把真机那套厂商属性删掉。
        // 删除只发生在**目标进程的私有副本**里 —— 系统框架、其它进程、真机属性都不受影响。
        if (!ecosystemOf(rb).equals(ecosystemOf(t.brand))) {
            return crossBrandDeletes(real, t, rm, rd, rb);
        }

        boolean family = t.familyOk();          // 构建身份族是否整族可用（见 appLevel 的长注释）
        for (String k : VENDOR_DERIVE) {
            String v = real.get(k);
            if (v == null || v.isEmpty()) continue;
            // ro.build.display.* 属于构建身份族：没有真指纹时**一个键都不动**
            // （厂商 ROM 串派生出来的 display.id 也是「身份」，写它就成了半族）
            if (!family && k.startsWith("ro.build.display.")) continue;
            String d = swap(v, rm, t.model, rd, t.device, rb, t.brand);
            if (!d.equals(v)) put(m, k, d);   // 没变 ⇒ 无派生依据 ⇒ 不写
        }

        // 铃声族：真值指向**真实存在的音频文件**，所以既不能凭空编一个文件名
        // （会造出「声明的铃声文件不存在」这种更硬的矛盾），也不该留着真机的品牌名。
        // 解：改用**这台设备自己的 `ro.config.notification_sound`** —— 同族默认值、
        // 文件必然存在（ROM 自带），且已验证不含任何品牌词（真机是 Whoop_doop.ogg）。
        // 它自己若带品牌词就返回 null ⇒ 保持原值不动（宁可不改，也不编）。
        String ring = ringtoneSubstitute(real, rm, rd, rb);
        if (ring != null) {
            for (String rk : RINGTONE_KEYS) {
                String rv = real.get(rk);
                if (rv != null && !rv.isEmpty() && hasVendorToken(rv)) put(m, rk, ring);
            }
        }

        // 产品系列：整族里**唯一**一个不能靠词元替换得到的键 ——
        // 真值 `OnePlus_Ace_Series` 里 `Ace` 既不是机型码也不是品牌词元，
        // 只换品牌会得到 `OPPO_Ace_Series`（Ace 是 OnePlus 独有系列，等于换了个错法）。
        // 按真值给出的命名惯例 `<品牌>_<系列>_Series` 推：系列名取目标市场名的首词
        // （Find X9s Pro → Find）。这是本族唯一的「按惯例推」，代码里单独隔离，
        // 便于日后拿到真值时替换掉。
        String series = real.get("ro.oplus.product.series");
        if (series != null && series.endsWith("_Series")) {
            String line = firstWord(t.market);
            if (!line.isEmpty() && !t.brand.isEmpty()) {
                put(m, "ro.oplus.product.series", t.brand + "_" + line + "_Series");
            }
        }
        putDescription(m, real, t);   // 覆盖 appLevel 那个「从污染基线学来的」形状
        return m;
    }

    /**
     * 只把「真值里的真词元」换成目标词元，其余字符原样。
     * 品牌要覆盖三种写法（`OnePlus` / `oneplus` / `ONEPLUS`）—— 实测这三种在本机
     * 属性里都真实出现（`domestic_OnePlus` / `ro.oplus.image.system_ext.brand=oneplus`
     * / `ro.product.cuptsm=ONEPLUS|ESE|01|27`）。
     */
    private static String swap(String v, String rm, String tm, String rd, String td, String rb, String tb) {
        String s = v;
        if (tm != null && !tm.isEmpty()) s = s.replace(rm, tm);
        if (rd != null && !rd.isEmpty() && td != null && !td.isEmpty()) s = s.replace(rd, td);
        if (tb != null && !tb.isEmpty()) {
            s = s.replace(rb, tb);
            s = s.replace(rb.toLowerCase(), tb.toLowerCase());
            s = s.replace(rb.toUpperCase(), tb.toUpperCase());
        }
        return s;
    }

    /** 市场名的首词 = 产品系列名（`Find X9s Pro` → `Find`）。 */
    private static String firstWord(String s) {
        if (s == null) return "";
        String x = s.trim();
        int sp = x.indexOf(' ');
        return sp > 0 ? x.substring(0, sp) : x;
    }

    // ───────────────── 跨生态清理：用 __DELETE__ 把真机的厂商属性删掉 ─────────────────

    /**
     * 厂商命名空间的常见词元。真机品牌（OnePlus）只是其中之一 —— OPPO 系共用 `oplus`
     * 命名空间。**换到别的厂商时按同样方式补词元**，否则跨生态清理会漏掉那一套。
     */
    private static final String[] VENDOR_TOKENS =
            {"oplus", "oneplus", "realme", "heytap", "coloros", "oppo"};

    // ───────────────────────── 安全补丁：只前进、不后退（2026-10-06 需求定稿）─────────────────────────

    /**
     * 写安全补丁时用的值：**取「目标机型的真实补丁」与「基线里本机的真实补丁」中较新的那个**。
     *
     * 为什么（真机实测）：机型库里 `PKJ110`（Find X8 Ultra）那一行是真实固件 dump ——
     * build id `AP3A.240617.008`、补丁 `2024-06-17`，内部完全自洽；但今天是 2026-10，
     * 一台「新旗舰」两年多没打补丁 ⇒ 检测方直接标「安全更新已过期，设备未修复漏洞」。
     *
     * 为什么允许这么取（不违反「不编造」铁律）：
     *   · 两个值**都是真实值** —— 一个来自机型库的固件 dump，一个来自真值基线里本机的
     *     `ro.build.version.security_patch`（由 Engine.baseline() 注入 Target.devicePatch）；
     *   · 本机自身就是 `build id BP2A.250605.015` + 补丁 `2026-08-01`（补丁晚于 build id 里的
     *     日期）—— 说明这台 ROM 上「补丁晚于构建日期」本来就存在，采用较新值**不会新增**
     *     一类不一致；
     *   · 目标补丁比本机新时（例如目标来自 2026-09 的固件）就写目标的值 —— 那更真实。
     *
     * @param targetPatch   目标机型的真实补丁（机型库那一行）
     * @param baselinePatch 基线里本机的真实补丁（取不到就传空）
     * @return 两者中较新的那个；任一方解析不了就退回可用的那个；都取不到就原样返回目标值
     */
    public static String newerPatch(String targetPatch, String baselinePatch) {
        long a = patchEpoch(targetPatch);
        long b = patchEpoch(baselinePatch);
        if (a < 0) return targetPatch == null ? "" : targetPatch;   // 目标值不合法 ⇒ 原样，不瞎比
        if (b < 0) return targetPatch;                              // 基线取不到 ⇒ 保持现状
        return b > a ? baselinePatch : targetPatch;                 // 相等时用目标值（更贴近机型）
    }

    /**
     * `YYYY-MM-DD` → epoch 秒；非法/空值一律 -1（**不抛异常、不瞎比**）。
     * 只认严格 10 字符 + 两个连字符 + 月日范围 —— 真机上见过 `2024-6-17`、空串这类脏值，
     * 宁可判为「取不到」。
     */
    private static long patchEpoch(String p) {
        if (p == null || p.length() != 10) return -1;
        if (p.charAt(4) != '-' || p.charAt(7) != '-') return -1;
        for (int i = 0; i < 10; i++) {
            if (i == 4 || i == 7) continue;
            char c = p.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        int m = Integer.parseInt(p.substring(5, 7));
        int d = Integer.parseInt(p.substring(8, 10));
        if (m < 1 || m > 12 || d < 1 || d > 31) return -1;
        try {
            return Target.epochOf(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 这一批配置要写出去的安全补丁值（两条路共用：`build()` 传 real=null 时走 Target.devicePatch）。
     * 基线补丁优先取传进来的真值表，取不到再退回 {@link Target#devicePatch}（Engine 注入的本机真值）。
     */
    public static String effectivePatch(Target t, Map<String, String> real) {
        if (t == null) return "";
        String base = real == null ? null : real.get("ro.build.version.security_patch");
        if (base == null || base.isEmpty()) base = Target.devicePatch;
        return newerPatch(t.patch, base);
    }

    /**
     * 这个键属不属于**构建身份族**。
     *
     * 族的定义（写在这里，避免下次又只写一半）：
     *   fingerprint 族 / build.id 族 / display.id 族（含 .show/.ota/.full_id）/ description /
     *   incremental 族 / release 族（含 release_or_codename / release_or_preview_display）/
     *   sdk 族（含 sdk_full / preview_sdk）/ security_patch 族 / type / tags / user / host /
     *   date / date.utc / base_os / preview_sdk_fingerprint —— 裸键与 `ro.<分区>.build.*` 副本都算。
     *
     * 用法只有两处，都是「整族原子」的守卫：
     *   · {@link #crossBrandDeletes}：这一族**永不删除**（删一半比不删更糟）；
     *   · {@link #appLevel} / {@link #build}：没有真指纹时这一族**一个键都不写**。
     */
    public static boolean isIdentityKey(String key) {
        if (key == null) return false;
        if (key.equals("ro.build.fingerprint")) return true;
        if (key.contains(".build.fingerprint")) return true;      // ro.<分区>.build.fingerprint
        if (key.equals("ro.build.id")) return true;
        if (key.endsWith(".build.id")) return true;
        if (key.startsWith("ro.build.display.")) return true;     // id / id.show / full_id / ota
        if (key.equals("ro.build.description")) return true;
        if (key.contains("build.version.incremental")) return true;
        if (key.contains("build.version.release")) return true;   // release / release_or_codename / …
        if (key.contains("build.version.sdk")) return true;       // sdk / sdk_full
        if (key.contains("build.version.preview_sdk")) return true;
        if (key.contains("build.version.base_os")) return true;
        if (key.contains("security_patch")) return true;          // ro[.<分区>].build.security_patch
        for (String t : new String[]{".build.type", ".build.tags", ".build.user", ".build.host",
                                     ".build.date", ".build.date.utc"}) {
            if (key.endsWith(t)) return true;
        }
        return false;
    }

    /**
     * 「机型核心键」—— **永远不删**（2026-10-06 定稿方案 A 的第二条守卫）。
     *
     * 为什么：没有真指纹时我们只写 model，brand/name/device 保持真机真值 —— 这样检测方按
     * Build 字段重组的指纹才与真指纹逐字一致。可是跨生态清理按「值里带真机型码/真品牌」挑键时，
     * `ro.product.brand`（值 `OnePlus`）、`ro.product.name`（值 `PLQ110`）与各分区副本正好都命中：
     * 一删，目标进程里 brand/name 就变成**空值**，重组的第 1/2/3 段直接空掉，两条又不一样了。
     * 所以这一族和构建身份族一样：**要么整族写（有真指纹时由 appLevel 认领），要么一个字节都不动**。
     */
    public static boolean isProductCoreKey(String key) {
        if (key == null) return false;
        if (key.equals("ro.product.marketname")) return true;
        for (String suffix : new String[]{".model", ".brand", ".name", ".device", ".manufacturer"}) {
            if (key.equals("ro.product" + suffix)) return true;
            if (key.startsWith("ro.product.") && key.endsWith(suffix)) return true;
        }
        return false;
    }

    /** 无论如何都不删的键：删了会变成「缺键」这种更硬的矛盾。 */
    private static final java.util.Set<String> NEVER_DELETE = new java.util.HashSet<>(
            java.util.Arrays.asList("ro.serialno", "ro.boot.serialno"));

    /**
     * 一次最多发多少条删除。
     * 模块侧的配置表有 MAX_PROPS（256）上限，超了会**静默截断** ——
     * 逐应用表本身已占 ~173 条，所以这里留 72 条余量（173+72=245 &lt; 256）。
     */
    private static final int MAX_DELETES = 72;

    /** 同一个厂商生态。OPPO / OnePlus / realme 同属 oplus —— 所以「一加伪装成 OPPO」成立。 */
    private static String ecosystemOf(String brand) {
        String b = brand == null ? "" : brand.trim().toLowerCase(java.util.Locale.ROOT);
        if (b.contains("oppo") || b.contains("oneplus") || b.contains("realme")) return "oplus";
        return b;
    }

    /** 键名里含真机厂商命名空间词元吗（对**任意字符串**都适用，值里的文件名同样能查）。 */
    private static boolean isVendorKey(String key) {
        return hasVendorToken(key);
    }

    /** 字符串里含厂商品牌词元吗（不分大小写）。键名与值都可用。 */
    private static boolean hasVendorToken(String s) {
        if (s == null || s.isEmpty()) return false;
        String k = s.toLowerCase(java.util.Locale.ROOT);
        for (String t : VENDOR_TOKENS) {
            if (k.contains(t)) return true;
        }
        return false;
    }

    /** 值里含真机型码 / 真代号 / 真品牌词元吗（品牌不分大小写）。 */
    private static boolean embedsRealId(String v, String rm, String rd, String rb) {
        if (v == null || v.isEmpty()) return false;
        if (v.contains(rm) || v.contains(rd)) return true;
        return v.toLowerCase(java.util.Locale.ROOT).contains(rb.toLowerCase(java.util.Locale.ROOT));
    }

    /** 铃声族的键：值指向真实音频文件，不能凭空编文件名（见 {@link #ringtoneSubstitute}）。 */
    private static final String[] RINGTONE_KEYS =
            {"ro.config.ringtone", "ro.config.ringtone_sim2"};

    /**
     * 铃声族键的「有依据的替换值」。返回 null = 这台设备给不出更好的值，那就谁也别动。
     *
     * 为什么不能像别的键那样做词元替换：`ro.config.ringtone` 的值是一个**文件名**，
     * 文件必须真实存在于铃声目录里。本机实测：目录 79 个文件里只有 3 个带 OnePlus 品牌
     * （`OnePlus_new_feeling.ogg` / `OnePlus_tune.ogg` / `OnePlus_tune_rhythm.ogg`），
     * **没有 OPPO 品牌的对应文件** —— 也就是说「把 OnePlus 换成 OPPO」在这一族里
     * 会直接造出「声明的铃声文件不存在」这个**更硬**的矛盾。
     *
     * 所以改用**同一台设备自己的 `ro.config.notification_sound`**：同族默认值、
     * 文件必然存在（ROM 自带、开机就在用），且真机实测不含任何品牌词
     * （`Whoop_doop.ogg`；`ro.config.alarm_alert` 也是中性的 `Cloudscape.ogg`，
     * 说明该 ROM 本来就用 AOSP 中性名做默认音）。
     * 若它自己带品牌词，返回 null ⇒ 保持原值（宁可不改，也不编一个可能不存在的文件名）。
     */
    private static String ringtoneSubstitute(Map<String, String> real, String rm, String rd, String rb) {
        String notify = real.get("ro.config.notification_sound");
        if (notify == null || notify.isEmpty()) return null;
        if (embedsRealId(notify, rm, rd, rb) || hasVendorToken(notify)) return null;
        return notify;
    }

    /**
     * `ro.build.description` 的**真机形状**：
     * `<flavor> <release> <buildId> <incremental> release-keys`
     * （本机实测 `qssi-user 16 BP2A.250605.015 1785914195466 release-keys`）。
     *
     * 而 `Target.description()` 造的是 `full_<产品名>-user …` —— 那个形状是**从被污染的基线
     * 学来的**（抓基线的时候内存里正跑着全局伪装），真机上根本不是这样。
     * 这里改用**真机的 flavor 前缀** + 我们自己的 buildId/incremental 重排：
     * 既与 `ro.build.id` 保持自洽，形状也对得上。
     * 真机没有 flavor（极少见）时**不写** —— 让真值留着，也不凭空编一个。
     */
    private static void putDescription(LinkedHashMap<String, String> m, Map<String, String> real, Target t) {
        // 构建身份族整族原子：没有真指纹/族不自洽时，description 也不写
        // （它描述的正是那套构建身份，写了就与真机的 id/incremental 打架）
        if (!t.familyOk()) return;
        String fl = real.get("ro.build.flavor");
        if (fl == null || fl.isEmpty()) return;
        // ★ 没有真内部版本号就整条不写：这个形状里第 4 段必须是真 incremental，缺了就是残废串
        //   （会变成「qssi-user 16 BP2A.250605.015  release-keys」两个空格）。宁可缺失，绝不编造。
        if (t.incremental == null || t.incremental.isEmpty()) return;
        put(m, "ro.build.description",
                fl + " " + t.release + " " + t.buildId + " " + t.incremental + " release-keys");
    }

    /**
     * 跨生态清理的键清单。两条并集，**都从真值现算、不写死清单**：
     *   ① 键的**值**里含真机型码 / 真代号 / 真品牌（可机器校验的矛盾，优先）
     *   ② 键名含真机厂商命名空间词元（oplus / oneplus / …）
     *
     * 跳过：逐应用表已经会写干净值的键（那些「改」比「删」好）、NEVER_DELETE，
     * 以及铃声族里**能给出有依据替换值**的键（键还在、文件也在、且不含品牌词 ——
     * 三重都满足，比删掉更好；替换值由 {@link #ringtoneSubstitute} 决定）。
     * 排序后取前 {@link #MAX_DELETES} 条 —— 上限是必要的，见该常量的说明。
     */
    private static LinkedHashMap<String, String> crossBrandDeletes(
            Map<String, String> real, Target t, String rm, String rd, String rb) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        java.util.Set<String> claimed = appLevel(t, real).keySet();   // 已经会写干净值的键
        String ring = ringtoneSubstitute(real, rm, rd, rb);
        java.util.Set<String> ringKeys = new java.util.HashSet<>(
                java.util.Arrays.asList(RINGTONE_KEYS));
        java.util.List<String> strong = new java.util.ArrayList<>();  // 值里带真机型码
        java.util.List<String> weak = new java.util.ArrayList<>();    // 键名在厂商命名空间里
        for (String k : real.keySet()) {
            if (claimed.contains(k) || NEVER_DELETE.contains(k)) continue;
            // ★ 构建身份族**永不删除**（2026-10-06 修缺陷②的第三条）：
            //   删掉 ro.build.fingerprint / ro.bootimage.build.fingerprint / ro.build.display.*
            //   而 id/incremental 又原样留着 ⇒ 同一族里一部分没了、一部分还在，
            //   检测方按 Build 字段重组的指纹与真指纹立刻打架（真机 Momo 实拍）。
            //   正确行为：没有真指纹就**整族一个键都不动**（不写也不删），
            //   目标进程保留真机自己那套、彼此一致。
            if (isIdentityKey(k)) continue;
            // 机型核心键同理：没写它就绝不删它（删了 brand/name 变空值，重组指纹当场对不上）
            if (isProductCoreKey(k)) continue;
            if (ring != null && ringKeys.contains(k)) { m.put(k, ring); continue; }  // 能改就不删
            if (embedsRealId(real.get(k), rm, rd, rb)) strong.add(k);
            else if (isVendorKey(k)) weak.add(k);
        }
        java.util.Collections.sort(strong);      // 顺序稳定，便于 diff / 复现
        java.util.Collections.sort(weak);
        int n = 0;
        for (String k : strong) { if (n >= MAX_DELETES) break; m.put(k, DELETE); n++; }
        for (String k : weak)   { if (n >= MAX_DELETES) break; m.put(k, DELETE); n++; }
        putDescription(m, real, t);   // 同上：description 的形状也要对
        return m;
    }

    /** 逐应用伪装要同步的「机型字段」分区。公开实现的做法：这些分区必须一起改。 */
    private static final String[] APP_PARTS =
            {"system", "vendor", "odm", "product", "system_ext", "bootimage"};

    /** 逐应用伪装要同步的「构建信息」分区（实测本机 9 个分区都有 ro.<分区>.build.* 键）。 */
    private static final String[] BUILD_PARTS =
            {"system", "vendor", "odm", "product", "system_ext", "bootimage",
             "system_dlkm", "vendor_dlkm", "odm_dlkm"};

    private static String field(Target t, String f) {
        switch (f) {
            case "model": return t.model;
            case "brand": return t.brand;
            case "manufacturer": return t.manufacturer;
            case "name": return t.product;
            case "device": return t.device;
            default: return "";
        }
    }
}
