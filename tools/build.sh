#!/bin/bash
# 手工构建 APK（不依赖 Gradle / Android Studio）
#
# 需要：
#   JDK 8+（javac、keytool 在 PATH）
#   Android SDK：build-tools（aapt2/d8/zipalign/apksigner）+ platform（android.jar）
#   Python 3（把 dex 塞进 apk 时用到）
#
# 通过环境变量指定 SDK（都不设则用 $ANDROID_SDK 的标准布局）：
#   ANDROID_SDK            SDK 根目录
#   ANDROID_BUILD_TOOLS    build-tools 目录，例如 $ANDROID_SDK/build-tools/34.0.0
#   ANDROID_PLATFORM       platform 目录，例如 $ANDROID_SDK/platforms/android-34
#   KEYSTORE_PASS          签名密码（默认 bath123，仅自用）
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# Windows 上的 javac/aapt2 不认 /d/xxx 这种 MSYS 路径，统一转成 D:/xxx
if command -v cygpath >/dev/null 2>&1; then
  HERE="$(cygpath -m "$HERE")"
  ROOT="$(cygpath -m "$ROOT")"
fi
OUT="$ROOT/build"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-}}"
BT="${ANDROID_BUILD_TOOLS:-}"
PLAT="${ANDROID_PLATFORM:-}"

if [ -z "$BT" ] && [ -n "$SDK" ]; then
  BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "$PLAT" ] && [ -n "$SDK" ]; then
  PLAT="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "$BT" ] || [ -z "$PLAT" ]; then
  echo "找不到 Android SDK，请设置 ANDROID_SDK（或 ANDROID_BUILD_TOOLS / ANDROID_PLATFORM）"
  exit 1
fi
PLATJAR="$PLAT/android.jar"
echo "build-tools: $BT"
echo "platform   : $PLAT"

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex"

echo "[1/6] 编译资源"
"$BT/aapt2.exe" compile --dir "$ROOT/res" -o "$OUT/compiled/res.zip"

echo "[2/6] 链接资源与清单"
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$PLATJAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -A "$ROOT/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay \
  "$OUT/compiled/res.zip" 2>&1 | grep -v "^$" || true

echo "[3/6] 编译 Java"
find "$ROOT/src" -name "*.java" > "$OUT/sources.txt"
find "$OUT/gen" -name "*.java" >> "$OUT/sources.txt" 2>/dev/null || true
javac -source 1.8 -target 1.8 -encoding UTF-8 -nowarn \
  -bootclasspath "$PLATJAR;$BT/core-lambda-stubs.jar" \
  -classpath "$PLATJAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "[4/6] 转 dex"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$BT/d8.bat" --lib "$PLATJAR" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt" 2>&1 | tail -5

echo "[5/6] 打包"
python - "$OUT" <<'PY'
import sys, os, zipfile, shutil
out = sys.argv[1]
src = os.path.join(out, 'base.apk')
dst = os.path.join(out, 'app-unsigned.apk')
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, 'dex', 'classes.dex'), 'classes.dex')
print('  packed ->', dst, os.path.getsize(dst), 'bytes')
PY

echo "[6/6] 对齐与签名"
"$BT/zipalign.exe" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

KS="$ROOT/keystore/bath.keystore"
PASS="${KEYSTORE_PASS:-bath123}"
mkdir -p "$ROOT/keystore"
if [ ! -f "$KS" ]; then
  echo "  生成签名证书（仅首次）"
  keytool -genkeypair -keystore "$KS" -alias bath -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Bath Helper, OU=Self, O=Self, L=Beijing, ST=Beijing, C=CN" 2>&1 | tail -2
fi

"$BT/apksigner.bat" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$OUT/bath.apk" "$OUT/app-aligned.apk" 2>&1 | tail -3

echo ""
echo "完成: $OUT/bath.apk"
ls -la "$OUT/bath.apk"
echo ""
echo "自检: python tools/audit_apk.py"
