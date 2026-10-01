#!/usr/bin/env bash
#
# 网页上传解析与文件名规整测试
# ---------------------------------------------------------------
# MultipartLite（流式 multipart 解析）与 LocalStore.sanitize（文件名规整）都是
# 纯逻辑、零 Android 运行时依赖 —— 直接编到桌面 JVM 上跑，不需要真机。
#
# 断言的 oracle 只有两条，都很硬：
#   · 解析出来的文件内容必须与写进去的**逐字节相同** —— 差一个字节，用户传上去的
#     视频就是坏的，而且往往要播到那一段才发现（花屏 / 末尾多一截垃圾）；
#   · 规整后的名字必须**只可能是本目录内的一个普通名字** —— 这个接口没有鉴权、
#     名字完全由请求方给，规整一旦失效就能读到应用私有数据。
#
# 除了断言，还做两条源码级不变量守卫：上传页不许引外部资源（盒子没有外网，
# 引一个 CDN 上的框架就是整页白屏），以及页面上的按钮背后都要有真路由。
#
# 用法：./tools/web-test/run.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

# JDK 的定位收敛到 tools/lib.sh，与 protocol-test / policy-test / proxy-test 同一顺序
. "$HERE/../lib.sh"
resolve_java_home
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "找不到 javac（需要 JDK 17）" >&2
    exit 2
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT INT TERM

WEB="app/src/main/java/com/juping/cast/web"

# android 的两个替身只为了让 LocalStore 编得过 —— 断言的是它的静态方法
if ! "$JAVA_HOME/bin/javac" -nowarn -encoding UTF-8 -d "$OUT" \
        "$WEB/MultipartLite.java" "$WEB/LocalStore.java" \
        "$HERE/android/util/Log.java" "$HERE/android/content/Context.java" \
        "$HERE/WebTest.java" 2>"$OUT/javac.err"; then
    echo "编译失败：" >&2
    cat "$OUT/javac.err" >&2
    exit 2
fi

RC=0
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$OUT" WebTest || RC=$?

# ── 源码级不变量（单元测试挡不住的那类回归）──
echo
echo "── 源码级不变量（单元测试挡不住的那类回归）──"

python3 - "$WEB/WebCastEndpoints.java" <<'PY' || RC=1
import re, sys, pathlib

src = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
failed = []

def report(name, ok, detail=''):
    print('  [%s] %s%s' % ('PASS' if ok else 'FAIL', name,
                           ('\n         ' + detail) if detail else ''))
    if not ok:
        failed.append(name)

# ① 上传页不许引外部资源。这个页面是要给手机浏览器用的，而盒子没有外网；
#    引一个 CDN 上的框架，整页就白屏 —— 而白屏在服务端日志里看不出任何异常。
i = src.find('private static final String PAGE')
report('上传页锚点已找到', i >= 0, '锚点：private static final String PAGE')
page = src[i:] if i >= 0 else ''
bad = re.findall(r'https?://|//(?:cdn|unpkg|jsdelivr)', page)
report('上传页无外部资源引用（盒子无外网，引一个就白屏）', not bad,
       '命中：%s' % bad[:3])

# ② 页面上的每个按钮背后都要有真路由 —— 防「页面有删除按钮、服务端没这条路」
#    这种漂移：点了没反应，浏览器控制台也不报错。
routes = ['"/upload".equals(path)', '"/files".equals(path)',
          '"/cast".equals(path)', '"/delete".equals(path)']
missing = [r for r in routes if r not in src]
report('上传页用到的四个端点都有路由', not missing, '缺：%s' % missing)

if failed:
    sys.exit(1)
PY

exit $RC