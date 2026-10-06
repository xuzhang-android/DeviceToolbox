#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""机型库指纹自洽校验（可复用；2026-10-06 补真实指纹数据时新增）

对 assets/devices.tsv 全表做四项硬检查（与 Java 侧 Target.parseFp 同一口径）：

  A. 行数不变         —— 有 --baseline <旧文件> 时比对，两边行数必须一致
  B. 指纹 8 段合法     —— brand/product/device:release/build_id/incremental:type/tags，
                        每段非空；列 9 里非空的值必须全部合法
  C. 行内自洽         —— 同一行 brand(0) / product(5) / device(4) / release(6) /
                        build_id(7) / incremental(8) 必须与指纹各段逐项一致
  D. 没有「半族」     —— 没有指纹的行，不许留下 build_id(7) 或 incremental(8)；
                        有 build_id/incremental 就必须有指纹

用法：
    python3 tools-src/check-fingerprints.py                  # 校验当前库
    python3 tools-src/check-fingerprints.py --baseline <bak>  # 同时断言行数没变
    python3 tools-src/check-fingerprints.py --cover           # 额外打印补全覆盖率
    python3 tools-src/check-fingerprints.py --list-bad        # 打印全部问题行

退出码：0 = 全过；1 = 有问题（脚本可进 CI）。
列顺序（与 Library.loadFromAssets 一致）：
    0品牌 1制造商 2通俗名 3机型码 4代号 5产品名 6版本 7构建号 8内部版本 9指纹 10补丁 11芯片 12平台
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TSV = os.path.join(HERE, "..", "assets", "devices.tsv")

C_BRAND, C_MODEL, C_DEVICE, C_PRODUCT = 0, 3, 4, 5
C_RELEASE, C_BUILDID, C_INCR, C_FP = 6, 7, 8, 9


def parse_fp(fp):
    """严格 8 段解析；不合法返回 None。"""
    fp = (fp or "").strip()
    if not fp or fp.count(":") != 2:
        return None
    head, mid, tail = fp.split(":")
    a, b, c = head.split("/"), mid.split("/"), tail.split("/")
    if len(a) != 3 or len(b) != 3 or len(c) != 2:
        return None
    if any(not s for s in a + b + c):
        return None
    return {"brand": a[0], "product": a[1], "device": a[2],
            "release": b[0], "buildId": b[1], "incremental": b[2],
            "type": c[0], "tags": c[1]}


def load(path):
    """返回 (rows, raw_line_count)。rows 里每项是 (行号, 字段列表, 原始行)。"""
    txt = open(path, encoding="utf-8", newline="").read()
    raw = txt.split("\n")
    rows = []
    for i, ln in enumerate(raw, 1):
        if not ln.strip():
            continue
        rows.append((i, ln.split("\t"), ln))
    return rows, len(raw)


def check(path, list_bad=False):
    rows, raw_total = load(path)
    problems = []
    has_fp = blank_fp = 0
    for i, f, _ln in rows:
        fp = f[C_FP].strip() if len(f) > C_FP else ""
        if not fp:
            blank_fp += 1
            # D. 半族：没有指纹却留着构建号/内部版本
            half = []
            if len(f) > C_BUILDID and f[C_BUILDID].strip():
                half.append("构建号(7)=%r" % f[C_BUILDID].strip())
            if len(f) > C_INCR and f[C_INCR].strip():
                half.append("内部版本(8)=%r" % f[C_INCR].strip())
            if half:
                problems.append((i, f[C_MODEL] if len(f) > C_MODEL else "?",
                                 "半族（无指纹但有 %s）" % "、".join(half)))
            continue
        has_fp += 1
        p = parse_fp(fp)
        if p is None:
            problems.append((i, f[C_MODEL] if len(f) > C_MODEL else "?",
                             "指纹不是合法 8 段：%r" % fp))
            continue
        # C. 行内逐项一致
        got = {"brand": f[C_BRAND].strip(),
               "product": f[C_PRODUCT].strip() if len(f) > C_PRODUCT else "",
               "device": f[C_DEVICE].strip() if len(f) > C_DEVICE else "",
               "release": f[C_RELEASE].strip() if len(f) > C_RELEASE else "",
               "buildId": f[C_BUILDID].strip() if len(f) > C_BUILDID else "",
               "incremental": f[C_INCR].strip() if len(f) > C_INCR else ""}
        diff = ["%s: 列=%r 指纹=%r" % (k, got[k], p[k]) for k in
                ("brand", "product", "device", "release", "buildId", "incremental")
                if got[k] != p[k]]
        if diff:
            problems.append((i, f[C_MODEL] if len(f) > C_MODEL else "?",
                             "行内与指纹不一致 → " + "；".join(diff)))

    print("文件：%s" % os.path.abspath(path))
    print("数据行 %d 行（物理行 %d）· 有指纹 %d · 无指纹 %d" % (len(rows), raw_total, has_fp, blank_fp))
    if problems:
        print("✗ 发现 %d 处问题：" % len(problems))
        shown = problems if list_bad else problems[:20]
        for i, model, why in shown:
            print("   L%-4d %-22s %s" % (i, model[:22], why))
        if not list_bad and len(problems) > 20:
            print("   …另有 %d 处，加 --list-bad 看全部" % (len(problems) - 20))
    else:
        print("✓ 全部通过：指纹 8 段合法 · 行内逐项自洽 · 无半族行")
    return len(rows), has_fp, blank_fp, problems


def main():
    args = sys.argv[1:]
    path = DEFAULT_TSV
    baseline = None
    skip = -1
    for k, a in enumerate(args):
        if k == skip:
            continue
        if a == "--baseline" and k + 1 < len(args):
            baseline = args[k + 1]
            skip = k + 1                    # --baseline 的值不是位置参数
        elif not a.startswith("--"):
            path = a
    list_bad = "--list-bad" in args

    n, has, blank, problems = check(path, list_bad)

    if baseline:
        bro, braw = load(baseline)
        print("--baseline %s：数据行 %d（物理行 %d）" % (baseline, len(bro), braw))
        if len(bro) != n:
            print("✗ 行数变了：%d → %d" % (len(bro), n))
            problems.append((0, "-", "行数变化 %d → %d" % (len(bro), n)))
        else:
            print("✓ 行数不变（%d 行）" % n)

    if "--cover" in args:
        print("补全覆盖率：%d/%d = %.1f%%（无指纹 %d）" % (has, n, 100.0 * has / n, blank))

    print("结论：%s" % ("有问题，见上" if problems else "通过"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
