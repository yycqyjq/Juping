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

# JAVA_HOME 的选择顺序：与 protocol-test/run.sh 相同 ——
# 已有可用的就尊重（CI 上的 setup-java 会设），没有才回退到自包含工具链；
# 工具链的两种目录布局（macOS 的 Contents/Home 与 Linux 的平铺）都要认。
# 这段是照着那边改的，两处必须保持一致 —— 一处改了另一处忘了，
# 症状是「协议闸门绿、策略闸门红」，看起来像断言挂了，其实是环境问题。
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    TOOLCHAIN="${ANDROID_BUILD_HOME:-$HOME/.android-build}"
    if [ -x "$TOOLCHAIN/jdk/Contents/Home/bin/javac" ]; then
        export JAVA_HOME="$TOOLCHAIN/jdk/Contents/Home"
    elif [ -x "$TOOLCHAIN/jdk/bin/javac" ]; then
        export JAVA_HOME="$TOOLCHAIN/jdk"
    fi
fi
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
ED="app/src/main/java/com/juping/cast/dlna/EventDispatcher.java"
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
MPROXY="app/src/main/java/com/juping/cast/player/MediaProxy.java"
if [ ! -f "$ANDROID_JAR" ]; then
    echo "  跳过（找不到 ${ANDROID_JAR}）"
else
    mkdir -p "$OUT/ctrl"
    if "$JAVAC" -nowarn -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/ctrl" \
            "$POLICY" "$CTRL" "$MPROXY" 2>"$OUT/ctrl.err"; then
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
python3 - "$SVC" "$HTTP" "$ACT" <<'PY' || RC=1
import re, sys, pathlib
svc_path, http_path, act_path = sys.argv[1], sys.argv[2], sys.argv[3]
svc = pathlib.Path(svc_path).read_text(encoding='utf-8')
http = pathlib.Path(http_path).read_text(encoding='utf-8')
act_src = pathlib.Path(act_path).read_text(encoding='utf-8')
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

# 顶部条的可见性依赖错误分类，所以「什么时候清错误」也要守住。
# 只在换片源 / 停止时清的话，一次**已经自愈**的断流会让那句报错一直挂着 ——
# 画面好好的，屏幕上却压着一条故障提示。这属于"谎报军情"，和漏报一样糟。
op = body_of(svc, 'public void onPrepared(int durationMs, boolean hasVideo)')
report('DlnaRendererService.onPrepared 方法体已找到', op is not None,
       '锚点：public void onPrepared(int durationMs, boolean hasVideo)')
if op:
    # 清错误已经收敛到 clearError()（要同时清「分类」和「细节」两个字段），
    # 所以这里判的是**调用**而不是字段赋值 —— 判旧写法的话，
    # 代码重构了、语义没变，守卫也会红，那就成了"守卫拦着重构"。
    report('播放就绪时清掉陈旧的错误（调 clearError）',
           'clearError()' in op,
           '不清的话，自愈之后顶部条会一直挂着那条已经过期的报错')

su = body_of(svc, 'public void onSetUri(String uri, String metadata)')
if su:
    report('收到投屏即唤起界面到前台（杜绝后台投屏）',
           'bringPlayerToFront()' in su,
           '服务开机自启后界面可能从未打开 —— 这时投屏只有声音没有画面，'
           '电视屏幕停在桌面（Hisense 真机实测）。每次 SetAVTransportURI 都唤起，'
           '换片时界面若已被退到后台也能被带回来')

bpf = body_of(svc, 'private void bringPlayerToFront()')
if bpf:
    report('前台唤起带 NEW_TASK 且兜住异常（不打断投屏）',
           'FLAG_ACTIVITY_NEW_TASK' in bpf and 'catch' in bpf and 'MainActivity' in bpf,
           'Service 里 startActivity 必须 NEW_TASK；唤起失败只能降级后台播放，'
           '绝不能把投屏本身掀翻。MainActivity 是 singleTask，重复唤起不会堆实例')
    report('唤起时置位 autoFront（对称设计的前半段）',
           'autoFront = true' in bpf,
           '标志位是「播完退回后台」的依据 —— 只有投屏自动唤起的界面'
           '才在播完后退回，手动打开的不动')

taf = body_of(svc, 'public boolean takeAutoFrontFlag()')
report('autoFront 标志位对外可取（界面消费「播完退回」指令）',
       taf is not None and 'autoFront = false' in taf,
       '标志位必须取走即清 —— 否则界面每次刷新都重复退回后台')

act = body_of(act_src, 'private void applyModeIfChanged(int mode)')
if act:
    report('播放结束且为自动唤起的界面 → 延迟退回后台（对称设计的后半段）',
           'hasAutoFrontFlag' in act and 'autoBackTask' in act
           and 'AUTO_BACK_DELAY_MS' in act,
           '播放结束停在前台会看到待机面板；直接退后台又会撞上 MTK 蓝屏窗口 —— '
           '延迟 2.5 秒等 SurfaceView 销毁、视频层干净移除后再退，两害相权取其轻')
if 'moveTaskToBack' in act_src and 'AUTO_BACK_DELAY_MS' in act_src:
    pass
else:
    report('退后台任务存在（moveTaskToBack + 延迟取消逻辑）', False,
           'applyModeIfChanged 的延迟退回任务丢了')

# clearError 自己也要守住：它必须**同时**清两个字段。
# 只清分类不清洁细节 → hasTransportError 说没错了、日志里却还留着旧报错；
# 只清细节不清分类 → 顶部条还挂着报错。漏掉任何一个都是自相矛盾的状态。
ce = body_of(svc, 'private void clearError()')
report('DlnaRendererService.clearError 方法体已找到', ce is not None,
       '锚点：private void clearError()')
if ce:
    report('clearError 同时清错误分类',
           'lastErrorKind' in ce,
           '只清洁细节不清分类的话，顶部条会继续挂着那条已经过期的报错')
    report('clearError 同时清错误细节',
           re.search(r'lastError\s*=\s*""', ce) is not None,
           '只清分类不清洁细节的话，日志里会留着已经自愈的那条旧报错')

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
python3 - "$CTRL" "$HTTP" "$POLICY" "$SVC" <<'PY' || RC=1
import re, sys, pathlib
ctrl_path, http_path, policy_path, svc_path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
ctrl = pathlib.Path(ctrl_path).read_text(encoding='utf-8')
http = pathlib.Path(http_path).read_text(encoding='utf-8')
pol_src = pathlib.Path(policy_path).read_text(encoding='utf-8')
svc = pathlib.Path(svc_path).read_text(encoding='utf-8')
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
    report('视频判定为纯音频时安排延迟复查（老芯片尺寸晚就绪）',
           'scheduleVideoRecheck' in prep,
           'MTK 5880 实测：onPrepared 时 getVideoWidth() 仍返回 0 —— '
           '只判一次的话视频流被误判成纯音频，电视上对着视频弹「音乐投屏」卡片')

vr = body_of(ctrl, 'private void scheduleVideoRecheck(final MediaPlayer mp, final int attempt)')
if vr:
    report('视频复查校验播放器实例与就绪状态（防过期复查串台）',
           'player != mp' in vr and 'prepared' in vr and 'hasVideo' in vr,
           '复查排队期间可能已重连/换片/停止 —— 不校验的话，过期复查会把'
           '上一个片源的判定套到新片源上')
    report('视频复查翻案后重新回调 onPrepared（而不是只改字段）',
           'listener.onPrepared' in vr,
           '只改字段的话界面（轮询刷新）要等下一拍才知道，'
           '重新回调让"纯音频→视频"的形态切换立刻发生')
    report('视频复查次数有上限（到顶认命，纯音频判成音乐卡片本来就是对的）',
           'VIDEO_RECHECK_MAX_ATTEMPTS' in vr,
           '无上限的话，一条真的没有视频的音频流会让复查永远空转')

# ---- ③ 本地预取代理：把富余带宽兑换成「数据已在本地」 ----
pol = strip_comments(pol_src)
report('代理开关默认关（厂商栈黑盒，根因清楚前保直连）',
       'PROXY_ENABLED = false' in pol,
       '海信 CmpbPlayer 真机实测：代理连接即被断开 + 降级直连也挂起，'
       '两个症状未定根因前默认关，机制与测试保留，开开关即可继续迭代')
report('http 流经本地预取代理（m3u8 直连）',
       'proxy.localize' in ctrl,
       '海信真机实测：起播/Seek 后的卡顿是老播放器自己取数保守，网络是闲的。'
       '经 127.0.0.1 预取代理，解码器永远读本地；m3u8 有自己的分片逻辑必须 bypass')
report('代理路径 prepare 有超时降级（不兼容厂商栈不死等）',
       'checkProxyFallback' in ctrl and 'PREPARE_PROXY_TIMEOUT_MS' in pol,
       'CmpbPlayer 实测：连接后立即断开，prepare 永远不完成 —— '
       '超时降级直连，不让投屏永远卡在 TRANSITIONING')
report('代理缓冲容量来自策略常量（0.6GB 盒子的内存纪律）',
       'PROXY_BUFFER_BYTES' in ctrl,
       '环形缓冲绝不落盘、绝不超限 —— 容量来自纯逻辑层的常量，方便标定')
report('Stop 时清代理缓冲（空闲不占 8MB）',
       'proxy.reset()' in ctrl,
       '断流重连特意保留缓冲，但 Stop 是控制点明确结束 —— 空闲时不能占着')
report('m3u8 不走代理',
       '.m3u8' in ctrl and 'proxyable' in ctrl,
       'HLS 的分片逻辑在播放器内部，按字节寻址的代理对它没意义')

# ---- ④ 播放列表（SetNextAVTransportURI）—— BubbleUPnP 歌单连播依赖它 ----
report('SetNextAVTransportURI 已在 KNOWN_ACTIONS（SCPD 同步）',
       'SetNextAVTransportURI' in http,
       'SCPD 里没有的 action 控制点不会发 —— 歌单连播整个失效')
report('自动续播复用 Set 路径（URI/元数据/事件全部对齐）',
       'nextUrl' in ctrl and 'listener.onSourceChanged' in ctrl,
       '自动续播不走 SOAP，服务层的 URI/元数据状态靠 onSourceChanged 对齐 —— '
       'GetMediaInfo 回读与事件推送都依赖它')
report('Stop/换片清下一曲队列（DLNA 语义）',
       'nextUrl = null' in ctrl,
       '队列不跨 Stop 存活；新 SetAVTransportURI 也作废旧队列')

# ---- ⑤ Auto-Stop：控制点离开后自动停止（借鉴 gmrender --auto-stop）----
report('Auto-Stop 判据：播放中 + 无订阅者 + 曾有订阅者 + 指令超时',
       'checkAutoStop' in svc and 'aliveSubscriberCount' in svc
       and 'lastSubscribeAt' in svc and 'AUTO_STOP_AFTER_MS' in pol,
       '手机退出了电视还在播 —— gmrender 用订阅者数做判据（比指令超时稳，'
       '不会误杀不发轮询的控制点）')
report('Auto-Stop 挂在服务自检看门狗里',
       'checkAutoStop();' in svc,
       '服务已有 30 秒自检节拍，复用它而不是另起线程')
report('/status 诊断页（浏览器可达，排障不需要 adb）',
       '/status' in http and 'buildStatusJson' in http and 'buildStatusJson' in svc,
       'gmrender 生态的 upnp-display 用小屏显示状态 —— 我们的「显示屏」'
       '就是手机浏览器')

ct = body_of(ctrl, 'private void scheduleContentTypeProbe(final MediaPlayer mp, final String url)')
if ct:
    report('Content-Type 探测只有 video/* 才翻案成视频',
           ct.find('startsWith("video/")') >= 0
           and 'video/' in ct and 'return' in ct,
           'audio/* 必须维持音乐卡片 —— 把音频误判成视频，用户对着黑屏'
           '以为投屏坏了，比卡片盖住视频更糟（宁漏勿错）')
    report('Content-Type 探测回调前校验实例与状态',
           'player != mp' in ct and 'prepared' in ct and 'hasVideo' in ct,
           '探测走独立线程，排队期间可能已重连/换片/停止 —— '
           '不校验的话过期回调会把上一个片源的判定套到新片源上')
pc = body_of(ctrl, 'private static String probeContentType(String url)')
if pc:
    report('Content-Type 探测有超时（不无限等）',
           'setConnectTimeout(3000)' in pc and 'setReadTimeout(3000)' in pc,
           '探测线程是拿片源连通性的试金石：服务器不回就该放弃，'
           '挂着等只会白养一个线程')

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
    report('seekTo 把水位线对齐到目标（防回拖误外推到片尾）',
           'hiRaw = ms' in sk,
           '往回拖之后旧的高水位线还在 —— 外推会从旧水位线出发把进度'
           '直接推到片尾。Seek 时必须把水位线对齐到目标')
if sk:
    report('seekTo 在未 prepare 时暂存目标，而不是丢弃',
           'pendingSeekMs' in sk,
           '丢弃的话，手机显示已经拖过去了、电视一动不动 —— 正是"不同步"')

gp = body_of(ctrl, 'public int getPosition()')
report('MediaPlayerController.getPosition 方法体已找到', gp is not None,
       '锚点：public int getPosition()')
if gp:
    report('位置冻结时按墙钟外推（修 MTK Seek 后媒体时钟停摆）',
           'POSITION_FREEZE_EXTRAPOLATE_MS' in gp and 'isActivelyPlaying' in gp,
           '海信 MTK 4.0.4 实测：Seek 后 getCurrentPosition() 永远停在 '
           'Seek 点而画面继续播 —— 原样上报的话手机进度条冻死在 Seek 位置')
    report('外推结果钳到时长（不越过片尾）',
           'est > dur' in gp,
           '外推是估出来的：越过时长的进度条会把「还剩多少」算成负数')
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
#
# 后面又补了两组同样「编译运行都正常、只是悄悄坏掉」的：
#
#   ·「开机后怎么都搜不到设备，重启一下 App 就好了」
#     服务是开机自启的，而开机广播到达时 Wi-Fi 往往还没连上 ——
#     那一刻候选网卡为空，原来的 run() 直接 return，SSDP 线程永久结束。
#     而 startService() 对已在跑的服务不会再触发 onCreate，手动打开 App 也救不回来。
#     现在改成带退避的重试，并把「失败原因」写进日志。
#
#   ·「搜得到设备，点进去却投不了屏」
#     组播绑的是哪张网卡、LOCATION 里写哪个 IP，原来是两次独立选择 ——
#     第一张候选网卡 joinGroup 失败时会分叉。现在 LOCATION 由实际绑定的
#     那张网卡算出，两条链路不可能再不一致。
python3 - "$CTRL" "$SVC" "$ACT" "$LAYOUT" "$HTTP" "$POLICY" "$ED" <<'PY' || RC=1
import re, sys, pathlib
ctrl_path, svc_path, act_path, layout_path, http_path, policy_path, ed_path = sys.argv[1:8]
ctrl = pathlib.Path(ctrl_path).read_text(encoding='utf-8')
svc = pathlib.Path(svc_path).read_text(encoding='utf-8')
act = pathlib.Path(act_path).read_text(encoding='utf-8')
layout = pathlib.Path(layout_path).read_text(encoding='utf-8')
http = pathlib.Path(http_path).read_text(encoding='utf-8')
policy = pathlib.Path(policy_path).read_text(encoding='utf-8')
ed = pathlib.Path(ed_path).read_text(encoding='utf-8')
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
ed_c = strip_comments(ed)

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
           re.search(r'bindUntilReady\(\)[\s\S]*?announceAlive\(\)', run) is not None,
           '必须发生在 joinGroup 成功之后 —— 早于它的话组播还没通，发出去没人收得到')
    report('有定期重播，不是只发一轮',
           'startAnnouncer()' in run,
           'UDP 会丢包、控制点缓存也会过期；只发一轮的话"在线却搜不到"会反复出现')
    report('run 里不再自己做网卡选择与「放弃」判断',
           re.search(r'pickInterfaces\(\)|isEmpty\(\)', run) is None,
           '在 run() 里直接 return 就是"永久失效"本身：这个服务是开机自启的，'
           '开机广播到达时 Wi-Fi 往往还没连上，那一刻候选网卡是空的。'
           '而 startService() 对已在跑的服务不会再触发 onCreate ——'
           '用户后来手动打开 App 也救不回来，只能重启服务。'
           '网卡选择和放弃判断都必须交给 bindUntilReady() / tryBindOnce()')

# ---- 绑定失败必须带退避重试 ----
bur = body_of(ssdp_c, 'private boolean bindUntilReady()')
report('SsdpResponder.bindUntilReady 方法体已找到', bur is not None,
       '锚点：private boolean bindUntilReady()')
if bur:
    report('bindUntilReady 是循环重试，不是失败一次就返回',
           re.search(r'while\s*\(\s*running\s*\)', bur) is not None
           and 'tryBindOnce()' in bur,
           '没有循环的话，"开机时 Wi-Fi 还没连上"就变成永久失败')
    report('重试之间有退避等待（不是忙等）',
           'Thread.sleep(' in bur,
           '不退避的话失败会变成每秒几千次的忙循环，把 0.6GB 的老盒子 CPU 吃满')
    report('退避有上限（不会越等越久到不可接受）',
           re.search(r'Math\.min\([\s\S]{0,60}?RETRY_MAX_MS', bur) is not None,
           '不封顶的话，失败几次之后下一次重试要等好几分钟 ——'
           '网络早就好了，用户却还是搜不到。'
           '注意判据必须是 Math.min(..., RETRY_MAX_MS) 这个**封顶动作**本身：'
           '只查"方法体里出现过 RETRY_MAX_MS"的话，日志节流那一行也含这个常量，'
           '把封顶去掉它照样绿 —— 这条断言就是这么被证伪抓出来的')
    report('重试日志有节流',
           'RETRY_LOG_EVERY' in bur,
           '没网时挂一整夜就是几千条失败日志，会把真正有用的日志冲掉；'
           '而排障恰恰要靠那些日志')

tbo = body_of(ssdp_c, 'private String tryBindOnce()')
report('SsdpResponder.tryBindOnce 方法体已找到', tbo is not None,
       '锚点：private String tryBindOnce()')
if tbo:
    report('失败路径会关掉自己建的 socket（不泄漏 fd）',
           re.search(r'reason\s*!=\s*null[\s\S]{0,200}?closeSocket\(', tbo) is not None,
           '不关的话每次重试漏一个 fd，几十次之后就是 Too many open files ——'
           '而那时报错的是**别的**模块，根本联想不到 SSDP 在重试')
    report('boundPort 在 location 之后赋值（绑上 ⇒ 地址已可用）',
           re.search(r'location\s*=[\s\S]{0,600}?boundPort\s*=\s*actualPort',
                     tbo) is not None,
           'isBound() 判的就是 boundPort。先赋它会出现'
           '「isBound() 已经是 true、LOCATION 还是 null」的窗口，'
           '而调用方（界面、协议测试）拿到 isBound() 就会立刻去读地址')
    report('LOCATION 由实际绑定的网卡算出（不是外部传进来的 IP）',
           re.search(r'NetUtil\.pickIpv4\(\s*bound\s*\)', tbo) is not None,
           '用外部传进来的 IP 就等于把「组播绑哪张网卡」和「告诉手机去哪取描述」'
           '拆成两次独立选择 —— 第一张网卡 joinGroup 失败时两者会分叉，'
           '表现为「搜得到设备却投不了屏」')
    report('构造函数收的是 HTTP 端口，不是拼好的 LOCATION 字符串',
           'public SsdpResponder(String uuid, int httpPort, String serverName, '
           'String versionName)' in ssdp_c.replace('\n', ' ')
           and 'LOCATION' not in ssdp_c[:ssdp_c.find('public SsdpResponder')],
           'LOCATION 里的 IP 必须等组播真的绑上某张网卡之后才知道。'
           '从外面传进来，就等于让「绑哪张网卡」和「告诉手机去哪取描述」各自算一次')

# ---- 主动广播间隔必须不超过 max-age 的一半（UPnP DA 1.0 §1.2.2）----
m_cache = re.search(r'CACHE_MAX_AGE_SEC\s*=\s*(\d+)L', ssdp_c)
m_ann = re.search(r'ANNOUNCE_INTERVAL_SEC\s*=\s*(\d+)L', ssdp_c)
report('ANNOUNCE_INTERVAL_SEC * 2 <= CACHE_MAX_AGE_SEC',
       bool(m_cache and m_ann) and int(m_ann.group(1)) * 2 <= int(m_cache.group(1)),
       '间隔超过 max-age/2 的话，控制点会在两次广播之间把设备判为过期 ——'
       '表现为「设备明明在线却从列表里消失」。'
       '读到的是 max-age=%s、interval=%s'
       % (m_cache.group(1) if m_cache else '?', m_ann.group(1) if m_ann else '?'))

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
    report('shutdown 会叫醒绑定重试的退避 sleep',
           re.search(r'closeQuietly\(\)[\s\S]{0,500}?this\.interrupt\(\)', sd) is not None,
           '绑定重试最多睡 30 秒，不叫醒的话服务都销毁了、线程还挂着不退出')
    report('shutdown 会叫醒重播线程（不白等一整个间隔）',
           re.search(r'announcer[\s\S]{0,300}?t\.interrupt\(\)', sd) is not None,
           '重播间隔是 120 秒。不持有线程引用、不 interrupt 的话，'
           '服务销毁后这个线程还要挂着睡满一轮才退出')

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

# ---- ⑤ 三处「无上限」的防御 ----
# 0.6GB 内存上，任何一处没上限都能让整个进程消失（连带 SSDP 一起）。
# 行为由协议测试第 12 节端到端覆盖（真发畸形请求去打），这里守的是**形状**：
# 常量在不在、检查的位置对不对、计数配不配对。
hc = body_of(http_c, 'private void handleConnection(Socket socket)')
report('UpnpHttpServer.handleConnection 方法体已找到', hc is not None,
       '锚点：private void handleConnection(Socket socket)')
if hc:
    # 顺序就是这一条的全部内容。先分配再检查的话，检查那一句自己就跑不到了 ——
    # 因为 OOM 已经发生在上一条语句。
    alloc = hc.find('new byte[contentLength]')
    guard = hc.find('MAX_BODY_BYTES')
    report('Content-Length 的上限检查发生在 new byte[] 之前',
           alloc >= 0 and guard >= 0 and guard < alloc,
           'Content-Length 完全由控制点决定：声明 2GB 就当场 OutOfMemoryError。'
           '先 new 再检查，等于检查永远执行不到')
    report('handleConnection 结束时把连接计数还回去',
           re.search(r'finally[\s\S]{0,200}?activeConnections\.decrementAndGet\(\)',
                     hc) is not None,
           '漏掉的话计数只增不减，十几次之后 HTTP 层再也不接连接 ——'
           '表现为「搜得到设备却投不了屏」，而且只有重启服务才能恢复')

hcr = body_of(http_c, 'public void run()')
report('UpnpHttpServer.run 方法体已找到', hcr is not None, '锚点：public void run()')
if hcr:
    report('accept 之后先查并发上限，再开线程',
           re.search(r'activeConnections\.get\(\)\s*>=\s*MAX_CONNECTIONS[\s\S]{0,400}?'
                     r'activeConnections\.incrementAndGet\(\)', hcr) is not None,
           '每个连接一个线程、默认栈 1MB。无上限的话一个端口扫描就能把内存吃光，'
           '连累整个进程 —— 而进程一死，SSDP 也一起没了')
    report('线程没起来时把计数还回去',
           re.search(r'if\s*\(\s*!\s*started\s*\)[\s\S]{0,150}?'
                     r'activeConnections\.decrementAndGet\(\)', hcr) is not None,
           '不还的话，几次 Thread 创建失败之后计数永远顶在上限，'
           'HTTP 层从此一个连接都不接')

sub_b = body_of(ed_c, 'public String subscribe(String service, String callbackHeader, '
                      'String timeoutHeader)')
report('EventDispatcher.subscribe 方法体已找到', sub_b is not None,
       '锚点：public String subscribe(String service, String callbackHeader, String timeoutHeader)')
if sub_b:
    report('订阅表到顶时淘汰最旧的一条',
           re.search(r'subs\.size\(\)\s*>=\s*MAX_SUBS[\s\S]{0,400}?subs\.remove\(',
                     sub_b) is not None,
           '不设上限的话，异常控制点（或有人拿脚本刷）能把这表撑到 OOM ——'
           '0.6GB 的盒子上撑爆的是整个进程')

# ---- ⑥ 服务销毁时的收尾 ----
# 前台通知不撤的话会变成一条僵尸通知：投屏早就断了，通知栏里却还挂着，
# 点一下还会去拉起一个已经死掉的服务。
od = body_of(svc_c, 'public void onDestroy()')
report('DlnaRendererService.onDestroy 方法体已找到', od is not None,
       '锚点：public void onDestroy()')
if od:
    # 只留一条：**顺序**里已经含了「有没有撤通知」这件事。
    # 拆成两条的话，删掉 stopForeground 那一行会让两条同时红 ——
    # 而"红了多条"说明判据重叠，反而定位不出到底哪儿坏了。同第 ② 节的做法。
    od_stop = od.find('stopForeground(')
    teardowns = [x for x in (od.find('ssdp.shutdown()'), od.find('httpServer.shutdown()'),
                             od.find('player.release()')) if x >= 0]
    od_first_teardown = min(teardowns) if teardowns else -1
    report('onDestroy 先撤前台通知，再拆服务',
           od_stop >= 0 and 0 <= od_stop < od_first_teardown,
           '不撤通知的话，通知栏会留一条僵尸通知：投屏早就断了，'
           '却还写着"正在投屏"，点一下还会去拉起一个已经死掉的服务。'
           '撤得太晚同样不行 —— 后面关 socket / 释放播放器都可能抛异常，'
           '抛在中间这条通知就永远撤不掉了')

# ---- ⑦ 界面显示的地址，必须就是控制点拿到的那个 ----
# 界面上的地址是排障时唯一能照着去 curl 的东西。它要是来自"候选列表第一张网卡"，
# 而 LOCATION 来自 joinGroup 真正成功的那张，两者一分叉：
# 照着界面地址怎么都复现不了用户的问题 —— 而且看起来还像"盒子没问题"。
# 这条只能钉源码：它是"哪个值流到界面"的问题，行为层看不见。
gli = body_of(svc_c, 'public String getLocalIp()')
report('DlnaRendererService.getLocalIp 方法体已找到', gli is not None,
       '锚点：public String getLocalIp()')
if gli:
    gli_bound = gli.find('getBoundIp()')
    gli_fallback = gli.rfind('return localIp')
    report('界面取地址优先用实际绑定的网卡，兜底才退回猜测值',
           gli_bound >= 0 and gli_bound < gli_fallback,
           '只返回 localIp 的话，joinGroup 换到第二张网卡（第一张没有 IPv4、'
           '或是隧道接口）时，界面显示的 IP 和 SSDP 告诉手机的 LOCATION 就不是同一个。'
           '判据是**顺序**：先 getBoundIp()，兜底才 return localIp ——'
           '反过来的话（先 return localIp）这段代码就永远走不到后面那句')

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

# ── 11. 照成熟 DMR 补齐的源码级不变量 ──
# 这一节守的是一批「编译、运行、日志全都正常，只是功能悄悄失效」的回归。
# 它们有个共同点：**只有严格的控制点会受影响**，而严格的控制点不会报错 ——
# 它只是静默地把这台设备划掉、或者悄悄把某个功能当成不支持。
# 所以本地怎么点都点不出来，只能钉在源码上。
#
#   · 设备描述缺 dlna:X_DLNADOC → 部分控制点根本不把设备列进投屏列表
#   · SCPD 的 relatedStateVariable 悬空 → 严格校验的控制点整份解析失败
#   · 静音只认 "1"/"true"（漏了规范允许的 "yes"）→ 按静音声音**反而回来了**
#   · M-SEARCH 不按 MX 随机延迟 → 多设备同网时 UDP 碰撞，"有时搜得到有时搜不到"
#   · SSDP 线程死了不复位绑定状态 → 界面说"已就绪"，实际一个搜索都收不到
#   · 播放器音量绕过 applyVolume 单独下发 → 设了静音、改一下音量又有声音了
echo
echo "── 11. 照成熟 DMR 补齐的源码级不变量 ──"
python3 - "$HTTP" "$CTRL" "$SVC" <<'PY' || RC=1
import re, sys, pathlib

http_path, ctrl_path, svc_path = sys.argv[1], sys.argv[2], sys.argv[3]
http = pathlib.Path(http_path).read_text(encoding='utf-8')
ctrl = pathlib.Path(ctrl_path).read_text(encoding='utf-8')
svc = pathlib.Path(svc_path).read_text(encoding='utf-8')
ssdp = pathlib.Path('app/src/main/java/com/juping/cast/dlna/SsdpResponder.java'
                    ).read_text(encoding='utf-8')
def strip_comments(src):
    """去掉 // 与 /* */ 注释（字符串字面量原样保留）。

    **必须剥。** 不剥的话守卫会把注释也算进判据 —— 于是"代码删了、
    说明注释还留着"照样绿。F13 那条就是这么被证伪抓出来的：
    去掉 startInternal 里的 setWakeMode 调用之后守卫仍然 PASS，
    因为紧挨着的注释里同时写着 setWakeMode 和 PARTIAL_WAKE_LOCK。
    一个只看注释就能通过的守卫，等于没有。
    """
    out = []
    i, n = 0, len(src)
    state = 'code'
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if state == 'code':
            if c == '/' and nxt == '/':
                state = 'line'
                i += 2
                continue
            if c == '/' and nxt == '*':
                state = 'block'
                i += 2
                continue
            if c == '"':
                state = 'str'
            out.append(c)
            i += 1
        elif state == 'line':
            if c == '\n':
                state = 'code'
                out.append(c)
            i += 1
        elif state == 'block':
            if c == '*' and nxt == '/':
                state = 'code'
                i += 2
                continue
            if c == '\n':
                out.append(c)          # 保留换行，别把行结构搅乱
            i += 1
        else:                          # 字符串字面量
            if c == '\\':
                out.append(c)
                if nxt:
                    out.append(nxt)
                i += 2
                continue
            if c == '"':
                state = 'code'
            out.append(c)
            i += 1
    return ''.join(out)


def action_args(src):
    """把源码里每个 action("...") 调用点的参数抠出来（按顶层逗号切）。

    只看 `action("` —— 方法定义 `action(String name, ...)` 因此被排除在外。
    """
    out = []
    for m in re.finditer(r'\baction\("', src):
        i = m.end() - 1              # 停在开头的那个引号上
        depth, j = 1, i + 1
        while j < len(src) and depth > 0:
            if src[j] == '(':
                depth += 1
            elif src[j] == ')':
                depth -= 1
            j += 1
        call = src[i:j - 1]
        parts, buf, d, in_str, k = [], '', 0, False, 0
        while k < len(call):
            ch = call[k]
            if in_str:
                if ch == '\\':
                    buf += call[k:k + 2]
                    k += 2
                    continue
                if ch == '"':
                    in_str = False
                buf += ch
            else:
                if ch == '"':
                    in_str = True
                    buf += ch
                elif ch in '([':
                    d += 1
                    buf += ch
                elif ch in ')]':
                    d -= 1
                    buf += ch
                elif ch == ',' and d == 0:
                    parts.append(buf.strip())
                    buf = ''
                else:
                    buf += ch
            k += 1
        if buf.strip():
            parts.append(buf.strip())
        out.append(parts)
    return out


http = strip_comments(http)
ctrl = strip_comments(ctrl)
svc = strip_comments(svc)
ssdp = strip_comments(ssdp)
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


# ---- (1) 设备描述：DLNA 类别标记 ----
bd = body_of(http, 'private String buildDeviceDescription()')
report('buildDeviceDescription 方法体已找到', bd is not None,
       '锚点：private String buildDeviceDescription()')
if bd:
    report('设备描述里有 dlna:X_DLNADOC 且值为 DMR-1.50',
           'X_DLNADOC' in bd
           and re.search(r'DLNA_DOC\s*=\s*"DMR-1\.50"', http) is not None,
           '判据要看**实际拼进 XML 的那个值**：值在 DLNA_DOC 常量里、'
           '元素在方法体里，两边都要查（只查方法体会假阴性 —— '
           '这条守卫第一版就是这么写错的，拿真源码一试才发现）。\n'
           '         缺了它，部分控制点（较新的国产投屏 SDK 尤其）根本不把'
           '设备列进投屏列表 —— 而 SSDP 那边看起来一切正常，'
           '最容易被当成"手机的问题"')
    report('设备描述刻意不声明 presentationURL',
           '<presentationURL>' not in bd,
           '我们没有任何 Web 界面。写 "/" 只会把 device.xml 本身喂给浏览器 ——'
           '不声明时控制点就不画那个按钮')

# ---- (2) 图标：声明了就必须给得出 ----
ail = body_of(http, 'private void appendIconList(StringBuilder sb)')
report('appendIconList 方法体已找到', ail is not None,
       '锚点：private void appendIconList(StringBuilder sb)')
if ail:
    report('没有图标时完全不声明 iconList（不留一个取不到的 URL）',
           'iconPng == null' in ail and 'return' in ail,
           '声明了控制点就会真的去 GET。404 在它的日志里就是一条'
           '"设备描述与实现不一致" —— 给不出就别声明')
si = body_of(http, 'public void setIcon(byte[] png, int width, int height)')
report('setIcon 方法体已找到', si is not None,
       '锚点：public void setIcon(byte[] png, int width, int height)')
if si:
    report('setIcon 对非法参数直接忽略（图标不能拖垮设备描述）',
           'png == null' in si and 'return' in si,
           '图标是纯装饰。参数不合法时若照收，device.xml 里就会出现一个'
           '宽高为 0 或取不到的 iconList')
hg = body_of(http, 'private StaticResource resolveStatic(String path)')
if hg:
    report('图标走二进制出口，不经过 String',
           'writeBinary' not in hg and 'iconPng' in hg and 'ICON_PATH' in hg,
           'PNG 用 UTF-8 编一遍再解回来会被替换字符毁掉（0x80~0xFF 里大量字节'
           '不是合法 UTF-8 序列），控制点收到的就是一张坏图。'
           '图标在 resolveStatic 里以 byte[] 原样返回，写入时不再过 String')
ws = body_of(http, 'private void writeStatic(OutputStream out, StaticResource r, boolean withBody)')
if ws:
    report('静态资源写出时 body 字节原样透传',
           "r.payload.length > 0" in ws and 'out.write(r.payload)' in ws
           and 'payload.getBytes' not in ws,
           'GET/HEAD 共用这一个出口。body 若在这里再过一次 String 编解码，'
           '图标的二进制字节就被毁掉 —— 头部走 UTF-8 没问题，body 不行')

# ---- (3) SCPD 的 relatedStateVariable 不能由参数名推出来 ----
report('SCPD 的 relatedStateVariable 不再拿参数名当变量名',
       '.append(args[i]).append("</relatedStateVariable>")' not in http,
       '参数名与变量名经常不一样（CurrentVolume → Volume、'
       'InstanceID → A_ARG_TYPE_InstanceID）。按参数名推就会生成悬空引用，'
       '严格校验 SCPD 的控制点会**整份解析失败** —— 不是少一个功能，'
       '是这台设备在它眼里不存在')
report('SCPD 参数一律写成 方向:参数名:状态变量 三段式',
       'p.length != 3' in http and 'IllegalArgumentException' in http,
       '少写一段会当场抛异常（而不是静默生成一份畸形 SCPD）。'
       '静态初始化失败声音很大，但好过只在部分控制点上表现为"设备是灰的"')
# 上一条只证明"校验存在"，不证明"模板里的参数都合规"。这一条逐个调用点查：
# 参数必须是字符串字面量、且恰好两个冒号（方向:参数名:状态变量）。
# 少了第三个字段，action() 里那句 p[2] 就会越界 —— 而它跑在**静态初始化**里，
# 结果是 UpnpHttpServer 类加载失败、整个服务起不来。
_bad_args = []
for _parts in action_args(http):
    for _a in _parts[1:]:
        if not (_a.startswith('"') and _a.endswith('"')) or _a.count(':') != 2:
            _bad_args.append(_a)
report('SCPD 每条 action 参数都是 方向:参数名:状态变量 三段式（逐个调用点查）',
       not _bad_args,
       '可疑参数: %s\n         （必须逐个调用点查，只查 action() 里有没有校验是不够的 ——'
       '模板里少写一段照样会越界）' % _bad_args[:6])

# ---- (4) 静音：布尔解析与回读 ----
pb = body_of(http, 'private static boolean parseBoolean(String s)')
report('parseBoolean 方法体已找到', pb is not None,
       '锚点：private static boolean parseBoolean(String s)')
if pb:
    report('parseBoolean 认规范允许的 yes / no',
           '"yes"' in pb.lower() and 'equalsIgnoreCase' in pb,
           '规范里 boolean 有六个合法取值。只认 1/true 的话，控制点发 "yes" '
           '会被解析成"取消静音" —— 用户按静音，声音**反而回来了**，'
           '方向反了比不支持更糟')
ra = body_of(http, 'private String responseArgs(String action)')
if ra:
    report('GetMute 回读真实状态，不写死常量',
           '<CurrentMute>0</CurrentMute>' not in ra and 'handler.getMute()' in ra,
           '写死的话，控制点按完静音回读一次看到"没静音"，会把开关又画回去 ——'
           '和已经修过的 GetVolume 恒回 100 是同一个 bug')
report('SINK_PROTOCOL_INFO 不声明 image/*',
       'image/' not in http.split('SINK_PROTOCOL_INFO =')[1].split(';')[0],
       '实现里根本没有图片这条路（kindOf 只认 audioItem / videoItem）。'
       '这份清单是控制点判断"能不能推给我"的**唯一依据**，声明了却做不到，'
       '控制点会把图片推过来然后必然失败')

# ---- (5) SSDP：M-SEARCH 的 MX 随机延迟 ----
hm = body_of(ssdp, 'private void handleMessage(String msg, DatagramPacket packet)')
report('SsdpResponder.handleMessage 方法体已找到', hm is not None,
       '锚点：private void handleMessage(String msg, DatagramPacket packet)')
if hm:
    report('M-SEARCH 走延迟调度，不在收包线程里直接回',
           'scheduleResponse' in hm and 'randomDelayMs' in hm
           and 'sendResponse(' not in hm,
           '规范要求 0~MX 秒随机延迟应答（把多设备/多目标的应答在时间上错开，'
           '减少 UDP 碰撞）。直接连发的话，几台设备同网时表现是'
           '「有时搜得到、有时搜不到」；而在收包线程里 sleep 会丢掉这一秒内的'
           '其它搜索 —— 两个都不行')
rd = body_of(ssdp, 'static long randomDelayMs(String mx)')
report('randomDelayMs 方法体已找到', rd is not None,
       '锚点：static long randomDelayMs(String mx)')
if rd:
    report('randomDelayMs 按 MX 夹上限（畸形报文不会把应答排到一天之后）',
           'MAX_MX_SEC' in rd and 'RANDOM.nextInt' in rd,
           'MX=99999 会让应答排在 27 小时之后 —— 那和不支持没有区别，'
           '还白占一个待发位置')
sr = body_of(ssdp, 'private void scheduleResponse(')
report('scheduleResponse 方法体已找到', sr is not None,
       '锚点：private void scheduleResponse(')
if sr:
    report('待发应答有上限，且计数在 finally 里还回去',
           'MAX_PENDING_REPLIES' in sr and 'finally' in sr
           and 'decrementAndGet' in sr,
           '无上限 = 内存泄漏（0.6GB 的盒子上一个异常控制点就能撑爆）。'
           '而计数漏还的话，几次之后它会永远顶在上限，'
           '从此所有搜索都被判成"队列满"而丢弃 —— 表现为「设备突然搜不到了」')

# ---- (6) SSDP：线程退出必须复位绑定状态 ----
cq = body_of(ssdp, 'private void closeQuietly()')
report('SsdpResponder.closeQuietly 方法体已找到', cq is not None,
       '锚点：private void closeQuietly()')
if cq:
    report('closeQuietly 把「已绑定」的状态一并复位',
           'boundPort = -1' in cq and 'boundInterface = null' in cq,
           '只 close 不复位的话，线程因任何原因退出之后 isBound() 仍返回 true ——'
           '界面显示"设备已就绪"，实际一个搜索请求都收不到。'
           '用户唯一能做的是重启盒子，而重启之后"看起来"又好了，'
           '于是永远定位不到')

# ---- (7) 服务侧：自检与销毁顺序 ----
ct = body_of(svc, 'private void checkThreadsAlive()')
report('DlnaRendererService.checkThreadsAlive 方法体已找到', ct is not None,
       '锚点：private void checkThreadsAlive()')
if ct:
    report('自检要求「线程已死」**且**「未绑定」才重建',
           '!s.isAlive()' in ct and '!s.isBound()' in ct
           and '!h.isAlive()' in ct and '!h.isBound()' in ct,
           '只看 isAlive() 会打断"开机 Wi-Fi 未就绪、正在退避重试"这个正常状态 ——'
           '变成每 30 秒重启一次、永远等不到网。只看 isBound() 同理')
od = body_of(svc, 'public void onDestroy()')
report('DlnaRendererService.onDestroy 方法体已找到', od is not None,
       '锚点：public void onDestroy()')
if od:
    i_flag = od.find('shuttingDown = true')
    i_ssdp = od.find('ssdp.shutdown()')
    report('onDestroy 先关掉自检，再拆 SSDP',
           i_flag >= 0 and i_ssdp >= 0 and i_flag < i_ssdp,
           '反过来的话，看门狗可能正好在"刚 shutdown、还没设标志"的窗口里醒来，'
           '看到线程死了就把它重新拉起来 —— 服务都销毁了 SSDP 还活着占着端口，'
           '下次启动直接 EADDRINUSE')
    report('onDestroy 摘掉看门狗的回调',
           'removeCallbacks' in od,
           '不摘的话，服务销毁后它还会被主线程队列捞起来跑一次，'
           '那时候字段全是空的')

# ---- (8) 播放器：唤醒锁与单一音量出口 ----
si2 = body_of(ctrl, 'private void startInternal()')
report('MediaPlayerController.startInternal 方法体已找到', si2 is not None,
       '锚点：private void startInternal()')
if si2:
    report('startInternal 里申请了 PARTIAL_WAKE_LOCK',
           'setWakeMode' in si2 and 'PARTIAL_WAKE_LOCK' in si2,
           '只靠 setScreenOnWhilePlaying(true) 是不够的：那一句**只对设了 Surface '
           '的视频有效**。音乐投屏没有 Surface，屏幕不亮、CPU 也不被钉住 ——'
           '用户关了屏音乐就会卡住甚至断流')
av = body_of(ctrl, 'private void applyVolume()')
report('MediaPlayerController.applyVolume 方法体已找到', av is not None,
       '锚点：private void applyVolume()')
if av:
    # 只数"真的带了参数"的调用：注释里那句 {@code player.setVolume()} 是空括号，
    # 用 `player\.setVolume\([^)]` 才不会被它算进去（这正是"守卫自己也会误报"
    # 的一个例子 —— 写完必须拿真源码试一遍）。
    n_setvol = len(re.findall(r'player\.setVolume\([^)]', ctrl))
    report('全项目只有 applyVolume 一处调 player.setVolume',
           n_setvol == 1 and 'player.setVolume(' in av,
           '实际出现 %d 次。任何一处绕过它单独下发，都会漏掉静音 ——'
           '于是出现「设了静音、改一下音量就又有声音了」这类只在特定顺序下'
           '复现的 bug' % n_setvol)
sv2 = body_of(ctrl, 'public void setMute(boolean mute)')
report('MediaPlayerController.setMute 先记状态再下发',
       sv2 is not None and 'this.muted = mute' in sv2 and 'applyVolume' in sv2,
       '重连会重建 MediaPlayer 实例。不先记的话，一次断流就把用户的静音取消了')

# ---- (9) 网络变化监听 ----
# LOCATION 是「绑上网卡那一刻生成一次」的，网络一变它就是旧 IP。
# 没有监听的话，Wi-Fi 断一下就得重启 App —— 而看门狗判据是
# 「线程已死且未绑定」，线程活得好好的它就看不见这个变化。
rcw = body_of(svc, 'private void registerConnectivityWatch()')
report('registerConnectivityWatch 方法体已找到', rcw is not None,
       '锚点：private void registerConnectivityWatch()')
if rcw:
    report('网络监听是动态注册且挂在 CONNECTIVITY_ACTION 上',
           'registerReceiver' in rcw and 'CONNECTIVITY_ACTION' in rcw,
           '静态注册从 Android 7.0 起收不到 CONNECTIVITY_ACTION，动态注册才是长期有效的写法')
    report('网络监听的注册包在 try 里',
           'try' in rcw,
           '注册不上只是少一层保险（看门狗和退避重试还在），绝不能因此让服务起不来')

anc = body_of(svc, 'private void applyNetworkChange()')
report('applyNetworkChange 方法体已找到', anc is not None,
       '锚点：private void applyNetworkChange()')
if anc:
    i_lock = anc.find('refreshLocks()')
    i_rs = anc.find('restartSsdp(')
    i_rh = anc.find('restartHttp(')
    report('网络重建的顺序：先刷锁、再重建链路',
           0 <= i_lock < i_rs < i_rh,
           '锁是「能收到组播包」的前提，SSDP 一绑上就开始收包 —— '
           '顺序反了重建出来的 socket 会有一段收不到包的空窗')
    report('网络重建有 shuttingDown 守卫',
           'shuttingDown' in anc,
           '服务销毁后排着的防抖任务还会被主线程队列捞起来跑一次，'
           '不能对着一堆已清空的字段动手')

rh_m = body_of(svc, 'private void restartHttp(String reason)')
report('restartHttp 方法体已找到', rh_m is not None,
       '锚点：private void restartHttp(String reason)')
if rh_m:
    i_icon = rh_m.find('provideDeviceIcon()')
    i_start = rh_m.find('httpServer.start()')
    report('HTTP 重建后重新给图标（且在 start 之前）',
           0 <= i_icon < i_start,
           '新实例的 iconPng 是空的 —— 不重给的话，网络变化之后控制点就再也拿不到图标')

od2 = body_of(svc, 'public void onDestroy()')
if od2:
    report('onDestroy 摘掉网络重建的防抖任务并注销监听',
           'removeCallbacks(rebuildOnNetworkChange)' in od2
           and 'unregisterReceiver' in od2,
           '不注销的话系统一直持有 Receiver，而它内部持有 Service 实例 —— '
           '0.6GB 的盒子上这就是一个永远回收不掉的 Service')

# ---- (9b) 设备改名：先落盘再拉服务，重建走既有路径 ----
rr_raw = pathlib.Path('app/src/main/java/com/juping/cast/RenameReceiver.java'
                      ).read_text(encoding='utf-8')
rr = strip_comments(rr_raw)
ar = body_of(svc, 'private void applyRename()')
report('改名先落盘、再拉服务（顺序不能反）',
       rr is not None and 'putString' in rr
       and rr.find('putString') < rr.find('startService'),
       '顺序反了的话，服务 onCreate 读到的还是旧名字 —— 广播等于白发。'
       '服务被拉起后才读到新名，用户看到的还是「改名失败」')
report('改名清洗：剔除控制字符、限长（exported 指令面的入口护栏）',
       rr is not None and '0x20' in rr and 'MAX_LEN' in rr and 'escapeXml' not in rr,
       'exported 意味着任何应用都能发这条广播。device.xml 侧另有 escapeXml '
       '兜底，但入口处就该拒掉明显非法的输入；在这里转义反而会把 & 弄成两层转义')
report('改名重建走 restartHttp/restartSsdp（不另写一份重建）',
       ar is not None and 'restartHttp("设备改名")' in ar
       and 'restartSsdp("设备改名")' in ar and 'new UpnpHttpServer' not in ar,
       '网络自愈那边已经处理好「先关旧的再开新的 / 图标重给 / SSDP 重播 alive」'
       '—— 同一段重建逻辑写两份，迟早有一处忘了改')

# ---- (10) 媒体元数据：原样回读 + 正确转义 ----
report('GetMediaInfo 回读元数据且做了转义',
       'getCurrentMetadata()' in http and 'escapeXml(meta)' in http,
       '恒回空会被依赖回读确认的控制点判成「设备没接收成功」，画面留在手机上不投了；'
       '不转义的话元数据里的 < 会把整条 SOAP 响应变成非法 XML')

didl = pathlib.Path('app/src/main/java/com/juping/cast/dlna/DidlLite.java'
                    ).read_text(encoding='utf-8')
didl = strip_comments(didl)
report('DidlLite 元素匹配认「无前缀」写法',
       didl.count('\\\\w+:)?') >= 2,
       '同一个字段，控制点可能写 dc:title / upnp:title，也可能不带前缀 —— '
       '只认一种的话换个手机就悄悄解析不出来，界面上退回文件名还不报错。'
       '（判据里四个反斜杠 = Java 源码里的 \\w，别数错层级 —— 这条自己就红过一次）')
report('DidlLite 反转义用一次扫描（quoteReplacement）',
       'quoteReplacement' in didl and 'ENTITY' in didl,
       '歌名里的 $ 或 \\ 不转义的话 appendReplacement 会把它当成组引用抛异常；'
       '每个实体只处理一次也天然避开 &amp;lt; 的二次替换')

# 注意：这个脚本里的 $CTRL 是 MediaPlayerController，不是 MainActivity ——
# MainActivity（$ACT）没有传进这个 heredoc，所以像上面的 ssdp / didl 一样
# 按路径硬编码加载。变量名语义（CTRL ≠ 界面）就是这个脚本自己的坑。
main_act = pathlib.Path('app/src/main/java/com/juping/cast/MainActivity.java'
                         ).read_text(encoding='utf-8')
main_act = strip_comments(main_act)
report('界面片源显示收敛到 currentLabel 单一出口',
       'private String currentLabel()' in main_act
       and main_act.count('currentLabel()') >= 4
       and 'getCurrentTitle' in main_act,
       '「标题优先、取不到回退文件名」这个判据写三份的话，迟早有一处忘了跟着改 —— '
       '界面上同一个片源在不同位置显示成不同的东西')

sys.exit(1 if failed else 0)
PY

echo
if [ "$RC" -ne 0 ]; then
    echo "播放策略核验未通过。" >&2
fi
exit "$RC"
