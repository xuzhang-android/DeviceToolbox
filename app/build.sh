#!/usr/bin/env bash
# 改机型工具箱 —— 设备内构建脚本（aapt2 + javac + d8 + apksigner）
#
# 用法：
#     bash build.sh
# 脚本用 `cd "$(dirname "$0")"` 定位自身目录，所有路径都相对脚本目录推导，
# 因此可以在任意工作目录下调用，不需要任何写死的绝对路径。
#
# 环境变量（默认值 → 说明）：
#     ANDROID_JAR  = <脚本目录>/tools/android.jar   android.jar（仓库不附带，需自备；
#                                                   放 app/tools/android.jar 或用本变量指定）
#     AAPT2        = aapt2          （取自 PATH）      aapt2 可执行文件
#     JAVAC        = javac          （取自 PATH）      javac 可执行文件
#     JAR          = jar            （取自 PATH）      jar 可执行文件
#     JAVA         = java           （取自 PATH）      运行 d8 / apksigner 用的 java
#     D8           = （无默认值，必填）                d8.jar 的路径（com.android.tools.r8.D8）
#     APKSIGNER    = （无默认值，必填）                apksigner.jar 的路径
#     ZYGISK_DIR   = <脚本目录>/../module/pkg/zygisk  Zygisk 模块的 pkg/zygisk 目录
#     KEYSTORE     = （无默认值，必填）                签名密钥库（.jks/.keystore）路径
#     KS_PASS      = （无默认值，必填）                密钥库口令
#     KS_ALIAS     = （无默认值，必填）                签名密钥别名
#     KEY_PASS     = $KS_PASS                          密钥口令（默认与密钥库口令相同）
#     PYTHON       = python3        （取自 PATH）      打包 dex/原生库进 APK 用的解释器
#     JAVA_TMPDIR  = $TMPDIR 或 /tmp                  java 的临时目录（-Djava.io.tmpdir）
#     BUILD_DIR    = <脚本目录>/build                 中间产物目录
#     OUT_APK      = <脚本目录>/改机型工具箱.apk        最终产物
set -e

SELF="$(cd "$(dirname "$0")" && pwd)" || exit 1
cd "$SELF" || exit 1

ANDROID_JAR="${ANDROID_JAR:-$SELF/tools/android.jar}"
AAPT2="${AAPT2:-aapt2}"
JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"
JAVA="${JAVA:-java}"
D8="${D8:-}"
APKSIGNER="${APKSIGNER:-}"
ZYGISK_DIR="${ZYGISK_DIR:-$SELF/../module/pkg/zygisk}"
KEYSTORE="${KEYSTORE:-}"
KS_PASS="${KS_PASS:-}"
KS_ALIAS="${KS_ALIAS:-}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"
PYTHON="${PYTHON:-python3}"
JAVA_TMPDIR="${JAVA_TMPDIR:-${TMPDIR:-/tmp}}"
BUILD_DIR="${BUILD_DIR:-$SELF/build}"
OUT_APK="${OUT_APK:-$SELF/改机型工具箱.apk}"

die() { echo "✗ $1" >&2; shift; for l in "$@"; do echo "  $l" >&2; done; exit 1; }

# ── 前置检查：缺什么就说清楚去哪儿补，不猜、不静默 ──
[ -f "$ANDROID_JAR" ] || die "找不到 android.jar：$ANDROID_JAR" \
    "仓库不附带 android.jar（约 26 MB）。" \
    "请把它放到 app/tools/android.jar，或用 ANDROID_JAR=/path/to/android.jar 指定。"
for t in "$AAPT2" "$JAVAC" "$JAR" "$JAVA" "$PYTHON"; do
    command -v "$t" >/dev/null 2>&1 || die "找不到可执行文件：$t" \
        "请把它加进 PATH，或用对应的环境变量（AAPT2 / JAVAC / JAR / JAVA / PYTHON）指定绝对路径。"
done
[ -n "$D8" ] || die "没有指定 d8.jar" \
    "构建需要 d8（class → dex）。请用 D8=/path/to/d8.jar 指定。"
[ -f "$D8" ] || die "找不到 d8.jar：$D8" \
    "请确认路径是否正确，或用 D8=/path/to/d8.jar 指定。"
[ -n "$APKSIGNER" ] || die "没有指定 apksigner.jar" \
    "构建需要 apksigner 签名。请用 APKSIGNER=/path/to/apksigner.jar 指定。"
[ -f "$APKSIGNER" ] || die "找不到 apksigner.jar：$APKSIGNER" \
    "请确认路径是否正确，或用 APKSIGNER=/path/to/apksigner.jar 指定。"
[ -n "$KEYSTORE" ] || die "没有指定签名密钥库" \
    "仓库不附带任何密钥。请用 KEYSTORE=/path/to/your.jks（配 KS_PASS、KS_ALIAS）指定。"
[ -f "$KEYSTORE" ] || die "找不到签名密钥库：$KEYSTORE" \
    "请确认路径是否正确，或用 KEYSTORE=/path/to/your.jks 指定。"
[ -n "$KS_PASS" ] || die "没有指定密钥库口令" "请用 KS_PASS=... 指定（也可以用 KEY_PASS 单独指定密钥口令）。"
[ -n "$KS_ALIAS" ] || die "没有指定签名密钥别名" "请用 KS_ALIAS=... 指定。"

mkdir -p "$JAVA_TMPDIR"
cd "$SELF"
B="$BUILD_DIR"
rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/dex"
JAVA_CMD=("$JAVA" "-Djava.io.tmpdir=$JAVA_TMPDIR")

echo "── 1/6 aapt2 compile 资源 ──"
"$AAPT2" compile --dir res -o "$B/res.zip"

echo "── 2/6 aapt2 link ──"
LINK_ASSETS=""
[ -d assets ] && LINK_ASSETS="-A assets"
"$AAPT2" link -o "$B/base.apk" -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml --java "$B/gen" \
  --min-sdk-version 21 --target-sdk-version 34 $LINK_ASSETS "$B/res.zip"

echo "── 3/6 javac ──"
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 \
  -cp "$ANDROID_JAR" -d "$B/classes" \
  $(find java -name '*.java') $(find "$B/gen" -name 'R.java')

echo "── 4/6 class → jar ──"
(cd "$B/classes" && "$JAR" cf ../classes.jar .)

echo "── 5/6 d8 → classes.dex ──"
"${JAVA_CMD[@]}" -cp "$D8" com.android.tools.r8.D8 \
  --min-api 21 --lib "$ANDROID_JAR" \
  --output "$B/dex" "$B/classes.jar"

echo "── 5b/6 合入原生库（与 Zygisk 模块同一个 .so）──"
if [ -f "$ZYGISK_DIR/libdtbprobe.so" ]; then
  cp "$ZYGISK_DIR/libdtbprobe.so" "$B/libdtbprobe.so"
  echo "   libdtbprobe.so $(stat -c%s "$B/libdtbprobe.so") 字节"
else
  echo "   ⚠ 没找到 $ZYGISK_DIR/libdtbprobe.so，先构建 zygisk 模块（module/build.sh）"
fi

echo "── 6/6 合入 dex + 原生库 + 签名 ──"
"$PYTHON" - "$B" <<'PY'
import sys, zipfile, os, shutil
b = sys.argv[1]
apk = os.path.join(b, "unsigned.apk")
shutil.copyfile(os.path.join(b, "base.apk"), apk)
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(b, "dex", "classes.dex"), "classes.dex")
    so = os.path.join(b, "libdtbprobe.so")
    if os.path.exists(so):
        z.write(so, "lib/arm64-v8a/libdtbprobe.so")
        print("   lib/arm64-v8a/libdtbprobe.so", os.path.getsize(so), "字节")
print("   classes.dex", os.path.getsize(os.path.join(b,"dex","classes.dex")), "字节")
PY
"${JAVA_CMD[@]}" -cp "$APKSIGNER" com.android.apksigner.ApkSignerTool sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KS_ALIAS" --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT_APK" "$B/unsigned.apk"
echo
echo "✅ $OUT_APK  $(stat -c%s "$OUT_APK") 字节"
