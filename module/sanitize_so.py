#!/usr/bin/env python3
# 把 .so 里的 DT_RUNPATH 字符串就地抹掉。
# Termux 的 clang 驱动会强行塞一条指向 Termux 前缀的 rpath，对设备上的 Zygisk 毫无用处，
# 留着只是一个「不该出现的路径」，所以原地改写成系统库路径（长度不变，不改任何表结构）。
import sys

REPL = b"/system/lib64"


def sanitize(path: str) -> int:
    with open(path, "rb") as f:
        data = bytearray(f.read())
    if not data.startswith(b"\x7fELF"):
        raise SystemExit(f"{path} 不是 ELF")
    # RUNPATH 字符串一定含有 "termux" 且以 / 开头、以 lib 结尾；直接找这串
    marker = b"/data/local/tmp/termuxusr"
    idx = data.find(marker)
    if idx < 0:
        print(f"  {path}: 没有 Termux rpath，跳过")
        return 0
    end = data.find(b"\0", idx)
    if end < 0:
        raise SystemExit("字符串没有终止符")
    old = bytes(data[idx:end])
    if len(old) < len(REPL):
        raise SystemExit(f"原字符串太短，无法等长替换: {old!r}")
    pad = REPL + b"\0" * (len(old) - len(REPL))
    data[idx:end] = pad
    with open(path, "wb") as f:
        f.write(data)
    print(f"  {path}: RUNPATH {old.decode()} → {REPL.decode()}")
    return 1


if __name__ == "__main__":
    n = 0
    for p in sys.argv[1:]:
        n += sanitize(p)
    print(f"  处理 {n} 个文件")
