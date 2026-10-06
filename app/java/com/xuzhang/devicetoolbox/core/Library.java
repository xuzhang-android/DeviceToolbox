package com.xuzhang.devicetoolbox.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置机型库 + 自定义机型生成器。
 *
 * 一行一台机，字段顺序：
 *   品牌 | 制造商 | 通俗名 | 机型码(ro.product.model) | 代号(ro.product.device) | 芯片 | 平台 | Android 版本
 *
 * 设计取舍：
 *   - 「通俗名」是给人在列表里认的（三星 Galaxy S24 Ultra），机型码才是写进系统的；
 *   - 代号不确定的留空，由 Target.normalize() 从机型码推导 —— 宁可推导，也不编一个假的；
 *   - 库可以整体用 JSON 导入覆盖，用户能自己补。
 */
public final class Library {

    private static final String[] DATA = {
            // ---------------- Google Pixel（Pixel 的 model 就是通俗名）----------------
            "google|Google|谷歌 Pixel 9 Pro XL|Pixel 9 Pro XL|komodo|Tensor G4|zumapro|15",
            "google|Google|谷歌 Pixel 9 Pro|Pixel 9 Pro|caiman|Tensor G4|zumapro|15",
            "google|Google|谷歌 Pixel 9|Pixel 9|tokay|Tensor G4|zumapro|15",
            "google|Google|谷歌 Pixel 8 Pro|Pixel 8 Pro|husky|Tensor G3|zuma|14",
            "google|Google|谷歌 Pixel 8|Pixel 8|shiba|Tensor G3|zuma|14",
            "google|Google|谷歌 Pixel 8a|Pixel 8a|akita|Tensor G3|zuma|14",
            "google|Google|谷歌 Pixel 7 Pro|Pixel 7 Pro|cheetah|Tensor G2|gs201|13",
            "google|Google|谷歌 Pixel 7|Pixel 7|panther|Tensor G2|gs201|13",
            "google|Google|谷歌 Pixel 7a|Pixel 7a|lynx|Tensor G2|gs201|13",
            "google|Google|谷歌 Pixel 6 Pro|Pixel 6 Pro|raven|Tensor|gs101|12",
            "google|Google|谷歌 Pixel 6|Pixel 6|oriole|Tensor|gs101|12",
            "google|Google|谷歌 Pixel 6a|Pixel 6a|bluejay|Tensor|gs101|12",
            "google|Google|谷歌 Pixel 5|Pixel 5|redfin|骁龙 765G|sm7250|11",

            // ---------------- 三星 ----------------
            "samsung|samsung|三星 Galaxy S24 Ultra|SM-S928B|e3q|骁龙 8 Gen 3|pineapple|14",
            "samsung|samsung|三星 Galaxy S24+|SM-S926B|e2q|Exynos 2400|s5e9945|14",
            "samsung|samsung|三星 Galaxy S24|SM-S921B|e1q|Exynos 2400|s5e9945|14",
            "samsung|samsung|三星 Galaxy S23 Ultra|SM-S918B|dm3q|骁龙 8 Gen 2|kalama|13",
            "samsung|samsung|三星 Galaxy S23+|SM-S916B|dm2q|骁龙 8 Gen 2|kalama|13",
            "samsung|samsung|三星 Galaxy S23|SM-S911B|dm1q|骁龙 8 Gen 2|kalama|13",
            "samsung|samsung|三星 Galaxy S22 Ultra|SM-S908B|b0q|骁龙 8 Gen 1|waipio|12",
            "samsung|samsung|三星 Galaxy S22+|SM-S906B|g0q|骁龙 8 Gen 1|waipio|12",
            "samsung|samsung|三星 Galaxy S22|SM-S901B|r0q|Exynos 2200|s5e9925|12",
            "samsung|samsung|三星 Galaxy S21 Ultra|SM-G998B|o1q|Exynos 2100|exynos2100|11",
            "samsung|samsung|三星 Galaxy S21|SM-G991B|o1q|Exynos 2100|exynos2100|11",
            "samsung|samsung|三星 Galaxy S20 Ultra|SM-G988B|z3q|Exynos 990|exynos990|10",
            "samsung|samsung|三星 Galaxy S20|SM-G981B|x1q|Exynos 990|exynos990|10",
            "samsung|samsung|三星 Galaxy Note20 Ultra|SM-N986B|c2q|骁龙 865+|kona|10",
            "samsung|samsung|三星 Galaxy Z Fold6|SM-F956B|q6q|骁龙 8 Gen 3|pineapple|14",
            "samsung|samsung|三星 Galaxy Z Fold5|SM-F946B|q5q|骁龙 8 Gen 2|kalama|13",
            "samsung|samsung|三星 Galaxy Z Flip6|SM-F741B|b6q|骁龙 8 Gen 3|pineapple|14",
            "samsung|samsung|三星 Galaxy Z Flip5|SM-F731B|b5q|骁龙 8 Gen 2|kalama|13",
            "samsung|samsung|三星 Galaxy A55|SM-A556B|a55x|Exynos 1480|s5e8845|14",
            "samsung|samsung|三星 Galaxy A54|SM-A546B|a54x|Exynos 1380|s5e8835|13",
            "samsung|samsung|三星 Galaxy A34|SM-A346B|a34x|天玑 1080|mt6877|13",
            "samsung|samsung|三星 Galaxy A25|SM-A256B|a25x|Exynos 1280|s5e8825|14",
            "samsung|samsung|三星 Galaxy A15|SM-A155F|a15|Helio G99|mt6789|14",
            "samsung|samsung|三星 Galaxy A14|SM-A145F|a14|Helio G80|mt6769|13",
            "samsung|samsung|三星 Galaxy M54|SM-M546B|m54x|Exynos 1380|s5e8835|13",
            "samsung|samsung|三星 Galaxy F54|SM-F546B|f54x|Exynos 1380|s5e8835|13",
            "samsung|samsung|三星 Galaxy Tab S9|SM-X710|gts9wifi|骁龙 8 Gen 2|kalama|13",

            // ---------------- 小米 / 红米 / POCO ----------------
            "xiaomi|Xiaomi|小米 14 Ultra|2405CPX3DC|aurora|骁龙 8 Gen 3|pineapple|14",
            "xiaomi|Xiaomi|小米 14 Pro|23116PN5BC|shennong|骁龙 8 Gen 3|pineapple|14",
            "xiaomi|Xiaomi|小米 14|23127PN0CC|houji|骁龙 8 Gen 3|pineapple|14",
            "xiaomi|Xiaomi|小米 13 Ultra|2304FPN6DC|ishtar|骁龙 8 Gen 2|kalama|13",
            "xiaomi|Xiaomi|小米 13 Pro|2210132C|nuwa|骁龙 8 Gen 2|kalama|13",
            "xiaomi|Xiaomi|小米 13|2211133C|fuxi|骁龙 8 Gen 2|kalama|13",
            "xiaomi|Xiaomi|小米 13 Lite|2210129SG|ziyi|骁龙 7 Gen 1|cape|13",
            "xiaomi|Xiaomi|小米 12S Ultra|2203121C|thor|骁龙 8+ Gen 1|cape|12",
            "xiaomi|Xiaomi|小米 12 Pro|2201122C|zeus|骁龙 8 Gen 1|waipio|12",
            "xiaomi|Xiaomi|小米 12|2201123C|cupid|骁龙 8 Gen 1|waipio|12",
            "xiaomi|Xiaomi|小米 11|M2011K2C|venus|骁龙 888|lahaina|11",
            "xiaomi|Xiaomi|小米 MIX Fold 3|2308CPXD0C|babylon|骁龙 8 Gen 2|kalama|13",
            "xiaomi|Xiaomi|小米 Civi 3|23046PNC9C|yuechu|天玑 8200 Ultra|mt6896|13",
            "redmi|Xiaomi|红米 K70 Pro|23117RK66C|manet|骁龙 8 Gen 3|pineapple|14",
            "redmi|Xiaomi|红米 K70|23113RKC6C|vermeer|骁龙 8 Gen 2|kalama|14",
            "redmi|Xiaomi|红米 K60|23013RK75C|mondrian|骁龙 8+ Gen 1|cape|13",
            "redmi|Xiaomi|红米 K50|22041211AC|rubens|天玑 8100|mt6895|12",
            "redmi|Xiaomi|红米 Note 13 Pro+|23090RA98C|zircon|天玑 7200 Ultra|mt6789|13",
            "redmi|Xiaomi|红米 Note 13 Pro|2312DRAABC|garnet|骁龙 7s Gen 2|crow|13",
            "redmi|Xiaomi|红米 Note 12|22101316C|tapas|骁龙 685|sm6225|13",
            "redmi|Xiaomi|红米 Note 11|21091116C|spes|骁龙 680|sm6225|11",
            "redmi|Xiaomi|红米 13C|23100RN82L|gale|Helio G85|mt6769|14",
            "redmi|Xiaomi|红米 Turbo 3|24069RA21C|duchamp|骁龙 8s Gen 3|pineapple|14",
            "poco|Xiaomi|POCO F5 Pro|23013PC75G|mondrian|骁龙 8+ Gen 1|cape|13",
            "poco|Xiaomi|POCO F5|23049PCD8G|marble|骁龙 7+ Gen 2|crow|13",
            "poco|Xiaomi|POCO X6 Pro|24069PC21G|duchamp|天玑 8300 Ultra|mt6897|14",
            "poco|Xiaomi|POCO X5 Pro|22101320G|redwood|骁龙 778G|sm7325|13",

            // ---------------- 一加 ----------------
            "oneplus|OnePlus|一加 12|CPH2573|waffle|骁龙 8 Gen 3|pineapple|14",
            "oneplus|OnePlus|一加 12R|CPH2585|aston|骁龙 8 Gen 2|kalama|14",
            "oneplus|OnePlus|一加 11|CPH2449|udon|骁龙 8 Gen 2|kalama|13",
            "oneplus|OnePlus|一加 10 Pro|NE2210|ovaltine|骁龙 8 Gen 1|waipio|12",
            "oneplus|OnePlus|一加 9 Pro|LE2120|lemonade|骁龙 888|lahaina|11",
            "oneplus|OnePlus|一加 Ace 2 Pro|PJA110||骁龙 8 Gen 2|kalama|13",
            "oneplus|OnePlus|一加 Nord 3|CPH2493||天玑 9000|mt6983|13",

            // ---------------- OPPO / realme ----------------
            "oppo|OPPO|OPPO Find X7 Ultra|PHY110||骁龙 8 Gen 3|pineapple|14",
            "oppo|OPPO|OPPO Find X6 Pro|PGEM10|wukong|骁龙 8 Gen 2|kalama|13",
            "oppo|OPPO|OPPO Find N3|PHN110||骁龙 8 Gen 2|kalama|13",
            "oppo|OPPO|OPPO Reno 11|CPH2603||天玑 8200|mt6896|14",
            "oppo|OPPO|OPPO Reno 10|CPH2531||天玑 7050|mt6855|13",
            "oppo|OPPO|OPPO A78|CPH2565||天玑 700|mt6833|13",
            "realme|realme|真我 GT5 Pro|RMX3888||骁龙 8 Gen 3|pineapple|14",
            "realme|realme|真我 GT Neo5|RMX3706||骁龙 8+ Gen 1|cape|13",
            "realme|realme|真我 11 Pro+|RMX3741||天玑 7050|mt6855|13",

            // ---------------- vivo / iQOO ----------------
            "vivo|vivo|vivo X100 Pro|V2309A||天玑 9300|mt6989|14",
            "vivo|vivo|vivo X90 Pro|V2219A||天玑 9200|mt6985|13",
            "vivo|vivo|vivo S18|V2323A||骁龙 7 Gen 3|crow|14",
            "vivo|vivo|vivo Y100|V2313A||天玑 7200|mt6789|14",
            "iqoo|vivo|iQOO 12|V2307A||骁龙 8 Gen 3|pineapple|14",
            "iqoo|vivo|iQOO 11|V2243A||骁龙 8 Gen 2|kalama|13",
            "iqoo|vivo|iQOO Neo9|V2338A||骁龙 8 Gen 2|kalama|14",

            // ---------------- 荣耀 / 华为 ----------------
            "honor|HONOR|荣耀 Magic6 Pro|BVL-N49||骁龙 8 Gen 3|pineapple|14",
            "honor|HONOR|荣耀 Magic5 Pro|PGT-N19||骁龙 8 Gen 2|kalama|13",
            "honor|HONOR|荣耀 100 Pro|MAA-AN00||骁龙 8 Gen 2|kalama|14",
            "honor|HONOR|荣耀 X50|ALI-NX1||骁龙 6 Gen 1|parrot|13",
            "huawei|HUAWEI|华为 Mate 60 Pro|ALN-AL00|aln|麒麟 9000S|kirin9000s|12",
            "huawei|HUAWEI|华为 P60 Pro|MNA-AL00||骁龙 8+ Gen 1|cape|13",
            "huawei|HUAWEI|华为 nova 12|ADA-AL00||麒麟 8000|kirin8000|14",

            // ---------------- 索尼 / 华硕 ----------------
            "sony|Sony|索尼 Xperia 1 VI|XQ-EC72||骁龙 8 Gen 3|pineapple|14",
            "sony|Sony|索尼 Xperia 1 V|XQ-DQ72|pdx234|骁龙 8 Gen 2|kalama|13",
            "sony|Sony|索尼 Xperia 5 V|XQ-DE72|pdx237|骁龙 8 Gen 2|kalama|13",
            "sony|Sony|索尼 Xperia 10 V|XQ-DC72|pdx235|骁龙 695|holi|13",
            "asus|asus|华硕 ROG Phone 8|AI2401||骁龙 8 Gen 3|pineapple|14",
            "asus|asus|华硕 ROG Phone 7|AI2205||骁龙 8 Gen 2|kalama|13",
            "asus|asus|华硕 Zenfone 10|ASUS_AI2302||骁龙 8 Gen 2|kalama|13",

            // ---------------- Nothing / 摩托罗拉 ----------------
            "nothing|Nothing|Nothing Phone (2a)|A142|pacman|天玑 7200 Pro|mt6886|14",
            "nothing|Nothing|Nothing Phone (2)|A065|spacewar|骁龙 8+ Gen 1|cape|13",
            "nothing|Nothing|Nothing Phone (1)|A063|Spacewar|骁龙 778G+|sm7325|12",
            "motorola|motorola|摩托罗拉 Edge 50 Pro|XT2403||骁龙 7 Gen 3|crow|14",
            "motorola|motorola|摩托罗拉 Edge 40|XT2303||天玑 8020|mt6893|13",
            "motorola|motorola|摩托罗拉 Razr 40 Ultra|XT2321||骁龙 8+ Gen 1|cape|13",

            // ---------------- 红魔 / 魅族 / 中兴 ----------------
            "nubia|nubia|红魔 9 Pro|NX769J||骁龙 8 Gen 3|pineapple|14",
            "nubia|nubia|红魔 8 Pro|NX729J||骁龙 8 Gen 2|kalama|13",
            "meizu|Meizu|魅族 21|M461Q||骁龙 8 Gen 3|pineapple|14",
            "meizu|Meizu|魅族 20|M381Q||骁龙 8 Gen 2|kalama|13",
            "zte|ZTE|中兴 Axon 50 Ultra|A2023||骁龙 8 Gen 2|kalama|13",
    };

    private static List<Target> cache;
    private static Context appCtx;

    /** 在 Application / Activity 启动时调用一次，用于读取机型库资源。 */
    public static void init(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    public static synchronized List<Target> all() {
        if (cache != null) return cache;
        List<Target> list = loadFromAssets();
        if (list.isEmpty()) list = fromBuiltin();
        cache = list;
        return list;
    }

    /**
     * 从 assets/devices.tsv 读机型库。列顺序：
     *   品牌 制造商 通俗名 机型码 代号 产品名 版本 构建号 内部版本 指纹 补丁 芯片 平台
     */
    private static List<Target> loadFromAssets() {
        List<Target> list = new ArrayList<>();
        if (appCtx == null) return list;
        InputStream in = null;
        BufferedReader r = null;
        try {
            in = appCtx.getAssets().open("devices.tsv");
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] f = line.split("\t", -1);
                if (f.length < 11 || f[3].trim().isEmpty()) continue;
                Target t = new Target();
                t.brand = f[0].trim();
                t.manufacturer = f[1].trim();
                t.market = f[2].trim();
                t.model = f[3].trim();
                t.device = f[4].trim();
                t.product = f[5].trim();
                t.release = f[6].trim();
                t.buildId = f[7].trim();
                t.incremental = f[8].trim();
                t.fingerprint = f[9].trim();
                t.patch = f[10].trim();
                if (f.length > 11) t.soc = f[11].trim();
                if (f.length > 12) t.platform = f[12].trim();
                // 不在这里 normalize：指纹与构建号已经给全，normalize 会把空的补上，
                // 但也会覆盖已有值；留到真正使用时再 normalize。
                list.add(t);
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
        // 补齐缺项（不覆盖已有值）
        for (Target t : list) fillGaps(t);
        return list;
    }

    /** 只补空字段，不动已有值。 */
    private static void fillGaps(Target t) {
        if (t.brand.isEmpty()) t.brand = "google";
        if (t.manufacturer.isEmpty()) t.manufacturer = Device.capitalize(t.brand);
        if (t.device.isEmpty()) t.device = Target.slug(t.model);
        if (t.product.isEmpty()) t.product = t.device;
        if (t.release.isEmpty()) t.release = "14";
        if (t.patch.isEmpty()) t.patch = Target.defaultPatch(t.release);
        // ★ 构建号**不再编造**（2026-10-06 修缺陷②，见 Target.normalize() 同一处）：
        //   库里这一行没有真指纹，就等于没有真构建号 —— 留空 ⇒ 那一族键不写。
        //   宁可缺失，绝不写一个「现实中不可能是这样」的值。
        if (t.displayId.isEmpty()) t.displayId = t.buildId;
        if (t.flavor.isEmpty()) t.flavor = Target.flavorOf(t.brand, t.device);
        if (t.date.isEmpty()) t.date = Target.dateOf(t.patch);
        if (t.dateUtc.isEmpty()) t.dateUtc = String.valueOf(Target.epochOf(t.patch));
        if (t.fingerprint.isEmpty()) t.fingerprint = t.fingerprint();
        // ★ 有真指纹 ⇒ **以指纹为真相源**，把这一行的身份列对齐（2026-10-06 缺陷②第二现场）。
        //   机型库是多个外部来源合并的，同一台机器会出现多行、列还互相矛盾 —— 真机实测：
        //   `PKJ110`（Find X8 Ultra）两行，一行 product=findx8ultra，一行 product=OP5DD3L1，
        //   而两行共用的真指纹第 2/3 段都是 `PKJ110/PKJ110`。用户选中后我们写
        //   `ro.product.name=OP5DD3L1` + 指纹 `oppo/PKJ110/PKJ110:…`，检测方按 Build 字段
        //   重组的指纹与 Build.FINGERPRINT 并排成了两条不同的指纹（Momo 12:56 实拍）。
        //   对齐之后：无论选中哪一行，写出去的机型字段与指纹逐段一致。
        if (!t.fingerprint.isEmpty()) t.alignToFingerprint();
        if (t.market.isEmpty()) {
            String m = t.model;
            if (m.matches(".*[A-Za-z].*") && m.contains(" ")) t.market = m;
        }
    }

    private static List<Target> fromBuiltin() {
        List<Target> list = new ArrayList<>();
        for (String row : DATA) {
            String[] f = row.split("\\|", -1);
            if (f.length < 8) continue;
            Target t = new Target();
            t.brand = f[0];
            t.manufacturer = f[1];
            t.market = f[2];
            t.model = f[3];
            t.device = f[4];
            t.soc = f[5];
            t.platform = f[6];
            t.release = f[7];
            list.add(t.normalize());
        }
        return list;
    }

    /** 用外部来源的列表覆盖内置库。 */
    public static synchronized void override(List<Target> list) {
        if (list != null && !list.isEmpty()) cache = list;
    }

    /** 按机型码反查内置条目（老版本存下的目标没有通俗名时用得上）。 */
    public static Target byModel(String model) {
        if (model == null || model.isEmpty()) return null;
        for (Target t : all()) if (model.equalsIgnoreCase(t.model)) return t;
        return null;
    }

    /**
     * 目标里没有真指纹时，按机型码从库里的那一行补一份。
     *
     * 为什么必须有这一步：内部版本号**只能**从真指纹第 6 段取（见 {@link Target#fingerprint()}）——
     * 没有真指纹，这个目标就写不出可信的构建身份，那一族键只能整族不写。而库里有 310 行带着
     * 真机 dump 的指纹（例如 `PLQ110` 那行的
     * `OnePlus/PLQ110/OP6113L1:16/BP2A.250605.015/B.19fa10c_b26ef2_b26ef5:user/release-keys`
     * 第 5 段是真构建号、第 6 段是真内部版本号）。补上之后 buildId / incremental 会自动与它对齐
     * （`Target.normalize()` 末尾那段）—— 真指纹一旦丢失就无法再推导出来，这一步正是它的解。
     *
     * 库里那一行也没有指纹 ⇒ 什么都不做，让调用方按「没有真数据」处理（不编造）。
     */
    public static void fillRealFingerprint(Target t) {
        if (t == null) return;
        if (t.fingerprint != null && t.fingerprint.contains("/")) return;
        Target row = byModel(t.model);
        if (row == null || row.fingerprint == null || !row.fingerprint.contains("/")) return;
        // ★ 两道校验（2026-10-06 修缺陷②）：
        //   ① 必须是 8 段合法指纹 —— 库里混着 `" // "` 这类脏值和段数不足的半截指纹，
        //      以前只要「含 /」就收下，等于把脏数据当真数据用；
        //   ② 段里的品牌/设备必须与**这一行**对得上 —— 否则我们可能把另一台机器的指纹
        //      安到这台上（库里 18 行品牌段、25 行设备段与自身列冲突）。
        //   任一条不过 ⇒ 什么都不做，让调用方按「没有真数据」处理（不编造）。
        Target.Fp p = Target.parseFp(row.fingerprint);
        if (p == null) return;
        if (!row.brand.isEmpty() && !p.brand.equalsIgnoreCase(row.brand)) return;
        String rowDev = !row.device.isEmpty() ? row.device : row.product;
        if (!rowDev.isEmpty() && !p.device.equalsIgnoreCase(rowDev)
                && !p.product.equalsIgnoreCase(rowDev)) return;
        t.fingerprint = row.fingerprint;
        t.alignToFingerprint();     // 以指纹为真相源对齐身份字段（brand/product/device/…）
        t.normalize();
    }

    /** 展示名：优先用目标自带的通俗名，其次查库，最后退化成 品牌 + 机型码。 */
    public static String displayName(Target t) {
        if (t == null) return "";
        if (t.market != null && !t.market.isEmpty()) return t.market;
        Target known = byModel(t.model);
        if (known != null && known.market != null && !known.market.isEmpty()) return known.market;
        return t.title();
    }

    // ------------------------------------------------------------ 生成器

    public static final String[] GEN_SOC = {
            "Tensor G4", "Tensor G3", "Tensor G2", "骁龙 8 Gen 3", "骁龙 8 Gen 2",
            "骁龙 8+ Gen 1", "骁龙 8 Gen 1", "骁龙 888", "骁龙 7+ Gen 2", "骁龙 7 Gen 3",
            "天玑 9300", "天玑 9200", "天玑 9000", "天玑 8300 Ultra", "天玑 8200",
            "Exynos 2400", "Exynos 2200", "麒麟 9000S",
    };

    public static final String[] GEN_RELEASE = {"16", "15", "14", "13", "12", "11"};

    public static final String[] GEN_BRAND = {
            "google", "samsung", "xiaomi", "redmi", "poco", "oneplus", "oppo", "realme",
            "vivo", "iqoo", "honor", "huawei", "sony", "asus", "nothing", "motorola",
            "nubia", "meizu", "zte",
    };

    public static Target generate(String brand, String model, String soc, String release) {
        Target t = new Target();
        t.brand = brand == null || brand.isEmpty() ? "google" : brand.toLowerCase();
        t.manufacturer = Device.capitalize(t.brand);
        t.model = model == null || model.trim().isEmpty() ? "Custom Device" : model.trim();
        t.market = t.model;
        t.device = Target.slug(t.model);
        t.product = t.device;
        t.board = t.device;
        t.soc = soc == null ? "" : soc;
        t.platform = platformOf(t.soc, t.brand);
        t.hardware = t.platform;
        t.release = release == null || release.isEmpty() ? "14" : release;
        return t.normalize();
    }

    public static String platformOf(String soc, String brand) {
        String s = soc == null ? "" : soc.toLowerCase();
        if (s.contains("tensor g4")) return "zumapro";
        if (s.contains("tensor g3")) return "zuma";
        if (s.contains("tensor g2")) return "gs201";
        if (s.contains("tensor")) return "gs101";
        if (s.contains("8 gen 3")) return "pineapple";
        if (s.contains("8 gen 2")) return "kalama";
        if (s.contains("8+ gen 1") || s.contains("8 gen 1")) return "cape";
        if (s.contains("888")) return "lahaina";
        if (s.contains("7+ gen 2") || s.contains("7 gen 3")) return "crow";
        if (s.contains("9300")) return "mt6989";
        if (s.contains("9200")) return "mt6985";
        if (s.contains("9000")) return "mt6983";
        if (s.contains("8300")) return "mt6897";
        if (s.contains("8200")) return "mt6896";
        if (s.contains("2400")) return "s5e9945";
        if (s.contains("2200")) return "s5e9925";
        if (s.contains("kirin")) return "kirin9000s";
        return brand == null ? "generic" : brand;
    }
}
