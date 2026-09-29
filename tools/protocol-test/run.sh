#!/usr/bin/env bash
#
# 协议层一致性测试
# ---------------------------------------------------------------
# 把真实的 UpnpHttpServer / SsdpResponder 编译到桌面 JVM 上跑起来，
# 用原始 socket 发真实的 DLNA 报文，核对响应是否合规。
#
# 为什么能这么干：整个 dlna 包对 Android 的依赖只有 android.util.Log 一个类，
# 补一个桌面替身（tools/protocol-test/android/util/Log.java）就齐了。
#
# 为什么值得干：整套 UPnP 是手写的，没用任何库 —— 这是全项目最可能出错、
# 又最难在真机上调的部分，而它恰好完全可以脱离 Android 验证。
#
# 用法：./tools/protocol-test/run.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

TOOLCHAIN="${ANDROID_BUILD_HOME:-$HOME/.android-build}"
export JAVA_HOME="$TOOLCHAIN/jdk/Contents/Home"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

if [ ! -x "$JAVAC" ]; then
    echo "找不到 javac —— 需要 JAVA_HOME 指向 JDK（当前: ${JAVA_HOME}）" >&2
    exit 2
fi

OUT="$(mktemp -d)"
SERVER_PID=""
cleanup() {
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    rm -rf "$OUT"
}
trap cleanup EXIT INT TERM

# ── 1. 编译（不需要 android.jar —— dlna 包只用到一个 Log）──
echo "── 编译协议层（桌面 JVM，零 Android 依赖）──"
mkdir -p "$OUT/classes"
if ! "$JAVAC" -nowarn -encoding UTF-8 -d "$OUT/classes" \
        "$HERE/android/util/Log.java" \
        app/src/main/java/com/juping/cast/dlna/NetUtil.java \
        app/src/main/java/com/juping/cast/dlna/UpnpHttpServer.java \
        app/src/main/java/com/juping/cast/dlna/EventDispatcher.java \
        app/src/main/java/com/juping/cast/dlna/SsdpResponder.java \
        "$HERE/ProtocolTestServer.java" 2>"$OUT/javac.err"; then
    echo "编译失败：" >&2
    cat "$OUT/javac.err" >&2
    exit 2
fi
echo "  通过（证明 dlna 包确实不依赖 Android 运行时）"

# ── 2. 找两个空闲端口（HTTP 走 TCP，SSDP 走 UDP）──
HTTP_PORT="$(python3 -c "
import socket
s = socket.socket(); s.bind(('127.0.0.1', 0))
print(s.getsockname()[1]); s.close()")"
SSDP_PORT="$(python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.bind(('127.0.0.1', 0))
print(s.getsockname()[1]); s.close()")"
CALL_LOG="$OUT/calls.log"

# ── 3. 起服务（单条命令内起、用、收）──
echo "── 启动 UpnpHttpServer（TCP ${HTTP_PORT}）+ SsdpResponder（UDP ${SSDP_PORT}）──"
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT/classes" ProtocolTestServer \
    "$HTTP_PORT" "$CALL_LOG" "$SSDP_PORT" \
    >"$OUT/server.out" 2>"$OUT/server.err" &
SERVER_PID=$!

for _ in $(seq 1 100); do
    if grep -q READY "$OUT/server.out" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "服务端启动即退出：" >&2
        cat "$OUT/server.err" >&2
        exit 2
    fi
    sleep 0.1
done

if ! grep -q READY "$OUT/server.out" 2>/dev/null; then
    echo "服务端 10 秒内没起来：" >&2
    cat "$OUT/server.err" >&2
    exit 2
fi

# READY <http端口> <实际ssdp端口> <location>
READY_LINE="$(grep READY "$OUT/server.out" | head -1)"
ACTUAL_SSDP_PORT="$(echo "$READY_LINE" | awk '{print $3}')"

# ── 4. 驱动 ──
python3 "$HERE/drive.py" "$HTTP_PORT" "$CALL_LOG" "$ACTUAL_SSDP_PORT"
RC=$?

# ── 5. 拿同一个靶机验证 dlna-probe.py 自身 ──
# 探测脚本是二夜在真机上唯一能用的排障工具。脚本自己报假警报，比没有工具更糟：
# 会把「盒子没问题」误判成「盒子有问题」，于是去改本来正确的代码。
# 所以它也得进回归 —— 而它恰好可以被这套靶机完整覆盖。
echo
echo "── 用同一靶机验证 tools/dlna-probe.py（控制点视角自检）──"
PROBE_LOG="$OUT/probe.log"
PROBE_RC=0
python3 "$ROOT/tools/dlna-probe.py" 127.0.0.1 \
    --ssdp-port "$ACTUAL_SSDP_PORT" --http-port "$HTTP_PORT" \
    --play 'http://127.0.0.1:9/probe.mp4?token=a&expire=1' \
    >"$PROBE_LOG" 2>&1 || PROBE_RC=$?

if [ "$PROBE_RC" -eq 0 ]; then
    echo "  $(grep '控制点自检：' "$PROBE_LOG" | tail -1 | sed 's/^ *//')"
else
    echo "  dlna-probe.py 未全过 —— 注意这是**脚本自身**的问题，不是被测代码的：" >&2
    grep -E '\[FAIL\]' "$PROBE_LOG" >&2 || true
    RC=1
fi

# ── 6. 再验一遍「真机验收脚本」自己 ──
# 同一个道理：verify-on-device.sh 是二夜在盒子上唯一能用的工具，
# 它坏了会把人引去查根本不存在的 SSDP bug。复用同一个靶机跑一遍管道。
echo
if ! "$HERE/verify-device-selftest.sh" "$HTTP_PORT" "$ACTUAL_SSDP_PORT"; then
    RC=1
fi

# ── 7. 失败时把服务端日志带出来 ──
if [ "$RC" -ne 0 ]; then
    echo
    echo "── 服务端日志（最后 40 行）──"
    tail -40 "$OUT/server.err" 2>/dev/null || true
fi

exit "$RC"
