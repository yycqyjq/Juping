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
    echo "找不到 javac —— 需要 JAVA_HOME 指向 JDK（当前: ${JAVA_HOME}）" >&2
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
    echo "  跳过（找不到 ${ANDROID_JAR}）"
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

def strip_comments(src):
    """去掉 // 与 /* */ 注释（字符串字面量原样保留）。

    Java 注释里最常出现「为什么不用 X」这类说明，不剥离的话检查器会把
    注释读成代码 —— "不用 X"被判成"用了 X"，反过来冤枉正确代码。
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c == '"' or c == "'":
            quote, j = c, i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == quote:
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)

act_src = strip_comments(act_src)

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

# ⑬b 顶部条那个圆点必须**有 id**，否则代码改不了它的颜色。
# 它原来是写死的 dot_online（绿）—— 而顶部条现在只在「暂停 / 出错 / 缓冲」
# 时出现，出错时左边绿点、右边「出错：…」，自己跟自己打架。
report('布局里有 @+id/overlay_dot（顶部条的圆点）',
       '@+id/overlay_dot' in by_id,
       '没有 id 就只能写死颜色；三米外先被看见的是颜色，不是那行小字')

# ⑭ 三态互斥必须真的落到 View 上
am = body_of(act_src, 'private void applyMode(int mode)')
report('applyMode(int) 方法体已找到', am is not None,
       '锚点：private void applyMode(int mode)')
if am:
    for call in ('panel.setVisibility', 'music.setVisibility'):
        report('applyMode 里设了 %s' % call, call in am,
               '三种形态互斥靠这几句，漏一句就会出现两层同时可见')
    report('音乐层只在 MODE_AUDIO 时可见',
           re.search(r'music\.setVisibility\([^;]*MODE_AUDIO', am) is not None,
           '写成恒 VISIBLE 的话，放视频时也会盖一层音符卡片上去')
    report('applyMode 里调了 applyTopBar',
           'applyTopBar(' in am,
           '形态切换时顶部条也要跟着变，否则从 idle 切进播放时它还挂着')

# ⑯ 顶部状态条不许常驻 —— 视频全屏就靠这一条
#
# 又是一个「静默回归」：把 applyTopBar 改回 overlay.setVisibility(VISIBLE)，
# 编译、运行、日志全都正常，只是视频画面上又压了一条半透明黑带。
# 而"多了一条带子"这种事，桌面上跑的任何断言都看不见。
tb = body_of(act_src, 'private void applyTopBar(int mode)')
report('applyTopBar(int) 方法体已找到', tb is not None,
       '锚点：private void applyTopBar(int mode)')
if tb:
    report('顶部条有隐藏分支（不是恒 VISIBLE）',
           re.search(r'overlay\.setVisibility\([^;]*GONE', tb) is not None,
           '恒 VISIBLE 就等于视频画面上永远压着一条带子')
    report('顶部条会因「暂停」出现',
           'PAUSED_PLAYBACK' in tb,
           '暂停时屏幕上没有别的反馈，停在哪只能靠它报')
    report('顶部条会因「出错」出现',
           'getLastError' in tb,
           '黑屏卡住时它是电视端唯一的排错出口')
    report('空闲形态下顶部条隐藏',
           re.search(r'MODE_IDLE', tb) is not None,
           'idle 时是主面板，再叠一条状态条就重复了')
    report('顶部条会按状态改圆点颜色',
           'overlayDot.setBackgroundResource(' in tb,
           '只在暂停/出错/缓冲时出现的条子，出错时必须亮红点 —— '
           '写死的绿点会和旁边的「出错：…」自相矛盾')
    report('顶部条出错时用红点',
           'dot_error' in tb,
           '出错却亮绿灯，等于告诉用户"一切正常"')

# ⑰ 顶部条必须每个 tick 重算，不能只在形态切换时算
rf = body_of(act_src, 'private void refresh()')
report('refresh() 方法体已找到', rf is not None, '锚点：private void refresh()')
if rf:
    report('refresh() 每个 tick 重算顶部条',
           'applyTopBar(' in rf,
           '只写在 applyMode 里的话，播放中途按暂停顶部条永远不出现 —— '
           '暂停是形态**内部**的变化，形态没切换就不会走到那段代码')

# ⑮ 界面必须去问服务「这是不是纯音频」，而不是自己猜
report('界面依据 service.isAudioOnly() 判形态',
       'isAudioOnly()' in act_src,
       '判据的权威来源在服务里（元数据 + 真实视频尺寸两个信号合并），界面不自己猜')

sys.exit(1 if failed else 0)
PY

# ── 7. Seek 路径的源码级不变量 ──
# 这一节守的是「拖一下进度条，电视上进度直接满了」这个现象。
# 机制：MediaPlayer.seekTo() 一旦越过末尾，位置会直接落到结尾并触发播放完成。
# 两种写法都能造成它，而两种在编译期和日志里都不报任何东西：
#   · 把越界目标原样放行；
#   · 把「解析失败」当成 0 下发（0 是合法时刻，语义却是"跳到开头"）。
python3 - "$SVC" "$HTTP" <<'PY' || RC=1
import re, sys, pathlib
svc_path, http_path = sys.argv[1], sys.argv[2]
svc = pathlib.Path(svc_path).read_text(encoding='utf-8')
http = pathlib.Path(http_path).read_text(encoding='utf-8')
failed = []

def report(name, ok, detail=''):
    print('  [%s] %s%s' % ('PASS' if ok else 'FAIL', name,
                           ('\n         ' + detail) if detail else ''))
    if not ok:
        failed.append(name)

def strip_comments(src):
    """去掉 // 行注释与 /* */ 块注释（字符串字面量原样保留）。

    不这么做的话，检查器会去匹配**注释里**的字符串 —— 而注释里恰恰最常出现
    「我们为什么不用 X」这种话，于是"不用 X"被读成"用了 X"，
    检查器反过来冤枉正确代码。本文件里已经栽过一次同类问题（注释里的假承诺）。
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c == '"' or c == "'":
            quote, j = c, i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == quote:
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)

svc = strip_comments(svc)
http = strip_comments(http)

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

seek = body_of(svc, 'public void onSeek(long positionMs)')
report('DlnaRendererService.onSeek 方法体已找到', seek is not None,
       '锚点：public void onSeek(long positionMs)')
if seek:
    report('onSeek 里有越界判断（target 超过 duration 不下发）',
           re.search(r'positionMs\s*>\s*duration', seek) is not None,
           '没有这一条，越界目标会让位置直接落到结尾 —— 用户看到的就是"进度满了"')
    report('onSeek 会记下 target 与 duration',
           'duration=' in seek or 'duration +' in seek,
           '只打 target 看不出它是不是被解析错了，必须和 duration 对着看')

pt = body_of(http, 'private static long parseTimeToMs(String t)')
report('parseTimeToMs 方法体已找到', pt is not None,
       '锚点：private static long parseTimeToMs(String t)')
if pt:
    report('parseTimeToMs 失败时返回 -1，不是 0',
           re.search(r'return\s+-1L', pt) is not None
           and re.search(r'return\s+0L', pt) is None,
           'return 0 等于把"看不懂"翻译成"跳到开头" —— 拖拽不同步的根因就在这')
    report('parseTimeToMs 处理了小数秒（.F+ 在协议里合法）',
           "'.'" in pt or '"."' in pt,
           '不少投屏 SDK 按 00:10:30.000 发，不处理就解析失败')
    report('parseTimeToMs 不用 Double.parseDouble',
           'Double.parseDouble' not in pt,
           '它接受 NaN，而 (long) NaN == 0 —— 又绕回"看不懂就跳到开头"那个坑')

disp = body_of(http, 'private void dispatch(String service, String action, Map<String, String> args)')
report('dispatch 方法体已找到', disp is not None,
       '锚点：private void dispatch(String service, String action, ...)')
if disp:
    report('dispatch 的 Seek 分支校验解析结果（不把 -1 当 0 下发）',
           re.search(r'ms\s*>=\s*0', disp) is not None,
           '不校验的话 -1 会被当成"跳到 0"')
    report('dispatch 区分 Unit（TRACK_NR 的 Target 是曲目号不是时刻）',
           'Unit' in disp and 'REL_TIME' in disp,
           '当时间解析会把"切下一曲"变成"跳回开头"')

# 顶部条的可见性依赖 lastError，所以「什么时候清 lastError」也要守住。
# 只在换片源 / 停止时清的话，一次**已经自愈**的断流会让那句报错一直挂着 ——
# 画面好好的，屏幕上却压着一条故障提示。这属于"谎报军情"，和漏报一样糟。
op = body_of(svc, 'public void onPrepared(int durationMs, boolean hasVideo)')
report('DlnaRendererService.onPrepared 方法体已找到', op is not None,
       '锚点：public void onPrepared(int durationMs, boolean hasVideo)')
if op:
    report('播放就绪时清掉陈旧的 lastError',
           re.search(r'lastError\s*=\s*""', op) is not None,
           '不清的话，自愈之后顶部条会一直挂着那条已经过期的报错')

sys.exit(1 if failed else 0)
PY

# ── 8. 重投 / 进度同步的源码级不变量 ──
# 守两个用户报上来的现象。它们的共同点是「编译、运行、日志全都正常」：
#
#   ·「取消投屏后重新投，有时候投不上去」
#     控制点（腾讯视频 / B站 这类）常把 SetAVTransportURI 和 Play **连着发**，
#     间隔只有几十毫秒。若 Play 到达时 prepare 还没完成，而 resume() 走
#     「有 URL 就 startInternal()」那条分支，就会把**正在准备的那个 MediaPlayer
#     释放掉重建** —— 两条指令互相拆台。最终投得上投不上取决于 prepare 的快慢，
#     于是表现成"有时候行、有时候不行"。
#
#   ·「拖完进度条，手机和电视的进度不同步」
#     三个独立机制都能造成，而且互不排斥：
#       (a) seek 在未 prepare 时被静默丢弃 —— 手机显示拖过去了，电视一动不动；
#       (b) seek 是异步的，位置在真正落地前读到的还是**旧值**，
#           手机轮询 GetPositionInfo 拿到旧位置，进度条被拉回去；
#       (c) seek 未落地时位置本来就不动，看门狗会把它判成"卡死"并触发重连 ——
#           而重连会把播放拉回开头。
python3 - "$CTRL" "$HTTP" <<'PY' || RC=1
import re, sys, pathlib
ctrl_path, http_path = sys.argv[1], sys.argv[2]
ctrl = pathlib.Path(ctrl_path).read_text(encoding='utf-8')
http = pathlib.Path(http_path).read_text(encoding='utf-8')
failed = []

def report(name, ok, detail=''):
    print('  [%s] %s%s' % ('PASS' if ok else 'FAIL', name,
                           ('\n         ' + detail) if detail else ''))
    if not ok:
        failed.append(name)

def strip_comments(src):
    """去掉 // 与 /* */ 注释（字符串字面量原样保留）。

    同第 7 节：不剥离的话，检查器会匹配到**注释里**的字符串 ——
    而注释里恰恰最常写「我们为什么不用 X」，于是"不用 X"被读成"用了 X"。
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c == '"' or c == "'":
            quote, j = c, i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == quote:
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)

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

ctrl = strip_comments(ctrl)
http = strip_comments(http)

# ---- ① 重投：Play 到达时若仍在 prepare，绝不能重建播放器 ----
resume = body_of(ctrl, 'public synchronized void resume()')
report('MediaPlayerController.resume 方法体已找到', resume is not None,
       '锚点：public synchronized void resume()')
if resume:
    report('resume 用 playAction 决策，而不是"有 URL 就重建"',
           'playAction(' in resume,
           '原来的 else-if 分支会把正在 prepare 的实例 release 掉重建 ——'
           'SetAVTransportURI 与 Play 连发的控制点必现「投不上去」')
    report('resume 把 preparing 真的传给了 playAction（不能恒传 false）',
           re.search(r'playAction\([^)]*preparing', resume) is not None,
           '传个常量 false 就等于把"准备中"这一格永远走成"重建" ——'
           '而守卫若只查 playAction( 存不存在，这种改法能悄悄溜过去。'
           '（这条是写完守卫后自查补上的，正是"守卫本身也会漏"的例子）')
    report('resume 里没有"有 URL 就无条件重建"的分支',
           re.search(r'currentUrl\s*!=\s*null[^}]*startInternal', resume, re.S) is None,
           '这个分支不区分"准备中"和"还没开始"，而两者的正确处理完全相反')
    report('resume 认得 PLAY_WAIT（准备中：什么都不做）',
           'PLAY_WAIT' in resume,
           'prepare 完成时本来就会 start()，这里再插一脚只会把准备中的实例掐掉')

report('MediaPlayerController 有 preparing 状态位',
       re.search(r'boolean\s+preparing', ctrl) is not None,
       '没有它就无法区分「准备中」与「还没开始」')

si = body_of(ctrl, 'private void startInternal()')
report('startInternal 方法体已找到', si is not None, '锚点：private void startInternal()')
if si:
    report('startInternal 在 prepareAsync 之前置 preparing',
           re.search(r'preparing\s*=\s*true', si) is not None,
           '不置位的话，prepare 期间的 Play 会走进"重建"分支')

prep = body_of(ctrl, 'new MediaPlayer.OnPreparedListener()')
report('OnPreparedListener 匿名类体已找到', prep is not None,
       '锚点：new MediaPlayer.OnPreparedListener()')
if prep:
    report('onPrepared 里清掉 preparing',
           re.search(r'preparing\s*=\s*false', prep) is not None,
           '不清的话播放器会永远停在"准备中"，后续 Play 全被吞掉')
    report('onPrepared 补发 prepare 期间暂存的 seek',
           'pendingSeekMs' in prep,
           '不补发的话，投屏刚起来时拖的进度条会被永久丢弃 —— 电视一动不动')

err = body_of(ctrl, 'new MediaPlayer.OnErrorListener()')
report('OnErrorListener 匿名类体已找到', err is not None,
       '锚点：new MediaPlayer.OnErrorListener()')
if err:
    report('onError 里也清掉 preparing',
           re.search(r'preparing\s*=\s*false', err) is not None,
           'prepare 失败时不复位，重连之后 Play 会被误判成"准备中"而永不生效')

# ---- ② 进度同步：seek 待决期间的三处特殊处理 ----
sk = body_of(ctrl, 'public synchronized void seekTo(int ms)')
report('MediaPlayerController.seekTo 方法体已找到', sk is not None,
       '锚点：public synchronized void seekTo(int ms)')
if sk:
    report('seekTo 在未 prepare 时暂存目标，而不是丢弃',
           'pendingSeekMs' in sk,
           '丢弃的话，手机显示已经拖过去了、电视一动不动 —— 正是"不同步"')

gp = body_of(ctrl, 'public int getPosition()')
report('MediaPlayerController.getPosition 方法体已找到', gp is not None,
       '锚点：public int getPosition()')
if gp:
    report('getPosition 在 seek 待决期间返回目标值（乐观值）',
           re.search(r'return\s+\(int\)\s*pending', gp) is not None,
           'seek 是异步的：落地前 getCurrentPosition() 还是旧值，'
           '手机轮询到旧位置会把进度条拉回去。'
           '判据必须是"真的把待决值返回出去了"——只查 pendingSeekMs 出现过是不够的，'
           '把返回值改回 raw 同样能骗过那种写法')
    report('getPosition 有"待决结束"的收敛判定',
           'isSeekSettled(' in gp and 'isSeekExpired(' in gp,
           '没有收敛判定，pendingSeekMs 会永久粘住，位置从此再不更新')

cs = body_of(ctrl, 'private void checkStall()')
report('MediaPlayerController.checkStall 方法体已找到', cs is not None,
       '锚点：private void checkStall()')
if cs:
    report('checkStall 在 seek 待决期间跳过（否则慢 seek 会被判成卡死）',
           'pendingSeekMs' in cs,
           'seek 未落地时位置本来就不动 —— 看门狗会重连，而重连把播放拉回开头')

for label, marker in [('stop()', 'public synchronized void stop()'),
                      ('releasePlayer()', 'private void releasePlayer()')]:
    b = body_of(ctrl, marker)
    report('%s 里清掉 seek 待决状态' % label,
           b is not None and re.search(r'pendingSeekMs\s*=\s*-1', b) is not None,
           '不清的话，下一次播放会拿着上一次的 seek 目标当"当前位置"报给控制点')

# ---- ③ 重投：HTTP 服务 bind 竞态不得泄漏端口 ----
run = body_of(http, 'public void run()')
report('UpnpHttpServer.run 方法体已找到', run is not None, '锚点：public void run()')
if run:
    # 判据必须落在「bind 之后真的关过一次 socket」上。
    #
    # 只查 if (!running) 是不够的 —— accept 的 catch 里本来就有一句
    # if (!running) break;，那句会让断言**恒真**，永远发现不了这里的回归。
    # （这条是证伪时发现的：把兜底整段删掉，断言居然还是绿的。）
    report('bind 之后有兜底：复查 running 并释放端口',
           re.search(r'new ServerSocket\(port\)[\s\S]*?serverSocket\.close\(\)',
                     run) is not None,
           'shutdown() 可能正好落在 bind 与赋值之间：那一刻 serverSocket 还是 null，'
           'close 被跳过，线程却把 49152 绑上了 —— 端口被永久占住，'
           '下次启动 bind 直接失败，表现为「搜得到设备但投不上去」')

ib = body_of(http, 'public boolean isBound()')
report('UpnpHttpServer.isBound() 方法体已找到', ib is not None,
       '锚点：public boolean isBound()')
if ib:
    report('isBound() 真的在查绑定状态，不是恒 true',
           'bound' in ib and 'serverSocket' in ib
           and re.search(r'return\s+true\s*;', ib) is None,
           'HTTP 服务死掉时 SSDP 可能还活着 —— 界面会显示"已就绪"，'
           '而手机搜得到设备却投不上去。isBound 恒 true 就是谎报军情本身')

sys.exit(1 if failed else 0)
PY

# ── 9. 投屏基础体验的源码级不变量 ──
# 守二夜真机实测报上来的三个现象。它们和前面几节有个共同点：
# 编译、运行、日志**全都正常**，只是功能悄悄失效。
#
#   ·「网易云音乐根本搜不到这台设备」
#     只应答 M-SEARCH、不主动广播 NOTIFY 的设备，在**被动发现**类控制点眼里
#     等于不存在。而腾讯视频 / B站 会主动搜索，所以搜得到 ——
#     于是这个 bug 极易被误判成"某个 App 的兼容性问题"。
#
#   ·「拖拽进度条 → 电视先蓝屏断开、再重连」
#     控制点拖进度条时会重发 SetAVTransportURI（同一个 URL）。play() 若不比较
#     URL 就 startInternal()，而它第一句是 releasePlayer() ——
#     播放器被释放重建：视频层关闭（蓝屏）+ 重新缓冲（"重连"观感）+ 位置归零。
#
#   ·「手机上断开连接 → 电视直接蓝屏，不回投屏之前的界面」
#     SurfaceView 从不隐藏、而 panel 是透明的 —— 停止之后那层已经没有内容的
#     surface 还压在面板底下。
python3 - "$CTRL" "$SVC" "$ACT" "$LAYOUT" "$HTTP" "$POLICY" <<'PY' || RC=1
import re, sys, pathlib
ctrl_path, svc_path, act_path, layout_path, http_path, policy_path = sys.argv[1:7]
ctrl = pathlib.Path(ctrl_path).read_text(encoding='utf-8')
svc = pathlib.Path(svc_path).read_text(encoding='utf-8')
act = pathlib.Path(act_path).read_text(encoding='utf-8')
layout = pathlib.Path(layout_path).read_text(encoding='utf-8')
http = pathlib.Path(http_path).read_text(encoding='utf-8')
policy = pathlib.Path(policy_path).read_text(encoding='utf-8')
ssdp = pathlib.Path('app/src/main/java/com/juping/cast/dlna/SsdpResponder.java'
                    ).read_text(encoding='utf-8')
failed = []

def report(name, ok, detail=''):
    print('  [%s] %s%s' % ('PASS' if ok else 'FAIL', name,
                           ('\n         ' + detail) if detail else ''))
    if not ok:
        failed.append(name)

def strip_comments(src):
    """去掉 // 与 /* */ 注释（字符串字面量原样保留）。

    不剥离的话，检查器会匹配到**注释里**的字符串 —— 而注释里恰恰最常写
    「我们为什么不用 X」，于是"不用 X"被读成"用了 X"。同第 7 / 8 节。
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c == '"' or c == "'":
            quote, j = c, i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == quote:
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)

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

ssdp_c = strip_comments(ssdp)
ctrl_c = strip_comments(ctrl)
svc_c = strip_comments(svc)
act_c = strip_comments(act)
http_c = strip_comments(http)

# ---- ① 网易云搜不到：必须有**主动**广播，而不只是应答 ----
aa = body_of(ssdp_c, 'private void announceAlive()')
report('SsdpResponder.announceAlive 方法体已找到', aa is not None,
       '锚点：private void announceAlive()')
if aa:
    # 判据落在"真的发出去了"，而不是"有个叫 announceAlive 的方法"——
    # 空方法同样能骗过后一种写法。
    report('announceAlive 真的调用了 sendNotify',
           'sendNotify(' in aa,
           '只留一个空壳方法的话，设备依然只能被动应答，网易云那类控制点照样搜不到')
    report('announceAlive 对每个 NT 都发，且重复 3 轮（对抗 UDP 丢包）',
           'announceTargets()' in aa and re.search(r'round\s*<\s*3', aa) is not None,
           'UPnP DA 1.0 §1.2.2：每个 NT 各发一次，且每条重复 3 次')

run = body_of(ssdp_c, 'public void run()')
report('SsdpResponder.run 方法体已找到', run is not None, '锚点：public void run()')
if run:
    report('加入组播成功之后才广播 alive',
           re.search(r'boundPort\s*=\s*actualPort[\s\S]*?announceAlive\(\)',
                     run) is not None,
           '必须发生在 joinGroup 成功之后 —— 早于它的话组播还没通，发出去没人收得到')
    report('有定期重播，不是只发一轮',
           'startAnnouncer()' in run,
           'UDP 会丢包、控制点缓存也会过期；只发一轮的话"在线却搜不到"会反复出现')

an = body_of(ssdp_c, 'private void startAnnouncer()')
report('SsdpResponder.startAnnouncer 方法体已找到', an is not None,
       '锚点：private void startAnnouncer()')
if an:
    report('重播线程真的在循环里调 announceAlive',
           'announceAlive()' in an,
           '只 sleep 不发广播的话，重播等于没有')

bn = body_of(ssdp_c, 'String buildNotify(String nt, String nts)')
report('SsdpResponder.buildNotify 方法体已找到', bn is not None,
       '锚点：String buildNotify(String nt, String nts)')
if bn:
    # 下面三条是 NOTIFY 与 M-SEARCH 应答的**关键差异**。写错任何一个，
    # 整条广播都会被控制点丢掉 —— 而且丢得毫无提示，日志里什么都看不到。
    report('NOTIFY 请求行写 *（不是路径）',
           'NOTIFY * HTTP/1.1' in bn,
           '规范要求目标写 *；写成路径的话控制点不认')
    # 判据必须**带引号**，落在"构造的头名"上。
    # 用裸的 'ST: ' 会误伤 —— "HOST: " 里正好含着 ST: 这个子串
    # （H-O-S-T-:-空格），于是无论代码怎么写这条都是红的。
    # 第一版就是这么写的，跑出来才发现：断言写错和代码写错一样费时间。
    report('NOTIFY 用 NT + NTS 两个头（不是 ST）',
           '"NT: "' in bn and '"NTS: "' in bn and '"ST: "' not in bn,
           '应答用 ST、广播用 NT + NTS —— 混用会让报文被直接丢弃')
    report('NOTIFY 带 HOST 头',
           'HOST: ' in bn,
           'HOST 在广播里是**必需**头（应答里反而没有）')
    report('LOCATION 只出现在 alive 分支（byebye 不带）',
           re.search(r'alive[\s\S]*?LOCATION', bn) is not None,
           '设备都要走了，报地址没有意义 —— 规范里 byebye 不带 LOCATION')

sd = body_of(ssdp_c, 'public void shutdown()')
report('SsdpResponder.shutdown 方法体已找到', sd is not None,
       '锚点：public void shutdown()')
if sd:
    report('shutdown 在关 socket 之前发 byebye',
           re.search(r'announceByeBye\(\)[\s\S]*?closeQuietly\(\)', sd) is not None,
           '顺序反了就发不出去（socket 已经关了）。不发 byebye 的话，'
           '控制点会把设备一直留在列表里直到 max-age 过期，最长 30 分钟')

# ---- ② 拖拽蓝屏：play() 必须幂等 ----
pl = body_of(ctrl_c, 'public synchronized boolean play(String url)')
report('MediaPlayerController.play 已改成返回 boolean（可幂等）', pl is not None,
       '锚点：public synchronized boolean play(String url)')
if pl:
    # 判据本体已经抽到 PlaybackPolicy.shouldRebuild（纯逻辑、桌面可断言），
    # 所以这里查的是**调用点**：真的调了它、参数传对、在 startInternal 之前拦下。
    report('play 里调用了幂等判定（PlaybackPolicy.shouldRebuild）',
           'PlaybackPolicy.shouldRebuild(' in pl,
           '不判幂等的话，控制点重发同一个地址就会被当成"换片源"')
    report('幂等判定传了地址与播放器状态两组参数',
           re.search(r'shouldRebuild\(\s*url\s*,\s*currentUrl\s*,'
                     r'\s*prepared\s*,\s*preparing\s*\)', pl) is not None,
           '只比 URL 不比状态的话，出错停掉的播放器会再也重建不起来 ——'
           '重发同地址就彻底救不回来了。四个参数缺一不可')
    report('幂等判定取反使用（判据给 false 时才拦）',
           re.search(r'if\s*\(\s*!\s*PlaybackPolicy\.shouldRebuild\(', pl) is not None,
           '去掉这个 ! 的话语义正好反了：同地址时反而重建、换片时反而忽略')
    # 位置判据：幂等判断必须发生在 startInternal() **之前**。
    # 只查"有没有调 shouldRebuild"是不够的 —— 放到 startInternal 之后的话，
    # 播放器已经重建完了才 return，等于什么都没拦。
    idem = pl.find('PlaybackPolicy.shouldRebuild(')
    start = pl.find('startInternal()')
    report('幂等判断发生在 startInternal 之前（否则拦不住）',
           idem >= 0 and start >= 0 and idem < start,
           '放到 startInternal() 之后的话，播放器已经重建完了才返回 ——'
           '蓝屏和"重连"照样发生。而只看调没调判定的断言，'
           '对这种写法完全无感')
    report('幂等分支真的 return，不是只打日志',
           re.search(r'PlaybackPolicy\.shouldRebuild\([\s\S]{0,400}?return\s+false',
                     pl) is not None,
           '只打日志不 return 等于没拦')

# 判据本体抽到了 PlaybackPolicy：**语义**由桌面断言（PolicyTest 第 10 节）逐格覆盖 ——
# 那边是纯函数，能穷举「同地址 × 播放器状态」的所有组合。
# 这里只留一条**形状**检查：方法确实在、签名没变。
# 刻意不在源码级重复语义断言 —— 同一个语义在两处各写一条，破坏性证伪时
# 一处破坏会让两条一起红，反而定位不出到底哪儿坏了。
sr = body_of(strip_comments(policy), 'public static boolean shouldRebuild(')
report('PlaybackPolicy.shouldRebuild 方法体已找到（语义由桌面断言逐格覆盖）',
       sr is not None,
       '锚点：public static boolean shouldRebuild(')

# ---- GetTransportInfo 的 CurrentTransportStatus 必须与事件里的 TransportStatus 同源 ----
# 这两处是**同一个语义**（出没出错）。之前一处如实报、一处写死 OK，
# 于是同一台设备、同一时刻，两个接口给出相反的答案 ——
# 靠轮询的控制点以为一切正常，靠事件的控制点知道在出错。
gi = body_of(http_c, 'private String responseArgs(String action)')
report('UpnpHttpServer.responseArgs 方法体已找到', gi is not None,
       '锚点：private String responseArgs(String action)')
if gi:
    report('CurrentTransportStatus 取自 handler，不是写死的 OK',
           'handler.getTransportStatus()' in gi
           and '<CurrentTransportStatus>OK</CurrentTransportStatus>' not in gi,
           '写死的话，它就和事件里的 TransportStatus 各说各话：'
           '同一台设备、同一时刻，两个接口给出相反的答案')

gts = body_of(svc_c, 'public String getTransportStatus()')
report('DlnaRendererService.getTransportStatus 方法体已找到', gts is not None,
       '锚点：public String getTransportStatus()')
if gts:
    report('getTransportStatus 用 hasTransportError 判据（不是恒 OK）',
           re.search(r'return\s+hasTransportError\(\)\s*\?', gts) is not None,
           '恒回 OK 等于没改 —— 只是把写死的位置从协议层挪到了服务层')

report('eventedVars 的 TransportStatus 与 getTransportStatus 同源',
       'vars.put("TransportStatus", getTransportStatus())' in svc_c,
       '两处各写一份判据的话，迟早有一份忘了跟着改 —— 而不一致'
       '恰恰是最难排查的一类问题：控制点自己都不知道该信哪个')

osu = body_of(svc_c, 'public void onSetUri(String uri, String metadata)')
report('DlnaRendererService.onSetUri 方法体已找到', osu is not None,
       '锚点：public void onSetUri(String uri, String metadata)')
if osu:
    report('onSetUri 用 play() 的返回值决定要不要报 TRANSITIONING',
           'restarted' in osu and 'player.play(uri)' in osu,
           '同地址重发时播放并没有被打断，却报 TRANSITIONING 的话，'
           '顶部条会闪一下、控制点还可能据此重画进度条')

# ---- ③ 停止后回面板：SurfaceView 必须藏起来 + panel 必须不透明 ----
am = body_of(act_c, 'private void applyMode(int mode)')
report('MainActivity.applyMode 方法体已找到', am is not None,
       '锚点：private void applyMode(int mode)')
if am:
    # 只留这一条强判据，不再单列「调用了 setVisibility」——
    # 后者是前者的前提，删掉那一行会让两条同时变红，而"红了多条"
    # 说明判据有重叠，反而定位不出到底哪儿坏了。
    report('SurfaceView 的可见性跟着 idle 走（空闲时藏起来）',
           re.search(r'surfaceView\.setVisibility\(idle\s*\?\s*View\.GONE',
                     am) is not None,
           '不隐藏的话，停止播放后那个已经没有内容的 Surface 还压在面板底下 ——'
           '老平台上视频层空着时输出的是**蓝色**，'
           '用户看到的就是"断开投屏后电视蓝屏"；'
           '写成恒 VISIBLE、或反过来（idle 时 VISIBLE），同样等于没修')

pm = re.search(r'<LinearLayout\s+android:id="@\+id/panel"[\s\S]{0,400}?>', layout)
report('layout 里找得到 panel 节点', pm is not None, '锚点：@+id/panel')
if pm:
    report('panel 有不透明背景（不是透明面板）',
           'android:background="@color/bg"' in pm.group(0),
           '透明面板盖不住底下的 Surface —— 面板显示出来了，'
           '用户看到的却还是那层蓝')

st = body_of(ctrl_c, 'public synchronized void stop()')
report('MediaPlayerController.stop 方法体已找到', st is not None,
       '锚点：public synchronized void stop()')
if st:
    report('stop 里显式解绑 Surface，且在 releasePlayer 之前',
           re.search(r'setSurface\(null\)[\s\S]*?releasePlayer\(\)', st) is not None,
           '先交出输出面再释放，视频层才会立刻关闭；'
           '直接 release 的话某些平台上那一层会残留一段时间')

# ---- ④ 进度同步：状态映射 + 位置兜底 + 事件带位置 ----
sc = body_of(svc_c, 'public void onStateChanged(String state)')
report('DlnaRendererService.onStateChanged 方法体已找到', sc is not None,
       '锚点：public void onStateChanged(String state)')
if sc:
    report('onStateChanged 认得 RECONNECTING',
           'RECONNECTING' in sc,
           '漏掉它的话 transportState 会停留在上一次的值（通常就是 PLAYING），'
           '而这段时间位置读不到 —— 控制点看到"正在播放但进度不动"，'
           '它的进度条就卡住了')
    report('onStateChanged 认得 ERROR',
           'ERROR' in sc,
           '出错时同样不能停留在 PLAYING，否则控制点会一直以为还在播')

ev = body_of(svc_c, 'public Map<String, String> eventedVars(String service)')
report('DlnaRendererService.eventedVars 方法体已找到', ev is not None,
       '锚点：public Map<String, String> eventedVars(String service)')
if ev:
    report('AVTransport 事件里带 RelativeTimePosition',
           'RelativeTimePosition' in ev,
           '一部分控制点（国产投屏 SDK 居多）不轮询 GetPositionInfo，'
           '只靠事件里的这个字段更新进度条 —— 不给就从头到尾不动')
    # 这里原来还有一条「TransportStatus 如实反映出错与否」（在 ev 里找
    # ERROR_OCCURRED 字面量）。判据挪进 getTransportStatus() 之后它就失效了，
    # 而且它和上面那条「同源」守的是同一个语义 —— 留着只会让一处破坏红两条，
    # 反而定位不出到底哪儿坏了。现在由「同源」+「getTransportStatus 用
    # hasTransportError 判据」两条共同覆盖。

scpd = re.search(r'SCPD_AV_TRANSPORT =[\s\S]*?</scpd>', http_c)
report('SCPD_AV_TRANSPORT 找得到', scpd is not None, '锚点：SCPD_AV_TRANSPORT =')
if scpd:
    report('SCPD 里 RelativeTimePosition 声明为可事件化',
           re.search(r'stateVar\("RelativeTimePosition",\s*"string",\s*true\)',
                     scpd.group(0)) is not None,
           'SCPD 里没声明的话，事件体里给了控制点也不会用 ——'
           '声明与实现必须成对出现')

gp = body_of(ctrl_c, 'public int getPosition()')
report('MediaPlayerController.getPosition 方法体已找到', gp is not None,
       '锚点：public int getPosition()')
if gp:
    report('未就绪时返回最后已知位置，而不是 0',
           re.search(r'!\s*prepared[\s\S]{0,120}?return\s+lastKnownPosition',
                     gp) is not None,
           '返回 0 的话，一次几百毫秒的重连就足以让控制点的进度条**跳回开头**')
    # 收敛 / 超时的收尾必须交给看门狗，不能在 getPosition 里顺手改状态 ——
    # 它跑在 HTTP 线程上，看门狗在主线程，两边同时改一个字段就是竞态。
    report('getPosition 不再自己清 pendingSeekMs（收尾交给看门狗）',
           re.search(r'isSeekSettled[\s\S]{0,400}?pendingSeekMs\s*=\s*-1',
                     gp) is None,
           'getPosition 跑在 HTTP 连接线程上，在这里清字段等于同时撤销看门狗的豁免，'
           '而豁免开关被两个线程读写只会表现为"偶发重连"')

cs = body_of(ctrl_c, 'private void checkStall()')
report('MediaPlayerController.checkStall 方法体已找到', cs is not None,
       '锚点：private void checkStall()')
if cs:
    report('seek 超时收尾时把看门狗基准对齐',
           re.search(r'isSeekExpired[\s\S]{0,500}?lastProgressAt\s*=',
                     cs) is not None,
           '不对齐的话，"seek 期间位置没动"这段静止会被下一轮检查直接算成卡死 ——'
           '于是超时兜底反而制造出一次多余的重连')

sys.exit(1 if failed else 0)
PY

# ── 10. 工具链脚本自身：变量名边界 ──
#
# `$VAR` 后面紧跟中文标点时，bash 在 **UTF-8 locale** 下会把多字节字符的字节
# 一起吞进变量名，于是 `$HTTP_PORT）` 变成 `${HTTP_PORT）}` → unbound variable。
# 而在 **C locale** 下 bash 逐字节判断、变量名恰好正确结束 —— 于是同一个脚本
# 在本地跑得好好的，换台机器（或 CI）直接崩，而且报错信息里变量名带乱码，
# 看着像文件损坏。
#
# 这不是假想：本项目 5 个脚本里有 12 处这种写法，一直没被发现，
# 就是因为开发机的 shell 恰好是 C locale。
echo
echo "── 10. 工具链脚本自身：shell 变量名边界 ──"
python3 - <<'PY' || RC=1
import pathlib, re, sys
# $VAR / $1 / $? 等紧跟一个非 ASCII 字节
PAT = re.compile(r'\$([A-Za-z_][A-Za-z0-9_]*|[0-9]|[?*@#$!])([^\x00-\x7f])')
scripts = sorted(pathlib.Path('tools').rglob('*.sh'))
bad = []
for p in scripts:
    for i, line in enumerate(p.read_text(encoding='utf-8').splitlines(), 1):
        # 跳过注释行：注释里的示例不会被执行，也就不是隐患。
        # （上面那段说明里就举了 `$HTTP_PORT）` 这个例子 —— 不跳过的话，
        #   检查器会把自己文档里的示例当成缺陷报出来。）
        if line.lstrip().startswith('#'):
            continue
        for m in PAT.finditer(line):
            bad.append((str(p), i, m.group(0)))
print('  [%s] $VAR 后紧跟中文标点时都用花括号界定（扫了 %d 个脚本）'
      % ('PASS' if not bad else 'FAIL', len(scripts)))
if bad:
    for f, i, frag in bad:
        print('        %s:%d  %r' % (f, i, frag))
    print('        bash 在 UTF-8 locale 下会把中文标点的字节吞进变量名 →'
          ' unbound variable；C locale 下逐字节判断、恰好正常，'
          '所以本地跑得好好的，换台机器就炸。改成 ${VAR} 即可。')
    sys.exit(1)
PY

echo
if [ "$RC" -ne 0 ]; then
    echo "播放策略核验未通过。" >&2
fi
exit "$RC"
