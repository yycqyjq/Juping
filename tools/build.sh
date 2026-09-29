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
#   ./tools/build.sh dist         # 两个都编，跑全部核验，成品归集到 dist/
#   ./tools/build.sh lint         # 跑 lint（API 兼容性检查）
#   ./tools/build.sh checkapi     # 逐个核验平台 API 引用是否在目标版本里存在
#   ./tools/build.sh dex          # 核验 dex 里框架回调/Thread 子类/协议常量是否完好
#   ./tools/build.sh protocol     # 跑 DLNA 协议层一致性测试（桌面 JVM，不需要真机）
#   ./tools/build.sh policy       # 跑播放重连策略测试（纯逻辑 + 源码不变量守卫）
#   ./tools/build.sh clean        # 清理构建产物
#
# 产物：
#   dist/juping-<版本号>-release.apk   ← 装机用这个
#   dist/juping-<版本号>-debug.apk     ← 排障用（含 debuggable 标记）
#
#   <版本号> 取自 app/build.gradle 的 versionName（脚本自己读，不写死）。
#   这里刻意不写具体版本号 —— 写死过一次，升版本时注释没跟着改，
#   变成文档漂移（要靠 grep 才发现）。

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
    # stderr 一起吞掉：AGP 往 META-INF 里塞的 app-metadata.properties 不在签名保护范围内，
    # apksigner 每次都要为此警告一句，属于噪声。
    if "$BT/apksigner" verify --min-sdk-version 15 "$apk" >/dev/null 2>&1; then
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
    # 先清掉旧的。不清的话，升过版本之后 dist/ 里会同时躺着 0.1.2 和 0.1.3 两个包 ——
    # 而下面那个 `for f in dist/*.apk` 会把**旧的也核一遍**（核的是上一轮的代码，
    # 却在报告里和新的混在一起，看着像"四个包都验过了"）。
    # 更糟的是装机时挑错文件：文件名只差一个数字，眼睛一扫就过去了。
    rm -f dist/*.apk
    cp -f app/build/outputs/apk/release/app-release.apk "dist/juping-$VER-release.apk"
    cp -f app/build/outputs/apk/debug/app-debug.apk     "dist/juping-$VER-debug.apk"
}

# API 兼容性核验：确认 dex 里引用的每个平台成员，在目标版本里真的存在。
# 这是独立于 lint 的第二道判据 —— 不存在的成员在真机上就是 NoSuchMethodError，
# 而编译期（对着新版 android.jar）完全不会报错。
verify_api() {
    local apk="$1" out
    out="$(mktemp)"
    local min
    min="$(python3 tools/apk_info.py "$apk" 2>/dev/null | grep minSdkVersion | awk '{print $3}')"
    if [ -z "$min" ]; then
        echo "  API : 跳过（读不出 minSdkVersion）"
        rm -f "$out"; return 0
    fi
    if [ ! -f "$ANDROID_HOME/platforms/android-$min/android.jar" ]; then
        echo "  API : 跳过（缺 API $min 的基线 android.jar）"
        rm -f "$out"; return 0
    fi
    if python3 tools/check_api_compat.py "$apk" "$min" >"$out" 2>&1; then
        echo "  API : $(grep -oE '结论：.*' "$out" | head -1)"
        rm -f "$out"; return 0
    fi
    echo "  !! API 越界 —— 目标版本里不存在这些成员，真机上必崩：" >&2
    sed -n '/找不到/,$p' "$out" >&2
    rm -f "$out"; return 1
}

# dex 入口点核验：开了 R8 之后，包里的名字会被改。**编译期能确定的引用**
# 改得一致就没问题，但框架靠名字回调的那些（Activity 生命周期、Runnable.run、
# MediaPlayer 的各种 Listener……）改不得 —— 改了就是"装得上、点开就崩"。
#
# 这是第五道判据，补的是前面几道**结构上就看不见**的盲区：
#   · 编译期看不到（源码里名字是对的）
#   · lint 看不到
#   · check_api_compat 看不到（它查"平台成员在不在"，不查"我们的名字对不对"）
#   · 协议层 / 策略层看不到（它们编译的是源码，不是 dex）
verify_dex() {
    local apk="$1" out
    if [ ! -f tools/check_dex_entrypoints.py ]; then
        echo "  dex入口: 跳过（没有 tools/check_dex_entrypoints.py）"
        return 0
    fi
    out="$(mktemp)"
    if python3 tools/check_dex_entrypoints.py "$apk" >"$out" 2>&1; then
        echo "  dex入口: $(grep -oE '结论：.*' "$out" | head -1)"
        rm -f "$out"; return 0
    fi
    echo "  !! dex 入口点核查未通过 —— 这个包装上会崩：" >&2
    grep -E '^\s+\[FAIL\]' -A 2 "$out" >&2
    rm -f "$out"; return 1
}

# 协议层一致性核验：把真实的 UpnpHttpServer / SsdpResponder 编到桌面 JVM 上，
# 用原始 socket 发真实 DLNA 报文，核对响应是否合规。
# 这是第三道判据 —— 前两道（签名、API）只管"装得上、跑不崩"，
# 这一道管"手机投得进来"。整套 UPnP 是手写的，最容易出错又最难在真机上调，
# 而它恰好完全不依赖 Android 运行时，所以可以在这里拦住。
verify_protocol() {
    if [ ! -x tools/protocol-test/run.sh ]; then
        echo "  DLNA: 跳过（没有 tools/protocol-test/run.sh）"
        return 0
    fi
    local out
    out="$(mktemp)"
    if tools/protocol-test/run.sh >"$out" 2>&1; then
        echo "  DLNA: $(grep -oE '协议一致性：.*' "$out" | head -1)"
        rm -f "$out"; return 0
    fi
    echo "  !! 协议层核验未通过 —— 手机可能投不进来：" >&2
    # 失败清单必须完整打出来，不能只 tail —— FAIL 行散布在整个输出的
    # 各个小节里，tail 一截，失败的「是哪几条」就丢了，只剩一句「没通过」。
    # 这不是理论风险：证伪脚本破坏一个守卫，FAIL 行落在输出中部，
    # tail -60 之后什么线索都不剩（第一版就是这样）。
    grep '\[FAIL\]' "$out" >&2 || true
    grep -E '^  · ' "$out" >&2 || true
    tail -15 "$out" >&2
    rm -f "$out"; return 1
}

# 播放策略核验：退避表、卡死阈值、熔断边界，以及「卡死→重连→又卡死」
# 这个循环到底会不会停。这些是「不断联」承诺的实现，但埋在要 MediaPlayer 的类里
# 就没法验证 —— 所以抽成了 PlaybackPolicy（纯逻辑，零 Android 依赖）。
# 另外附一道源码级不变量守卫：单元测试挡不住「有人把 stallCount 清零挪回
# onPrepared」这种回归（策略函数本身会全绿，但熔断整体失效）。
verify_policy() {
    if [ ! -x tools/policy-test/run.sh ]; then
        echo "  策略: 跳过（没有 tools/policy-test/run.sh）"
        return 0
    fi
    local out
    out="$(mktemp)"
    if tools/policy-test/run.sh >"$out" 2>&1; then
        echo "  策略: $(grep -oE '播放策略：.*' "$out" | head -1)"
        rm -f "$out"; return 0
    fi
    echo "  !! 播放策略核验未通过 —— 断联后可能不会恢复，或无限重连：" >&2
    # 同 verify_protocol：FAIL 行散布在各小节，tail 会把「是哪几条」截没。
    # 证伪实测：破坏一个中部守卫，tail -60 之后一条线索都不剩。
    grep '\[FAIL\]' "$out" >&2 || true
    grep -E '^  · ' "$out" >&2 || true
    tail -15 "$out" >&2
    rm -f "$out"; return 1
}

# 密钥核查：这个仓库是要公开的，而 release 签名密钥一旦泄漏，
# 任何人都能伪造出「能覆盖升级到已装设备上」的 APK —— 比源码泄漏严重得多。
# 所以把「密钥有没有混进被跟踪的文件」做成闸门，而不是靠人记得去翻。
# 判据按值的性质分类（密码类必须零命中；路径/别名类只提示），
# 否则会报一堆假阳性 —— 检查器喊狼来了，跟不检查一样糟。
verify_secrets() {
    if [ ! -f tools/check_no_secrets.py ]; then
        echo "  密钥: 跳过（没有 tools/check_no_secrets.py）"
        return 0
    fi
    local out
    out="$(mktemp)"
    if python3 tools/check_no_secrets.py >"$out" 2>&1; then
        echo "  密钥: $(grep -oE '结论：.*' "$out" | head -1)"
        rm -f "$out"; return 0
    fi
    echo "  !! 密钥核查未通过 —— 不要把仓库推公开：" >&2
    tail -30 "$out" >&2
    rm -f "$out"; return 1
}

case "${1:-debug}" in
    protocol)
        verify_protocol
        ;;

    policy)
        verify_policy
        ;;

    secrets)
        verify_secrets
        ;;

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

    checkapi)
        APK="app/build/outputs/apk/release/app-release.apk"
        [ -f "$APK" ] || APK="app/build/outputs/apk/debug/app-debug.apk"
        if [ ! -f "$APK" ]; then
            echo "还没有编译产物，先跑 ./tools/build.sh" >&2
            exit 1
        fi
        # 两个基线都核：minSdk 是最严格的（APK 声明支持它），
        # 目标设备版本是实际要跑的。两个都过才算数。
        MIN="$(python3 tools/apk_info.py "$APK" 2>/dev/null | grep minSdkVersion | awk '{print $3}')"
        python3 tools/check_api_compat.py "$APK" "$MIN"
        echo
        echo "--- 再对实际设备版本（API 15）核一遍 ---"
        python3 tools/check_api_compat.py "$APK" 15
        ;;

    dex)
        APK="app/build/outputs/apk/release/app-release.apk"
        [ -f "$APK" ] || APK="app/build/outputs/apk/debug/app-debug.apk"
        if [ ! -f "$APK" ]; then
            echo "还没有编译产物，先跑 ./tools/build.sh" >&2
            exit 1
        fi
        python3 tools/check_dex_entrypoints.py "$APK"
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
        verify_dex app/build/outputs/apk/release/app-release.apk
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
            verify_api "$f"
            verify_dex "$f"
            echo
        done
        echo "=== 协议层（与芯片/系统版本无关，只需核一次）==="
        verify_protocol
        echo
        echo "=== 播放策略（同上，纯逻辑，不需要真机）==="
        verify_policy
        echo
        echo "=== 密钥核查（这个仓库要公开）==="
        verify_secrets
        echo
        echo "装机 + 真机验收（一条命令）："
        echo "  ./tools/verify-on-device.sh              # USB 连接的盒子"
        echo "  ./tools/verify-on-device.sh 192.168.1.9  # 局域网 adb"
        echo
        echo "  它会安装、拉起服务、从日志里读出真实地址，再跑控制点自检。"
        echo "  手工装：$ANDROID_HOME/platform-tools/adb install -r dist/juping-$VER-release.apk"
        ;;

    debug|*)
        "$GRADLE_BIN" assembleDebug
        echo
        echo "=== debug APK ==="
        verify_apk app/build/outputs/apk/debug/app-debug.apk
        ;;
esac
