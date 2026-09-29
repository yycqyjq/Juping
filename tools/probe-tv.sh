#!/usr/bin/env bash
# probe-tv.sh — Android TV 盒子能力探测（面向 Android 4.0 老盒子）
#
# 目的：一次性拿到投屏接收端技术选型所需的全部关键信息
#   1. 真实 Android 版本（很多盒子标称 4.0，实际是 4.2 / 4.4）
#   2. 芯片方案（决定硬解走哪条路）
#   3. 硬件解码能力（OMX 组件 / 编解码器清单）
#   4. 网络接口（有没有线网口 —— 这是稳定性最关键的一环）
#   5. 内存、屏幕、已安装的投屏类 App
#
# 用法：
#   ./probe-tv.sh                    # 探测 USB 连接的设备
#   ./probe-tv.sh 192.168.1.100      # 先局域网 adb 连接再探测
#   ADB=~/platform-tools/adb ./probe-tv.sh
#
# 前置条件：盒子需开启 USB 调试（设置 → 开发者选项），或支持网络调试

set -u

ADB="${ADB:-adb}"
TARGET="${1:-}"

if ! command -v "$ADB" >/dev/null 2>&1; then
  cat <<'EOF'
[!] 找不到 adb。

macOS 安装方式：
    brew install --cask android-platform-tools

或手动下载 Android SDK Platform-Tools：
    https://developer.android.com/tools/releases/platform-tools

装好后重跑本脚本。
EOF
  exit 1
fi

if [ -n "$TARGET" ]; then
  echo ">>> 尝试通过局域网连接 $TARGET ..."
  "$ADB" connect "$TARGET" 2>&1 | sed 's/^/    /'
  echo
fi

CONNECTED=$("$ADB" devices 2>/dev/null | tail -n +2 | grep -c "device$")
if [ "$CONNECTED" -eq 0 ]; then
  cat <<'EOF'
[!] 没有检测到已连接的设备。

排查顺序：
  1. 盒子「设置 → 开发者选项 → USB 调试」是否已打开
     （Android 4.0 路径一般是 设置 → 开发者选项 → USB 调试）
  2. USB 线是否支持数据传输（很多线只供电）
  3. 首次连接需在盒子屏幕上点「允许调试」
  4. 走局域网：先用 adb connect <盒子IP>:5555
EOF
  exit 1
fi

# 老设备上命令/属性经常不存在，统一容错：过滤掉 shell 报错噪声
sh_get() {
  "$ADB" shell "$1" 2>/dev/null | tr -d '\r' \
    | grep -viE "not found|no such file|inaccessible|permission denied|^$" || true
}
prop() { sh_get "getprop $1"; }

echo
echo "============================================================"
echo " Android TV 盒子能力探测报告"
echo "============================================================"
echo

# ---------- 1. 系统版本 ----------
SDK="$(prop ro.build.version.sdk)"
echo "[1] 系统版本 —— 决定 API 选择"
echo "    Android 版本 : $(prop ro.build.version.release)"
echo "    API level    : ${SDK:-未知}"
echo "    Build 号     : $(prop ro.build.display.id)"
echo "    厂商 / 型号  : $(prop ro.product.manufacturer) / $(prop ro.product.model)"
echo "    构建指纹     : $(prop ro.build.fingerprint)"
case "${SDK:-0}" in
  14|15) echo "    >> API ${SDK}：真正的 Android 4.0 —— 无 MediaCodec，硬解需走野路子" ;;
  16)    echo "    >> API 16：Android 4.1 —— 有 MediaCodec，但功能不全" ;;
  17)    echo "    >> API 17：Android 4.2 —— MediaCodec 基本可用" ;;
  18)    echo "    >> API 18：Android 4.3 —— MediaCodec 较完善" ;;
  19)    echo "    >> API 19：Android 4.4 —— 生态支持好很多" ;;
  20|21) echo "    >> API ${SDK}：Android 5.x —— 主流方案都能跑" ;;
  *)     echo "    >> API ${SDK:-未知}：请对照版本表判断" ;;
esac
echo

# ---------- 2. 芯片方案 ----------
echo "[2] 芯片方案 —— 决定硬解走哪条路"
echo "    平台     : $(prop ro.board.platform)"
echo "    硬件     : $(prop ro.hardware)"
echo "    芯片     : $(prop ro.product.board) $(prop ro.chipname)"
echo "    CPU 架构 : $(prop ro.product.cpu.abi)"
echo "    CPU 信息 :"
sh_get "cat /proc/cpuinfo" | grep -iE "^(Processor|Hardware|model name|Features|BogoMIPS)" | head -6 | sed 's/^/      /'
echo

# ---------- 3. 硬解能力 ----------
echo "[3] 硬件解码能力 —— 决定能播什么、要不要降级"
OMX_LIST="$(sh_get "ls /system/lib/omx")"
if [ -z "$OMX_LIST" ]; then
  OMX_LIST="$(sh_get "ls /system/lib" | grep -i omx || true)"
fi
if [ -n "$OMX_LIST" ]; then
  echo "    OMX 组件："
  printf '%s\n' "$OMX_LIST" | sed 's/^/      /'
else
  echo "    OMX 组件目录未找到，继续尝试其他途径"
fi

MC="$(sh_get "cat /system/etc/media_codecs.xml")"
if [ -n "$MC" ]; then
  echo "    media_codecs.xml 声明的解码器："
  printf '%s\n' "$MC" | grep -iE "MediaCodec name" | sed 's/^ *//' | head -20 | sed 's/^/      /'
fi

DUMPSYS="$(sh_get "dumpsys media.player")"
if [ -n "$DUMPSYS" ]; then
  echo "    dumpsys media.player（前 25 行）："
  printf '%s\n' "$DUMPSYS" | head -25 | sed 's/^/      /'
fi

if [ -n "$(sh_get "ls /system/lib/libstagefrighthw.so")" ]; then
  echo "    libstagefrighthw.so 存在 → 有硬件编解码插件，硬解可用"
else
  echo "    libstagefrighthw.so 不存在 → 可能只有软解"
fi
echo

# ---------- 4. 网络 ----------
echo "[4] 网络接口 —— 有线网口是稳定性的关键"
sh_get "netcfg" | sed 's/^/      /'
sh_get "ip addr" | grep -E "^[0-9]+:|inet " | sed 's/^/      /'
echo

# ---------- 5. 内存与屏幕 ----------
echo "[5] 内存与屏幕"
sh_get "cat /proc/meminfo" | head -3 | sed 's/^/      /'
echo "      屏幕密度 : $(prop ro.sf.lcd_density)"
echo "      分辨率   : $(prop ro.sf.lcd_width) x $(prop ro.sf.lcd_height)"
sh_get "wm size" | sed 's/^/      /'
echo

# ---------- 6. 已装投屏类 App ----------
echo "[6] 已安装的投屏相关 App —— 先看自带的是谁"
sh_get "pm list packages" \
  | grep -iE "hpplay|lebo|cast|dlna|airplay|miracast|screenmirror|wifidisplay" \
  | sed 's/^/      /'
echo

echo "============================================================"
echo " 报告结束。把以上输出发我，即可确定技术选型。"
echo "============================================================"
