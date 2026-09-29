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

echo
if [ "$RC" -ne 0 ]; then
    echo "播放策略核验未通过。" >&2
fi
exit "$RC"
