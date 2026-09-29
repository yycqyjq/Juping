#!/usr/bin/env bash
#
# verify-on-device.sh — 一条命令完成真机验收
# ---------------------------------------------------------------
# 为什么要有这个脚本：
#   前面五道闸（签名 / API 引用 / 协议一致性 / 播放策略 / R8 dex 入口点）
#   全都是**桌面端**跑的。
#   它们能证明"代码自洽"，但证明不了"盒子真的收得到投屏"。真机上只有三件事
#   必须真机验，且都验不了于桌面：
#     ① SSDP 组播收不收得到 —— 受 MulticastLock、网卡选择、路由器 IGMP 影响
#     ② MediaPlayer 能不能硬解 MT5880 上的真实码流
#     ③ 断流自愈策略在真实网络下走不走得通
#   这个脚本把这三件事压成一条命令，省掉每次手敲十几条 adb。
#
# 用法：
#   ./tools/verify-on-device.sh                       # USB 连接的盒子
#   ./tools/verify-on-device.sh 192.168.1.100         # 先局域网 adb connect
#   ./tools/verify-on-device.sh 192.168.1.100 --play 'http://x/y.mp4?a=1&b=2'
#
# 可选参数：
#   --play <URL>        顺带真投一个流（会改变盒子上的播放状态）
#   --ssdp-port <N>     探测用的 SSDP 端口，默认 1900。
#                       盒子上应用固定用 1900，一般不用动；它存在的意义是
#                       让这个脚本自己也能被自测（见 verify-device-selftest.sh）。
#
# 前置：盒子已开 USB 调试（Android 4.0 路径：设置 → 开发者选项 → USB 调试）
#
# 退出码：0 = 全过；1 = 有失败项；2 = 环境问题（没 adb / 没设备 / 装不上）

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

ADB="${ADB:-adb}"
PKG="com.juping.cast"
ACTIVITY="$PKG/.MainActivity"
# 等的是 **SSDP 绑上组播**那一行，不是「接收端已就绪」。
#
# 为什么换了：LOCATION 现在由 SsdpResponder 在绑上网卡之后算出来并打进日志，
# 「接收端已就绪」那条早于绑定完成，拿它当就绪信号会读到空地址。
# 而且「绑上了」才是用户真正在意的状态 —— 绑不上就是手机搜不到设备。
# 绑定失败现在会自动带退避重试（见 SsdpResponder.bindUntilReady），
# 所以这里等不到就说明是**持续**失败，日志里能直接看到原因。
TAG_READY="SSDP 已加入组播组"

TARGET=""
PLAY_URL=""
SSDP_PORT=1900
while [ $# -gt 0 ]; do
    case "$1" in
        --play) PLAY_URL="${2:-}"; shift 2 ;;
        --ssdp-port) SSDP_PORT="${2:-1900}"; shift 2 ;;
        -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) TARGET="$1"; shift ;;
    esac
done

# ─────────────────────────────────────────── 0. 前置检查
if ! command -v "$ADB" >/dev/null 2>&1; then
    cat >&2 <<'EOF'
[!] 找不到 adb。

macOS 安装：brew install --cask android-platform-tools
或手动下载 Android SDK Platform-Tools：
    https://developer.android.com/tools/releases/platform-tools

装好后重跑本脚本（也可用 ADB=/path/to/adb 指定）。
EOF
    exit 2
fi

if [ -n "$TARGET" ]; then
    echo ">>> 局域网连接 $TARGET ..."
    "$ADB" connect "$TARGET" 2>&1 | sed 's/^/    /'
fi

if [ "$("$ADB" devices 2>/dev/null | tail -n +2 | grep -c 'device$')" -eq 0 ]; then
    cat >&2 <<'EOF'
[!] 没有检测到设备。

排查顺序：
  1. 盒子「设置 → 开发者选项 → USB 调试」是否已打开
  2. USB 线是否支持数据传输（很多线只供电）
  3. 首次连接要在盒子屏幕上点「允许调试」
  4. 走局域网：./tools/verify-on-device.sh <盒子IP>
EOF
    exit 2
fi

# 老设备上很多命令/属性不存在，统一容错
sh_get() { "$ADB" shell "$1" 2>/dev/null | tr -d '\r'; }

DEV_SDK="$(sh_get 'getprop ro.build.version.sdk' | head -1 | tr -d '[:space:]')"
DEV_REL="$(sh_get 'getprop ro.build.version.release' | head -1 | tr -d '[:space:]')"
DEV_MODEL="$(sh_get 'getprop ro.product.model' | head -1 | tr -d '[:space:]')"

echo
echo "============================================================"
echo " 真机验收 —— 聚屏（Juping）"
echo "============================================================"
echo "  设备      : ${DEV_MODEL:-未知}"
echo "  Android   : ${DEV_REL:-未知}（API ${DEV_SDK:-未知}）"

# ─────────────────────────────────────────── 1. 选包 + minSdk 比对
APK=""
for cand in dist/juping-*-release.apk dist/juping-*-debug.apk; do
    [ -f "$cand" ] && APK="$cand" && break
done
if [ -z "$APK" ]; then
    echo "  产物      : 没有找到 —— 先跑 ./tools/build.sh dist" >&2
    exit 2
fi
echo "  安装包    : $APK"

# 提前拦一道：装不上的真实原因常常是 minSdk 高于设备 API，
# 而 adb 只会回一句 INSTALL_FAILED_OLDER_SDK，看不出差多少。
MIN_SDK="$(python3 "$HERE/apk_info.py" "$APK" 2>/dev/null \
           | sed -n 's/.*minSdkVersion *: *\([0-9]*\).*/\1/p' | head -1)"
if [ -n "$MIN_SDK" ] && [ -n "$DEV_SDK" ] && [ "$MIN_SDK" -gt "$DEV_SDK" ] 2>/dev/null; then
    echo
    echo "[×] 这个包装不上：minSdk=${MIN_SDK}，设备 API=${DEV_SDK}。" >&2
    echo "    差 $((MIN_SDK - DEV_SDK)) 级。要么换低版本包，要么降 minSdk。" >&2
    exit 2
fi

# ─────────────────────────────────────────── 2. 安装
echo
echo "── ① 安装 ──"
# -r 覆盖安装，保留数据；老设备上偶发 INSTALL_FAILED_ALREADY_EXISTS，故带 -r
if ! "$ADB" install -r "$APK" 2>&1 | sed 's/^/  /' | grep -q Success; then
    echo "  安装失败。常见原因：" >&2
    echo "    · 空间不足 —— adb shell df /data 看看" >&2
    echo "    · 签名冲突 —— 之前装过另一个签名的同名包，先 adb uninstall $PKG" >&2
    exit 2
fi
echo "  安装成功"

# ─────────────────────────────────────────── 3. 启动服务
echo
echo "── ② 启动接收端 ──"
sh_get "am force-stop $PKG" >/dev/null 2>&1 || true
"$ADB" logcat -c 2>/dev/null || true
# MainActivity 在 onCreate 里 startService，所以拉起界面就等于拉起服务
sh_get "am start -n $ACTIVITY" | sed 's/^/  /'

# ─────────────────────────────────────────── 4. 从 logcat 抠出真实地址
# 不去猜盒子在哪个网卡上：SsdpResponder 会把**实际绑定的那张网卡**算出的
# 设备描述地址打进日志。这比解析 netcfg / ip addr 可靠 ——
# 它反映的正是组播真正绑上的那个接口，也就是手机唯一能访问到的那个。
echo
echo "── ③ 等 SSDP 绑上组播（最多 30 秒）──"
LOCATION=""
for _ in $(seq 1 60); do
    LOG="$("$ADB" logcat -d -v brief 2>/dev/null || true)"
    LINE="$(printf '%s\n' "$LOG" | grep "$TAG_READY" | tail -1 || true)"
    if [ -n "$LINE" ]; then
        # 形如：... 网卡=eth0（候选 2 张），LOCATION=http://192.168.1.9:49152/upnp/device.xml
        LOCATION="$(printf '%s' "$LINE" | sed -n 's|.*LOCATION=\(http://[0-9.]*:[0-9]*/[^ ]*\).*|\1|p')"
        [ -n "$LOCATION" ] && break
    fi
    sleep 0.5
done

if [ -z "$LOCATION" ]; then
    echo "  [×] 30 秒内没等到「${TAG_READY}」。" >&2
    echo >&2
    echo "  ── 相关日志 ──" >&2
    printf '%s\n' "$LOG" | grep -E "DlnaRenderer|SsdpResponder|UpnpHttpServer|NetUtil" | tail -30 >&2
    echo >&2
    echo "  重点看这几类行：" >&2
    echo "    · 「第 N 次绑定 SSDP 失败：...」—— 正在重试。若一直重试，看它给的原因：" >&2
    echo "      找不到网卡 = 盒子还没连上网；所有候选网卡都无法加入组播组 = 网卡本身不行" >&2
    echo "    · MulticastLock —— 没拿到锁就收不到任何 SSDP 搜索" >&2
    echo "    · 1900 端口被占（BindException）—— 盒子自带的投屏服务在抢" >&2
    exit 1
fi

BOX_IP="${LOCATION#http://}"
BOX_IP="${BOX_IP%%:*}"
BOX_PORT="$(printf '%s' "$LOCATION" | sed -n 's|http://[0-9.]*:\([0-9]*\)/.*|\1|p')"
echo "  就绪：$LOCATION"
echo "  盒子 IP=$BOX_IP  HTTP 端口=$BOX_PORT"

# ─────────────────────────────────────────── 5. 控制点视角自检
echo
echo "── ④ 控制点视角自检（dlna-probe.py）──"
PROBE_ARGS=("$BOX_IP" --http-port "$BOX_PORT" --ssdp-port "$SSDP_PORT")
[ -n "$PLAY_URL" ] && PROBE_ARGS+=(--play "$PLAY_URL")
python3 "$HERE/dlna-probe.py" "${PROBE_ARGS[@]}"
PROBE_RC=$?

# ─────────────────────────────────────────── 6. 播放相关日志
echo
echo "── ⑤ 播放链路日志（MediaPlayer 相关）──"
"$ADB" logcat -d -v brief 2>/dev/null \
    | grep -E "MediaPlayerController|DlnaRendererService|MediaPlayer|NuPlayer|AwesomePlayer|Stagefright" \
    | tail -40 | sed 's/^/  /'
echo "  （Android 4.0 上是 AwesomePlayer；出现 error / 无法播放 就要看编码格式了）"

# ─────────────────────────────────────────── 7. 网络诊断（仅失败时）
if [ "$PROBE_RC" -ne 0 ]; then
    echo
    echo "── ⑥ 网络诊断（自检没过才打）──"
    echo "  · 盒子侧接口："
    sh_get 'ip addr' | grep -E '^[0-9]+:|inet ' | sed 's/^/      /'
    sh_get 'netcfg' | sed 's/^/      /'
    echo "  · 组播锁状态："
    "$ADB" logcat -d -v brief 2>/dev/null | grep -i multicast | tail -5 | sed 's/^/      /'
fi

# ─────────────────────────────────────────── 8. 结论
echo
echo "============================================================"
if [ "$PROBE_RC" -eq 0 ]; then
    echo " 真机验收通过 —— 盒子能被控制点发现，且控制指令全部合规"
    echo
    echo " 还剩一件事只能你自己确认："
    echo "   用手机上的腾讯视频 / B站 点一次投屏，看画面能不能出来、"
    echo "   拖进度条会不会卡死、拔网线 20 秒再插上会不会自愈。"
    echo "   这三件事脚本替不了你。"
else
    echo " 真机验收未通过 —— 上面的失败项就是原因"
    echo " 把完整输出发我。"
fi
echo "============================================================"

exit "$PROBE_RC"
