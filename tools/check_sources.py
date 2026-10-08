#!/usr/bin/env python3
"""Java / XML 源码结构检查器（不需要 JDK 或 Android SDK）。

在没有 Android 工具链的机器上，用来快速发现低级但致命的错误：
  1. 括号不平衡（漏闭合是编译失败最常见的原因）
  2. 缺少 package 声明 —— **只查源根 `src/*/java/` 下的文件**。
     `tools/` 下的测试脚手架是「单文件直接编译」的（run.sh 把文件名逐个传给
     javac），**本来就该在默认包**，硬加 package 反而要改调用方式，故豁免。
     （2026-10-08 修：这条规则原先对所有 .java 一视同仁，于是对 5 个脚手架文件
     常年误报、本工具永久 RC=1，没人愿意用 —— 它真正的价值跟着一起废掉。）
  3. public 类型名与文件名不一致（Java 硬性要求）
  4. 大括号内方法定义重复
  5. XML 资源是否可解析

用法:
    python3 tools/check_sources.py            # 检查当前目录
    python3 tools/check_sources.py <项目根目录>

注意：第 2 条依赖路径里能看出源根。**从仓库根目录跑**（或任何路径里含
`src/<名字>/java/` 的目录）才会核 package；在 `app/src/main/java` 内部跑会
识别不出源根、整条跳过。其余 4 条与路径无关，在哪跑都生效。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

PAIRS = {')': '(', ']': '[', '}': '{'}
OPENERS = set('([{')

# 源根判定：`src/<任意一层>/java/`。Gradle / Maven 的标准布局都是这个形状
# （本项目是 app/src/main/java/）。只有这种文件才要求 package 声明。
JAVA_SRC_ROOT = re.compile(r'(^|[/\\])src[/\\][^/\\]+[/\\]java[/\\]')


def in_java_source_root(path):
    return JAVA_SRC_ROOT.search(path) is not None


def strip_code(src):
    """把字符串字面量、字符字面量和注释替换成等长空白，保留位置信息。"""
    out = []
    i = 0
    n = len(src)
    state = 'code'
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if state == 'code':
            if c == '/' and nxt == '/':
                state = 'line_comment'
                out.append('  ')
                i += 2
                continue
            if c == '/' and nxt == '*':
                state = 'block_comment'
                out.append('  ')
                i += 2
                continue
            if c == '"':
                state = 'string'
                out.append(' ')
                i += 1
                continue
            if c == "'":
                state = 'char'
                out.append(' ')
                i += 1
                continue
            out.append(c)
            i += 1
        elif state == 'line_comment':
            if c == '\n':
                state = 'code'
                out.append('\n')
            else:
                out.append(' ')
            i += 1
        elif state == 'block_comment':
            if c == '*' and nxt == '/':
                state = 'code'
                out.append('  ')
                i += 2
                continue
            out.append('\n' if c == '\n' else ' ')
            i += 1
        elif state in ('string', 'char'):
            if c == '\\':
                out.append('  ')
                i += 2
                continue
            if (state == 'string' and c == '"') or (state == 'char' and c == "'"):
                state = 'code'
                out.append(' ')
                i += 1
                continue
            out.append('\n' if c == '\n' else ' ')
            i += 1
    return ''.join(out)


def check_braces(clean, path, problems):
    stack = []
    line = 1
    for ch in clean:
        if ch == '\n':
            line += 1
        elif ch in OPENERS:
            stack.append((ch, line))
        elif ch in PAIRS:
            if not stack:
                problems.append("%s:%d 多余的 '%s'" % (path, line, ch))
                continue
            opener, oline = stack.pop()
            if opener != PAIRS[ch]:
                problems.append(
                    "%s:%d '%s' 与第 %d 行的 '%s' 不匹配" % (path, line, ch, oline, opener))
    for opener, oline in stack:
        problems.append("%s:%d '%s' 未闭合" % (path, oline, opener))


def mask_anonymous_classes(clean):
    """把匿名内部类的内容整体掩成空白。

    否则 `new Runnable() { public void run() {...} }` 里的 run()
    会被当成外层类的重复方法定义，产生误报。
    """
    out = list(clean)
    for m in re.finditer(r'\bnew\s+[\w.]+(?:<[^<>]*>)?\s*\([^()]*\)\s*\{', clean):
        start = m.end() - 1
        depth = 0
        i = start
        while i < len(clean):
            if clean[i] == '{':
                depth += 1
            elif clean[i] == '}':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        for j in range(start, min(i + 1, len(clean))):
            if out[j] != '\n':
                out[j] = ' '
    return ''.join(out)


def check_java(path, problems):
    """返回 True 表示这个文件按「源根下的文件」核过 package 声明。"""
    with open(path, encoding='utf-8') as f:
        src = f.read()
    clean = strip_code(src)

    check_braces(clean, path, problems)

    base = os.path.splitext(os.path.basename(path))[0]
    pkg_checked = in_java_source_root(path)
    if pkg_checked and not re.search(r'^\s*package\s+[\w.]+\s*;', src, re.M):
        problems.append("%s 缺少 package 声明（源根 src/*/java/ 下的文件必须有）" % path)

    # public 类型名必须与文件名一致
    m = re.search(r'\bpublic\s+(?:final\s+|abstract\s+)?(?:class|interface|enum)\s+(\w+)', clean)
    if m and m.group(1) != base:
        problems.append("%s public 类型为 '%s'，与文件名 '%s' 不一致（Java 不允许）"
                        % (path, m.group(1), base))

    # 同一文件内重复的方法签名（粗查，忽略重载以外的明显冲突）
    # 先掩掉匿名内部类，否则 new Runnable(){ public void run(){} } 会被误判
    sigs = re.findall(
        r'\b(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\],.\s?]+\s+(\w+)\s*\(([^)]*)\)\s*\{',
        mask_anonymous_classes(clean))
    seen = {}
    for name, params in sigs:
        ptypes = ','.join(p.strip().split()[0] for p in params.split(',') if p.strip())
        key = name + '(' + ptypes + ')'
        if key in seen:
            problems.append("%s 疑似重复定义方法 %s" % (path, key))
        seen[key] = True

    return pkg_checked


def check_xml(path, problems):
    try:
        ET.parse(path)
    except Exception as e:
        problems.append("%s XML 解析失败: %s" % (path, e))


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    problems = []
    java_count = xml_count = pkg_checked = 0

    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ('build', '.gradle', '.idea')]
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            if fn.endswith('.java'):
                java_count += 1
                if check_java(p, problems):
                    pkg_checked += 1
            elif fn.endswith('.xml'):
                xml_count += 1
                check_xml(p, problems)

    print("扫描 %d 个 .java 文件、%d 个 .xml 文件" % (java_count, xml_count))
    # 把豁免**显式打出来** —— 静默豁免等于悄悄放宽判据，出了事没人知道判据长什么样
    print("  其中 %d 个在源根 src/*/java/ 下，按规则核了 package 声明；"
          "其余 %d 个（tools/ 脚手架等）豁免该规则。"
          % (pkg_checked, java_count - pkg_checked))
    if problems:
        print("\n发现 %d 个问题：" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1
    print("结构检查通过：括号平衡、package 声明、类型名与文件名一致、XML 可解析。")
    return 0


if __name__ == '__main__':
    sys.exit(main())
