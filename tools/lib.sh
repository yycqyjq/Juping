#!/usr/bin/env bash
#
# tools/ 下各构建/测试脚本共用的环境准备。
# ---------------------------------------------------------------
# 为什么要有这个文件：JDK 的定位逻辑本来复制在 4 处（build.sh 与
# protocol / policy / proxy 三个 run.sh），而且注释里自己都写着
# 「改一处要同步另一处」—— 一处改了另一处忘了，症状是「协议闸门绿、
# 策略闸门红」，看起来像断言挂了，其实是环境问题。收敛到这里。
#
# 用法（source，不要执行）：
#   build.sh 里：      . "$ROOT/tools/lib.sh"
#   tools/<x>-test/：  . "$HERE/../lib.sh"
#
# 本文件只定义函数：不执行动作、不改 shell 选项，单独运行无副作用。

# 选定一个可用的 JDK 并把 JAVA_HOME 导出。
#
# 顺序：
#   ① 环境里已有可用的 javac → 尊重它。CI 上 setup-java 会设；本地也可能
#      指向系统 JDK。**不能无条件覆盖** —— 那样在没有本地工具链的机器
#      （CI runner）上会直接报「工具链不完整」退出，而这几道桌面闸门本来
#      只需要一个 JDK。
#   ② 否则回退到自包含工具链（ANDROID_BUILD_HOME 或 ~/.android-build）。
#      **两种目录布局都要认**：当初绕开 sdkmanager 下载的 macOS 包解出来是
#      Contents/Home 结构，Linux 的 tar.gz 是平铺的 —— 只认一种，换个平台
#      这套脚本就跑不了。
#   ③ 都没有：把 JAVA_HOME 指向首选布局，让调用方的存在性检查报出可读的错
#      ——而不是在 `set -u` 下炸成 unbound variable。
#
# 不返回值、不 exit：「找没找到」由调用方决定怎么报（各脚本的文案不同）。
resolve_java_home() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        return 0
    fi
    local toolchain="${ANDROID_BUILD_HOME:-$HOME/.android-build}"
    if [ -x "$toolchain/jdk/Contents/Home/bin/javac" ]; then
        export JAVA_HOME="$toolchain/jdk/Contents/Home"
    elif [ -x "$toolchain/jdk/bin/javac" ]; then
        export JAVA_HOME="$toolchain/jdk"
    else
        export JAVA_HOME="$toolchain/jdk/Contents/Home"
    fi
}