#!/usr/bin/env bash
#
# 播放重连策略测试
# ---------------------------------------------------------------
# PlaybackPolicy 是纯逻辑、不依赖任何 Android 类，所以能直接在桌面 JVM 上跑。
#
# 除了跑断言，这个脚本还做一道「源码级不变量守卫」：
# 把「卡死计数只能在播放有实际进展时归零」这条规则钉在源码上。
# 光靠单元测试挡不住 —— 有人把 stallCount = 0 挪回 onPrepared，
# 策略函数本身全绿，但整个熔断机制又失效了。
#
# 用法：./tools/policy-test/run.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

TOOLCHAIN="${ANDROID_BUILD_HOME:-$HOME/.android-build}"
export JAVA_HOME="$TOOLCHAIN/jdk/Contents/Home"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

if [ ! -x "$JAVAC" ]; then
    echo "找不到 javac —— 需要 JAVA_HOME 指向 JDK（当前: $JAVA_HOME）" >&2
    exit 2
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT INT TERM

POLICY="app/src/main/java/com/juping/cast/player/PlaybackPolicy.java"
CTRL="app/src/main/java/com/juping/cast/player/MediaPlayerController.java"

# ── 1. 编译（不需要 android.jar —— PlaybackPolicy 零 Android 依赖）──
echo "── 编译播放策略（桌面 JVM，零 Android 依赖）──"
if ! "$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" \
        "$POLICY" "$HERE/PolicyTest.java" 2>"$OUT/javac.err"; then
    echo "编译失败：" >&2
    cat "$OUT/javac.err" >&2
    exit 2
fi
echo "  通过（证明 PlaybackPolicy 确实不依赖 Android 运行时）"

# ── 2. 单独把控制器也编一遍（对着 android.jar，不走 gradle）──
# 为什么要单独做这一步：PolicyTest 只编译 PlaybackPolicy，
# 所以 MediaPlayerController 里的语法错误它**看不见** ——
# 曾经因此漏掉一个未闭合的块注释，直到跑 gradle 才发现。
# 这里用 android.jar 做一次快速语法/类型检查，几秒钟就能挡住这类问题。
echo "── 编译 MediaPlayerController（对着 android.jar 快速语法检查）──"
ANDROID_JAR="${ANDROID_HOME:-$TOOLCHAIN/sdk}/platforms/android-33/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
    echo "  跳过（找不到 $ANDROID_JAR）"
else
    mkdir -p "$OUT/ctrl"
    if "$JAVAC" -nowarn -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/ctrl" \
            "$POLICY" "$CTRL" 2>"$OUT/ctrl.err"; then
        echo "  通过（MediaPlayerController 语法与类型检查无误）"
    else
        echo "编译失败：" >&2
        cat "$OUT/ctrl.err" >&2
        exit 2
    fi
fi

# ── 3. 跑断言 ──
RC=0
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT" PolicyTest || RC=$?

# ── 4. 源码级不变量守卫 ──
echo
echo "── 源码级不变量（单元测试挡不住的那类回归）──"

python3 - "$CTRL" <<'PY' || RC=1
import re, sys, pathlib

src = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
failed = []

def body_of(marker):
    """从 marker 处开始，按大括号配对抠出方法体"""
    i = src.find(marker)
    if i < 0:
        return None
    j = src.find('{', i)
    if j < 0:
        return None
    depth, k = 0, j
    while k < len(src):
        if src[k] == '{':
            depth += 1
        elif src[k] == '}':
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
        k += 1
    return None

def report(name, ok, detail=''):
    print('  [%s] %s%s' % ('PASS' if ok else 'FAIL', name,
                           ('\n         ' + detail) if detail else ''))
    if not ok:
        failed.append(name)

# ① onPrepared 里不许碰 stallCount
op = body_of('public void onPrepared(MediaPlayer mp)')
report('onPrepared 方法体已找到', op is not None,
       '锚点：public void onPrepared(MediaPlayer mp)')
if op:
    hit = [l.strip() for l in op.splitlines() if 'stallCount' in l]
    report('onPrepared 里不出现 stallCount（否则熔断失效）', not hit,
           ('出现了：' + ' / '.join(hit)) if hit else 'onPrepared 只管 retryCount')

# ② checkStall 里必须在「位置变了」的分支清零
cs = body_of('private void checkStall()')
report('checkStall 方法体已找到', cs is not None, '锚点：private void checkStall()')
if cs:
    report('checkStall 里有 stallCount = 0（唯一该归零的地方）',
           'stallCount = 0' in cs, '位置前进时必须归零')

# ③ 卡死计数的清零点只能落在三个地方：
#      play()       —— 换新视频，从头开始
#      stop()       —— 停止播放
#      checkStall() —— 位置真的前进了
#    绝不能落在 onPrepared 里（那就是熔断失效的根因）。
meth_decls = [(m.start(), m.group(1)) for m in re.finditer(
    r'(?m)^\s*(?:public|private|protected)\s+[^\n;{=]*?\b(\w+)\s*\(', src)]

def enclosing_method(pos):
    name = None
    for s, n in meth_decls:
        if s < pos:
            name = n
        else:
            break
    return name

allowed = {'play', 'stop', 'checkStall'}
sites = []
for m in re.finditer(r'stallCount\s*=\s*0', src):
    sites.append((src.count('\n', 0, m.start()) + 1, enclosing_method(m.start())))
owners = set(n for _, n in sites)
report('stallCount 清零点只落在 play / stop / checkStall',
       owners == allowed,
       '实际清零点：%s（应为 %s）' % (sites, sorted(allowed)))
report('stallCount 清零点不在 onPrepared 里（熔断的命门）',
       'onPrepared' not in owners,
       '一旦回到 onPrepared，熔断永不触发，变成无限重连')

# ④ 文档里不许再出现「分辨率降级」这种没实现的承诺。
#    注意只查"承诺式"的写法（<li><b>分辨率降级</b>），
#    因为文件里另外有一处是**说明它没实现**的正常文字。
report('注释里没有未实现的「分辨率降级」承诺',
       not re.search(r'<li>\s*<b>\s*分辨率降级', src),
       'DLNA 推的是手机指定的 URL，换不了码率；写成稳定性特性就是假承诺')

# ⑤ 待执行的重连必须持有引用（否则取消不掉）
report('pendingRetry 被持有并可取消',
       'private Runnable pendingRetry' in src and 'cancelPendingRetry' in src,
       '匿名 Runnable 直接 postDelayed 就没法取消')

# ⑥ 块注释定界符必须配平。
#    这一步是"零依赖兜底"：上面的 android.jar 编译检查更准，
#    但找不到 android.jar 时会跳过，而块注释漏个 */ 会让整个类被注释吞掉，
#    报出的错误行号（第一个字段声明处）与真实原因（几十行之前的注释）相距甚远，很难查。
report('块注释定界符配平（/* 与 */ 数量一致）',
       src.count('/*') == src.count('*/'),
       '/* = %d，*/ = %d' % (src.count('/*'), src.count('*/')))

sys.exit(1 if failed else 0)
PY

echo
if [ "$RC" -ne 0 ]; then
    echo "播放策略核验未通过。" >&2
fi
exit "$RC"
