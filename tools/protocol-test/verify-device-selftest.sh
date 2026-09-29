#!/usr/bin/env bash
#
# verify-device-selftest.sh — 给 verify-on-device.sh 做管道自测
# ---------------------------------------------------------------
# 为什么要有这个：
#   verify-on-device.sh 是二夜在盒子上唯一能用的验收工具，而它自己没被测过。
#   里面有一堆 sed 抽取、闸门顺序、参数拼装 —— 任何一处坏了，表现都是
#   **对着一台完全健康的盒子说"30 秒内没等到就绪"**，把人引去查根本不存在的
#   SSDP bug。（dlna-probe.py 已经踩过一模一样的坑，见 protocol-test/run.sh。）
#
# 做法：
#   造一个"假 adb"，按真实 adb 的输出格式回放那几条命令；
#   靶机用 protocol-test 起的那个**真服务**（真 UpnpHttpServer + 真 SsdpResponder）。
#   于是整条管道——解析参数 → 比 minSdk → 装包 → 起服务 → 从 logcat 抠地址
#   → 调 dlna-probe.py → 判结论——都被真跑了一遍。
#
# 用法（由 run.sh 在靶机起来之后调用）：
#   ./tools/protocol-test/verify-device-selftest.sh <HTTP端口> <SSDP端口>
#
# 退出码：0 = 全过；1 = 有失败项

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

HTTP_PORT="${1:?用法: verify-device-selftest.sh <HTTP端口> <SSDP端口>}"
SSDP_PORT="${2:?用法: verify-device-selftest.sh <HTTP端口> <SSDP端口>}"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

FAIL=0
ok()  { echo "  [PASS] $1"; }
bad() { echo "  [FAIL] $1"; FAIL=1; }

# ── 假 logcat ──
# 「接收端已就绪」那一行是 verify-on-device.sh 唯一的地址来源，
# 格式必须与 DlnaRendererService 里真正打印的一致。
cat > "$OUT/logcat.txt" <<EOF
D/DlnaRendererService( 1234): 服务创建
I/DlnaRendererService( 1234): MulticastLock 已获取
I/DlnaRendererService( 1234): WifiLock 已获取
I/DlnaRendererService( 1234): 接收端已就绪：名称=聚屏-MT5880 地址=http://127.0.0.1:$HTTP_PORT/upnp/device.xml
D/UpnpHttpServer( 1234): UPnP HTTP 服务已启动，端口 $HTTP_PORT
EOF

# ── 假 adb ──
# 只回放 verify-on-device.sh 真正会用到的那几条；其余一律静默成功。
cat > "$OUT/adb" <<'FAKE'
#!/usr/bin/env bash
case "$1" in
  devices) printf 'List of devices attached\nFAKE0001\tdevice\n' ;;
  connect) echo "connected to ${2:-}" ;;
  install) echo "Success" ;;
  logcat)
    [ "${2:-}" = "-c" ] && exit 0
    cat "$FAKE_LOGCAT"
    ;;
  shell)
    case "$2" in
      "getprop ro.build.version.sdk")     echo "${FAKE_SDK:-15}" ;;
      "getprop ro.build.version.release") echo 4.0.4 ;;
      "getprop ro.product.model")         echo MT5880-BOX ;;
      am\ start*)                         echo "Starting: Intent { cmp=com.juping.cast/.MainActivity }" ;;
      "ip addr") printf '1: lo: <LOOPBACK,UP>\n2: eth0: <BROADCAST,MULTICAST,UP>\n' ;;
      netcfg)    echo "eth0 UP 127.0.0.1/8" ;;
      *) : ;;
    esac
    ;;
  *) : ;;
esac
exit 0
FAKE
chmod +x "$OUT/adb"

echo "── 真机验收脚本自测（假 adb + 真靶机）──"

LOG="$OUT/verify.log"
FAKE_LOGCAT="$OUT/logcat.txt" \
ADB="$OUT/adb" \
    "$ROOT/tools/verify-on-device.sh" --ssdp-port "$SSDP_PORT" \
    >"$LOG" 2>&1
RC=$?

# ① 靶机是健康的，脚本必须报通过 —— 这是最重要的一条
[ "$RC" -eq 0 ] \
    && ok "退出码 0（靶机健康，脚本不该报错）" \
    || bad "退出码 ${RC}，期望 0"

# ② 地址抽取：全脚本最容易静默坏掉的一步
grep -q "盒子 IP=127.0.0.1  HTTP 端口=$HTTP_PORT" "$LOG" \
    && ok "从 logcat 正确抠出 IP 与端口" \
    || bad "地址抽取不对：$(grep -m1 '盒子 IP=' "$LOG" || echo '(没打印这一行)')"

# ③ minSdk 闸门没有误拦（APK 是 minSdk 14，假设备是 API 15）
grep -q "安装成功" "$LOG" \
    && ok "minSdk 闸门没误拦，装包走通" \
    || bad "没看到「安装成功」—— 闸门或安装步骤坏了"

# ④ 探测脚本真的被调起来了（而不是被跳过）
grep -q "控制点自检" "$LOG" \
    && ok "dlna-probe.py 被真的调起" \
    || bad "没看到控制点自检输出"

# ⑤ 结尾结论正确
grep -q "真机验收通过" "$LOG" \
    && ok "打印了「真机验收通过」结论" \
    || bad "没有打印通过结论"

# ⑥ 反面对照：设备 API 只有 13（低于 minSdk 14），闸门必须拦住
#    —— 不做这一条，就等于不知道 ③ 那条闸门到底有没有在工作
FAKE_LOGCAT="$OUT/logcat.txt" FAKE_SDK=13 ADB="$OUT/adb" \
    "$ROOT/tools/verify-on-device.sh" --ssdp-port "$SSDP_PORT" \
    >"$OUT/neg.log" 2>&1
NEG=$?
if [ "$NEG" -eq 2 ] && grep -q "这个包装不上" "$OUT/neg.log"; then
    ok "反面对照：API 13 < minSdk 14 时被拦住（退出码 2，且说清了差多少级）"
else
    bad "反面对照失败：退出码 ${NEG}（期望 2），$(grep -m1 '装不上' "$OUT/neg.log" || echo '没打印拒绝原因')"
fi

if [ "$FAIL" -ne 0 ]; then
    echo
    echo "  ── verify-on-device.sh 的完整输出 ──"
    sed 's/^/    /' "$LOG"
fi
exit "$FAIL"
