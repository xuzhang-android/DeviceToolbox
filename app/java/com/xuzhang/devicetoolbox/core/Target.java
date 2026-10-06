package com.xuzhang.devicetoolbox.core;


/**
 * 目标机型：我们想伪装成的那台设备。
 *
 * 字段全部用 String，方便序列化与分享码；派生值（指纹 / 安全补丁 / SDK）由本类推导，
 * 保证同一份配置任何时候算出来的结果都一样（确定性，不随机）。
 */
public final class Target {

    public String brand = "";          // 品牌，小写，如 google
    public String manufacturer = "";   // 制造商，如 Google
    public String market = "";         // 通俗名，如「三星 Galaxy S24 Ultra」；空则用 品牌+型号 拼
    public String model = "";          // 型号（机型码），如 SM-S928B
    public String device = "";         // 设备代号，如 husky
    public String product = "";        // 产品名，通常同 device
    public String board = "";          // 主板名
    public String hardware = "";       // 硬件名
    public String soc = "";            // 芯片，如 Tensor G3
    public String platform = "";       // 平台，如 tensor

    public String release = "";        // Android 版本，如 14
    public String buildId = "";        // Build.ID，如 AP4A.250105.002
    public String incremental = "";    // 版本号（内部），如 20250105
    public String patch = "";          // 安全补丁，如 2025-01-05
    public String fingerprint = "";    // 完整指纹；留空则由字段拼
    public String displayId = "";      // 显示版本号
    public String flavor = "";         // 构建风味

    public String type = "user";
    public String tags = "release-keys";
    // ★ 构建来源（user/host）**不再有默认值**。
    //
    // 原先默认是 `android-build` / `abfarm-release` —— 这两个都是 **Google 自己的标识**
    // （AOSP 的标准构建用户 + Google Android Build Farm 的构建机名）。
    // 给一台伪装成「小米 14T Pro」的设备写 Google 的构建农场名，比补丁日期旧更离谱：
    // 这是一眼就能认出的 Google 指纹（真机是 `root` / `dg02-pool06-kvm43`）。
    //
    // 按与补丁日期同一条原则处理：**没有真数据就不写**，让设备真实的值留着。
    // `Props` 侧用的是 `put()`，空值直接跳过 —— 所以这里留空就是「不伪装这两个键」。
    public String user = "";
    public String host = "";
    public String date = "";           // 构建日期，如 Mon Jan 06 00:00:00 UTC 2025
    public String dateUtc = "";        // 同上，epoch 秒

    // ------------------------------------------------------------ 派生

    /** 补全所有派生字段（幂等）。 */
    public Target normalize() {
        if (brand.isEmpty()) brand = "google";
        if (manufacturer.isEmpty()) manufacturer = Device.capitalize(brand);
        if (device.isEmpty()) device = slug(model);
        if (product.isEmpty()) product = device;
        // ★ 平台域的键**不再用 device 代号兜底**（2026-10-06「全面检查」发现）。
        //   真机实测：ro.product.board=sun、ro.hardware=qcom、ro.build.product=qssi ——
        //   全是**平台/构建域**的值，而 device 是机型代号（OP6113L1）。
        //   用代号兜底会写出「ro.hardware = OP61C1L1」这种**在任何真机上都读不出来的值**。
        //   这比「矛盾」更糟：ro.hardware 是 native 代码广泛读取的键（大量分支看它是 qcom
        //   还是 mtk），写错可能让目标 App 的代码**走错分支**。
        //   库里没有平台数据时**留空** ⇒ Props 侧 put() 自动跳过 ⇒ **保留设备真值**：
        //   同生态目标的平台通常就是同一套，真值本身自洽。
        //   （要真正伪装平台，需要「库里有 soc/platform 的那 186 行 + 删掉真机厂商的
        //     ro.vendor.qti.* / ro.mediatek.* 键」——属于后续项，不是兜底能解决的。）
        if (release.isEmpty()) release = "14";
        // ★ 补丁级别**不能只按构建号里的日期推**：真机的构建号日期是「**分支基线日期**」，
        // 而补丁级别随每月更新前进。本机实测：构建号 `BP2A.250605.015`（2025-06），
        // 真实补丁却是 `2026-08-01`。只按构建号推会把补丁**系统性地推旧**，
        // 检测方（Momo）直接点「安全更新已过期，设备存在未修复漏洞」。
        // 所以：构建号里的日期**只有在本身就是近期时**才采信；否则按
        // 「这台机器还活着」的规则取值（`defaultPatch`，仍在支持期的版本取当前月）。
        if (patch.isEmpty()) {
            String fromBuild = patchOf(buildId);
            if (!fromBuild.isEmpty() && !isStalePatch(fromBuild)) patch = fromBuild;
        }
        if (patch.isEmpty()) patch = defaultPatch(release);
        // ★ 构建号**不再编造**（2026-10-06 修缺陷②，与内部版本号同一条铁律）。
        //   这里原本是 `if (buildId.isEmpty()) buildId = buildIdOf(brand, device, patch);`
        //   —— 造出 `UKQ1.260801.009` 这种「品牌前缀 + 补丁月日 + 稳定计数」。它不是任何真机的值，
        //   却会被存进 prefs、显示在界面上，让人以为这台目标「有构建身份」。构建号**只能**来自
        //   真指纹第 5 段（下面 alignToFingerprint 会取）；拿不到就留空 ⇒ identity 门整族不写。
        //   宁可缺失，绝不编造。
        // ★ 内部版本号**不再编造**（2026-10-06 修缺陷②）。
        //   这里原本是 `if (incremental.isEmpty()) incremental = incrementalOf(buildId, patch);`
        //   —— 造出「补丁日期 + 3 位计数」（如 `20260801014`）。真机 OPPO/一加 上**没有这种形状**
        //   （只有 13 位毫秒时间戳或 `<字母>.<hash>_<hash>_<hash>`），而那个值会一路进到
        //   `ro.build.version.incremental` 与指纹第 6 段 —— 等于在设备上盖了一个现实中不存在的构建号。
        //   现在只有**真指纹**能给出它（下面那段「与真指纹对齐」，以及 Library.fillRealFingerprint）；
        //   拿不到就留空 ⇒ Props 的 put() 会跳过这个键 ⇒ **宁可缺失，绝不编造**。
        if (displayId.isEmpty()) displayId = buildId;
        if (flavor.isEmpty()) flavor = flavorOf(brand, product);
        if (date.isEmpty()) date = dateOf(patch);
        if (dateUtc.isEmpty()) dateUtc = String.valueOf(epochOf(patch));
        if (fingerprint.isEmpty()) fingerprint = fingerprint();

        // ★ 指纹是**真相源**：能解析成 8 段就按它把身份字段全部对齐（不是「为空时才填」）。
        //   2026-10-06「全面检查」发现：目标库里 PME110 那一行带着从真机 dump 的完整指纹
        //   `OPPO/PME110/OP61C1L1:16/BP2A.250605.015/B.331e10a-f8b732-f9320b:user/release-keys`，
        //   第 5 段就是构建号、第 6 段就是内部版本号 —— **真数据一直在手边**。
        //   可是 healInventedDates() 把构建号/内部版本号清空后，normalize() 又用
        //   buildIdOf()/incrementalOf() **重新造**了一遍（造出 `SP1A.260801.009` / `20260801056`），
        //   等于拿假数据替掉了真数据。
        //   而且造出来的内部版本号形状不对：真机上 OPPO/一加 只有两种形状 ——
        //   13 位毫秒时间戳（`1758264862075`）或 `<字母>.<hash>_<hash>_<hash>`
        //   （`V.10eb425_12d9949_12ce9c3`）；`20260801056` 两种都不像。
        //   **形状错比数值错更容易被便宜的检查抓到**，所以这里一律以指纹为准。
        //
        //   ★ 2026-10-06 深夜再扩大一次（缺陷②的第二现场，真机 Momo 实测）：
        //     原先只对齐构建号/内部版本号，**产品名/代号/品牌/版本没对齐** ——
        //     而机型库里同一台机器有多个来源，行的列会互相矛盾：
        //       PKJ110（Find X8 Ultra）两行：一行 product=findx8ultra，一行 product=OP5DD3L1，
        //       而两行共用的真指纹第 2/3 段都是 `PKJ110/PKJ110`。
        //     于是我们写出 `ro.product.name=OP5DD3L1` + 指纹 `oppo/PKJ110/PKJ110:…` ——
        //     检测方按 Build 字段重组的指纹（第 2 段取 ro.product.name）与 Build.FINGERPRINT
        //     并排显示成两条不同的指纹（Momo 12:56 实拍），一眼就是「两套值」。
        //     现在 brand/product/device/release 也一起对齐 ⇒ 第 1、2 段必然一致。
        alignToFingerprint();
        return this;
    }

    // ───────────────────────── 真指纹的 8 段解析 + 指纹族自洽门 ─────────────────────────

    /** 真指纹的 8 段：`brand/product/device:release/buildId/incremental:type/tags`。 */
    public static final class Fp {
        public String brand, product, device, release, buildId, incremental, type, tags;
    }

    /**
     * 解析真指纹。**严格 8 段、每段非空**，否则返回 null（= 不是可用的真指纹）。
     *
     * 为什么必须这么严：机型库是若干外部来源合并的，里面有 `" // "` 这种脏值、也有
     * 「指纹与自身列冲突」的行（实测 103 行）。公开实现的通行做法是
     * 「指纹是真相源、只单向补别人」：段数不符/段为空 ⇒ 当作没有真数据，那一族就不再补。
     * 我们照这条做 —— 解析不了就**整族不写**，绝不拿半截指纹去拼。
     */
    public static Fp parseFp(String fp) {
        if (fp == null) return null;
        String[] c = fp.split(":");
        if (c.length != 3) return null;
        String[] a = c[0].split("/", -1);
        String[] b = c[1].split("/", -1);
        String[] t = c[2].split("/", -1);
        if (a.length != 3 || b.length != 3 || t.length != 2) return null;
        Fp p = new Fp();
        p.brand = a[0]; p.product = a[1]; p.device = a[2];
        p.release = b[0]; p.buildId = b[1]; p.incremental = b[2];
        p.type = t[0]; p.tags = t[1];
        String[] all = {p.brand, p.product, p.device, p.release, p.buildId, p.incremental, p.type, p.tags};
        for (String s : all) if (s == null || s.isEmpty()) return null;
        return p;
    }

    /**
     * 用真指纹把身份字段对齐（brand / product / device / release / buildId / incremental /
     * type / tags 八项）。指纹解析不了就**什么都不动**，由 {@link #familyConflict()} 报冲突。
     *
     * @return true = 指纹可解析且已对齐（此后身份字段与指纹逐段一致）
     */
    public boolean alignToFingerprint() {
        Fp p = parseFp(fingerprint);
        if (p == null) return false;
        // ★ 品牌段必须与目标自己的品牌一致（不区分大小写）—— 机型库是多个来源合并的，
        //   实测有 18 行挂着**别的机器**的指纹（例如一行 nothing/A065 挂着
        //   `Micromax/A065/A065:4.4.2/…`）。那种指纹不能拿来对齐，否则会把机型整台改姓；
        //   一个字段都不动 ⇒ familyConflict() 会如实报冲突 ⇒ 构建身份族整族不写（符合定稿行为）。
        if (!segEq(p.brand, brand)) return false;
        brand = p.brand;
        product = p.product;
        device = p.device;
        release = p.release;
        buildId = p.buildId;
        incremental = p.incremental;
        type = p.type;
        tags = p.tags;
        return true;
    }

    /** 不区分大小写的「同一段」判断；任一方为空 ⇒ false（空段永远算冲突）。 */
    private static boolean segEq(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return a.equalsIgnoreCase(b);
    }

    /**
     * **指纹族自洽门**：指纹的 8 段与「这一批要写出去的身份字段」必须逐项一致。
     *
     * 为什么要有这道门（真机教训）：检测方（Momo）会把 Build 的字段重组成一条指纹，
     * 与我们写进去的 `Build.FINGERPRINT` **并排显示**。只要有一段对不上，屏幕上就是
     * 两条互相矛盾的指纹 —— 比「没有指纹」更容易被判定为伪装痕迹：
     *   · 12:56 实拍：`oppo/PKJ110/PKJ110:15/…` vs `oppo/OP5DD3L1/PKJ110:15/…`（第 2 段）
     *   · 13:00 复现：`__DELETE__` vs `xiaomi/lhasa/lhasa:14/BP2A.250605.015/B.19fa…`（半族伪装的产物）
     * 所以写入前必须保证「指纹 ↔ 机型字段 ↔ 构建身份字段」是一个整体；对不上就不写整族。
     *
     * @return "" = 自洽可用；否则返回给界面/日志看的原因（一句话，含具体冲突段）
     */
    public String familyConflict() {
        String fp = fingerprint();
        if (fp == null || fp.isEmpty()) {
            return "这台机型在机型库里没有真指纹（指纹列是空的），产不出可信的构建身份族";
        }
        Fp p = parseFp(fp);
        if (p == null) {
            return "指纹不是 8 段标准形状（" + fp + "），不能当作真数据使用";
        }
        if (!segEq(p.brand, brand)) return "指纹品牌段 " + p.brand + " ≠ 目标品牌 " + brand;
        if (!segEq(p.product, product)) return "指纹产品段 " + p.product + " ≠ 目标产品名 " + product;
        if (!segEq(p.device, device)) return "指纹设备段 " + p.device + " ≠ 目标代号 " + device;
        if (!segEq(p.release, release)) return "指纹系统版本段 " + p.release + " ≠ 目标版本 " + release;
        if (!segEq(p.buildId, buildId)) return "指纹构建号段 " + p.buildId + " ≠ 目标构建号 " + buildId;
        if (!segEq(p.incremental, incremental)) {
            return "指纹内部版本段 " + p.incremental + " ≠ 目标内部版本号 " + incremental;
        }
        return "";
    }

    /** 指纹族自洽（{@link #familyConflict()} 为空）。写入侧用它当 identity 门。 */
    public boolean familyOk() { return familyConflict().isEmpty(); }

    /**
     * `product` / `device` 是不是**机型库里的真实值**？
     *
     * 判据：`normalize()` 在没有来源时会把它们填成 `slug(model)`（机型码压成代号风格）——
     * 那是**推导**，不是数据。等于 slug(model) ⇒ 当作没有真值、**不写**（宁可缺，绝不编）。
     * 真机实测：`2608BPX34C` 那一行库里有 `lhasa`（≠ slug）、`GXQ96` 行有 `tegu`（≠ slug），
     * 都会被正常写出去；而只有机型码、没有代号/产品名的行就不会凭空造一个。
     *
     * 用途：没有真指纹的目标「只写型号族」时，`ro.product.name` / `ro.product.device`
     * 与它们的各分区副本都按这个判据决定写不写（库里有真值才写）。
     */
    public boolean productIsReal() {
        return product != null && !product.isEmpty() && !product.equalsIgnoreCase(slug(model));
    }

    /** 同 {@link #productIsReal()}，针对 device。 */
    public boolean deviceIsReal() {
        return device != null && !device.isEmpty() && !device.equalsIgnoreCase(slug(model));
    }

    /** brand/product/device:release/buildId/incremental:type/tags */
    public String fingerprint() {
        // ★ 有真指纹就**直接用真指纹**（那是最可信的数据）。
        // 这里原本是个空壳 if：注释写着「已构造过就复用」，实际什么都不做 ——
        // 于是即便机型行里带着从真机 dump 出来的指纹，也会被「按字段重建」覆盖掉，
        // 一旦库里的产品名/代号跟指纹不完全一致，写出去的就不是真指纹了。
        // 遵循「能用真数据就用真数据」的原则：**有真数据就直接用，不重建**。
        if (fingerprint != null && !fingerprint.isEmpty() && fingerprint.contains("/")) {
            return fingerprint;
        }
        // ★ 没有真内部版本号 / 真构建号 ⇒ **组不出指纹，返回空串**（2026-10-06 修缺陷②）。
        //   真机指纹的第 5/6 段一定是真实存在的构建号与内部版本号；编一个出来（旧版是
        //   incrementalOf() 的「日期+计数」、兜底的 `RKQ1.<ymd>.001`）就是往设备上盖假值 ——
        //   而检测方读的正是这两段。返回空串 ⇒ Props 的 identity 门整族不写 ⇒
        //   **设备保留它自己的真指纹**（真值、自洽）。
        if (incremental == null || incremental.isEmpty()) return "";
        if (buildId == null || buildId.isEmpty()) return "";
        String b = brand.isEmpty() ? "google" : brand;
        String p = product.isEmpty() ? slug(model) : product;
        String d = device.isEmpty() ? p : device;
        String rel = release.isEmpty() ? "14" : release;
        String out = b + "/" + p + "/" + d + ":" + rel + "/" + buildId + "/" + incremental
                + ":" + type + "/" + tags;
        // 自己拼出来的也必须仍是「8 段合法形状」，否则宁可返回空（防御：字段里混进空段）
        return parseFp(out) == null ? "" : out;
    }

    public String description() {
        // ★ 没有真内部版本号 / 真构建号就**整条不写**（返回空串）：description 的形状是
        //   `<flavor> <release> <buildId> <incremental> release-keys`，缺一段就是残废串；
        //   原先这里还写死了兜底值 `20250105` 与 `RKQ1.<ymd>.001` —— 都是凭空的值。
        //   宁可缺失，绝不编造。
        if (incremental == null || incremental.isEmpty()) return "";
        if (buildId == null || buildId.isEmpty()) return "";
        String rel = release.isEmpty() ? "14" : release;
        return "full_" + (product.isEmpty() ? slug(model) : product) + "-user " + rel + " " + buildId + " "
                + incremental + " release-keys";
    }

    // ------------------------------------------------------ 静态推导规则

    /** Android 版本 → SDK 号。 */
    public static int sdkOf(String release) {
        String r = release == null ? "" : release.trim();
        switch (r) {
            case "5.0": return 21;
            case "5.1": return 22;
            case "6": case "6.0": return 23;
            case "7": case "7.0": return 24;
            case "7.1": return 25;
            case "8": case "8.0": return 26;
            case "8.1": return 27;
            case "9": return 28;
            case "10": return 29;
            case "11": return 30;
            case "12": case "12L": return 32;
            case "12.0": return 31;
            case "13": return 33;
            case "14": return 34;
            case "15": return 35;
            case "16": return 36;
            // ★ Android 16 以上**按递推**，不再写死 36（2026-10-06 审计 M1(a) 同族的真机发现）：
            //   数据代理把机型库扩到含 Android 17 的行之后，写死的 `d >= 16 ⇒ 36` 会让
            //   Android 17 的目标写出 `ro.build.version.sdk = 36` —— 而 Android 17 实际是 **37**，
            //   变成「release=17 + sdk=36」这种**可机器校验的矛盾**。
            //   递推规则：sdk = 36 + floor(release - 16) ⇒ 16→36 · 17→37 · 18→38 …
            //   （release 可能是 "17.1" 这类小版本号，取整后仍正确）
            default:
                try {
                    double d = Double.parseDouble(r);
                    if (d >= 16) return 36 + (int) Math.floor(d - 16);
                    if (d >= 15) return 35;
                    if (d >= 14) return 34;
                    return 34;
                } catch (Throwable t) {
                    return 34;
                }
        }
    }

    public int sdk() { return sdkOf(release); }

    /** 从 Build.ID 里的日期段抠出安全补丁日期，形如 2025-01-05。 */
    public static String patchOf(String buildId) {
        if (buildId == null) return "";
        String[] parts = buildId.split("[.\\-_]");
        for (String p : parts) {
            if (p.length() == 6 && p.matches("\\d{6}")) {
                int yy = Integer.parseInt(p.substring(0, 2));
                int mm = Integer.parseInt(p.substring(2, 4));
                int dd = Integer.parseInt(p.substring(4, 6));
                if (mm >= 1 && mm <= 12 && dd >= 1 && dd <= 31) {
                    return String.format("%04d-%02d-%02d", 2000 + yy, mm, dd);
                }
            }
        }
        return "";
    }

    /**
     * 没有真实日期信息时的补丁日期。
     *
     * ★ 这里原本是一张**按 SDK 写死的表**（SDK 34 → 2024-06-05）。实测后果很糟：
     * 机型库里 `2407FPN8EG`（小米 14T Pro）那一行的构建号/内部版本/指纹/补丁**都是空的**，
     * 于是走进这张表，把 `2024-06-05` 盖到了**全部 6 个 security_patch 键**上 ——
     * 而真机补丁是 `2026-08-01`。Momo 直接把这条点了出来：
     * 「安全更新已过期，设备存在未修复漏洞」。**等于我们比不伪装更糟**
     * （什么都不写的话，真机那个近期值就原样留着了）。
     *
     * 对比公开实现的内置模板：156 个里 **0 个**带 security_patch、
     * 常见实现的内置模板里只有极少数 —— 它们根本不写这些键，所以没有这个问题。
     * 它们的原则是「不凭空编数据」。
     *
     * 但我们不能完全照抄「不写」：指纹里带着品牌/产品/代号，不写指纹会让
     * 「真机指纹（一加）↔ 假机型（小米）」**直接矛盾**，比日期旧更糟。
     * 所以要写，只是**按「这台机器现在还活着」来写，而不是按「这个 Android 版本发布的年代」**。
     *
     * 规则：仍在支持期的版本（Android 13 / SDK 33 及以上）→ 取**当前月**的补丁级别
     * （Android 惯例是每月 5 号）；更老的版本确实早已停更，沿用历史值反而更贴近真实。
     */
    public static String defaultPatch(String release) {
        int sdk = sdkOf(release);
        // ★ 最优先：**宿主设备真实的补丁级别**（由 App 从 baseline.tsv 注入）。
        // 理由（用户指出后修正）：原先我取「当月」—— 那意味着「补丁发布当天就装上了」，
        // 太激进；实测宿主是 2026-08-01，比当月落后两个月，那才是「一台正常在用、
        // 按节奏更新」的样子。而补丁级别是 AOSP 的**月度级别、不区分机型**，
        // 所以目标机型沿用宿主的值完全合理，而且「不会被判过期」这条也满足。
        // 只对仍在支持期的版本这么做；老机型（≤ Android 12）早就停更，用宿主的近期值反而离谱。
        if (sdk >= 33 && devicePatch != null && !devicePatch.isEmpty()) return devicePatch;
        if (sdk >= 33) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            return String.format("%04d-%02d-05", c.get(java.util.Calendar.YEAR),
                                 c.get(java.util.Calendar.MONTH) + 1);
        }
        switch (sdk) {
            case 32: case 31: return "2022-10-05";
            case 30: return "2021-10-05";
            case 29: return "2020-10-05";
            default: return "2019-10-05";
        }
    }

    /** 宿主设备真实的补丁级别，由 App 从 baseline.tsv 注入（拿不到就是空串）。 */
    public static String devicePatch = "";

    /**
     * 迁移：把旧版本**发明出来的**日期族清掉，让它按新规则重新派生。
     *
     * 背景：旧版的 `defaultPatch` 是一张按 SDK 写死的表（SDK 34 → 2024-06-05 等）。
     * 选机型时算好的这些值会被 `serialize()` 存进 SharedPreferences，之后 `normalize()`
     * 只填**空**字段，所以光改 `defaultPatch()` 对**已经存过的目标**不生效 ——
     * 那台机器会一直顶着 2 年前的补丁日期，被检测方点「安全更新已过期」。
     *
     * 这里只在「补丁值**正好等于**旧表里那 7 个值之一」时才清 ——
     * 也就是能确定「这是我们当年编的」的情况。用户手填的、机型库带来的真值一律不动。
     * 代价是：万一某台机型的真实补丁恰好是这 7 个值之一，会被重算成近期值 ——
     * 可接受（近期值本身更贴近一台在用的设备）。
     */
    public static void healInventedDates(Target t) {
        if (t == null) return;
        // ① 构建来源：旧版默认写死了 Google 的标识，一律清掉（让真值留着）。
        //    这一段**不依赖补丁是否命中**，所以放在前面的提前返回之前。
        if ("android-build".equals(t.user)) t.user = "";
        if ("abfarm-release".equals(t.host)) t.host = "";
        if (t.patch == null) return;
        // ② 补丁日期：只在「正好等于旧表那 7 个值之一」时才清（能确定是我们编的）
        String[] invented = {"2024-12-05", "2024-06-05", "2023-10-05", "2022-10-05",
                             "2021-10-05", "2020-10-05", "2019-10-05"};
        boolean hit = false;
        for (String p : invented) if (p.equals(t.patch)) { hit = true; break; }
        // ②-b 上一版把补丁取成「当月」，会显得**比宿主还新**（实测宿主 8 月、我们写了 10 月）——
        // 那正是用户指出的「太新了」。这里精确命中「当月那个值」才清，不碰任何真值。
        if (!hit) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            String thisMonth = String.format("%04d-%02d-05", c.get(java.util.Calendar.YEAR),
                                             c.get(java.util.Calendar.MONTH) + 1);
            hit = thisMonth.equals(t.patch);
        }
        // ②-c 内部版本号：旧版 incrementalOf() 造的是「补丁日期 + 3 位计数」——
        //      `20` + yymmdd(patch) + NNN，共 **11 位**。**精确命中才清**（前缀正好是本条 patch 的日期
        //      且整体是 11 位数字），真机的 13 位毫秒时间戳与 `<字母>.<hash>_…` 都不会被误伤。
        //      清掉之后 normalize() 不再编造，改由 Library.fillRealFingerprint() 从机型库取真指纹，
        //      内部版本号随之变成真值；库里也没有 ⇒ 这个键就不写（宁可缺失，绝不编造）。
        if (!hit && t.patch != null && t.patch.matches("\\d{4}-\\d{2}-\\d{2}")
                && t.incremental != null && t.incremental.matches("20\\d{9}")) {
            String pre = "20" + t.patch.substring(2, 4) + t.patch.substring(5, 7) + t.patch.substring(8, 10);
            hit = t.incremental.startsWith(pre);
        }
        if (!hit) return;
        t.patch = "";
        t.buildId = "";
        t.incremental = "";
        t.date = "";
        t.dateUtc = "";
        t.fingerprint = "";     // 指纹里也带着那个旧构建号，一起重算
        t.normalize();
    }

    /**
     * 这个补丁日期是不是「旧得不像一台在用的设备」？超过 180 天即视为旧。
     *
     * 为什么需要它：构建号里的日期是**分支基线日期**，不是当前补丁级别
     * （本机实测：构建号 BP2A.250605.015 对应 2025-06，而真实补丁是 2026-08-01）。
     * 直接采信会得到一个「一年多没更新」的设备 —— 那是很显眼的信号。
     */
    private static boolean isStalePatch(String patch) {
        if (patch == null || !patch.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        long e = epochOf(patch);
        long now = System.currentTimeMillis() / 1000L;
        return (now - e) > 180L * 86400L;
    }

    /** 生成一个合理且确定的 Build.ID。 */
    public static String buildIdOf(String brand, String device, String patch) {
        String ymd = ymdOf(patch);
        String prefix = prefixOf(brand);
        return prefix + "." + ymd + "." + String.format("%03d", (stable(device) % 20) + 1);
    }

    public static String prefixOf(String brand) {
        String b = brand == null ? "" : brand.toLowerCase();
        switch (b) {
            case "google": return "AP4A";
            case "samsung": return "UP1A";
            case "xiaomi": case "redmi": case "poco": return "UKQ1";
            case "oneplus": return "IN2023";
            case "oppo": case "realme": case "oneplus-oppo": return "SP1A";
            case "vivo": case "iqoo": return "PD2";
            case "huawei": case "honor": return "HUAWEI";
            case "sony": return "A201SO";
            case "motorola": return "T2SNS33";
            case "asus": return "WW_AI2201";
            case "nothing": return "AP2A";
            default: return "RKQ1";
        }
    }

    public static String ymdOf(String patch) {
        if (patch != null && patch.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return patch.substring(2, 4) + patch.substring(5, 7) + patch.substring(8, 10);
        }
        return "250105";
    }

    // ★ 这里原本有个 `incrementalOf(buildId, patch)`：按「补丁日期 + 3 位计数」造内部版本号
    //   （`20260801014`）。该形状在真机 OPPO/一加 上**不存在**（真机只有 13 位毫秒时间戳或
    //   `<字母>.<hash>_<hash>_<hash>`）—— 属于「写了一个现实中不可能是这样的值」，而且它会进到
    //   `ro.build.version.incremental` 与指纹第 6 段（缺陷②）。已整个删掉：
    //   内部版本号**只能**来自真指纹（见 normalize() 末尾的对齐），拿不到真数据就不写这个键。

    /**
     * 构建风味（flavor）。
     *
     * 真机实测 OPPO/一加 的惯例是 `<品牌>-<机型码>-user`（本机 `OnePlus-PLQ110-user`）——
     * **用机型码，不是设备代号**。原先传的是 device，于是写出 `OPPO-OP61C1L1-user`，
     * 与真机形状不符（2026-10-06「全面检查」的「真值 vs 写入值」比对发现）。
     * 改传 product：在 OPPO/一加 上它等于机型码（PLQ110），在 AOSP 系上是产品代号
     * （mustang）—— 两种都比设备代号更贴近真机。
     */
    public static String flavorOf(String brand, String product) {
        return (brand == null || brand.isEmpty() ? "generic" : brand) + "-"
                + (product == null || product.isEmpty() ? "device" : product) + "-user";
    }

    /** 构建日期串（确定性）。 */
    public static String dateOf(String patch) {
        int[] ymd = ymd(patch);
        return weekday(ymd) + " " + month(ymd[1]) + " " + String.format("%02d", ymd[2])
                + " 00:00:00 UTC " + ymd[0];
    }

    public static long epochOf(String patch) {
        int[] d = ymd(patch);
        // 用 days-from-civil 算法算 epoch，避免依赖 Calendar 的时区
        int y = d[0], m = d[1], day = d[2];
        int yy = y - (m <= 2 ? 1 : 0);
        int era = (yy >= 0 ? yy : yy - 399) / 400;
        int yoe = yy - era * 400;
        int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        long days = (long) era * 146097 + doe - 719468;
        return days * 86400L;
    }

    private static int[] ymd(String patch) {
        if (patch != null && patch.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                return new int[]{Integer.parseInt(patch.substring(0, 4)),
                        Integer.parseInt(patch.substring(5, 7)),
                        Integer.parseInt(patch.substring(8, 10))};
            } catch (Throwable ignored) { }
        }
        return new int[]{2025, 1, 5};
    }

    private static String month(int m) {
        String[] M = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return (m >= 1 && m <= 12) ? M[m - 1] : "Jan";
    }

    private static String weekday(int[] d) {
        long days = epochOf(String.format("%04d-%02d-%02d", d[0], d[1], d[2])) / 86400L;
        // 1970-01-01 是周四
        String[] W = {"Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"};
        int idx = (int) (((days % 7) + 7) % 7);
        return W[idx];
    }

    /** 把型号名压成代号风格：小写、去空格、保留字母数字下划线。 */
    public static String slug(String s) {
        if (s == null) return "device";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else if (c == ' ' || c == '-' || c == '_') sb.append('_');
        }
        String out = sb.toString().replaceAll("_+", "_");
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? "device" : out;
    }

    /** 稳定哈希（不用 String.hashCode，跨版本也一致）。 */
    public static int stable(String s) {
        int h = 0x811C9DC5; // FNV-1a 32 位偏移基数
        if (s == null) s = "";
        for (byte b : s.getBytes()) {
            h ^= (b & 0xff);
            h *= 16777619;
        }
        return Math.abs(h);
    }

    // ------------------------------------------------------------ 序列化

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        put(sb, "brand", brand); put(sb, "manufacturer", manufacturer);
        put(sb, "market", market); put(sb, "model", model);
        put(sb, "device", device); put(sb, "product", product); put(sb, "board", board);
        put(sb, "hardware", hardware); put(sb, "soc", soc); put(sb, "platform", platform);
        put(sb, "release", release); put(sb, "buildId", buildId); put(sb, "incremental", incremental);
        put(sb, "patch", patch); put(sb, "fingerprint", fingerprint); put(sb, "displayId", displayId);
        put(sb, "flavor", flavor); put(sb, "type", type); put(sb, "tags", tags);
        put(sb, "user", user); put(sb, "host", host); put(sb, "date", date); put(sb, "dateUtc", dateUtc);
        return sb.toString();
    }

    private static void put(StringBuilder sb, String k, String v) {
        if (v == null || v.isEmpty()) return;
        sb.append(k).append('=').append(esc(v)).append('\n');
    }

    public static Target parse(String text) {
        Target t = new Target();
        if (text == null) return t.normalize();
        for (String line : text.split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            String v = unesc(line.substring(i + 1));
            switch (k) {
                case "brand": t.brand = v; break;
                case "manufacturer": t.manufacturer = v; break;
                case "market": t.market = v; break;
                case "model": t.model = v; break;
                case "device": t.device = v; break;
                case "product": t.product = v; break;
                case "board": t.board = v; break;
                case "hardware": t.hardware = v; break;
                case "soc": t.soc = v; break;
                case "platform": t.platform = v; break;
                case "release": t.release = v; break;
                case "buildId": t.buildId = v; break;
                case "incremental": t.incremental = v; break;
                case "patch": t.patch = v; break;
                case "fingerprint": t.fingerprint = v; break;
                case "displayId": t.displayId = v; break;
                case "flavor": t.flavor = v; break;
                case "type": t.type = v; break;
                case "tags": t.tags = v; break;
                case "user": t.user = v; break;
                case "host": t.host = v; break;
                case "date": t.date = v; break;
                case "dateUtc": t.dateUtc = v; break;
                default: break;
            }
        }
        return t;
    }

    public static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "");
    }

    public static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    public Target copy() { return parse(serialize()); }

    /** 展示名：优先用通俗名，没有才退化成 品牌 + 机型码。 */
    public String title() {
        if (market != null && !market.isEmpty()) return market;
        String b = manufacturer == null || manufacturer.isEmpty() ? Device.capitalize(brand) : manufacturer;
        return (b + " " + model).trim();
    }

}
