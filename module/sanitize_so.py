#!/usr/bin/env python3
"""把 .so 里的 DT_RUNPATH 就地改写成设备上的系统库路径。

交叉编译器的驱动有时会往产物里塞一条指向**编译机前缀**的 rpath；设备上的 Zygisk 用不到它，
留着只是一个不该出现在产物里的本机路径。这里按 ELF 结构精确定位 DT_RUNPATH
（程序头 → PT_DYNAMIC → DT_RUNPATH/DT_RPATH → DT_STRTAB），把那个字符串**就地等长**
改写成 /system/lib64、余量补 NUL —— 不动任何表结构、不改变文件长度。

匹配规则（不写死任何具体路径）：
  · 环境变量 RUNPATH_FIX_FROM 给定时，只处理以该前缀开头的 RUNPATH；
  · 未给定时，处理任何以 /data/ 开头的 RUNPATH（编译机前缀都落在这个挂载点下）；
  · 其它 RUNPATH（例如厂商自带的 /vendor/lib64）原样跳过。

用法：
    sanitize_so.py <file.so> [<file2.so> ...]
"""
import os
import struct
import sys

REPL = b"/system/lib64"
ENV_HINT = "RUNPATH_FIX_FROM"
DEFAULT_FROM = b"/data/"

PT_LOAD, PT_DYNAMIC = 1, 2
DT_NULL, DT_STRTAB, DT_RPATH, DT_RUNPATH = 0, 5, 15, 29


def _u16(d, o):
    return struct.unpack_from("<H", d, o)[0]


def _u32(d, o):
    return struct.unpack_from("<I", d, o)[0]


def _u64(d, o):
    return struct.unpack_from("<Q", d, o)[0]


def find_runpath(data):
    """定位 RUNPATH 字符串：返回 (起始偏移, 终止 NUL 偏移)；没有则 None。

    DT_RUNPATH 优先于 DT_RPATH（动态加载器在前者存在时忽略后者）。
    """
    if bytes(data[:4]) != b"\x7fELF":
        raise SystemExit("不是 ELF 文件")
    if data[4] != 2:
        raise SystemExit("只支持 ELF64")
    if data[5] != 1:
        raise SystemExit("只支持小端 ELF")

    phoff, phentsize, phnum = _u64(data, 0x20), _u16(data, 0x36), _u16(data, 0x38)
    loads, dyn = [], None
    for i in range(phnum):
        ph = phoff + i * phentsize
        p_type = _u32(data, ph)
        if p_type == PT_LOAD:
            loads.append((_u64(data, ph + 0x08),    # p_offset
                          _u64(data, ph + 0x10),    # p_vaddr
                          _u64(data, ph + 0x20)))   # p_filesz
        elif p_type == PT_DYNAMIC:
            dyn = (_u64(data, ph + 0x08), _u64(data, ph + 0x20))   # p_offset, p_filesz
    if dyn is None:
        return None

    strtab, runpath, rpath = None, None, None
    off, size = dyn
    for i in range(size // 16):
        tag = _u64(data, off + i * 16)
        val = _u64(data, off + i * 16 + 8)
        if tag == DT_NULL:
            break
        if tag == DT_STRTAB:
            strtab = val
        elif tag == DT_RUNPATH and runpath is None:
            runpath = val
        elif tag == DT_RPATH and rpath is None:
            rpath = val
    want = runpath if runpath is not None else rpath
    if strtab is None or want is None:
        return None

    base = None
    for p_offset, p_vaddr, p_filesz in loads:
        if p_vaddr <= strtab < p_vaddr + p_filesz:
            base = p_offset - p_vaddr      # 该 PT_LOAD 的 vaddr → 文件偏移换算
            break
    if base is None:
        raise SystemExit("DT_STRTAB 不在任何 PT_LOAD 段内")

    start = base + strtab + want
    end = data.find(b"\0", start)
    if end < 0:
        raise SystemExit("RUNPATH 字符串没有终止符")
    return start, end


def sanitize(path, prefix):
    with open(path, "rb") as f:
        data = bytearray(f.read())
    found = find_runpath(data)
    if found is None:
        print(f"  {path}: 没有 DT_RUNPATH/DT_RPATH，跳过")
        return 0
    start, end = found
    old = bytes(data[start:end])
    if old == REPL:
        print(f"  {path}: RUNPATH 已是 {REPL.decode()}，跳过")
        return 0
    if prefix and not old.startswith(prefix):
        print(f"  {path}: RUNPATH {old.decode(errors='replace')} 不以 {prefix.decode()} 开头，跳过")
        return 0
    if len(old) < len(REPL):
        raise SystemExit(f"原字符串太短，无法等长替换: {old!r}")
    data[start:end] = REPL + b"\0" * (len(old) - len(REPL))
    with open(path, "wb") as f:
        f.write(data)
    print(f"  {path}: RUNPATH {old.decode(errors='replace')} → {REPL.decode()}"
          f"（等长原地改写，余 {len(old) - len(REPL)} 字节补 NUL）")
    return 1


def main(argv):
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    prefix = os.environ.get(ENV_HINT, "").encode() or DEFAULT_FROM
    n = 0
    for p in argv[1:]:
        n += sanitize(p, prefix)
    print(f"  处理 {n} 个文件")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
