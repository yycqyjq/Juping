#!/usr/bin/env bash
#
# 本地预取缓冲代理（MediaProxy）测试
# ---------------------------------------------------------------
# 用 JDK 自带 HttpServer 当片源，站在 MediaPlayer 的视角把真实请求
# 序列走一遍。断言的 oracle 只有一条：代理吐出的字节流必须与源
# 逐字节一致 —— 差一个字节，画面就是花屏。
#
# 与 protocol-test 同样的工具链选择顺序（macOS/Linux 两种布局都认）。
#
# 用法：./tools/proxy-test/run.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    TOOLCHAIN="${ANDROID_BUILD_HOME:-$HOME/.android-build}"
    if [ -x "$TOOLCHAIN/jdk/Contents/Home/bin/javac" ]; then
        export JAVA_HOME="$TOOLCHAIN/jdk/Contents/Home"
    elif [ -x "$TOOLCHAIN/jdk/bin/javac" ]; then
        export JAVA_HOME="$TOOLCHAIN/jdk"
    fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "找不到 javac（需要 JDK 17）" >&2
    exit 2
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT INT TERM

if ! "$JAVA_HOME/bin/javac" -nowarn -encoding UTF-8 -d "$OUT" \
        app/src/main/java/com/juping/cast/player/PlaybackPolicy.java \
        app/src/main/java/com/juping/cast/player/MediaProxy.java \
        "$HERE/android/util/Log.java" \
        "$HERE/ProxyTest.java" 2>"$OUT/javac.err"; then
    echo "编译失败：" >&2
    cat "$OUT/javac.err" >&2
    exit 2
fi

"$JAVA_HOME/bin/java" -cp "$OUT" ProxyTest
exit $?
