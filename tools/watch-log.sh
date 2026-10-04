#!/usr/bin/env bash
#
# watch-log.sh — 实时看「聚屏」自己的日志（不看系统噪音，也不看控制点轮询）
#
# 用法：
#   ./tools/watch-log.sh                    # 看当前连接的盒子
#   ./tools/watch-log.sh 192.168.1.8:5555   # 先局域网 adb connect
#
# 为什么要有它：盒子系统日志极吵 —— 全量 logcat 里**我们自己的日志只占 2.8%**
# （其余是 Cmpb_MW / wpa_supplicant / HiMarket / SmartFS / AudioPolicyService…）；
# 再叠上控制点每秒 2~3 次的 GetPositionInfo/GetTransportInfo 轮询（每条请求 6 行、
# 含 SOAP 全文 XML），真正的事件根本看不见。这条命令把**两层噪音都滤掉**，
# 只留状态变化与写指令。
#
# 与它配套：
#   tools/verify-on-device.sh    —— 主动跑一遍验收
#   tools/dlna-probe.py          —— 控制点视角自检 / 手动投一个流
#   tools/device-log-analyze.py  —— 事后按测试项提取判据
set -uo pipefail

ADB="${ADB:-adb}"
TARGET="${1:-}"

if [ -n "$TARGET" ]; then
    "$ADB" connect "$TARGET" >/dev/null 2>&1 || true
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "[!] 没有设备。先 adb connect <盒子IP>:5555，或接上 USB。" >&2
    exit 2
fi

# 只留我们的 TAG + 播放器/音频这几个排障相关的。
# 再把「逐条取证」与「只读轮询」滤掉 —— 它们占了日志的 98% 以上。
"$ADB" logcat -s \
    DlnaRendererService:V UpnpHttpServer:V MediaPlayerController:V \
    SsdpResponder:V MediaProxy:V MainActivity:V WebCastHost:V \
    CmpbPlayer:V AwesomePlayer:V MediaPlayer:V \
    AndroidRuntime:E ActivityManager:E \
| grep -v -E "取证 (请求头|body|已回|音量指令)|action=(GetPositionInfo|GetTransportInfo|GetMediaInfo)|<s:Envelope|<< (GET|POST) /"
