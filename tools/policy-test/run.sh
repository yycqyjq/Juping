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

# JDK 的定位收敛到 tools/lib.sh（原先在 build.sh + 三个 run.sh 里各有一份，
# 那段注释自己都写着「两处必须保持一致」—— 收敛掉这个隐患）。顺序见该文件。
. "$HERE/../lib.sh"
resolve_java_home
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
QR="app/src/main/java/com/juping/cast/QrRenderer.java"

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
python3 - "$LAYOUT" "$COLORS" "$ACT" "$QR" <<'PY' || RC=1
import re, sys, pathlib
import xml.etree.ElementTree as ET

layout_path, colors_path, act_path, qr_path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
layout_src = pathlib.Path(layout_path).read_text(encoding='utf-8')
colors_src = pathlib.Path(colors_path).read_text(encoding='utf-8')
act_src = pathlib.Path(act_path).read_text(encoding='utf-8')
qr_src = pathlib.Path(qr_path).read_text(encoding='utf-8') if pathlib.Path(qr_path).exists() else ''
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
qr_src = strip_comments(qr_src)

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

# ⑲ 音乐层字号层级（批 3.6）：最大最粗的那一行必须是**歌名**，不是静态装饰。
#
# 守的是一个已经发生过的真实 bug：原来音乐层里最大的是写死的「音乐投屏」
# （34sp 粗体），而真正的歌名（music_source）被挤在 17sp —— 字号层级整个排反。
# 这种回归编译、运行、日志全都正常，只是用户最想看的信息又变回最小那行。
report('布局里有封面 @+id/music_cover', '@+id/music_cover' in by_id,
       '封面控件；取不到封面时它继续显示 ic_music 图标（不留白块）')
report('布局里有歌手行 @+id/music_artist', '@+id/music_artist' in by_id,
       '歌手行；取不到歌手时整行 GONE（不显示空行）')
report('布局里有歌词行 @+id/music_lyrics', '@+id/music_lyrics' in by_id,
       '歌词行；DLNA 无标准歌词字段，控制点不送就整行 GONE')

_cover = by_id.get('@+id/music_cover')
report('封面 ImageView 有默认 src（取不到封面不留白块）',
       _cover is not None and bool(_cover.get(AND + 'src')),
       '实际：%r —— 没有兜底图，取不到封面时那一块就是空白，'
       '在深色卡片上看着像界面坏了'
       % (_cover.get(AND + 'src') if _cover is not None else None))

def _tsize(el):
    v = el.get(AND + 'textSize')
    if not v:
        return None
    m = re.match(r'([0-9.]+)sp$', v)
    return float(m.group(1)) if m else None

_music = by_id.get('@+id/music')
if _music is not None:
    _texts = [(el, _tsize(el)) for el in _music.iter()
              if el.tag == 'TextView' and _tsize(el) is not None]
    _src = by_id.get('@+id/music_source')
    _src_size = _tsize(_src) if _src is not None else None
    report('歌名字号 ≥ 音乐层内所有其它 TextView',
           _src_size is not None and all(sz <= _src_size for _, sz in _texts),
           '歌名 %s，其余 %s —— 排反了的话，用户最想看的信息又变成最小那行'
           % (_src_size, [sz for _, sz in _texts]))
    report('写死的「音乐投屏」大字已移除',
           not any(el.get(AND + 'text') == '@string/music_title' and (sz or 0) >= 30
                   for el, sz in _texts),
           '静态大标题永远不变，既重复又抢走歌名的视觉权重')

# ⑳ 封面拉取的源码级不变量（批 3.6）：封面是**唯一新增的网络 + 解码路径**，
# 与图片投屏同一套纪律，少一条就会在 0.6GB 的盒子上卡 UI / OOM / 刷屏重试。
_lc = body_of(act_src, 'private void loadCover(String uri)')
report('loadCover 方法体已找到', _lc is not None,
       '锚点：private void loadCover(String uri)')
if _lc:
    report('封面下载解码在后台线程（不占主线程）',
           'new Thread' in _lc,
           '封面地址是控制点给的任意 URL，要真的联网取。放主线程会抛 '
           'NetworkOnMainThreadException，或直接卡住界面')
report('封面有三字段缓存（coverUri / coverLoadingUri / coverFailedUri）',
       all(k in act_src for k in ('coverUri', 'coverLoadingUri', 'coverFailedUri')),
       '少了缓存键就会每 tick 重下；少了失败记录就会对坏地址刷屏式重试'
       '（图片层已踩过：15 秒 30 次请求）')
_sc = body_of(act_src, 'private void showCover(String uri, Bitmap bmp)')
report('showCover 换图时回收旧位图',
       _sc is not None and 'recycle()' in _sc,
       'Bitmap 占的是 native 内存，GC 看不见它。换歌不 recycle，'
       '在 0.6GB 的盒子上几首就能把内存耗光')

# ㉑ 封面解码目标必须按 View 尺寸，不能复用照片那个 1600（批 3.6 收尾）。
#
# 守的是一条真实的内存回归：封面只显示在 220dp 方框里，拿 1600px 去解一张
# 3000×3000 的封面会解出约 9–10MB（4 倍过采样），在 0.6GB 的设备上是纯浪费。
# 项目里已有正确答案（qrSizePx 的同一条原则），封面必须遵循同一条。
_cs = body_of(act_src, 'private int coverSizePx()')
report('coverSizePx 方法体已找到', _cs is not None,
       '锚点：private int coverSizePx()')
if _cs:
    report('coverSizePx 按 View 尺寸取（拿不到退回下限）',
           'getLayoutParams()' in _cs and 'COVER_SIZE_FALLBACK_PX' in _cs,
           '必须读 musicCover 的实际尺寸（照 qrSizePx 的写法），拿不到再退回下限 —— '
           '不能写死一个"够大"的数')
_ld = body_of(act_src, 'private void loadCover(String uri)')
report('封面解码用 View 尺寸作目标（不复用照片的 1600）',
       _ld is not None and 'coverSizePx()' in _ld,
       'loadCover 必须把 coverSizePx() 的结果传给封面解码；'
       '传 1600 就是 4 倍过采样，3000×3000 封面解出约 9–10MB')

# ㉒ 封面拉取改「流式落盘 + 从文件两遍解码」（批 3.7）：真机实测同一个控制点送的
# 封面从 27KB 到 5.5MB 不等 —— 5.5MB 整份进内存是 OOM 入口，而旧的 4MB 上限又把
# 那张直接拒了（回落图标，用户看到「没封面」）。改成先落盘再解码，约束从「内存」
# 变成「磁盘/时间」；解码从文件两遍，失败按类别打日志（原来几乎完全静默）。
_dcv = body_of(act_src, 'private Bitmap decodeCoverScaled(String uri, long maxBytes, int targetPx)')
report('decodeCoverScaled 方法体已找到', _dcv is not None,
       '锚点：private Bitmap decodeCoverScaled(String uri, long maxBytes, int targetPx)')
if _dcv:
    report('封面下载流式落临时文件（不整份进内存）',
           'createTempFile' in _dcv and 'getCacheDir' in _dcv,
           '封面地址是控制点给的任意 URL，真机实测最大 5.5MB —— 整份读进 byte[] 在 '
           '0.6GB 的盒子上就是 OOM 入口。改成边下边落盘，内存占用与文件大小脱钩')
    report('封面临时文件在 finally 里删（成功/失败/超限/中断都删）',
           'finally' in _dcv and 'delete()' in _dcv,
           '临时文件不删会随换歌次数累积，把盒子缓存目录塞满')
    report('封面从文件两遍解码（inJustDecodeBounds + inSampleSize + decodeFile）',
           'inJustDecodeBounds' in _dcv and 'inSampleSize' in _dcv and 'decodeFile' in _dcv,
           '与照片同一条纪律：先量尺寸再降采样；从文件解，不把整份数据留在内存')
    report('封面失败有分类日志（带 URL）',
           'Log.w' in _dcv and 'uri' in _dcv,
           '封面失败原来几乎完全静默（超限那条直接 return null，连日志都没有）—— '
           '真机排障只能靠 logcat，静默失败等于没法查')

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

# ⑱ 二维码（批 2）：电视端唯一的"入口"，错了用户就进不来
# 这几条守的都是「静默回归」：编译过、运行不报错、日志干净，
# 只是电视上那块二维码扫不出来 —— 而"扫不出来"没有人会报 bug，
# 用户只会默默放弃、回去用数据线。
report('布局里有二维码整块 @+id/qr_box', '@+id/qr_box' in by_id, str(order))
report('布局里有二维码位图 @+id/qr_image', '@+id/qr_image' in by_id, '')

# 底色必须纯白。二维码解码靠黑白对比，画在深色卡片上对比度直接没了 ——
# 而"灰底 / 半透明底"在电视上看起来"也就是暗一点"，肉眼根本不会怀疑。
qr_bg = by_id['@+id/qr_image'].get(AND + 'background') if '@+id/qr_image' in by_id else None
report('二维码有背景色', bool(qr_bg), '实际：%r' % qr_bg)
if qr_bg:
    m = re.match(r'@color/(\w+)$', qr_bg)
    c = re.search(r'<color name="%s">\s*#([0-9A-Fa-f]{6,8})\s*</color>' % m.group(1), colors_src) if m else None
    report('二维码底色是纯白（黑块要靠它才有对比度）',
           c is not None and c.group(1).upper() in ('FFFFFFFF', 'FFFFFF'),
           '实际：%s = #%s —— 非纯白（尤其带 alpha）会让扫码成功率骤降，'
           '而电视上看起来只是"暗了一点点"' % (qr_bg, c.group(1) if c else '?'))

uq = body_of(act_src, 'private void updateQr(String url)')
report('updateQr(String) 方法体已找到', uq is not None,
       '锚点：private void updateQr(String url)')
if uq:
    # 0.6GB 内存的铁律：二维码是每 tick 都会路过的地方，地址没变就不能重画。
    # 少了这个短路，每秒一张 196dp 的 ARGB_8888 位图，电视很快就卡。
    report('地址没变就不重画（按 URL 缓存）',
           'equals(qrPayload)' in uq,
           '二维码每 tick 都会被路过。不按地址短路，'
           '每秒重新生成一张全屏位图 —— 0.6GB 的机器撑不住')
    report('换图时回收旧位图（不留给 GC 猜）',
           'recycle()' in uq,
           '电视内存是稀缺资源。不回收旧位图，反复换地址会把内存喂满')
    report('生成失败时整块藏起来（不留空白方块）',
           'setVisibility(' in uq and 'GONE' in uq,
           '生成不出来时若仍显示一块空白方块，用户会一直对着它扫 —— '
           '藏起来至少提示"这条路暂时不通"')

report('MainActivity 在 idle 时把地址喂给二维码',
       'updateQr(' in act_src and 'getLocalIp()' in act_src,
       '二维码地址必须和服务报出来的本机地址同源（与 device.xml 的 '
       'presentationURL 是同一对来源），否则"扫码打开的"和"控制点打开的"会分叉')

# ⑱-b 二维码右列（2026-10-02 二夜拍板）：码单独占右侧通高一列，带使用说明。
#    守的是「改回卡片内」的回归 —— 那种改法编译过、运行无报错，只是面板被
#    一张 196dp 的码撑满屏、说明文字没了，而没人会为"布局丑"报 bug。
qr_el = by_id.get('@+id/qr_box')
report('二维码在右侧独立列（qr_box 高度通高）',
       qr_el is not None and qr_el.get(AND + 'layout_height') == 'match_parent',
       '实际：%r —— 包在引导卡片里（wrap_content）会把整块撑高顶满屏，'
       '这是 2026-10-02 重构的起因' % (qr_el.get(AND + 'layout_height') if qr_el is not None else 'qr_box 不存在'))
report('二维码列自带使用说明（上标题 + 操作步骤）',
       '@string/qr_top' in layout_src and '@string/qr_body' in layout_src,
       '远看要知道"这是给手机扫的"，走近要看步骤 —— '
       '光秃一个码没人知道往哪扫、扫了能干嘛')
report('使用说明文案真实（同 Wi-Fi + 两条路径都提）',
       (lambda s: bool(re.search(r'<string name="qr_top">[^<]*扫码[^<]*</string>', s))
                   and bool(re.search(r'<string name="qr_body">[\s\S]*?同一个 Wi-Fi[\s\S]*?投屏功能[\s\S]*?</string>', s)))(
           pathlib.Path('app/src/main/res/values/strings.xml').read_text(encoding='utf-8')),
       '文案要与实际能力一致：扫码传文件、手机自带投屏选设备名，两条路都存在才写')

# ⑱-c 设备基础信息四格（2026-10-02 二夜要求：面板多列安卓/处理器/内存/存储）。
#    守的是两类静默失败：① 布局里删了格子或 MainActivity 忘了绑定 → 值永远空白，
#    编译过、运行不报错；② 内存那格改用 MemoryInfo.totalMem —— **API16 才有**，
#    这台 4.0.4（API15）一点开就 NoSuchFieldError 崩掉整个 Activity，而 minSdk 14
#    的 lint 只会提示、拦不住。
_info_ids = ['@+id/info_system', '@+id/info_cpu', '@+id/info_mem', '@+id/info_storage']
report('设备信息卡有基础信息四格（系统/处理器/内存/存储）',
       all(i in by_id for i in _info_ids),
       '缺：%s —— 少一格面板就是空白行，没人会为"少个数字"报 bug，'
       '但排障时它偏偏是最想一眼看到的东西'
       % ([i for i in _info_ids if i not in by_id]))
report('MainActivity 把四格都绑上并喂了值',
       all(('R.id.' + i[5:]) in act_src for i in _info_ids)
       and 'fillStaticDeviceFacts()' in act_src
       and 'fillDynamicDeviceFacts()' in act_src,
       '绑了不填 / 填了没绑，格子都永远是空的 —— 这类问题编译器不吭声')
_act_code = '\n'.join(l.split('//')[0] for l in act_src.splitlines())   # 去掉行内注释再匹配
report('内存总量走 /proc/meminfo，不用 MemoryInfo.totalMem（API16）',
       'totalMem' not in _act_code and '/proc/meminfo' in act_src,
       'totalMem 是 API16 字段，本机 API15 调用即 NoSuchFieldError 直接崩界面；'
       '项目纪律又是零反射（没有绕法），/proc/meminfo 是唯一正路 —— '
       '注释里可以提这个字段名，代码里用就红')
report('读不到的容量显示「—」而不是 0（老设备 statvfs 返回 0）',
       'fmtBytes(' in act_src and 'if (n <= 0)' in act_src,
       '把 0 报成「0 GB」等于谎报盘满了 —— 与 LocalStore.usableBytes 同一条纪律')

# 图片层（批 3）：MediaPlayer 解不了静态图，所以图片有自己的一层。
# 这几条守的是「照片投上去，电视全黑」——布局把这一层漏了 / 藏反了，
# 代码全都照常编译运行，只是照片永远显示不出来。
report('布局里有图片层 @+id/image', '@+id/image' in by_id,
       '图片走的是 BitmapFactory + ImageView 这条独立通道，'
       '少了这一层，解码出来的位图无处可画 —— 屏幕全黑')
if '@+id/image' in by_id:
    _iv = by_id['@+id/image']
    report('图片层默认隐藏（不可见时不留黑块盖住面板）',
           _iv.get(AND + 'visibility') == 'gone',
           '实际：%r —— 默认可见的话，待机面板会被这一整层黑底压住' % _iv.get(AND + 'visibility'))
    report('图片层有不透明背景（挡住下面那层视频 Surface 的蓝底）',
           bool(_iv.get(AND + 'background')),
           '实际：%r —— 照片常带透明通道（PNG），没有底色透明区会露出'
           '视频层的蓝色，一片花' % _iv.get(AND + 'background'))

# QrRenderer：Android 上没有 java.awt / ImageIO，位图必须逐格自己画。
report('QrRenderer.java 存在', bool(qr_src), '锚点文件：%s' % qr_path)
if qr_src:
    report('QrRenderer 逐格读取模块（getModule）',
           'getModule(' in qr_src,
           '必须一个模块一个模块地取黑/白 —— 这是替代上游 toImage() 的核心，'
           '少了它只能画出一个空方块')
    report('QrRenderer 不依赖 java.awt / ImageIO（Android 上没有）',
           'java.awt' not in qr_src and 'imageio' not in qr_src.lower()
           and 'BufferedImage' not in qr_src,
           '上游 toImage()/toSvgString() 靠 java.awt + javax.imageio，'
           'Android 运行时里根本没有这两个包 —— 在桌面上编得过，装到电视上就崩')
    report('QrRenderer 一次成图（用 setPixels，不逐点 setPixel）',
           'setPixels(' in qr_src and 'setPixel(' not in qr_src,
           '逐个 setPixel 在 0.6GB 的设备上慢得肉眼可见。攒成一整个 int[] '
           '再一次性灌进去，是这台机器上唯一可接受的写法')

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

# ---- 图片投屏（批 3）：静态图走的是**另一条通道**，四个环节缺一不可 ----
#
# 这一节守的是「投一张照片，电视全黑」这个现象。成因不是某个函数写错，
# 而是**通道选错了**：MediaPlayer 解不了静态图，喂进去只会立刻报错。
# 所以整条链必须是：①认出是图片 → ②不交给 MediaPlayer → ③界面用
# BitmapFactory 出画面 → ④大图先降采样（不然 48MB 的位图直接 OOM）。
# 这四步每一步都能"编译过、运行不报错"，只是照片显示不出来 —— 静默回归。
#
# act_src 在这个块里**没剥注释**（前面的锚点用的是原文本），而 MainActivity
# 的注释里恰好就出现了 inSampleSize / BitmapFactory 这些词 —— 不剥的话，
# 一个只看注释就能通过的守卫，等于没有。所以这里单独取一份剥过的。
act_code = strip_comments(act_src)

report('服务定义 KIND_IMAGE（图片是第四类内容，不并进视频）',
       'KIND_IMAGE' in svc,
       '并进 KIND_VIDEO 的话，界面会走视频通道去等一个永远不会来的视频帧 —— '
       '结果就是一块黑屏')

ko = body_of(svc, 'private static int kindOf(String metadata)')
report('kindOf 方法体已找到', ko is not None,
       '锚点：private static int kindOf(String metadata)')
if ko:
    report('kindOf 认 object.item.imageItem',
           'imageItem' in ko,
           '相册 / 文件管理器推照片时带的 upnp:class 就是 '
           'object.item.imageItem.photo。不认它，照片会落到"未知"，'
           '再被当成视频处理')

df = body_of(svc, 'private static String didlFor(File file)')
report('didlFor 方法体已找到', df is not None,
       '锚点：private static String didlFor(File file)')
if df:
    report('didlFor 把图片判成 object.item.imageItem.photo',
           'imageItem' in df,
           '上传页投的是本地文件，走的就是 didlFor 拼元数据这条路。'
           '少了图片这一档，照片会被拼成 videoItem，kindOf 再把它判成视频 —— '
           '又是黑屏')

report('isImageName 按扩展名认图片（含 jpg 与 png）',
       'isImageName' in svc and re.search(r'"jpg"', svc) is not None
       and re.search(r'"png"', svc) is not None,
       '扩展名是 didlFor 分类的唯一依据；漏掉 jpg/png 等于最常见的照片都不认')

su2 = body_of(svc, 'public void onSetUri(String uri, String metadata)')
if su2:
    report('图片态不走 MediaPlayer（有 KIND_IMAGE 分支且停掉了播放器）',
           'KIND_IMAGE' in su2 and 'player.stop()' in su2,
           'MediaPlayer 解不了静态图，喂进去只会立刻报 '
           'error(1, -2147483648)；不停掉上一个片源，照片还会盖在'
           '上一部片子的画面上')
    report('图片态的传输状态报 PLAYING（不是 STOPPED）',
           re.search(r'transportState\s*=\s*"PLAYING"', su2) is not None,
           '对控制点来说"一张图正在展示"就是 PLAYING。报 STOPPED 会让'
           '手机上的界面显示成"已停止"，用户以为投屏失败')

ii = body_of(svc, 'public boolean isImage()')
report('服务对外暴露 isImage()（界面据此选通道，不自己猜）',
       ii is not None and 'KIND_IMAGE' in ii,
       '判据的权威来源必须在服务里 —— 界面自己猜的话，'
       '服务说音频、界面说图片，两边就打架')

report('界面有 MODE_IMAGE（第四种形态）',
       'MODE_IMAGE' in act_code,
       '没有这一态，图片只能落进"视频"或"音频"，两种显示都是错的')

cm = body_of(act_code, 'private int currentMode()')
report('currentMode 方法体已找到（去注释后）', cm is not None,
       '锚点：private int currentMode()')
if cm:
    report('currentMode 先判 isImage（图片优先于音频/视频）',
           'isImage()' in cm,
           '图片不是音频，落到那个二选一里只会被判成"视频" —— 又是黑屏')
    # ㉔ 换歌不闪面板：UI 侧宽限（批 3.7）。网易云换歌 = 先 Stop 再 SetAVTransportURI
    # （相隔 ~230ms），而 IDLE 的刷新间隔是 1500ms —— 230ms 的瞬态被放大成 1.5s 的
    # 待机面板。宽限只对音频（视频宽限会把「闪面板」换成「闪蓝屏」）。
    report('currentMode 有宽限期（GRACE_MS）',
           'GRACE_MS' in cm,
           '没有宽限，换歌时 Stop→Set 的 230ms 窗口一旦落拍就闪面板（真机实测 1–2s）')
    report('宽限只作用于 MODE_AUDIO（不推广到视频）',
           'lastPlayingMode == MODE_AUDIO' in cm,
           '视频态宽限会把「闪面板」换成「闪蓝屏」：player.stop() 后视频层无内容，'
           '老 MTK 输出一屏蓝，宽限会把蓝屏多留 1.5s')
    report('宽限有会终结的时间判据（lastPlayingAtMs 与 GRACE_MS 比较）',
           'lastPlayingAtMs' in cm and 'GRACE_MS' in cm,
           '宽限必须靠时间比较终结 —— 写成「只要 lastPlayingMode != IDLE 就维持」'
           '会永不回 IDLE（用户按停止后卡在音乐卡片）')

# 冻结：宽限期内 refresh 不更新音乐字段（只加宽限不冻结 = 从「闪面板」变「闪空卡片」）
_rfz = body_of(act_code, 'private void refresh()')
report('refresh 的音频更新块受 staleHeld 冻结',
       _rfz is not None and 'staleHeld' in _rfz,
       '只加宽限不冻结，卡片会拿服务里已被清空的字段重绘成空白 —— '
       '仍是一次可见的闪，只是从「闪面板」变成「闪空卡片」')

# 遥控器返回键 = 真结束，不吃宽限（用户主动停止必须立即回面板）
_kd = body_of(act_code, 'public boolean onKeyDown(int keyCode, KeyEvent event)')
report('onKeyDown 置 userInitiatedStop（遥控器返回不吃宽限）',
       _kd is not None and 'userInitiatedStop' in _kd,
       '遥控器返回 = 用户明确要结束，不该等 1.5s；不置位就会被宽限拖住')

am2 = body_of(act_code, 'private void applyMode(int mode)')
report('applyMode 方法体已找到（去注释后）', am2 is not None,
       '锚点：private void applyMode(int mode)')
if am2:
    report('applyMode 里图片层按形态显隐',
           'imageView.setVisibility(' in am2 and 'MODE_IMAGE' in am2,
           '图片层不跟着形态显隐的话，放视频时它也压在最上面'
           '（或反过来，照片被藏起来）—— 两层同时可见时表面看"正常"，'
           '实际在互相盖')
    report('applyMode 里图片态隐藏 SurfaceView',
           re.search(r'boolean\s+image\s*=\s*\(mode\s*==\s*MODE_IMAGE\)', am2) is not None
           and re.search(r'surfaceView\.setVisibility\([^;]*idle\s*\|\|\s*image', am2)
           is not None,
           '图片不经过视频层，那个 Surface 上什么都没有 —— 老 MTK 平台会'
           '露出一屏蓝底，把刚画上去的照片盖住（和 idle 态是同一个坑）')

# ⚠️ 这条锚点**跟着 decodeScaled 的签名走**，已经被重定向过两次了：
#     (String uri) → (String uri, long maxBytes) → (String uri, long maxBytes, int targetPx)。
# 为什么必须钉签名：`body_of` 用 `src.find(marker)` 找方法，**marker 是子串匹配** ——
# 一旦有人给 decodeScaled 加参数（比如这次加 targetPx），旧锚点就找不到（`...maxBytes)`
# 不再出现），守卫会红；更阴的是：若保留一个同名前缀的重载（委派给真实现），
# find 可能锚到那个空壳方法体上，守卫**静默失效**。所以改签名必须同步改这里。
ds = body_of(act_code, 'private Bitmap decodeScaled(String uri, long maxBytes, int targetPx)')
report('decodeScaled 方法体已找到（去注释后）', ds is not None,
       '锚点：private Bitmap decodeScaled(String uri, long maxBytes, int targetPx)')
if ds:
    report('图片解码先量尺寸再降采样（inJustDecodeBounds + inSampleSize）',
           'inJustDecodeBounds' in ds and 'inSampleSize' in ds,
           '手机随手一张照片 4000×3000，直接解码要 48MB —— 这台盒子必然 OOM，'
           '崩的是整个应用。必须先只读尺寸、算出 inSampleSize 再真解码')

li = body_of(act_code, 'private void loadImage(String uri)')
report('loadImage 方法体已找到（去注释后）', li is not None,
       '锚点：private void loadImage(String uri)')
if li:
    report('图片下载解码在后台线程（不占主线程）',
           'new Thread' in li,
           '地址是 http://…/media/<名字>，要真的联网取。放主线程上会抛 '
           'NetworkOnMainThreadException；即使不抛，界面也会卡住')

si = body_of(act_code, 'private void showImage(String uri, Bitmap bmp)')
report('showImage 换图时回收旧位图',
       si is not None and 'recycle()' in si,
       'Bitmap 占的是 native 内存，GC 看不见它。换图不 recycle，'
       '在 0.6GB 的盒子上几张照片就能把内存耗光')

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
# 判据必须钉在 KNOWN_ACTIONS 数组**本身**，而不是整个文件：这个字符串在
# dispatch 分支和 SCPD 声明里也出现，只查整个文件的话，从数组里删掉它
# 守卫照样绿（破坏性证伪实测：删数组元素 → 红 0 条）。body_of 从数组声明处
# 按大括号配对抠出初始化体，正好只覆盖那一段。
ka = body_of(http, 'private static final String[] KNOWN_ACTIONS =')
report('KNOWN_ACTIONS 数组已找到', ka is not None,
       '锚点：private static final String[] KNOWN_ACTIONS =')
report('SetNextAVTransportURI 已在 KNOWN_ACTIONS（SCPD 同步）',
       ka is not None and 'SetNextAVTransportURI' in ka,
       'SCPD 里没有的 action 控制点不会发 —— 歌单连播整个失效')
report('自动续播复用 Set 路径（URI/元数据/事件全部对齐）',
       'nextUrl' in ctrl and 'listener.onSourceChanged' in ctrl,
       '自动续播不走 SOAP，服务层的 URI/元数据状态靠 onSourceChanged 对齐 —— '
       'GetMediaInfo 回读与事件推送都依赖它')
# 判据必须钉在 stop() 的**方法体**里，而不是整个文件：nextUrl = null 在文件里
# 共 3 处（play / 续播接棒 / stop），只查整个文件的话，删掉 stop() 里那处
# 守卫照样绿（破坏性证伪实测：删 stop 里的清零 → 红 0 条）。
stop_body = body_of(ctrl, 'public synchronized void stop()')
report('MediaPlayerController.stop 方法体已找到', stop_body is not None,
       '锚点：public synchronized void stop()')
report('Stop/换片清下一曲队列（DLNA 语义）',
       stop_body is not None and 'nextUrl = null' in stop_body,
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
# ---- ⑯ 收尾必须回空闲：onStop() 清片源，不能只 player.stop() ----
# 真机现象（用户报）：音乐投屏没有"关闭"的地方 —— 一首歌播完，电视就停在
# 音乐界面、冻在结束位置不动。根因是收尾只停了播放器、没清 currentUri，
# MainActivity.isPlaying() 时长归零后退化成「有没有 URI」，于是永远非空闲。
_auto = body_of(strip_comments(svc), 'private void checkAutoStop()')
report('Auto-Stop 收尾走 onStop()（清片源回空闲）',
       _auto is not None and 'onStop();' in _auto and 'player.stop()' not in _auto,
       '只 player.stop() 的话 currentUri 还挂着，电视停在音乐界面不回引导页')
report('/status 诊断页（浏览器可达，排障不需要 adb）',
       '/status' in http and 'buildStatusJson' in http and 'buildStatusJson' in svc,
       'gmrender 生态的 upnp-display 用小屏显示状态 —— 我们的「显示屏」'
       '就是手机浏览器')

# ⑪ /status 无鉴权，currentUri 必须打码 —— 签名即凭证，别把 CDN URL 送出去。
# 判据先剥注释再判（本节 svc 是原始文本，注释里也会出现 currentUri 等字眼），
# 且只看 buildStatusJson 的方法体：认结构性事实，不认排版。
svc_nc = strip_comments(svc)
bs = body_of(svc_nc, 'public String buildStatusJson()')
report('buildStatusJson 方法体已找到（/status 数据源）', bs is not None,
       '锚点：public String buildStatusJson()')
if bs:
    report('/status 的 currentUri 已打码（不直接吐原始 URL）',
           re.search(r'jsonPut\(sb,\s*"currentUri",\s*currentUri\s*\)', bs) is None
           and re.search(r'maskUri\s*\(\s*currentUri\s*\)', bs) is not None,
           'DLNA 推来的 CDN 地址常带 token/expire，签名本身就是播放凭证；'
           '而 /status 无鉴权，局域网内谁都能取走拿去别处播')
mk = body_of(svc_nc, 'private static String maskUri(String uri)')
report('maskUri 方法体已找到（currentUri 打码唯一出处）', mk is not None,
       '锚点：private static String maskUri(String uri)')
if mk:
    report('maskUri 抹掉 query（host/port/path 保留供排障）',
           "indexOf('?')" in mk and 'substring(0' in mk and '?***' in mk,
           '签名/token 在 ? 之后，整段换成 *** 即不可复原；'
           'host/path 要留着判断是不是 CDN、是哪个文件')
    report('maskUri 无 ? 时只做 userinfo 打码（不打 query 码）',
           re.search(r"indexOf\('\?'\)[\s\S]{0,200}?return masked", mk) is not None,
           '没有 ? 就没有签名可泄 —— 返回前只保留 userinfo 打码结果')
    report('maskUri 调用了 maskUserInfo（userinfo 凭证也打码）',
           'maskUserInfo(' in mk,
           '私有 NAS 的 http://user:pass@host/path 会把 basic-auth 凭证写进 URL，'
           '同样不能出现在无鉴权的 /status 上')
mu = body_of(svc_nc, 'private static String maskUserInfo(String uri)')
report('maskUserInfo 方法体已找到', mu is not None,
       '锚点：private static String maskUserInfo(String uri)')
if mu:
    report('maskUserInfo 只在 authority 段内认 @（扫到 / ? # 就停，不误伤 path 的 @）',
           '"://"' in mu and "indexOf('@'" in mu
           and "c == '/'" in mu and "c == '#'" in mu,
           'authority 到第一个 / ? # 为止；否则 /a@b.mp4 这种 path 里的 @ '
           '会被误当成 userinfo，把路径也抹了')
    report('maskUserInfo 把 user:pass 换成 ***（保留 @ 与 host）',
           '"***"' in mu and 'substring' in mu,
           '凭证换成 ***，@ 与 host/port/path 全部保留 —— 排障信息不丢')

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
    report('seek 在飞期间不采位置水位线（防旧位置顶掉 seekTo 对齐的水位线）',
           re.search(r'boolean\s+seekInFlight\s*=[^;]*pendingSeekLanded', gp) is not None
           and re.search(r'!\s*seekInFlight\s*&&\s*raw\s*>\s*hiRaw', gp) is not None,
           'seekTo 对齐了水位线，但往回拖时 player 还停在旧位置、旧位置比目标大：'
           '不挡住的话下一次轮询（网易云 15 次/秒）就把水位线顶回旧位置，'
           '落地后的位置低于水位线，外推便拿旧位置当基准 —— '
           '手机进度条卡 2 秒再跳回原处，看起来就是"拖了没生效"')
    report('seek 在飞期间也不采「假 EOS」水位线 maxPlayedMs',
           re.search(r'!\s*seekInFlight\s*&&\s*raw\s*>\s*maxPlayedMs', gp) is not None,
           '厂商播放器在 seekTo 之后的几十毫秒里会报出≈(时长-1 秒)的位置：采进去'
           '会让紧随其后的假 EOS 看起来"离片尾只差 1 秒"→ 判成真播完 → 报 STOPPED'
           '（真机第一首：Seek 200000ms 后 167ms 来 EOS，263000ms/264000ms）')
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
    # ---- 「起播了，但一直没出声」 ----
    # 厂商栈偶发「prepare 成功、状态报 PLAYING，音频却根本没推出去」：
    # 位置从起播那一刻起恒为 0。真机（秋殇 mp3）位置冻在 0ms 整整 25 秒才被
    # 20s 的通用卡死阈值捞到 —— 用户听到的就是「切到下一首了但没播放」。
    report('checkStall 用 isNotStarted 早捞「起播了但没出声」',
           re.search(r'isNotStarted\(', cs) is not None,
           '只靠通用卡死阈值（点播 20s + 轮询 5s）要等 25 秒才重连，'
           '那 25 秒就是白静音 —— 这段等待本身就是用户报的现象')
    report('「没起播」判据用起播时刻 playStartedAtMs（不是会动的 lastProgressAt）',
           'playStartedAtMs' in cs,
           'lastProgressAt 会被位置前进反复刷新；位置恒 0 时拿它当基准会把这段静止'
           '重新算短，判据被拖回通用阈值 —— 这个 bug 会安静地回来，日志也不会报错')
    res = body_of(ctrl, 'public synchronized void resume()')
    report('onPrepared 与 resume 都刷新起播时刻',
           'playStartedAtMs' in prep and res is not None and 'playStartedAtMs' in res,
           'onPrepared 不刷 → 换片 / 重连后没有基准；resume 不刷 → '
           '「暂停很久再取消暂停」会被当成起播超时而误重建')
    # 判据本身（纯逻辑层）的两条前提，各守一个方向：
    ins = body_of(pol_src, 'public static boolean isNotStarted(')
    report('PlaybackPolicy.isNotStarted 方法体已找到', ins is not None,
           '锚点：public static boolean isNotStarted(')
    if ins:
        report('位置动过（≥1ms）就不算「没起播」——交给卡死阈值',
               re.search(r'positionMs\s*>\s*0', ins) is not None,
               '位置动过说明音频通道是通的，那是"卡死"不是"没起来"；'
               '混为一谈会把正常缓冲误杀成重建')
        report('直播被排除在「没起播」之外',
               'isLiveStream' in ins,
               '直播的位置本来就可能长时间是 0（还没拿到首个时间戳）——'
               '不排除的话，正常直播会被反复重建，从"不播"变成"不停重启"')

for label, marker in [('stop()', 'public synchronized void stop()'),
                      ('releasePlayer()', 'private void releasePlayer()')]:
    b = body_of(ctrl, marker)
    report('%s 里清掉 seek 待决状态' % label,
           b is not None and re.search(r'pendingSeekMs\s*=\s*-1', b) is not None,
           '不清的话，下一次播放会拿着上一次的 seek 目标当"当前位置"报给控制点')

# ---- ③ 重投：HTTP 服务 bind 竞态不得泄漏端口 ----
# 绑定已从 run() 移进 bindWithFallback()（为的是同步绑定 + 端口被占时回退），
# 判据的锚点跟着移 —— 守的还是同一件事：绑上之后真关过一次 socket。
bb = body_of(http, 'private boolean bindWithFallback()')
report('UpnpHttpServer.bindWithFallback 方法体已找到', bb is not None,
       '锚点：private boolean bindWithFallback()')
if bb:
    # 判据必须落在「绑上之后真的复查 running 并关过一次 socket」上。
    #
    # 只查 if (!running) 是不够的 —— accept 的 catch 里本来就有一句
    # if (!running) break;，那句会让断言**恒真**，永远发现不了这里的回归。
    # （这条是证伪时发现的：把兜底整段删掉，断言居然还是绿的。）
    report('bind 之后有兜底：复查 running 并释放端口',
           re.search(r'new ServerSocket\([\s\S]*?if\s*\(!running\)[\s\S]*?closeQuietly\(',
                     bb) is not None,
           'shutdown() 可能正好落在 bind 与赋值之间：那一刻 serverSocket 还是 null，'
           'close 被跳过，线程却把端口绑上了 —— 端口被永久占住，'
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

# ---- 「假 EOS」判据 ----
# 厂商栈（海信 Vision-TV / MTK CmpbPlayer）会把「释放旧播放器资源」与
# 「切换输入源」规范化成一次 EOS（日志原文
# "This is adt event,the Normal event type is EOS!!!"），框架据此回调 onCompletion。
# 真机实测 8 次，每次都在起播 1 秒内紧跟一个 STOPPED；最典型的表现是
# 「投完视频再投音频，首次 Set 直接变 STOPPED」—— 手机显示投上了、电视已经停了。
#
# 这一组守卫守的是「判据还在、接线还没断」：把判据删掉、把重连换成直接报停、
# 或把判据挪到 notifyState("STOPPED") 之后，编译运行全都正常，
# 只是「首次 Set 即 STOPPED」悄悄回来。
oc = body_of(ctrl, 'new MediaPlayer.OnCompletionListener()')
report('OnCompletionListener 匿名类体已找到', oc is not None,
       '锚点：new MediaPlayer.OnCompletionListener()')
if oc:
    report('onCompletion 校验过期实例（mp != player）',
           re.search(r'mp\s*!=\s*player', oc) is not None,
           '重连 / 换片 / 续播都会重建实例，老实例的回调可能在新实例开工之后才到 —— '
           '照单全收会把刚起来的播放报成 STOPPED')
    report('onCompletion 用 isSpuriousCompletion 判「假 EOS」',
           'isSpuriousCompletion(' in oc,
           '不判的话，厂商层的一次"切输入源"就被我们如实报成了"播完了"')
    report('假 EOS 分支走 scheduleRetry（重建播放器，而不是报停）',
           re.search(r'isSpuriousCompletion\([\s\S]*?scheduleRetry\(', oc) is not None,
           '只记日志不重连的话，电视侧依然停着、手机侧以为在播 —— 等于没修')
    sp = oc.find('isSpuriousCompletion(')
    ns = oc.find('notifyState("STOPPED")')
    report('假 EOS 判据排在 notifyState("STOPPED") 之前',
           sp >= 0 and ns >= 0 and sp < ns,
           '排到后面就是先报停再补救 —— 控制点已经收到 STOPPED 了，'
           '状态机的歪斜补不回来')
    report('假 EOS 重建前把待决的 seek 存下来',
           'seekAfterRebuildMs = pendingSeekMs' in oc,
           '重建必经 releasePlayer()，而那里会清掉 pendingSeekMs —— '
           '真机第一首「拖了完全没反应」就是被一次 seek 后 113ms 的假 EOS 吃掉的；'
           '后面几首偶尔不触发假 EOS，于是"切到后面几首又可以了"')
    report('onPrepared 补发 seek 时把「重建暂存」也算上',
           'seekAfterRebuildMs' in prep and 'replayMs' in prep,
           '只认 pendingSeekMs 的话，假 EOS 重建出来的那份拷贝永远不会被补发，'
           '存下来等于白存')
    report('换片源与 Stop 都要作废「重建暂存」的 seek',
           'seekAfterRebuildMs = -1L' in body_of(ctrl, 'public synchronized boolean play(String url)')
           and 'seekAfterRebuildMs = -1L' in stop_body,
           '它刻意扛过 releasePlayer（重建要走那条路），所以只能在这两处显式清 —— '
           '否则新片子 prepare 完会拿着上一部片子的进度去 seek')
    report('假 EOS 判据带 !userPaused 守卫',
           re.search(r'if\s*\(\s*!userPaused[\s\S]*?isSpuriousCompletion', oc) is not None,
           '用户主动暂停后控制点可能补一条 completion —— 那不是假 EOS，照实报停才对')
    # ---- 「重建后补发 seek」必须有次数上限 ----
    # 厂商栈对个别文件直接拒绝 seek（真机：秋殇 mp3 → Failed / MTK ret -6），
    # 补发过去照样失败、照样来假 EOS。没有上限就是无限重建（真机连续 51 轮）。
    report('假 EOS 重建的 seek 补发受 canReplaySeekAfterRebuild 上限约束',
           'canReplaySeekAfterRebuild(' in oc,
           '少了这道闸，厂商拒绝的 seek 会把播放拖进「重建 → 补发 → 又失败 → 又重建」'
           '的死循环 —— 盒子既不播、又一直谎报"已到某个位置"')
    report('补发超限时丢掉待决 seek（不再谎报位置）',
           re.search(r'canReplaySeekAfterRebuild\([\s\S]*?else\b[\s\S]*?pendingSeekMs\s*=\s*-1',
                     oc) is not None,
           '只停止补发、不清 pendingSeekMs 的话，位置会被永久报成那个到不了的目标点，'
           '控制点的进度条冻死在那儿')
    seek_body = body_of(ctrl, 'public synchronized void seekTo(int ms)')
    report('seekTo 每次新拖拽都重置补发计数',
           seek_body is not None and 'seekReplayCount = 0' in seek_body,
           '不清的话，一次失败的 seek 会让"以后所有 seek"都被当成厂商做不到而直接放弃')
    report('换片源与 Stop 都要把补发计数清零',
           'seekReplayCount = 0' in body_of(ctrl, 'public synchronized boolean play(String url)')
           and 'seekReplayCount = 0' in stop_body,
           '它是"同一次 seek"的计数，换片源 / 停止就是新的一次，必须归零')

if cs:
    report('checkStall 采样「观察到的最远位置」',
           'maxPlayedMs' in cs,
           '看门狗这一路是「控制点不轮询 GetPositionInfo」时的唯一采样点 —— '
           '缺了它，判据会把"根本没采到位置"读成"位置是 0"')
if gp:
    report('getPosition 采样「观察到的最远位置」',
           'maxPlayedMs' in gp,
           '控制点轮询是另一路采样点；两路必须都在，否则一条投屏路径上判据就失灵')
rl = body_of(ctrl, 'private void releasePlayer()')
report('releasePlayer 复位「观察到的最远位置」',
       rl is not None and 'maxPlayedMs' in rl,
       '新实例是另一次播放（重连 / 换片 / 续播）—— 不复位的话，'
       '上一次播放的远位置会让新播放的假 EOS 判据直接失灵')
report('PlaybackPolicy 定义了假 EOS 判据与三个阈值',
       'isSpuriousCompletion(' in pol_src
       and 'SPURIOUS_EOS_MIN_REMAINING_MS' in pol_src
       and 'SPURIOUS_EOS_MIN_DURATION_MS' in pol_src
       and 'SPURIOUS_EOS_EARLY_WINDOW_MS' in pol_src,
       '阈值与判据收在纯逻辑层，才能在桌面上把'
       '「真播完 vs 厂商误报」的每条边界逐个跑断言')

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


def _brace_span(src, open_idx):
    """从 open_idx（一个 '{' 的下标）起按大括号配对返回 (体, 体后下标)。

    跳过字符串 / 字符字面量里的括号 —— 否则分支体里写个 "}" 就会把配对算错、
    把分支体截断（守卫会误红；检查器喊狼来了跟不检查一样糟）。
    """
    depth, k, n = 0, open_idx, len(src)
    while k < n:
        c = src[k]
        if c == '"' or c == "'":
            q, k = c, k + 1
            while k < n:
                if src[k] == '\\':
                    k += 2
                    continue
                if src[k] == q:
                    break
                k += 1
            k += 1          # 跳过收尾引号
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[open_idx:k + 1], k + 1
        k += 1
    return None, None


def positive_if_else(src, cond):
    """切出 `if (cond) {…} else {…}` 两个分支体，条件必须是**正向**写法。

    返回 (if体, else体)；条件被取反（`if (!cond)`）或没有 else 时返回 (None, None)。
    判据要看**条件方向**，不能只看两个子串在不在 —— 反转 if/else 后子串都还在，
    只查「存在」的写法会假阴性。
    """
    m = re.search(r'\bif\s*\(\s*' + re.escape(cond) + r'\s*\)\s*\{', src)
    if m is None:
        return None, None
    if_body, after = _brace_span(src, src.find('{', m.start()))
    if if_body is None:
        return None, None
    em = re.compile(r'\s*else\s*\{').match(src, after)
    if em is None:
        return None, None
    else_body, _ = _brace_span(src, src.find('{', em.start()))
    return if_body, else_body


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
# 广播的触发点已经收敛到 onMulticastJoined()：首次加入成功与「后台重试加入成功」
# 两条路径共用同一处（announceAlive + 启动定期重播）。所以判据跟着触发点走 ——
# 停在旧结构（run() 里直接调 announceAlive）上会变成假阴性。
omj = body_of(ssdp_c, 'private void onMulticastJoined()')
report('SsdpResponder.onMulticastJoined 方法体已找到', omj is not None,
       '锚点：private void onMulticastJoined()')
if run and omj:
    # 判据必须看**条件方向**，不能只看「子串存在」：把 run() 里的 if/else 反转成
    # `if (!multicastJoined) { onMulticastJoined(); } else { startJoinRetry(); }` 后，
    # 两个子串都还在，只查「存在」的写法照样放行 —— 而那等于「广播早于 join
    # （组播还没通，发出去没人收得到）+ 重试永不启动」= 手机永远搜不到。
    # 所以用 positive_if_else 抠出 `if (multicastJoined)` 的分支体，要求广播确实
    # 落在**正向**分支里。
    _ib_join, _ = positive_if_else(run, 'multicastJoined')
    report('加入组播成功之后才广播 alive',
           re.search(r'bindUntilReady\(\)[\s\S]*?multicastJoined[\s\S]*?onMulticastJoined\(\)',
                     run) is not None
           and 'announceAlive()' in omj
           and _ib_join is not None and 'onMulticastJoined()' in _ib_join,
           '必须发生在 joinGroup 成功之后 —— 早于它的话组播还没通，发出去没人收得到。'
           '触发点现在是 onMulticastJoined()，且 run() 里由 multicastJoined 这个条件'
           '把关（未加入走后台重试，不广播）；判据看的是**条件方向**，反转 if/else 必红')
    report('有定期重播，不是只发一轮',
           'startAnnouncer()' in omj,
           'UDP 会丢包、控制点缓存也会过期；只发一轮的话"在线却搜不到"会反复出现')
if run:
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

# AVTransport 的事件现在装在 LastChange 的值里（见下面 ④ 那一节），
# 所以这条判据从 eventedVars 的本体挪到了它的实参上：LastChange 文档的
# TransportStatus 必须仍然取自 getTransportStatus()。
report('LastChange 的 TransportStatus 与 getTransportStatus 同源',
       re.search(r'avtLastChange\([\s\S]{0,300}?getTransportStatus\(\)', svc_c) is not None,
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
    report('SurfaceView 的可见性跟着 idle / 图片态走（这两种形态都要藏起来）',
           re.search(r'surfaceView\.setVisibility\(\(?[^?]*idle[^?]*\)?\s*\?\s*View\.GONE',
                     am) is not None,
           '不隐藏的话，停止播放后那个已经没有内容的 Surface 还压在面板底下 ——'
           '老平台上视频层空着时输出的是**蓝色**，'
           '用户看到的就是"断开投屏后电视蓝屏"；'
           '写成恒 VISIBLE、或反过来（idle 时 VISIBLE），同样等于没修。'
           '图片态同理：照片不经过视频层，那个 Surface 只会露出一屏蓝底')

pm = re.search(r'<LinearLayout\s+android:id="@\+id/panel"[\s\S]{0,400}?>', layout)
report('layout 里找得到 panel 节点', pm is not None, '锚点：@+id/panel')
if pm:
    report('panel 有不透明背景（不是透明面板）',
           'android:background="@color/bg"' in pm.group(0),
           '透明面板盖不住底下的 Surface —— 面板显示出来了，'
           '用户看到的却还是那层蓝')

# ---- ③.5 面板上的版本号：必须在，且必须来自构建产物 ----
report('面板上有「版本」这一行（layout: @+id/info_version）',
       # 判据必须带上结尾的引号：只查子串的话，把 id 改成
       # info_version_x 照样"命中"（@+id/info_version 是它的前缀）——
       # 这是证伪实测抓到的假绿。
       re.search(r'@\+id/info_version"', layout) is not None and 'infoVersion' in act_c,
       '真机排障第一句问的是「盒子上装的是哪一版」：界面上没有这一行，'
       '就只能去翻设置或 adb，而现场往往只有一台电视 + 一个遥控器')
report('版本号取 BuildConfig（不写死字面量），且界面只显示 versionName',
       'BuildConfig.VERSION_NAME' in act_c and 'BuildConfig.VERSION_CODE' not in act_c,
       '写死就一定会漂 —— 「APK 是 0.1.11、界面上还写着 0.1.10」让人对着'
       '错误版本号排障，比不显示更糟。取 BuildConfig 则发版只需改 '
       'build.gradle 的 versionName/versionCode，界面自动跟上。'
       '而 versionCode 是给系统比新旧用的整数，界面上没有意义 —— '
       '原来显示成 0.1.9（10），用户只会问"括号里那个数是什么"')

# ---- ③.6 遥控器返回键 = 结束投屏 ----
# 真机需求（用户提）：盒子这边没有"关闭投屏"的入口 —— 音乐播完就停在结束位置。
# 先做过"播完 60 秒没有控制指令就自动收尾"，用户明确否掉了：那是在替他猜意图。
# 改成返回键手动结束，按下去走控制点 Stop 的同一条路。
bk = body_of(act_c, 'public boolean onKeyDown(int keyCode, KeyEvent event)')
report('遥控器返回键 = 结束投屏（走 onStop 清片源回引导面板）',
       bk is not None and 'KEYCODE_BACK' in bk and 'onStop()' in bk
       and 'return true' in bk,
       '锚点：public boolean onKeyDown(int keyCode, KeyEvent event)。'
       '必须 return true 把按键吃掉 —— 不然系统会顺手把 Activity 关掉，'
       '电视直接退回 launcher，而不是停在引导面板上；'
       '必须走 onStop() 而不是 service.getPlayer().stop() —— 后者不清 '
       'currentUri，界面仍判"在播"，电视就停在结束位置不动')
report('空闲态按返回键不拦（那时它就该是"退出应用"）',
       bk is not None and 'MODE_IDLE' in bk,
       '少了这个判断，空闲态按返回会被吃掉 —— 遥控器再也退不出这个应用')

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

# ---- AVTransport 的事件承载必须是规范的 LastChange 形态 ----
# 规范里 AVTransport 整张 stateVariable 表只有 LastChange 声明为
# sendEvents="yes"，其余变量（TransportState / CurrentTrackURI …）都是 "no"，
# 内容以一段 AVT 命名空间的 XML 文档塞进 LastChange 的**值**里。
# 逐变量推送时 Cling 系控制点（芒果 TV 实测，按 SCPD 生成桩）会把整条事件
# 忽略掉 —— 它就一直收不到 TransportState，手机上的按钮/进度条不跟着走。
ev = body_of(svc_c, 'public Map<String, String> eventedVars(String service)')
report('DlnaRendererService.eventedVars 方法体已找到', ev is not None,
       '锚点：public Map<String, String> eventedVars(String service)')
if ev:
    report('AVTransport 分支只推 LastChange 一个键（规范形态）',
           re.search(r'vars\.put\("LastChange",\s*UpnpHttpServer\.avtLastChange\(',
                     ev) is not None,
           '装了 LastChange 就不能再逐变量 put —— Cling 系控制点按 SCPD 生成桩，'
           '未声明的事件变量会被整条忽略')
    # 这里原来还有一条「TransportStatus 如实反映出错与否」（在 ev 里找
    # ERROR_OCCURRED 字面量）。判据挪进 getTransportStatus() 之后它就失效了，
    # 而且它和上面那条「同源」守的是同一个语义 —— 留着只会让一处破坏红两条，
    # 反而定位不出到底哪儿坏了。现在由「同源」+「getTransportStatus 用
    # hasTransportError 判据」两条共同覆盖。

# SCPD 的声明形态（只事件化 LastChange）、事件文档的命名空间 / InstanceID /
# 双层转义，都由协议测试**行为级**覆盖 —— SCPD 与 avtLastChange 都在
# UpnpHttpServer 里，协议靶机编的就是这份源码。这里**刻意不重复**同一语义：
# 两处各写一条，破坏一处会红两条，反而定位不出到底哪儿坏了。
scpd = re.search(r'SCPD_AV_TRANSPORT =[\s\S]*?</scpd>', http_c)
report('SCPD_AV_TRANSPORT 找得到', scpd is not None, '锚点：SCPD_AV_TRANSPORT =')

lc = body_of(http_c, 'public static String avtLastChange(')
report('UpnpHttpServer.avtLastChange 方法体已找到', lc is not None,
       '锚点：public static String avtLastChange(')
if lc:
    # 协议驱动只抽查了 TransportState / CurrentTrackDuration /
    # RelativeTimePosition 三个字段（外加按值校验的 CurrentTrackURI），
    # **没有**盯 TransportStatus。这里只补这一个缺口 —— 另外四个已在协议侧
    # 行为级钉住，再在这儿重列一遍就是同一语义写两处（破坏一处红两条）。
    report('LastChange 文档带上 TransportStatus（协议驱动没抽查的那个）',
           'TransportStatus' in lc,
           '少给这个字段，控制点会一直以为设备"一切正常" —— 而它可能正在反复重连')

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

# ---- ⑥ 订阅不能因为「回调暂时投不通」就消失 ----
# 真机铁证（2026-10-01）：16:00:56 SUBSCRIBE（回调 http://192.168.1.6:8058/evetSub，
# 手机息屏后 App 被挂起）→ 三次 NOTIFY ECONNREFUSED → 原实现把订阅删了 →
# 16:06:50 控制点拿同一个 SID 来续订，被回 412；它不会去重建订阅，
# 于是整首歌零事件：电视上下一首已经在放，手机 UI 卡在「暂停」不动。
# 这几条守卫钉的就是「投递失败只能标记、不能删表」这个不变量。
del_b = body_of(ed_c, 'private void deliver(String sid)')
report('EventDispatcher.deliver 方法体已找到', del_b is not None,
       '锚点：private void deliver(String sid)')
if del_b:
    # 判据要看**位置**：deliver 开头那段"过期就清"是应该留着的，
    # 不能删的是"投递失败"分支里的删除。只查子串存在会把这两件事混淆 ——
    # 一开始就是这么写红的。
    _fail_at = del_b.find('s.failCount++')
    report('投递失败到上限时不删订阅（只标记不可达）',
           _fail_at >= 0
           and re.search(r'failCount\s*>=\s*MAX_FAIL', del_b) is not None
           and 'subs.remove' not in del_b[_fail_at:],
           '删表 = 控制点续订必然被回 412，而控制点收到 412 不会重建订阅 ——'
           '事件通道就此永久断掉（真机症状：电视在放下一首，手机 UI 卡在「暂停」）')
    report('过期订阅仍然会被清掉（不可达标记不是永不回收）',
           _fail_at > 0 and 'subs.remove' in del_b[:_fail_at],
           '订阅表必须能自己收敛：条目过期就该走。把 deliver 开头那段一起删掉的话，'
           '投不通的订阅会一直堆到 MAX_SUBS 才靠淘汰收场')
    report('失败超限只标记一次 unreachable',
           re.search(r'failCount\s*>=\s*MAX_FAIL\s*&&\s*!\s*s\.unreachable[\s\S]{0,200}?'
                     r's\.unreachable\s*=\s*true', del_b) is not None,
           '不标的话，Auto-Stop 会把一条已经收不到事件的订阅算作「还有人在听」，'
           '电视守着一条死订阅永远不停')
    report('投递成功同时清零失败计数与不可达标记',
           re.search(r'failCount\s*=\s*0[\s\S]{0,120}?s\.unreachable\s*=\s*false',
                     del_b) is not None,
           '只清 failCount 的话标记会一直挂到超时：手机明明已经恢复收事件，'
           'Auto-Stop 的存活计数却还是 0')

ren_b = body_of(ed_c, 'public long renew(String sid, String timeoutHeader)')
report('EventDispatcher.renew 方法体已找到', ren_b is not None,
       '锚点：public long renew(String sid, String timeoutHeader)')
if ren_b:
    report('续订唯一的拒绝理由是「表里没有这个 SID」',
           ren_b.count('return -1L') == 1,
           '再挂一条「失败太多就拒」正是真机上那次 412；条目只该在超时或'
           '显式退订时才从表里消失')
    report('续订清零失败计数与不可达标记（重新算它可达）',
           re.search(r'failCount\s*=\s*0[\s\S]{0,120}?unreachable\s*=\s*false',
                     ren_b) is not None,
           '续订是控制点主动连上来发的请求 —— 等于它活着的直接证据。'
           '不清标记的话，点亮手机后存活计数仍是 0，Auto-Stop 会把正在放的投屏停掉')

asc_b = body_of(ed_c, 'public int aliveSubscriberCount()')
report('EventDispatcher.aliveSubscriberCount 方法体已找到', asc_b is not None,
       '锚点：public int aliveSubscriberCount()')
if asc_b:
    report('Auto-Stop 的存活计数跳过不可达订阅',
           re.search(r'expireAtMs\s*>\s*now\s*&&\s*!\s*s\.unreachable', asc_b) is not None,
           '把不可达的算作存活，Auto-Stop 就永远不会触发 —— 而它存在的意义正是'
           '「手机退出了，电视别再自己放着」')

na_b = body_of(ed_c, 'public void notifyAll(String service)')
report('EventDispatcher.notifyAll 方法体已找到', na_b is not None,
       '锚点：public void notifyAll(String service)')
if na_b:
    report('状态变化时对全部订阅照常投递（不滤掉不可达的）',
           re.search(r'subs\.values\(\)', na_b) is not None and 'unreachable' not in na_b,
           '滤掉不可达的，它就再没有恢复的机会 —— 投递本身就是探测：'
           '手机点亮、或换回原来的网段之后，靠它自动接上线')

# ---- ⑦ 服务销毁时的收尾 ----
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

# ---- ⑧ 界面显示的地址，必须就是控制点拿到的那个 ----
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

# 端口同一个道理：HTTP 首选端口被占会自动回退，界面 / 状态页显示的端口
# 必须来自「实际在听」的那个，而不是首选常量 —— 否则照着界面地址 curl，
# 怎么都复现不了用户的问题，看着还像"盒子没问题"。
ghp = body_of(svc_c, 'public int getHttpPort()')
report('DlnaRendererService.getHttpPort 方法体已找到', ghp is not None,
       '锚点：public int getHttpPort()')
if ghp:
    report('界面取端口走 currentHttpPort（实际端口），不直接回常量',
           'currentHttpPort()' in ghp and 'HTTP_PORT' not in ghp,
           '直接 return HTTP_PORT 的话，49152 被占、回退到相邻端口之后，'
           '界面和 /status 还在报 49152 —— 那是没人监听的地址（与上面 getLocalIp 同理）')

# 网络变化时 applyNetworkChange 是「先 SSDP 后 HTTP」（守卫钉着这个顺序），
# 所以重建 HTTP 若是换了端口，必须回头让 SSDP 重算 LOCATION，否则手机拿到的
# 地址指向旧端口 —— 正是「搜得到但投不了」。
rhb = body_of(svc_c, 'private void restartHttp(')
report('DlnaRendererService.restartHttp 方法体已找到', rhb is not None,
       '锚点：private void restartHttp(')
if rhb:
    report('restartHttp 端口变化时重同步 SSDP 的 LOCATION',
           re.search(r'getPort\(\)[\s\S]*?restartSsdp\(', rhb) is not None,
           '端口因占用变了却不同步的话，SSDP 广播的 LOCATION 还指着旧端口；'
           '重启的是 HTTP、手机却去了一个没人听的地址')

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


def _brace_span(src, open_idx):
    """从 open_idx（一个 '{' 的下标）起按大括号配对返回 (体, 体后下标)。

    跳过字符串 / 字符字面量里的括号 —— 否则分支体里写个 "}" 就会把配对算错、
    把分支体截断（守卫会误红；检查器喊狼来了跟不检查一样糟）。
    """
    depth, k, n = 0, open_idx, len(src)
    while k < n:
        c = src[k]
        if c == '"' or c == "'":
            q, k = c, k + 1
            while k < n:
                if src[k] == '\\':
                    k += 2
                    continue
                if src[k] == q:
                    break
                k += 1
            k += 1          # 跳过收尾引号
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[open_idx:k + 1], k + 1
        k += 1
    return None, None


def positive_if_else(src, cond):
    """切出 `if (cond) {…} else {…}` 两个分支体，条件必须是**正向**写法。

    返回 (if体, else体)；条件被取反（`if (!cond)`）或没有 else 时返回 (None, None)。
    判据要看**条件方向**，不能只看两个子串在不在 —— 反转 if/else 后子串都还在，
    只查「存在」的写法会假阴性。
    """
    m = re.search(r'\bif\s*\(\s*' + re.escape(cond) + r'\s*\)\s*\{', src)
    if m is None:
        return None, None
    if_body, after = _brace_span(src, src.find('{', m.start()))
    if if_body is None:
        return None, None
    em = re.compile(r'\s*else\s*\{').match(src, after)
    if em is None:
        return None, None
    else_body, _ = _brace_span(src, src.find('{', em.start()))
    return if_body, else_body


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
    # presentationURL 从批 2 起**要**声明了：上传页在 "/"，控制点点那个按钮
    # 就该落到上传页。但声明它有个硬前提 —— 地址必须是"本机",不能凭空捏。
    # 所以这里查的不是"有没有声明"，而是"声明出来的地址是从哪来的"：
    # 必须走 handler.getLocalIp()（和 LOCATION 同源），端口必须走 getPort()
    # （端口单一真源）。谁自己再算一遍，就会在"第一张候选网卡 joinGroup
    # 失败、换到第二张"时分叉 —— 那时二维码里的地址能打开、
    # 控制点按钮打开的却打不开。
    apu = body_of(http, 'private void appendPresentationUrl(StringBuilder sb)')
    report('appendPresentationUrl 方法体已找到', apu is not None,
           '锚点：private void appendPresentationUrl(StringBuilder sb)')
    if apu:
        report('presentationURL 的地址取自 handler.getLocalIp()（与 LOCATION 同源）',
               'handler.getLocalIp()' in apu,
               '自己算出本机地址 = 第二处真源。SSDP 换网卡时它和 LOCATION 就分叉了')
        report('presentationURL 的端口取自 getPort()（端口单一真源）',
               'getPort()' in apu and '49152' not in apu,
               '自己拼端口号（或写死 49152）= 第二处真源。'
               'HTTP 端口会因占用而回退，写死的那份必然在回退时是错的')
        report('拿不到有效 IP 时不声明 presentationURL',
               re.search(r'0\.0\.0\.0', apu) is not None and 'return' in apu,
               '空串 / 0.0.0.0 时若照写，device.xml 里就会出现'
               'http://0.0.0.0:49152/ 这种地址 —— 控制点照着点必然打不开。'
               '给不出就别声明（和 iconList 同一条纪律）')
    report('buildDeviceDescription 真的调用了 appendPresentationUrl',
           'appendPresentationUrl(sb)' in bd,
           '方法写好了不调用 = 白写。这条专门防"改一半"：'
           '新方法加进去、调用点忘了接')

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
report('SINK_PROTOCOL_INFO 声明了 image/*（图片通道已落地）',
       'image/jpeg' in http.split('SINK_PROTOCOL_INFO =')[1].split(';')[0]
       and 'image/png' in http.split('SINK_PROTOCOL_INFO =')[1].split(';')[0],
       '这条原来是反过来的：当时实现里根本没有图片这条路（kindOf 只认 '
       'audioItem / videoItem），所以**刻意不声明** image/*。现在图片通道'
       '（KIND_IMAGE + BitmapFactory）已经落地，清单就必须加回来 —— '
       '这份清单是控制点判断"能不能推给我"的**唯一依据**：做到了却不声明，'
       '相册/文件管理器就不会把照片推过来，那条通道等于白做')

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

# ---- (6b) SSDP：端口绑定与组播加入是两个独立事实 ----
# CI（GitHub runner = Azure VM）不转发组播，joinGroup 必失败。若把「加入组播」
# 当作靶机就绪的必要条件，protocol 闸门在 CI 上永远红 —— 而本机（组播正常）
# 复现不出来。这几条把「端口可用」与「能被搜到」钉成两个独立事实。
_tbo2 = body_of(ssdp, 'private String tryBindOnce()')
report('SsdpResponder 暴露 isPortBound / isMulticastJoined（端口与组播拆开）',
       'public boolean isPortBound()' in ssdp
       and 'public boolean isMulticastJoined()' in ssdp
       and 'public int getLocalPort()' in ssdp,
       'CI 上 joinGroup 必失败，但端口能绑、单播能收 —— 把「端口可用」'
       '从「能被搜到」里拆出来，协议靶机才不会 exit 3')
report('tryBindOnce 不再把 joinGroup 失败当作「绑定失败」',
       _tbo2 is not None and 'bound == null' in _tbo2 and 'closeSocket(s)' in _tbo2
       and re.search(r'reason\s*=\s*"所有候选网卡都无法加入组播组', _tbo2) is None,
       '把加入失败赋给 reason 会进重试循环、端口永远不对外报 —— CI 上靶机就起不来。'
       '现在只有 bind 本身失败才 return reason')
report('有跳过组播的测试开关（JUPING_SSDP_NO_MULTICAST）',
       'JUPING_SSDP_NO_MULTICAST' in ssdp,
       '用来在本地模拟 CI 的无组播环境，让这个修复能被验证而不是靠推理')
_pts = pathlib.Path('tools/protocol-test/ProtocolTestServer.java').read_text(encoding='utf-8')
_pts = strip_comments(_pts)
report('协议靶机 READY 只要求端口绑定（不要求加入组播）',
       'ssdp.isPortBound()' in _pts and 'ssdp.getLocalPort()' in _pts,
       'READY 若还要求 isBound()，CI 上（joinGroup 失败）靶机会 exit 3 ——'
       '这正是 protocol 闸门在 CI 上一直红的根因')
report('协议靶机把组播状态单独报告（不静默）',
       'isMulticastJoined()' in _pts,
       '让人一眼看出是「单播模式」还是「真机同款的组播模式」，而不是靠猜')

# ---- (6c) SSDP：组播加入失败改为「后台重试」，不阻塞端口可用性 ----
# 「网卡 up + 有 IPv4」并不等于「此刻内核/驱动的组播已就绪」。老 MTK 设备开机
# 初期 joinGroup 瞬时失败是合理的 —— 旧代码正是靠退避重试自愈的。若把加入做成
# 「一次失败即永久放弃」，就表现为「手机突然永远搜不到、重启 App 才好」，
# 而所有闸门照样全绿（这正是本组守卫存在的理由）。所以加入必须带退避地在后台
# 重试，且**不阻塞**端口可用性（端口绑上即 isPortBound()=true）。
_rb = body_of(ssdp, 'public void run()')
report('SsdpResponder.run 方法体已找到', _rb is not None,
       '锚点：public void run()')
if _rb:
    # 判据必须看**条件方向**，不能只看「两个子串都在」：把 run() 里的 if/else 反转成
    # `if (!multicastJoined) { onMulticastJoined(); } else { startJoinRetry(); }` 后，
    # startJoinRetry() / onMulticastJoined() 两个子串**都还在**，只查「存在」的写法
    # 照样全绿（假阴性）—— 而那等于「广播早于 join（组播还没通，发出去没人收得到）
    # + 重试永不启动」= 手机永远搜不到，正是 T4/T4b 修的病的反面。
    # 所以抠出 `if (multicastJoined) {…} else {…}` 两个分支体，要求：
    #   正向分支里有 onMulticastJoined()，else 分支里有 startJoinRetry()。
    _rb_if, _rb_else = positive_if_else(_rb, 'multicastJoined')
    report('run() 未加入组播时启动后台重试（不是失败即放弃）',
           _rb_if is not None and _rb_else is not None
           and 'onMulticastJoined()' in _rb_if
           and 'startJoinRetry()' in _rb_else,
           'run() 有两条路径：已加入 → 立即广播 alive；未加入 → 交给后台重试。'
           '判据看的是**条件方向**（if (multicastJoined) 里广播、else 里重试），'
           '反转 if/else 必红 —— 只查子串存在会放行反转，joinGroup 一次失败就再没有'
           '第二次机会，正是老设备开机初期瞬时失败后「永久搜不到」的成因')
_sjr = body_of(ssdp, 'private void startJoinRetry()')
report('SsdpResponder.startJoinRetry 方法体已找到', _sjr is not None,
       '锚点：private void startJoinRetry()')
if _sjr:
    report('后台重试带退避地反复 joinMulticast（不是只试一次）',
           'joinMulticast(' in _sjr and 'while' in _sjr
           and 'RETRY_BASE_MS' in _sjr and 'RETRY_MAX_MS' in _sjr,
           '退避节奏复用绑定重试那一套（2 秒起、30 秒封顶、按 RETRY_LOG_EVERY 节流）—— '
           '写成一个循环、每次现取候选网卡，网络就绪后自愈；只试一次就等于放弃')
    # 赋值顺序：boundPort 必须**晚于** location —— 后台重试换上的网卡可能与
    # tryBindOnce() fallback 用的第一张候选**不同**，先置 boundPort 会让 isBound()
    # 提前为 true，而窗口内 location 还是 fallback 算出的**旧网卡地址**，
    # 于是 SSDP 应答把「旧网卡的 LOCATION」发出去 —— 正是「搜到了却投不了屏」。
    # 这个窗口纳秒级、单线程测试抓不到，只能靠读代码/守卫钉住（项目已咬过两次：
    # T4 的 localPort 早于 location、以及本处）。
    _m_loc = re.search(r'location\s*=', _sjr)
    _m_bp = re.search(r'boundPort\s*=', _sjr)
    report('startJoinRetry 里 boundPort 晚于 location 赋值（绑上⇒地址已可用）',
           _m_loc is not None and _m_bp is not None
           and _m_loc.start() < _m_bp.start(),
           '顺序必须与 tryBindOnce() 一致：先算 LOCATION，boundPort 最后。'
           '反过来就会开一个「isBound() 为 true、LOCATION 还是旧网卡」的窗口')
report('后台重试线程被 closeQuietly 回收（不漏线程）',
       'joinRetryThread' in ssdp and 'joinRetryThread' in cq
       and 'interrupt()' in cq,
       '重试线程可能卡在最长 30 秒的退避 sleep 里。不 interrupt 的话，服务销毁后'
       '它还挂着，醒来第一件事是拿一个已经关闭的 socket 去 joinGroup')

# isBound() 的两个条件必须都保留。回退分支（未加入组播时）现在也会置
# boundInterface（退回第一张候选网卡，好让「按 LOCATION 抓描述」这条链路先走完），
# 所以「只查 boundInterface」会假阳性 —— 端口还没绑上就报「已就绪」。
_ib = body_of(ssdp, 'public boolean isBound()')
report('SsdpResponder.isBound 方法体已找到', _ib is not None,
       '锚点：public boolean isBound()')
if _ib:
    report('isBound() 同时要求「端口已绑」与「网卡已选」（缺一不可）',
           'boundPort' in _ib and 'boundInterface' in _ib
           and re.search(r'boundPort\s*>\s*0', _ib) is not None
           and re.search(r'boundInterface\s*!=\s*null', _ib) is not None,
           '回退分支现在也会置 boundInterface，所以「只查 boundInterface」'
           '会让 isBound() 在端口还没绑上时也返回 true —— 界面据此报「设备已就绪」，'
           '而实际一个搜索请求都收不到。两个条件必须都在')

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

# ---- (11) 手机投屏的两个实测现象 ----
# 用户真机报的两句：「投视频投上去了却没有画面」「投照片切了好几张电视还是原来那张」。
# 它们的共同点是**编译、运行、日志全都正常**，只是功能悄悄失效 ——
# 正是源码级守卫存在的理由。
#
#   ·「照片不跟手」：连投多张照片时形态从头到尾都是 MODE_IMAGE，
#     applyModeIfChanged() 首句 mode == lastMode 就早退 —— 形态没变，
#     applyMode() 里的 loadImage() 一次都不会被调到，电视永远停在第 1 张。
#
#   ·「视频没有画面」：控制点不给 upnp:class、媒体服务器把 Content-Type 报成
#     application/octet-stream、而本机 getVideoWidth() 恒返回 0 —— 三路信号全哑，
#     一个真视频被判成「纯音频」，音乐卡片把画面整个盖住。
rf = body_of(main_act, 'private void refresh()')
report('MainActivity.refresh 方法体已找到', rf is not None,
       '锚点：private void refresh()')
if rf:
    report('图片形态每个 tick 都重问片源（同一个形态里也要跟上换图）',
           re.search(r'mode\s*==\s*MODE_IMAGE[\s\S]{0,200}?'
                     r'loadImage\(service\.getCurrentUri\(\)\)', rf) is not None,
           '连投几张照片时形态恒为 MODE_IMAGE，而 applyModeIfChanged() 只在形态'
           '真的变了时才调 applyMode()（首句 mode == lastMode 直接 return）—— '
           '于是 loadImage() 一次都不会被调到，电视永远停在第 1 张'
           '（用户报的「切了好几张还是原来那张」）。'
           '去重交给 loadImage() 自己（imageUri / imageLoadingUri），不会重复下载')

li2 = body_of(main_act, 'private void loadImage(String uri)')
si2 = body_of(main_act, 'private void showImage(String uri, Bitmap bmp)')
ri2 = body_of(main_act, 'private void releaseImage()')
report('失败的图片地址被记住，不再每 0.5 秒重试一次（三处接线）',
       li2 is not None and 'imageFailedUri' in li2
       and si2 is not None
       and re.search(r'bmp\s*==\s*null[\s\S]{0,200}?imageFailedUri\s*=', si2) is not None
       and ri2 is not None and 'imageFailedUri = null' in ri2,
       '三处缺一不可：① loadImage 的早退条件里要带上它；② showImage 的失败分支'
       '要记下这个地址；③ releaseImage 要清掉它（不然回空闲后再投同一张图永远'
       '显示不出来）。缺 ① 或 ②：refresh() 每 0.5 秒又问一次，真机实测坏地址'
       '15 秒内发了 30 次请求、弹了 30 次 Toast —— 0.6GB 的盒子上是纯粹的浪费')

ko = body_of(svc, 'private static int kindOfAny(String uri, String metadata)')
report('形态判定：元数据判不出来时按地址扩展名兜底',
       ko is not None and 'kindOfUrl(uri)' in ko
       and re.search(r'kind\s*!=\s*KIND_UNKNOWN[\s\S]{0,80}?return\s+kind',
                     ko) is not None,
       '锚点：private static int kindOfAny(String uri, String metadata)。'
       '必须先让元数据说话，判不出来才轮到扩展名 —— 顺序反了会把控制点'
       '明确声明过的形态顶掉（明明给了 upnp:class: audioItem，却因为地址像视频'
       '而当成视频）')
report('onSetUri 与 onSourceChanged 都走 kindOfAny（两条入口同一判据）',
       'kindOfAny(uri, metadata)' in svc
       and 'kindOfAny(uri, currentMetadata)' in svc,
       '只改一条的话：正常投屏（onSetUri）对了，而重投 / 续播接棒'
       '（onSourceChanged）仍按老判据把视频判成纯音频 —— '
       '表现成"有时行、有时不行"，是最难查的那种形态')
ex = body_of(svc, 'private static String extensionOf(String name)')
report('extensionOf 支持 URL：先切查询串/锚点，再只认合法扩展名',
       ex is not None
       and re.search(r"indexOf\('\?'\)[\s\S]{0,120}?substring\(0,\s*cut\)",
                     ex) is not None
       and re.search(r"indexOf\('#'\)[\s\S]{0,120}?substring\(0,\s*cut\)",
                     ex) is not None
       and re.search(r"c\s*>=\s*'a'\s*&&\s*c\s*<=\s*'z'", ex) is not None
       and re.search(r'ext\.length\(\)\s*>\s*5', ex) is not None,
       '不切 ? / # 的话 …/a.mp4?token=xx 取出来是 mp4?token=xx；'
       '不限字形与长度的话 http://h/1.2/video 会取出 "2/video"、'
       'http://h/video.2019 会取出 "2019" —— 都会把非视频地址误判成视频。'
       '（本条第一版只查 indexOf(\'#\') 在不在，把 substring(0, cut) 改成 '
       'substring(0, 0) 照样绿 —— 证伪时抓出来的，所以现在连"切法"一起钉）')
report('isVideoName 收录常见容器，且刻意不收 m3u8',
       'private static boolean isVideoName(String name)' in svc
       and '"mp4".equals(ext)' in svc and '"m3u8"' not in svc,
       '扩展名兜底靠的就是这张表，漏掉 mp4 等于没兜底；'
       'm3u8（HLS）是**刻意**不收的 —— 它既可能是视频、也可能是纯音频网络电台，'
       '而猜错方向的代价不对称：视频被判成音频时，音乐卡片把画面整个盖住')

# ---- (12) 视频「有声音没画面」：判据必须走厂商 info 事件 ----
# 用户真机报：「投视频投上去了，电视上一片纯黑、声音正常」。
# 第一版修复用的是 getVideoWidth() —— 当时误以为"画面解出来才 > 0"，结果**被反面
# 控制抓出误报**：同一台盒子上 H.264（画面完全正常、厂商侧日志有
# VIDEO_FMT_UPDATE 960x540）@2s/@5s 读出来也是 0x0。画面走厂商硬件图层，
# 框架压根不知道尺寸。实测对照表见 PlaybackPolicy.INFO_VIDEO_CODEC_NOT_SUPPORT。
# 下面这组守卫就是"不许再退回那个判据"。
ctrl = pathlib.Path('app/src/main/java/com/juping/cast/player/MediaPlayerController.java'
                    ).read_text(encoding='utf-8')
ctrl = strip_comments(ctrl)
pol = pathlib.Path('app/src/main/java/com/juping/cast/player/PlaybackPolicy.java'
                   ).read_text(encoding='utf-8')
pol = strip_comments(pol)

report('画面判据：只有厂商 info 码 0x8003 这一个信号，且判据留在纯逻辑层',
       re.search(r'INFO_VIDEO_CODEC_NOT_SUPPORT\s*=\s*0x8003', pol) is not None
       and re.search(r'isVideoCodecUnsupportedInfo\(int what\)\s*\{'
                     r'[\s\S]{0,160}?what\s*==\s*INFO_VIDEO_CODEC_NOT_SUPPORT',
                     pol) is not None,
       '判据放 PlaybackPolicy 是为了能离线跑断言（与错误分类同一套做法）；'
       '码值 0x8003 是 MTK 私有区间（标准 MEDIA_INFO_* 最大只到 802），'
       '真机实测 HEVC 每次发、H.264 从不发')

report('控制器：装了 OnInfoListener 且真的把事件转出去',
       'setOnInfoListener' in ctrl
       and re.search(r'isVideoCodecUnsupportedInfo\(what\)[\s\S]{0,400}?'
                     r'onVideoCodecUnsupported\(', ctrl) is not None,
       '缺监听：厂商把「画面编码解不了」当 info 发（不中断播放、不走 onError），'
       '不监听就什么都收不到，用户看到的仍是一块没有任何解释的黑屏；'
       '装了不转发：服务层拿不到消息，界面照样不吭声')

report('不许再用 getVideoWidth() 当「画面出没出来」的判据（第一版误报的教训）',
       'hasVideoFrames' not in ctrl and 'hasVideoFrames' not in svc
       and 'VIDEO_MISSING_GRACE_MS' not in pol
       and 'scheduleVideoMissingCheck' not in svc,
       '这三样是"按时间观察 getVideoWidth()"那版修复的化石。它在本机上必然误报：'
       'H.264 也恒为 0x0，于是正常视频一播到 4 秒就被扣上"编码不支持"。'
       '这条守卫是**防止有人看它"读起来更直白"又改回去**')

uni = body_of(svc, 'public void onVideoCodecUnsupported(String detail)')
report('厂商事件只在元数据判成视频时才采纳',
       uni is not None
       and re.search(r'kindFromMetadata\s*!=\s*KIND_VIDEO[\s\S]{0,80}?return',
                     uni) is not None
       and 'videoMissing = true' in uni
       and 'notifyEvent("AVTransport")' in uni,
       '音频流编码不支持时厂商发的是**同一个码** —— 不加这道闸就会对着一个'
       '"有画面没声音"的片子说"画面编码不支持"，比不报还糟；'
       '不发事件则控制点与界面都不知道状态变了')

report('换片两条入口都清掉「画面出不来」',
       re.search(r'kindFromMetadata\s*=\s*kindOfAny\(uri, metadata\)'
                 r'[\s\S]{0,600}?videoMissing = false', svc) is not None
       and re.search(r'kindFromMetadata\s*=\s*kindOfAny\(uri, currentMetadata\)'
                     r'[\s\S]{0,300}?videoMissing = false', svc) is not None,
       'onSetUri 与 onSourceChanged 缺一不可：只清一条的话，投完一段解不了的'
       'HEVC 再自动续播下一部（或换片），新片源一上来就顶着上一部的黑屏告警')

tb = body_of(main_act, 'private void applyTopBar(int mode)')
report('顶条：「画面出不来」要出现、且与出错一样亮红点',
       tb is not None
       and re.search(r'noPicture\s*=\s*service\.isVideoMissing\(\)', tb) is not None
       and re.search(r'show\s*=\s*error \|\| noPicture', tb) is not None
       and re.search(r'if \(error \|\| noPicture\)', tb) is not None,
       '不参与 show：顶条根本不出现，黑屏上还是一个字都没有（第一版就是'
       '只在 idle/暂停时出现，等于白写）；不亮红点：绿点会说"一切正常"，'
       '跟右边那句自相矛盾')

rf = body_of(main_act, 'private void refresh()')
report('顶条文案：画面出不来时报原因，且真报错时不抢',
       rf is not None
       and re.search(r'if \(!error && service\.isVideoMissing\(\)\)\s*\{'
                     r'[\s\S]{0,120}?playingText\.setText\('
                     r'R\.string\.hint_video_unsupported\)', rf) is not None,
       '这时进度条上的时间对用户毫无意义（屏幕一片黑，他要的是"为什么黑"），'
       '所以让原因压过进度；但前面那个 !error 不能去掉 —— '
       '真正的故障有它自己的话要说，拿"编码不支持"去替它背锅会把人带偏')

strings = pathlib.Path('app/src/main/res/values/strings.xml'
                       ).read_text(encoding='utf-8')
report('「编码不支持」那句话必须给出可执行的动作',
       'hint_video_unsupported' in strings and 'H.264' in strings,
       '只说"不支持"等于没说：用户不会知道下一步该干什么，'
       '只会以为盒子坏了')

report('dex 核查表收录 OnInfoListener（否则闸门报「没被核到」而红）',
       'MediaPlayer$OnInfoListener'
       in pathlib.Path('tools/check_dex_entrypoints.py').read_text(encoding='utf-8'),
       'R8 会改实现类的名字，不列入核查表就核不到这个方法 —— '
       '而它一旦被裁掉，厂商事件永远送不过来')

# ---- (13) 视频蓝屏修复（F1 去 reset / F2 阈值 / MODE_VIDEO_PENDING 占位层）----
# 真机 A/B 定案：releasePlayer 的 reset() 触发厂商异步 reset_nosync，污染新实例
# 的 prepareAsync（"already reset" 空操作）→ 投视频先蓝屏 30 秒。
# 这组守卫钉的是「修好的东西别被改回去」—— 设计见 `.agent/video-bluescreen-plan.md`。
rlp = body_of(ctrl, 'private void releasePlayer()')
report('releasePlayer 已找到且不含 player.reset()（F1 的直接回归判据）',
       rlp is not None and 'player.reset(' not in rlp,
       '锚点：private void releasePlayer()。reset() 一旦被加回来，换片必现'
       '「prepareAsync 空操作 → 蓝屏到看门狗重建」—— 这是本 bug 的竞态源头，'
       '实例释放后从不复用，reset() 没有任何留存理由')
report('releasePlayer 仍然 release 并置空（去 reset 不等于不释放）',
       rlp is not None and 'player.release()' in rlp and 'player = null' in rlp,
       '只防「加回 reset」不防「顺手删 release」的话，实例泄漏在 0.6GB 盒子上'
       '比蓝屏更糟')
report('prepare 卡死阈值已降到 10s（F2 安全网，不再是 30s）',
       re.search(r'PREPARE_STUCK_REBUILD_MS\s*=\s*10000L', pol) is not None
       and re.search(r'SWITCH_SETTLE_MS\s*=\s*500L', pol) is not None,
       '确认的失败模式是 prepareAsync 空操作（0ms 真实工作）—— 10s 足够宽；'
       '改回 30s 等于把中招时的用户等待再放大 3 倍。SWITCH_SETTLE_MS 是 F3 '
       '备用常量（真机验收前刻意不接线），漂移即红')
report('控制器暴露 isPreparing()（判据的原料在服务之外不可见）',
       re.search(r'public boolean isPreparing\(\)\s*\{[^}]*return preparing;',
                 ctrl) is not None,
       '界面的「视频准备中」全靠它；被裁掉的话占位层永不出现，蓝屏直接露出来')
vp = body_of(svc, 'public boolean isVideoPending()')
report('服务侧「视频准备中」判据用 isPreparing()，且绝不拿 getDuration()==0 凑',
       vp is not None and 'isPreparing()' in vp and 'getDuration' not in vp
       and 'isAudioOnly()' in vp and 'isImage' not in vp,
       'HLS 直播的时长恒为 0 —— 用它当判据会把正常播放的直播永远判成准备中，'
       '占位层再也撤不掉（铁律：判据必须会终结）。isImage 不在判据里是因为'
       'pending 只在「判成视频」之后才会被问到，但反向依赖 audioOnly 必须显式排除')
cm = body_of(main_act, 'private int currentMode()')
report('形态机：视频且准备中 → MODE_VIDEO_PENDING，图片优先判仍在',
       cm is not None and 'MODE_VIDEO_PENDING' in cm
       and re.search(r'lastPlayingMode\s*==\s*MODE_VIDEO\s*&&\s*'
                     r'service\.isVideoPending\(\)', cm) is not None
       and 'isImage()' in cm,
       'pending 分支必须排在 lastPlayingMode 赋值**之后**（宽限/退后台判据'
       '仍按 MODE_VIDEO 记账）；图片判据一旦被它吃掉，投图会显示'
       '「正在准备视频…）」')
am = body_of(main_act, 'private void applyMode(int mode)')
report('占位层接线：pending 藏 SurfaceView + 亮 video_wait',
       am is not None and 'videoWait.setVisibility(' in am
       and 'MODE_VIDEO_PENDING' in am
       and re.search(r'boolean videoPending\s*=\s*\(mode == MODE_VIDEO_PENDING\)',
                     am) is not None
       and re.search(r'surfaceView\.setVisibility\(\s*\(\s*idle \|\| image'
                     r'\s*\|\| videoPending\s*\)', am) is not None,
       '可见性表达式少了 videoPending：准备窗口 SurfaceView 保持可见，'
       '空视频层在老 MTK 上直接输出蓝 —— 整套修复只剩占位层被压在蓝底下')
wsp = re.search(r'boolean wasPlaying\s*=([\s\S]{0,200}?\);)', main_act)
report('退后台宽限把 VIDEO_PENDING 计入 wasPlaying（§8.4 的坑）',
       wsp is not None and 'MODE_VIDEO_PENDING' in wsp.group(1),
       'pending 期间投屏失败回 IDLE 时，漏计会跳过 2.5s 延迟退后台 —— '
       '同一帧切后台把蓝色视频帧粘在 launcher 上（MTK 实测老坑）')
lay = pathlib.Path('app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
vw = re.search(r'android:id="@\+id/video_wait"([\s\S]{0,600}?)>', lay)
report('占位层存在且背景不透明（@color/bg）',
       vw is not None and '@color/bg' in vw.group(1),
       '透明的占位层等于没盖 —— 老 MTK 的空视频层蓝色会直接透出来，'
       '这是 music/panel/image 三层同一纪律（布局注释里写了三遍）')
report('strings.xml 有「正在准备视频」占位文案',
       'name="video_wait"' in strings,
       '布局里 @string/video_wait 引用它，缺了会编译期红 —— 钉这条是为了'
       '让「删文案」这种回退在闸门而不是装机时暴露')

# ---- 版本纪律（二夜定的规矩：每次 dist 构建必须升版本）----
build_sh = pathlib.Path('tools/build.sh').read_text(encoding='utf-8')
report('build.sh dist 有版本硬闸（同版本连出两包直接红）',
       'VERSION_STATE="dist/.last-build-version"' in build_sh
       and '[ -f "$VERSION_STATE" ] && [ "$(cat "$VERSION_STATE")" = "$VER" ]'
           in build_sh
       and '版本号没更新' in build_sh,
       '0.2.4 曾连出两个 dist 包（批 3.9 + modeName 修复），装机上分不清跑的'
       '哪个 —— 硬闸删掉的话这条纪律就只剩口头约定，必然再犯')
_gw = build_sh.find('verify_secrets', build_sh.find('    dist)'))
_ws = build_sh.find('echo "$VER" > "$VERSION_STATE"', build_sh.find('    dist)'))
report('版本状态文件在密钥核查之后才写（失败构建不占版本号）',
       _gw != -1 and _ws != -1 and _ws > _gw,
       'collect 后就写的话，一次中途红掉的构建会把这个版本号烧掉 —— '
       '修好重跑会被自己的闸门拦下，逼人手动升版，纪律变成惩罚')
report('build.sh 有 version 子命令（patch/minor/major 自动升，免手改两行）',
       'version)                   NEED_JAVA=0' in build_sh
       and 'LEVEL="${2:-patch}"' in build_sh
       and 'versionName "%d.%d.%d"' in build_sh
       and 'versionCode %d' in build_sh,
       '硬闸只堵不疏的话，每次升版都得手改 build.gradle 两行（改漏一边就是'
       ' versionName 没动 / versionCode 没 +1）—— 子命令让升版是一步动作')

sys.exit(1 if failed else 0)
PY

echo
if [ "$RC" -ne 0 ]; then
    echo "播放策略核验未通过。" >&2
fi
exit "$RC"
