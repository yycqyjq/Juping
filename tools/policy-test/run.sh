#!/usr/bin/env bash
#
# 播放重连策略测试
# ---------------------------------------------------------------
# PlaybackPolicy 是纯逻辑、不依赖任何 Android 类，所以能直接在桌面 JVM 上跑。
#
# 除了跑断言，这个脚本还做「源码级不变量守卫」：
# 把「卡死计数只能在播放有实际进展时归零」「初始事件必须晚于 200 响应」
# 「音乐层必须不透明且盖在 SurfaceView 之上」这类规则钉在源码上。
# 它们有个共同点：一旦被破坏，编译、运行、日志**全都正常**，只是功能悄悄失效。
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
HTTP="app/src/main/java/com/juping/cast/dlna/UpnpHttpServer.java"
SVC="app/src/main/java/com/juping/cast/DlnaRendererService.java"
ACT="app/src/main/java/com/juping/cast/MainActivity.java"
LAYOUT="app/src/main/res/layout/activity_main.xml"
COLORS="app/src/main/res/values/colors.xml"

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

# ── 5. GENA 事件层的源码级不变量 ──
# 这几条有两个共同点：都曾经是"写了但没接上"的口子，而且**都没法用网络断言测**。
#   · 初始事件早于 200 响应 —— 比较两条不同 socket 的到达时刻本质上是竞态的，
#     测出来是"偶尔红"的假信号，比不测更糟；
#   · 音量回读是不是常量、事件源接没接上 —— 属于实现细节，报文层面看不出来。
# 所以钉在源码上。判据取"够精确但不脆"：只认结构性事实，不认排版。
python3 - "$HTTP" "$SVC" <<'PY' || RC=1
import re, sys, pathlib

http_src = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
svc_src = pathlib.Path(sys.argv[2]).read_text(encoding='utf-8')
failed = []

def report(name, ok, detail=''):
    print('  [%s] %s%s' % ('PASS' if ok else 'FAIL', name,
                           ('\n         ' + detail) if detail else ''))
    if not ok:
        failed.append(name)

def body_of(src, marker):
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

# ⑦ SUBSCRIBE 必须真的推事件，不能回个 SID 就完事
report('SUBSCRIBE 走真订阅（调 events.subscribe）',
       'events.subscribe(' in http_src,
       '旧实现只回 200 + SID 从不推送 —— 控制点的播放状态永远不刷新')
report('UNSUBSCRIBE 走真退订（调 events.unsubscribe）',
       'events.unsubscribe(' in http_src, '')
for h in ('callback:', 'sid:', 'timeout:'):
    report('请求头解析读到了 %s' % h, h in http_src,
           '少读一个头，订阅流程整条走不通，而且日志里看起来一切正常')

# ⑧ 初始事件必须在 200 响应之后发
flush_at = http_src.find('out.flush()')
fire_at = http_src.find('events.fireInitial(')
report('先 flush 响应、再推初始事件',
       flush_at >= 0 and fire_at > flush_at,
       'flush@%d fireInitial@%d —— 事件早于响应会被控制点当成未知 SID 丢掉'
       % (flush_at, fire_at))

# ⑨ 事件分发器自带线程池，必须随服务一起关
sd = body_of(http_src, 'public void shutdown()')
report('shutdown() 方法体已找到', sd is not None, '锚点：public void shutdown()')
if sd:
    report('shutdown() 里关掉了事件线程池', 'events.shutdown()' in sd,
           '不关就留一个后台线程不放，0.6GB 内存的盒子上不该有这种东西')

# ⑩ 业务层必须真的是事件源，且音量回读不许是常量
report('DlnaRendererService 实现了 EventSource',
       'EventDispatcher.EventSource' in svc_src, '')

ev = body_of(svc_src, 'public Map<String, String> eventedVars(String service)')
report('eventedVars 方法体已找到', ev is not None,
       '锚点：public Map<String, String> eventedVars(String service)')
if ev:
    m = re.search(r'vars\.put\("Volume",\s*([^;]+?)\)\s*;', ev)
    report('事件里的 Volume 取自 getVolume0to100()',
           bool(m) and 'getVolume0to100()' in m.group(1),
           ('实际写成：' + m.group(1).strip()) if m else '找不到 Volume 那一行')

gv = body_of(svc_src, 'public int getVolume0to100()')
report('getVolume0to100 方法体已找到', gv is not None,
       '锚点：public int getVolume0to100()')
if gv:
    lit = re.search(r'return\s+(\d+)\s*;', gv)
    report('音量回读不是硬编码常量', lit is None,
           ('回的是字面量 %s —— 控制点拖完音量条再读会看到跳回去，等于对着它撒谎'
            % lit.group(1)) if lit else '委托给播放器')

sys.exit(1 if failed else 0)
PY

# ── 6. 音乐模式界面的源码级不变量 ──
# 这几条守的是「静默回归」：把音乐层的背景改成透明、或者把它挪到 SurfaceView
# 前面，代码照样编译、照样运行、日志里一个字都不多 —— 只是电视又变回一片黑。
# 而「声音在放、画面全黑」恰恰是用户最容易误判成「投屏坏了」的现象。
python3 - "$LAYOUT" "$COLORS" "$ACT" <<'PY' || RC=1
import re, sys, pathlib
import xml.etree.ElementTree as ET

layout_path, colors_path, act_path = sys.argv[1], sys.argv[2], sys.argv[3]
layout_src = pathlib.Path(layout_path).read_text(encoding='utf-8')
colors_src = pathlib.Path(colors_path).read_text(encoding='utf-8')
act_src = pathlib.Path(act_path).read_text(encoding='utf-8')
failed = []

def report(name, ok, detail=''):
    print('  [%s] %s%s' % ('PASS' if ok else 'FAIL', name,
                           ('\n         ' + detail) if detail else ''))
    if not ok:
        failed.append(name)

def body_of(src, marker):
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

AND = '{http://schemas.android.com/apk/res/android}'
try:
    root = ET.fromstring(layout_src)
    order = [el.get(AND + 'id') for el in root.iter() if el.get(AND + 'id')]
    by_id = {el.get(AND + 'id'): el for el in root.iter() if el.get(AND + 'id')}
except Exception as e:
    report('布局是合法 XML', False, str(e))
    sys.exit(1)

# ⑪ 音乐层必须画在 SurfaceView **之后**（FrameLayout 里后画的在上面）
report('布局里有音乐层 @+id/music', '@+id/music' in by_id, str(order))
report('音乐层排在 SurfaceView 之后（否则被画面盖住）',
       '@+id/surface' in by_id and '@+id/music' in by_id
       and order.index('@+id/music') > order.index('@+id/surface'),
       '实际顺序：%s' % order)

# ⑫ 音乐层的背景必须**完全不透明**，否则底下 SurfaceView 的黑色会透出来
music_bg = by_id['@+id/music'].get(AND + 'background') if '@+id/music' in by_id else None
report('音乐层有背景色', bool(music_bg), '实际：%r' % music_bg)
if music_bg:
    opaque = True
    why = ''
    if music_bg in ('@null', '@android:color/transparent') or 'transparent' in music_bg:
        opaque, why = False, '直接写成了透明：%s' % music_bg
    else:
        m = re.match(r'@color/(\w+)$', music_bg)
        if not m:
            opaque, why = False, '认不出这是哪个颜色资源：%s' % music_bg
        else:
            c = re.search(r'<color name="%s">\s*#([0-9A-Fa-f]{6,8})\s*</color>' % m.group(1),
                          colors_src)
            if not c:
                opaque, why = False, '在 colors.xml 里找不到 %s' % music_bg
            else:
                hexv = c.group(1)
                if len(hexv) == 8 and hexv[0:2].upper() != 'FF':
                    opaque, why = False, 'alpha=%s，不是完全不透明' % hexv[0:2]
                else:
                    why = '%s = #%s' % (music_bg, hexv)
    report('音乐层背景完全不透明（挡住 SurfaceView 的黑）', opaque,
           why + ' —— 半透明/透明都会让底下那片黑透出来')

# ⑬ 代码要能找得到音乐卡片上的两个 TextView
for rid in ('@+id/music_source', '@+id/music_progress'):
    report('布局里有 %s' % rid, rid in by_id, '')

# ⑭ 三态互斥必须真的落到 View 上
am = body_of(act_src, 'private void applyMode(int mode)')
report('applyMode(int) 方法体已找到', am is not None,
       '锚点：private void applyMode(int mode)')
if am:
    for call in ('panel.setVisibility', 'music.setVisibility', 'overlay.setVisibility'):
        report('applyMode 里设了 %s' % call, call in am,
               '三种形态互斥靠这三句，漏一句就会出现两层同时可见')
    report('音乐层只在 MODE_AUDIO 时可见',
           re.search(r'music\.setVisibility\([^;]*MODE_AUDIO', am) is not None,
           '写成恒 VISIBLE 的话，放视频时也会盖一层音符卡片上去')

# ⑮ 界面必须去问服务「这是不是纯音频」，而不是自己猜
report('界面依据 service.isAudioOnly() 判形态',
       'isAudioOnly()' in act_src,
       '判据的权威来源在服务里（元数据 + 真实视频尺寸两个信号合并），界面不自己猜')

sys.exit(1 if failed else 0)
PY

echo
if [ "$RC" -ne 0 ]; then
    echo "播放策略核验未通过。" >&2
fi
exit "$RC"
