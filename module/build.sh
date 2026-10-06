#!/usr/bin/env bash
# 改机型工具箱 · Zygisk 原生模块 —— 设备内构建（不需要电脑）
#
# 用法：
#     bash build.sh
# 脚本用 `cd "$(dirname "$0")"` 定位自身目录，所有路径都相对脚本目录推导，
# 因此可以在任意工作目录下调用，不需要任何写死的绝对路径。
#
# 环境变量（默认值 → 说明）：
#     CXX         = clang++                    C++ 编译器（需支持 -target aarch64-linux-android<API>）
#     CXX_TARGET  = aarch64-linux-android24    编译目标三元组（-target 的值）
#     CXX_LIBDIR  = （空，即只用编译器的默认搜索路径）
#                                              额外的库搜索目录（-L）；-llog 找不到时才需要设
#     PYTHON      = python3                   跑 sanitize_so.py 的解释器
#     READELF     = readelf                   ELF 依赖 / 导出符号检查（找不到就跳过，不算失败）
#
# 产物（都在脚本目录下）：
#     out/arm64-v8a.so           编译出的原始 .so
#     pkg/zygisk/arm64-v8a.so    模块包里的模块本体
#     pkg/zygisk/libdtbprobe.so  同一份 .so，供 App 侧内嵌
#     pkg/module.prop            模块描述（每次构建重写）
#
# 关键点：不链接 libc++（-nostdlib++）。这样 .so 只依赖 libc/liblog，
# 不再需要 libc++_shared.so 与 $ORIGIN rpath —— Zygisk 从哪儿 dlopen 都不会找不到库。

SELF="$(cd "$(dirname "$0")" && pwd)" || exit 1
cd "$SELF" || exit 1

CXX="${CXX:-clang++}"
CXX_TARGET="${CXX_TARGET:-aarch64-linux-android24}"
CXX_LIBDIR="${CXX_LIBDIR:-}"
PYTHON="${PYTHON:-python3}"
READELF="${READELF:-readelf}"

if ! command -v "$CXX" >/dev/null 2>&1; then
    echo "✗ 找不到 C++ 编译器：$CXX" >&2
    echo "  请把工具链加进 PATH，或用 CXX=/path/to/clang++ 指定。" >&2
    exit 1
fi
if ! command -v "$PYTHON" >/dev/null 2>&1; then
    echo "✗ 找不到 python3：$PYTHON（sanitize_so.py 需要它）" >&2
    echo "  请把 python3 加进 PATH，或用 PYTHON=/path/to/python3 指定。" >&2
    exit 1
fi

LIB_OPT=()
[ -n "$CXX_LIBDIR" ] && LIB_OPT=(-L"$CXX_LIBDIR")

mkdir -p out pkg/zygisk

echo "── 编译 zygisk 模块 ──"
"$CXX" -shared -fPIC -O2 -std=c++20 -fno-exceptions -fno-rtti -nostdlib++ \
  -target "$CXX_TARGET" \
  -Wall -Wextra -Wno-unused-parameter \
  -o out/arm64-v8a.so jni/spoof.cpp \
  "${LIB_OPT[@]}" -llog 2>&1 | grep -v "unused function" | head -30
rc=${PIPESTATUS[0]}
echo "   clang 退出码 $rc"
[ "$rc" != "0" ] && exit 1

echo
echo "── 清掉编译器塞进来的 rpath ──"
"$PYTHON" sanitize_so.py out/arm64-v8a.so

cp out/arm64-v8a.so pkg/zygisk/arm64-v8a.so
cp out/arm64-v8a.so pkg/zygisk/libdtbprobe.so

# ── module.prop：与 App 的安装路径**同一份中性文案**（2026-10-06 审计 M2）──
# 设备上那份是 App 的 Zygisk.install() 写的（core/Zygisk.java 里那段字符串），
# 而手工安装走的是这个 pkg/module.prop —— 两处曾经漂移（App 已中性化、包里还是
# 「改机型工具箱 · Zygisk 伪装」）。这里每次构建都重写一次，保证与 App 侧一致。
cat > pkg/module.prop <<'PROP'
id=devicetoolbox_zygisk
name=System Props Helper
version=1.0
versionCode=1
author=xuzhang
description=Per-process system property normalization
PROP
echo "   module.prop 已刷新（中性文案，与 Zygisk.java 的安装路径一致）"
ls -la out/arm64-v8a.so pkg/zygisk/
echo
echo "── 依赖与入口符号 ──"
if command -v "$READELF" >/dev/null 2>&1; then
    "$READELF" -d out/arm64-v8a.so 2>/dev/null | grep -E "NEEDED|RPATH|RUNPATH"
    echo "-- 导出入口 --"
    "$READELF" --dyn-syms out/arm64-v8a.so 2>/dev/null | grep -iE "zygisk_module_entry|zygisk_companion_entry|Java_"
else
    echo "   （没有 $READELF，跳过依赖/符号检查）"
fi
