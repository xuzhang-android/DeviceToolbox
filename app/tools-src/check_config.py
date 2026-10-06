#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
配置自洽矩阵检查器 —— 纯离线（只读 zygisk.conf + baseline.tsv），不需要设备在线。

为什么要有它：这个项目反复栽在「单个值看着没问题、**几个值之间**打架」上。
人工逐键看是看不出来的（配置 95~175 键），但**不变式**一句话就能验：
比如「指纹的 6 段必须与 ro.product.* / ro.build.* 一一对应」——
历史上「display.id 写了构建号」「description 形状不对」这些问题，全都会在这里现形。

用法:
  check_config.py <zygisk.conf> <baseline.tsv> [包名]
    · 不给包名：所有行并进一张表（仅适用于单应用配置）
    · 给包名  ：只检查该应用的行（隐藏 ≥2 个应用时必须这么用）
退出码 0 = 全部通过；1 = 有不变式不成立。
"""
import sys, re, datetime

FAIL = []
WARN = []
OK = 0


def load_conf(path, pkg=None):
    """读 zygisk.conf → ({key: value}, pkgs, {删除键: 包名})。

    2026-10-06 起配置有两种行（见 App 侧 core/Zygisk.java）：
      包名<TAB>属性键<TAB>值      写入
      DEL<TAB>包名<TAB>属性键     删除（独立通道）
    删除行**不是属性值**，所以单独收在 dels 里 —— 混进 cfg 会让后面所有不变式失真。
    """
    out, pkgs, dels, by_pkg = {}, set(), {}, {}
    del_all = 0
    for ln in open(path, encoding="utf-8"):
        ln = ln.rstrip("\n")
        if not ln or ln.startswith("#"):
            continue
        f = ln.split("\t")
        if len(f) != 3:
            continue
        if f[0] == "DEL":                       # 删除独立通道
            pkgs.add(f[1])
            del_all += 1                        # 头部对账要的是全量（与是否按包名过滤无关）
            if pkg is None or f[1] == pkg:
                dels[f[2]] = f[1]
            continue
        pkgs.add(f[0])
        by_pkg.setdefault(f[0], {})[f[1]] = f[2]
        if pkg is None or f[0] == pkg:
            out[f[1]] = f[2]
    return out, pkgs, dels, by_pkg, del_all


def load_base(path):
    out = {}
    for ln in open(path, encoding="utf-8"):
        ln = ln.rstrip("\n")
        f = ln.split("\t")
        if len(f) == 2:
            out[f[0]] = f[1]
    return out


def chk(name, cond, detail=""):
    global OK
    if cond:
        OK += 1
        print("  ✓ %s" % name)
    else:
        FAIL.append(name)
        print("  ✗ %s %s" % (name, ("— " + detail) if detail else ""))


def warn(name, cond, detail=""):
    if not cond:
        WARN.append(name)
        print("  ! %s %s" % (name, ("— " + detail) if detail else ""))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    # ★ 2026-10-06：可选第三个参数 = 只检查这一个包名（多应用配置必需）。
    #   不给参数时行为与从前一致（= 所有包名的行并进一张表，键相同者后出现的覆盖先出现的）——
    #   那种"混表"只适合单应用配置；隐藏 ≥2 个应用时请显式传包名，逐应用检查。
    only = sys.argv[3] if len(sys.argv) > 3 else None
    cfg, pkgs, dels, by_pkg, del_all = load_conf(sys.argv[1], pkg=only)
    if only is not None:
        print("（只检查包名：%s）" % only)
    base = load_base(sys.argv[2])
    pkg = sorted(pkgs)[0] if pkgs else "?"
    print("═══ 配置自洽矩阵 ═══")
    print("包名: %s   配置键数: %d   基线键数: %d\n" % (pkg, len(cfg), len(base)))

    g = cfg.get
    fp = g("ro.build.fingerprint", "")

    # ── 1. 指纹六段与各键一一对应 ──
    print("[1] 指纹 ↔ 各字段")
    m = re.match(r"^([^/]+)/([^/]+)/([^:]+):([^/]+)/([^/]+)/([^:]+):([^/]+)/([^/]+)$", fp)
    if not m:
        chk("指纹格式 b/p/d:rel/id/inc:type/tags", False, "实际 [%s]" % fp)
    else:
        chk("指纹格式", True)
        b, p, d, rel, bid, inc, typ, tags = m.groups()
        chk("指纹 brand == ro.product.brand", b == g("ro.product.brand"), "%s vs %s" % (b, g("ro.product.brand")))
        chk("指纹 product == ro.product.name", p == g("ro.product.name"), "%s vs %s" % (p, g("ro.product.name")))
        chk("指纹 device == ro.product.device", d == g("ro.product.device"), "%s vs %s" % (d, g("ro.product.device")))
        chk("指纹 release == ro.build.version.release", rel == g("ro.build.version.release"), "%s vs %s" % (rel, g("ro.build.version.release")))
        chk("指纹 buildId == ro.build.id", bid == g("ro.build.id"), "%s vs %s" % (bid, g("ro.build.id")))
        chk("指纹 incremental == ro.build.version.incremental", inc == g("ro.build.version.incremental"), "%s vs %s" % (inc, g("ro.build.version.incremental")))
        chk("指纹 type == ro.build.type", typ == g("ro.build.type"), "%s vs %s" % (typ, g("ro.build.type")))
        chk("指纹 tags == ro.build.tags", tags == g("ro.build.tags"), "%s vs %s" % (tags, g("ro.build.tags")))

    # ── 2. description 形状（真机是 `<flavor> <release> <id> <inc> release-keys`）──
    print("\n[2] description 形状")
    desc = g("ro.build.description", "")
    real_flavor = base.get("ro.build.flavor", "")
    exp = "%s %s %s %s release-keys" % (real_flavor, g("ro.build.version.release"), g("ro.build.id"), g("ro.build.version.incremental"))
    chk("description == `<真机flavor> <release> <id> <inc> release-keys`", desc == exp,
        "实际 [%s] 期望 [%s]" % (desc, exp))
    warn("description 里不含真机品牌词", "oneplus" not in desc.lower() and "plq110" not in desc)

    # ── 3. 日期族：build.id 里的日期 vs date / date.utc / patch ──
    print("\n[3] 日期族")
    bid = g("ro.build.id", "")
    d = re.search(r"\.(\d{6})\.", bid)
    if not d:
        chk("build.id 含 6 位日期段", False, bid)
    else:
        y, mo = 2000 + int(d.group(1)[:2]), int(d.group(1)[2:4])
        patch = g("ro.build.version.security_patch", "")
        pm = re.match(r"^(\d{4})-(\d{2})-(\d{2})$", patch)
        chk("security_patch 格式 YYYY-MM-DD", bool(pm), patch)
        if pm:
            gap = (int(pm.group(1)) - y) * 12 + (int(pm.group(2)) - mo)
            chk("build.id 日期不早于补丁超过 1 个月（分支基线 vs 月度补丁）", gap >= -1, "差 %d 个月" % gap)
            chk("补丁不早于真机真实补丁（不写「更旧」的值）",
                patch >= base.get("ro.build.version.security_patch", patch),
                "配置 %s < 真机 %s" % (patch, base.get("ro.build.version.security_patch")))
        du = g("ro.build.date.utc", "")
        if du.isdigit() and pm:
            real_dt = datetime.datetime(int(pm.group(1)), int(pm.group(2)), int(pm.group(3)), tzinfo=datetime.timezone.utc)
            chk("date.utc == 补丁日期的 epoch", abs(int(du) - int(real_dt.timestamp())) < 86400,
                "配置 %s 期望≈%d" % (du, int(real_dt.timestamp())))
        date = g("ro.build.date", "")
        # 注意：**不能**要求 build.date 的年份等于 build.id 里的年份 ——
        # 真机自己就不相等：build.id 里的日期是「**分支基线日期**」（本机
        # `BP2A.250605.015` = 2025-06），而 build.date 是这次构建的时间（2026-08）。
        # 第一版校验器就是这么写错的（把真机的正常状态判成失败）。
        # 正确的不变式只有「build.date **不早于**分支基线日期」。
        dyear = re.search(r"(\d{4})", date)
        chk("build.date 不早于 build.id 的分支基线日期",
            bool(dyear) and int(dyear.group(1)) >= y, "id=%s date=%s" % (bid, date))

    inc = g("ro.build.version.incremental", "")
    if d:
        # 内部版本号不一定含日期（真机 OPPO/一加 是毫秒时间戳或 <字母>.<hash>_…），
        # 所以这里只做「**形状**」检查 + 与真指纹第 6 段一致性检查。
        # ★ 2026-10-06 审计 M1(b)：OPPO/一加 的「毫秒时间戳 / <字母>.<hash>_…」是**宿主厂商惯例**，
        #   不是通用规则 —— 小米的真实形态是 `OS2.0.16.0.VNNEUXM`（与它自己指纹第 6 段一致），
        #   原写法对它恒定误报。改成按品牌分派：仅 OPPO/一加/realme 要求那两种惯例，
        #   其余品牌做宽松形状检查（非空、无空白、无 `/`、长度合理）。
        fb = fp.split("/")[0] if "/" in fp else g("ro.product.brand", "")   # 指纹品牌段（此处 brand 还没定义）
        oplus = any(x in fb.lower() for x in ("oppo", "oneplus", "realme"))
        if oplus:
            chk("incremental 形状像 OPPO/一加 的两种惯例之一（毫秒时间戳 / <字母>.<hash>_…）",
                bool(re.match(r"^\d{13}$", inc) or re.match(r"^[A-Za-z]\.[0-9a-fA-F_-]+$", inc)),
                "inc=[%s]（品牌=%s）" % (inc, fb))
        else:
            chk("incremental 形状合法（非空 · 无空白 · 无斜杠 · ≤64 字符）",
                bool(inc) and len(inc) <= 64 and not re.search(r"\s", inc) and "/" not in inc,
                "inc=[%s]（品牌=%s）" % (inc, fb))
        real_inc = ""
        mm = re.match(r"^[^/]+/[^/]+/[^:]+:[^/]+/[^/]+/([^:]+):", fp)
        if mm:
            real_inc = mm.group(1)
        chk("incremental 与指纹第 6 段一致", inc == real_inc, "%s vs 指纹里的 %s" % (inc, real_inc))

    # ── 4. 版本族 ──
    print("\n[4] 版本族")
    rel, sdk = g("ro.build.version.release", ""), g("ro.build.version.sdk", "")
    # ★ 2026-10-06 审计 M1(a)：原表把 13/14/15 各写低一档且缺 35 —— 对 Android 13/14/15
    #   的目标恒定误报。这里与 App 自己那张表（core/Target.java sdkOf）**同语义**：
    #   13→33 / 14→34 / 15→35 / 16→36。
    sdkmap = {"10": "29", "11": "30", "12": "31", "12L": "32", "13": "33", "14": "34",
              "15": "35", "16": "36", "17": "37"}
    # 16 以上 App 侧是**递推**（core/Target.java sdkOf：sdk = 36 + floor(release - 16)）——
    # 这里补 17→37 与它对齐；将来 18/19 出现时，这条断言按同一规则再加一档。
    chk("sdk 与 release 对应", sdkmap.get(rel) == sdk, "release=%s sdk=%s 期望 %s" % (rel, sdk, sdkmap.get(rel)))
    chk("release_or_codename == release", g("ro.build.version.release_or_codename", rel) == rel)
    chk("release_or_preview_display == release", g("ro.build.version.release_or_preview_display", rel) == rel)
    chk("sdk_full == sdk + '.0'", g("ro.build.version.sdk_full", "") == sdk + ".0")
    chk("preview_sdk == 0", g("ro.build.version.preview_sdk", "0") == "0")
    chk("codename == REL", g("ro.build.version.codename", "REL") == "REL")

    # ── 5. 品牌族 ──
    print("\n[5] 品牌族")
    brand, manu, market = g("ro.product.brand", ""), g("ro.product.manufacturer", ""), g("ro.vendor.oplus.market.name", "")
    chk("brand 与 manufacturer 同族", manu.lower().startswith(brand.lower()[:4]) or brand.lower().startswith(manu.lower()[:4]),
        "%s vs %s" % (brand, manu))
    chk("market.name 非空且不含真机品牌", bool(market) and "oneplus" not in market.lower(), market)
    chk("market.enname == market.name（同族两键应一致）",
        g("ro.vendor.oplus.market.enname", market) == market)

    # ── 6. 平台域：不该被写成机型码 ──
    print("\n[6] 平台域")
    model, device = g("ro.product.model", ""), g("ro.product.device", "")
    for k in ("ro.product.board", "ro.hardware", "ro.board.platform", "ro.build.product", "ro.build.flavor"):
        v = g(k)
        if v is None:
            continue
        chk("%s 没有被写成机型码/代号" % k, v != model and v != device, "值 [%s]" % v)

    # ── 7. 分区一致：只该写「机型码组」的分区 ──
    print("\n[7] 分区组")
    base_fp = re.sub(r"^([^/]+)/", "", base.get("ro.build.fingerprint", ""))
    base_prod = base_fp.split("/")[0] if "/" in base_fp else ""
    for part in ("system", "vendor", "product", "system_ext", "odm", "bootimage",
                 "system_dlkm", "vendor_dlkm", "odm_dlkm"):
        pf = base.get("ro." + part + ".build.fingerprint", "")
        if not pf:
            continue
        pprod = pf.split("/")[1] if pf.count("/") >= 1 else ""
        shared = (pprod != base_prod)
        wrote = ("ro.product." + part + ".model") in cfg
        chk("分区 %s（%s）%s" % (part, "共享镜像" if shared else "机型码组",
                                "不写" if shared else "写"),
            (not wrote) if shared else wrote)

    # ── 8. 泄漏面：配置里不该出现真机机型码/品牌 ──
    print("\n[8] 泄漏面")
    real_model = base.get("ro.product.model", "")
    real_dev = base.get("ro.product.device", "")
    real_brand = base.get("ro.product.brand", "")
    hits = []
    for k, v in cfg.items():
        if v == "__DELETE__":
            continue
        lv = v.lower()
        if real_model and real_model in v:
            hits.append((k, v))
        elif real_dev and real_dev in v:
            hits.append((k, v))
        elif real_brand and real_brand.lower() in lv:
            hits.append((k, v))
    chk("配置里没有带真机机型码/品牌的键", not hits, str(hits[:4]))

    # ── 9. 哨兵与包名 ──
    print("\n[9] 其它")
    # ★ 2026-10-06 审计 M4：原来写死「只有一个包名」——隐藏 ≥2 个应用时恒失败（假失败会淹没真失败）。
    #   改成与 App 写在配置里的**汇总行**对账（`# 共 N 个应用 / M 条属性 / K 条删除`），数量无关。
    #   注意：那行在**文件末尾**（尾巴），不在开头 —— 这里全文件扫一遍，位置无关。
    head_apps = head_props = head_dels = None
    for ln in open(sys.argv[1], encoding="utf-8"):
        m = re.match(r"^#\s*共\s*(\d+)\s*个应用\s*/\s*(\d+)\s*条属性\s*/\s*(\d+)\s*条删除", ln)
        if m:
            head_apps, head_props, head_dels = int(m.group(1)), int(m.group(2)), int(m.group(3))
    chk("汇总行应用数与配置里的包名数一致",
        head_apps is not None and head_apps == len(pkgs),
        "汇总行 %s / 实际包名 %d 个 %s" % (head_apps, len(pkgs), sorted(pkgs)[:4]))
    all_props = sum(len(v) for v in by_pkg.values())     # 全文件（不受 --包名 过滤影响）
    chk("汇总行属性数与实际写入行数一致",
        head_props is not None and head_props == all_props,
        "汇总行 %s / 实际 %d 条" % (head_props, all_props))
    chk("汇总行删除数与 DEL 行数一致",
        head_dels is not None and head_dels == del_all,
        "汇总行 %s / 实际 %d 条" % (head_dels, del_all))
    print("       （包名 %d 个：%s）" % (len(pkgs), " ".join(sorted(pkgs)[:4])))
    # ★ 每个包名都要有目标机型（数量无关的等价断言）
    for pk in sorted(pkgs):
        chk("包 %s 有目标机型（ro.product.model）" % pk, ("ro.product.model" in by_pkg.get(pk, {})))
    # ★ 模块侧上限：MAX_PROPS=256 条（超了 companion 会静默截断）；键 ≤96 / 值 ≤192（超了静默丢弃）
    worst = max([sum(1 for k in by_pkg.get(pk, {})) for pk in pkgs] or [0])
    chk("单应用键数 < MAX_PROPS(256)", worst < 256, "最多 %d 条" % worst)
    longk = sorted(k for k in cfg if len(k.encode()) >= 96)
    longv = sorted(k for k, v in cfg.items() if len(v.encode()) >= 192)
    chk("键长 < 96 字节", not longk, str(longk[:3]))
    chk("值长 < 192 字节", not longv, str(longv[:3]))
    # ★ 发行版配置里不得留调试开关（spoof.cpp 见到 #debug=1 会把整条链路改成话痨模式）
    dbg = any(ln.lstrip().startswith("#debug=1") or ln.lstrip().startswith("# debug=1")
              for ln in open(sys.argv[1], encoding="utf-8"))
    chk("配置里没有 #debug=1 调试开关", not dbg)
    # ★ 硬失败项（2026-10-06 缺陷①）：**值域里绝不允许出现删除哨兵**。
    #   历史事故：哨兵是带内的（值 == "__DELETE__"），模块 Java 侧把它当值写进了
    #   Build.FINGERPRINT —— Momo 屏幕上第 1 行就是字面量 "__DELETE__"。
    #   删除必须走独立通道（DEL<TAB>包名<TAB>键），所以这里从「统计」升为「失败」。
    leaked = sorted(k for k, v in cfg.items() if v == "__DELETE__")
    chk("值域里没有删除哨兵（删除必须走 DEL 行）", not leaked,
        ("有 %d 条，例如 %s" % (len(leaked), leaked[:4])) if leaked else "")
    print("       （独立删除通道 DEL 行: %d 条）" % len(dels))
    warn("同生态目标不该出现删除（跨生态才用）", len(dels) == 0)

    print("\n═══ 结果：通过 %d 项，失败 %d 项，提示 %d 项 ═══" % (OK, len(FAIL), len(WARN)))
    if FAIL:
        print("失败项:")
        for f in FAIL:
            print("  - " + f)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
