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
#   ./tools/build.sh proxy        # 跑本地预取代理测试（字节一致性）
#   ./tools/build.sh web          # 跑网页上传解析测试（multipart 逐字节一致 + 名字规整）
#   ./tools/build.sh version      # 升版本号（patch 默认 / minor / major）——出包前跑这个
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
# JDK 的定位逻辑已收敛到 tools/lib.sh（原先在本文件 + 四个 run.sh 里各有一份，
# 注释里自己写着「改一处要同步另一处」）。选择顺序与理由见该文件。
. "$ROOT/tools/lib.sh"
resolve_java_home
export ANDROID_HOME="$TOOLCHAIN/sdk"
export ANDROID_SDK_ROOT="$TOOLCHAIN/sdk"
export GRADLE_USER_HOME="$TOOLCHAIN/gradle-home"
GRADLE_BIN="$TOOLCHAIN/gradle/bin/gradle"
BT="$ANDROID_HOME/build-tools/33.0.0"

# --- 前置检查：按子命令区分需要什么 ---
# protocol / policy / proxy / web 是纯桌面闸门，只需要一个 JDK（CI 上跑的就是它们）；
# secrets 连 JDK 都不需要（纯 python3）。**不能一律要求完整工具链** ——
# 那会让这几道闸门在 CI 上永远跑不起来（实测：workflow 连续 4 次全红，
# 根因就是这里无条件检查 gradle / android.jar）。
case "${1:-debug}" in
    protocol|policy|proxy|web) NEED_JAVA=1; NEED_TOOLCHAIN=0 ;;
    secrets)                   NEED_JAVA=0; NEED_TOOLCHAIN=0 ;;
    version)                   NEED_JAVA=0; NEED_TOOLCHAIN=0 ;;   # 只改 build.gradle 两行
    *)                         NEED_JAVA=1; NEED_TOOLCHAIN=1 ;;
esac

missing=0
check_tool() {
    if [ ! -e "$1" ]; then
        echo "缺少工具链组件: $1" >&2
        missing=1
    fi
}
if [ "$NEED_JAVA" -eq 1 ]; then
    check_tool "$JAVA_HOME/bin/java"
fi
if [ "$NEED_TOOLCHAIN" -eq 1 ]; then
    check_tool "$GRADLE_BIN"
    check_tool "$ANDROID_HOME/platforms/android-33/android.jar"
fi
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

# --- 版本纪律（二夜定的规矩：每次要拿去装机的构建，版本号必须更新）---
# dist/ 记状态文件（dist/ 本身在 .gitignore 里，clean 会连它删掉 —— 删了
# 就重新记，不影响判断）。build_version() 出包成功后写入本次版本；
# dist 分支构建**前**对比：版本还等于上一包的 → 红，先跑
# `./tools/build.sh version` 升号再来。
VERSION_STATE="dist/.last-build-version"

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
        # 断言总数守卫 —— 防的是「断言被静默删掉」。probe 那次实测：
        # 一条关键断言因条件分支不再执行，总数 33→32，闸门照样绿 ——
        # 「全部通过」和「该测的都测了」是两回事。断言只会越写越多，
        # 总数变少几乎必然有问题；有意增删后同步更新这里的期望值即可。
        local summary
        summary="$(grep -oE '协议一致性：[0-9]+ / [0-9]+' "$out" | head -1)"
        if [ "$summary" != "协议一致性：245 / 245" ]; then
            echo "  !! 协议断言总数变了：期望「协议一致性：245 / 245」，实际「${summary:-（没找到）}」" >&2
            echo "     总数变少几乎必然是有一条断言被静默删掉或跳过 —— 先查清楚，" >&2
            echo "     确认是有意增删后再同步这里的期望值。" >&2
            rm -f "$out"; return 1
        fi
        echo "  DLNA: $summary"
        rm -f "$out"; return 0
    fi
    echo "  !! 协议层核验未通过 —— 手机可能投不进来：" >&2
    # 失败清单必须完整打出来，不能只 tail —— FAIL 行散布在整个输出的
    # 各个小节里，tail 一截，失败的「是哪几条」就丢了，只剩一句「没通过」。
    # 这不是理论风险：证伪脚本破坏一个守卫，FAIL 行落在输出中部，
    # tail -60 之后什么线索都不剩（第一版就是这样）。
    grep '\[FAIL\]' "$out" >&2 || true
    grep -E '^  · ' "$out" >&2 || true
    grep '!!' "$out" >&2 || true
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
        # 计数守卫 —— 防「文档里写的用例总数」与「闸门期望值/实际跑出来的」悄悄对不上。
        # 存在的数字散落在 README.md 与 .agent/AGENTS.md 里手写同步，靠人肉必然漂：
        # 之前有过 proxy 用例 10→11、两处文档漏改（QA 跑测试才发现），README 里还
        # 长期留着一句 `协议一致性：219 / 219`（实际早是 237）。现在五道闸门的总数
        # 都由 tools/check_gate_counts.py 核对：规范值是这里/run.sh 里的期望字符串，
        # 文档跟不上就红。对齐不上就红，逼着改文档或改期望值。
        if [ -f tools/check_gate_counts.py ]; then
            if ! python3 tools/check_gate_counts.py "$out"; then
                rm -f "$out"; return 1
            fi
        fi
        rm -f "$out"; return 0
    fi
    echo "  !! 播放策略核验未通过 —— 断联后可能不会恢复，或无限重连：" >&2
    # 同 verify_protocol：FAIL 行散布在各小节，tail 会把「是哪几条」截没。
    # 证伪实测：破坏一个中部守卫，tail -60 之后一条线索都不剩。
    grep '\[FAIL\]' "$out" >&2 || true
    grep -E '^  · ' "$out" >&2 || true
    grep '!!' "$out" >&2 || true
    tail -15 "$out" >&2
    rm -f "$out"; return 1
}

verify_proxy() {
    if [ ! -f tools/proxy-test/run.sh ]; then
        echo "  代理: 跳过（没有 tools/proxy-test/run.sh）"
        return 0
    fi
    local out
    out="$(mktemp)"
    if tools/proxy-test/run.sh >"$out" 2>&1; then
        # 断言总数守卫 —— 同 verify_protocol：防的是「断言被静默删掉」。
        # 代理的 oracle 是逐字节一致，漏测一条 = 一条字节路径没人守，
        # 而闸门照样全绿 —— 「全部通过」和「该测的都测了」是两回事。
        # 断言只会越写越多；有意增删后同步更新这里的期望值即可。
        local summary
        summary="$(grep -oE '代理一致性：[0-9]+ / [0-9]+' "$out" | head -1)"
        if [ "$summary" != "代理一致性：11 / 11" ]; then
            echo "  !! 代理断言总数变了：期望「代理一致性：11 / 11」，实际「${summary:-（没找到）}」" >&2
            echo "     总数变少几乎必然是有一条断言被静默删掉或跳过 —— 先查清楚，" >&2
            echo "     确认是有意增删后再同步这里的期望值。" >&2
            rm -f "$out"; return 1
        fi
        echo "  代理: 字节一致性通过（11 / 11，全量/Range/回拖/EOS/中途重连）"
        rm -f "$out"; return 0
    fi
    echo "  !! 本地预取代理核验未通过 —— 投屏可能花屏或数据错乱：" >&2
    echo "     代理吐出的字节必须与源逐字节一致，差一个就是花屏。" >&2
    # 同 verify_protocol / verify_policy：失败清单必须完整打出来，不能只 tail ——
    # FAIL 行散布在各个断言里，tail 一截「是哪几条」就丢了，只剩一句「没通过」。
    grep '\[FAIL\]' "$out" >&2 || true
    grep -E '^  · ' "$out" >&2 || true
    grep '!!' "$out" >&2 || true
    tail -15 "$out" >&2
    rm -f "$out"; return 1
}

# 网页上传解析核验：multipart 流式解析与文件名规整都是纯逻辑，桌面就能跑。
# 两条 oracle 都很硬：解析出来的文件内容必须与写进去的**逐字节相同**（差一个字节，
# 用户传上去的视频就是坏的，而且要播到那一段才发现），规整后的名字必须**只可能是
# 上传目录内的一个普通名字**（接口无鉴权、名字完全由请求方给）。
# 附带两条源码级守卫：上传页不许引外部资源（盒子没有外网，一个 CDN 就足以整页白屏），
# 页面上的按钮背后都要有真路由（防"点了没反应"的静默漂移）。
verify_web() {
    if [ ! -x tools/web-test/run.sh ]; then
        echo "  网页: 跳过（没有 tools/web-test/run.sh）"
        return 0
    fi
    local out
    out="$(mktemp)"
    if tools/web-test/run.sh >"$out" 2>&1; then
        # 断言总数守卫 —— 同 verify_protocol / verify_proxy：防的是「断言被静默删掉」。
        # 这道闸门刚立起来就抓到一条真 bug（字段段之后的那一段体恒为 0 字节，
        # 只在同一请求里带多个分段时才现形 —— 真机每次传一个文件永远碰不到），
        # 正是"断言只会越写越多、总数变少必有蹊跷"这条判据的意义。
        local summary
        summary="$(grep -oE '网页逻辑：[0-9]+ / [0-9]+' "$out" | head -1)"
        if [ "$summary" != "网页逻辑：52 / 52" ]; then
            echo "  !! 网页用例总数变了：期望「网页逻辑：52 / 52」，实际「${summary:-（没找到）}」" >&2
            echo "     总数变少几乎必然是有一条断言被静默删掉或跳过 —— 先查清楚，" >&2
            echo "     确认是有意增删后再同步这里的期望值。" >&2
            rm -f "$out"; return 1
        fi
        echo "  网页: ${summary}（逐字节一致 / 名字规整 / 源码级不变量）"
        rm -f "$out"; return 0
    fi
    echo "  !! 网页上传解析核验未通过 —— 传上来的文件可能是坏的，或名字能穿越出目录：" >&2
    # 同 verify_protocol / verify_policy：失败清单必须完整打出来，不能只 tail。
    grep '\[FAIL\]' "$out" >&2 || true
    grep '!!' "$out" >&2 || true
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

    proxy)
        verify_proxy
        ;;

    web)
        verify_web
        ;;

    dist)
        # 版本纪律硬闸：同一个版本号不允许连出两包。
        # 教训：0.2.4 落地后连着发了两个 dist 包（批 3.9 + modeName 修复），
        # 装机上分不清跑的是哪个 —— 这正是 versionCode 存在的意义。
        if [ -f "$VERSION_STATE" ] && [ "$(cat "$VERSION_STATE")" = "$VER" ]; then
            echo "!! 版本号没更新：上一包就是 ${VER}（状态文件 ${VERSION_STATE}）。" >&2
            echo "   每次要拿去装机的构建必须升版 —— 跑 ./tools/build.sh version" >&2
            echo "   （小更新 patch / 新能力 minor / 大更新 major，自动改 build.gradle）" >&2
            exit 1
        fi
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
        echo "=== 本地预取代理（同上，纯 Java，不需要真机）==="
        verify_proxy
        echo
        echo "=== 网页上传解析（同上，纯逻辑，不需要真机）==="
        verify_web
        echo
        echo "=== 密钥核查（这个仓库要公开）==="
        verify_secrets
        # 全绿到这里才算「这个版本真出了个能装的包」—— 状态文件在闸门之后写，
        # 半途红掉的构建不占用版本号（否则失败了还得手动升一次才能重试）。
        echo "$VER" > "$VERSION_STATE"
        echo
        echo "装机 + 真机验收（一条命令）："
        echo "  ./tools/verify-on-device.sh              # USB 连接的盒子"
        echo "  ./tools/verify-on-device.sh 192.168.1.9  # 局域网 adb"
        echo
        echo "  它会安装、拉起服务、从日志里读出真实地址，再跑控制点自检。"
        echo "  手工装：$ANDROID_HOME/platform-tools/adb install -r dist/juping-$VER-release.apk"
        ;;

    version)
        # 升版本号 —— 每次要装机的构建前跑这个，dist 硬闸盯着没升的。
        # 规则（build.gradle 注释里二夜定的）：
        #   patch（默认）末位 +1；minor 中间位 +1、末位归零；major 首位 +1、后归零。
        #   versionCode 恒 +1（它只随「拿去装机的包」递增，不参与语义化）。
        LEVEL="${2:-patch}"
        python3 - "$LEVEL" <<'PY'
import re, sys
level = sys.argv[1]
if level not in ('patch', 'minor', 'major'):
    sys.exit("未知级别 %r（可用 patch/minor/major）" % level)
p = 'app/build.gradle'
t = open(p, encoding='utf-8').read()
m = re.search(r'versionName "(\d+)\.(\d+)\.(\d+)"', t)
c = re.search(r'versionCode (\d+)', t)
if not m or not c:
    sys.exit('build.gradle 里找不到 versionName/versionCode —— 格式变了，脚本没跟上')
a, b, d = map(int, m.groups())
old = '%d.%d.%d' % (a, b, d)
if level == 'patch': a, b, d = a, b, d + 1
elif level == 'minor': a, b, d = a, b + 1, 0
else: a, b, d = a + 1, 0, 0
t = t.replace(m.group(0), 'versionName "%d.%d.%d"' % (a, b, d))
t = re.sub(r'versionCode \d+', 'versionCode %d' % (int(c.group(1)) + 1), t)
open(p, 'w', encoding='utf-8').write(t)
print('版本已升（%s）：%s → %d.%d.%d，versionCode → %d'
      % (level, old, a, b, d, int(c.group(1)) + 1))
print('记得把这一行改动纳入本次提交。')
PY
        ;;

    debug|*)
        "$GRADLE_BIN" assembleDebug
        echo
        echo "=== debug APK ==="
        verify_apk app/build/outputs/apk/debug/app-debug.apk
        ;;
esac
