#!/usr/bin/env bash
#
# 聚屏（Juping）一键构建脚本
# ---------------------------------------------------------------
# 本机没有系统级 JDK / Android SDK / brew，所有工具链都在
# $HOME/.android-build/ 下自成一套，互不污染系统。
#
# 用法：
#   ./tools/build.sh              # 编译 debug APK
#   ./tools/build.sh release      # 编译已签名的 release APK
#   ./tools/build.sh dist         # 两个都编，并把成品归集到 dist/
#   ./tools/build.sh lint         # 跑 lint（API 兼容性检查）
#   ./tools/build.sh clean        # 清理构建产物
#
# 产物：
#   dist/juping-0.1.0-release.apk   ← 装机用这个
#   dist/juping-0.1.0-debug.apk     ← 排障用（含 debuggable 标记）

set -euo pipefail

# --- 项目根目录（脚本所在目录的上一级）---
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# --- 工具链路径 ---
TOOLCHAIN="${ANDROID_BUILD_HOME:-$HOME/.android-build}"
export JAVA_HOME="$TOOLCHAIN/jdk/Contents/Home"
export ANDROID_HOME="$TOOLCHAIN/sdk"
export ANDROID_SDK_ROOT="$TOOLCHAIN/sdk"
export GRADLE_USER_HOME="$TOOLCHAIN/gradle-home"
GRADLE_BIN="$TOOLCHAIN/gradle/bin/gradle"
BT="$ANDROID_HOME/build-tools/33.0.0"

# --- 前置检查：工具链是否齐 ---
missing=0
for p in "$JAVA_HOME/bin/java" "$GRADLE_BIN" "$ANDROID_HOME/platforms/android-33/android.jar"; do
    if [ ! -e "$p" ]; then
        echo "缺少工具链组件: $p" >&2
        missing=1
    fi
done
if [ "$missing" -ne 0 ]; then
    cat >&2 <<'EOF'

工具链不完整。需要以下三项：
  1. JDK 17         -> $TOOLCHAIN/jdk/Contents/Home
  2. Gradle 7.5     -> $TOOLCHAIN/gradle
  3. Android SDK 33 -> $TOOLCHAIN/sdk  (platforms/android-33 + build-tools/33.0.0)

下载地址：
  JDK:    https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse
  Gradle: https://services.gradle.org/distributions/gradle-7.5-bin.zip
  SDK:    需 platform-33 与 build-tools 33.0.0
EOF
    exit 1
fi

VER="$(grep -oE 'versionName "[^"]+"' app/build.gradle | head -1 | sed 's/.*"\(.*\)"/\1/')"

# 签名校验：以 API 15 为目标确认这个包在目标设备上装得上
verify_apk() {
    local apk="$1"
    if [ ! -f "$apk" ]; then
        echo "  !! 没找到 $apk" >&2
        return 1
    fi
    ls -lh "$apk" | awk '{print "  文件: " $9 "  " $5}'
    # --min-sdk-version 15 是关键：让 apksigner 按 Android 4.0.3+ 的规则校验，
    # 而不是按当前的默认规则。少了这个参数，"校验通过" 是没意义的。
    if "$BT/apksigner" verify --min-sdk-version 15 "$apk" 2>/dev/null; then
        echo "  签名: 在 API 15+ 上验证通过"
    else
        echo "  !! 签名在 API 15 上验证失败" >&2
        return 1
    fi
    local min
    min="$(python3 tools/apk_info.py "$apk" 2>/dev/null | grep minSdkVersion | awk '{print $3}')"
    echo "  minSdk: $min"
}

collect() {
    mkdir -p dist
    cp -f app/build/outputs/apk/release/app-release.apk "dist/juping-$VER-release.apk"
    cp -f app/build/outputs/apk/debug/app-debug.apk     "dist/juping-$VER-debug.apk"
}

case "${1:-debug}" in
    clean)
        "$GRADLE_BIN" clean
        rm -rf dist
        echo "已清理。"
        ;;

    lint)
        "$GRADLE_BIN" :app:lintDebug
        echo
        python3 - <<'PY'
import xml.etree.ElementTree as ET, os
p = 'app/build/reports/lint-results-debug.xml'
if not os.path.exists(p):
    print("没有生成 lint 报告"); raise SystemExit
issues = ET.parse(p).getroot().findall('issue')
print("lint 剩余问题: %d" % len(issues))
for i in sorted(issues, key=lambda x: x.get('id')):
    loc = i.find('location')
    f = loc.get('file','').replace(os.getcwd()+'/','') if loc is not None else ''
    print("  [%s] %s  %s:%s" % (i.get('severity'), i.get('id'), f,
                               loc.get('line','?') if loc is not None else '?'))
    print("        %s" % i.get('message')[:100])
PY
        echo
        echo "完整报告: app/build/reports/lint-results-debug.html"
        ;;

    release)
        if [ ! -f keystore.properties ]; then
            echo "!! 缺少 keystore.properties，release 包会没有签名、装不上。" >&2
            echo "   见 README 的「签名」一节。" >&2
            exit 1
        fi
        "$GRADLE_BIN" assembleRelease
        echo
        echo "=== release APK ==="
        verify_apk app/build/outputs/apk/release/app-release.apk
        ;;

    dist)
        if [ ! -f keystore.properties ]; then
            echo "!! 缺少 keystore.properties，跳过 release 构建。" >&2
            "$GRADLE_BIN" assembleDebug
        else
            "$GRADLE_BIN" assembleDebug assembleRelease
        fi
        collect
        echo
        echo "=== 成品（dist/）==="
        for f in dist/*.apk; do
            echo "$(basename "$f")"
            verify_apk "$f"
            echo
        done
        echo "装机："
        echo "  $ANDROID_HOME/platform-tools/adb install -r dist/juping-$VER-release.apk"
        ;;

    debug|*)
        "$GRADLE_BIN" assembleDebug
        echo
        echo "=== debug APK ==="
        verify_apk app/build/outputs/apk/debug/app-debug.apk
        ;;
esac
