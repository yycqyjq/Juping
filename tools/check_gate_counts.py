#!/usr/bin/env python3
"""闸门计数守卫：文档里手写的「用例总数」，必须和闸门里的期望值对得上。

为什么需要它
-----------
五道闸门的用例总数散落在 README.md 与 .agent/AGENTS.md 里**手写同步**，
靠人肉必然漂。本轮之前就出过漂移：proxy 用例 10→11 时两处文档漏改
（QA 跑测试才发现），README 里还留着一句样本输出写着 `协议一致性：219 / 219`
（实际早就是 237）。「全部通过」和「该测的都测了」是两回事：断言被静默
删掉，闸门照样全绿，而文档里的数字还停在旧值上。

规范值的唯一出处
---------------
本脚本**不抄任何字面量**，而是把「闸门里那句比较用的期望字符串」读出来当规范值：
  · 协议总数 ← tools/build.sh 的 `协议一致性：N / N`
  · 代理总数 ← tools/build.sh 的 `代理一致性：N / N`
  · 网页总数 ← tools/build.sh 的 `网页逻辑：N / N`
  · probe 总数 ← tools/protocol-test/run.sh 的 `控制点自检：N / N 通过`（双态）
  · 策略断言/守卫 ← 本次策略测试的实际输出（argv[1]）
改期望值只需改闸门那一处；文档跟不上就红。

判据
----
每个 (文档, 闸门) 锚点是一条**窄正则**——「这句话就是在声明这个数」的固定措辞。
为什么用窄正则而不是「抓文档里所有数字」：README 里到处是数字（端口、时间码、
证伪实验里的「4 条断言」），宽抓必然一堆假阳性 —— 检查器喊狼来了，跟不检查
一样糟（旧脚本的注释已经踩过这个坑）。锚点匹配不到、或匹配到的值与规范值不符，
都算漂移；文档改了措辞，这里红了就跟着改锚点，顺带逼人确认数字本身没漂。

用法：check_gate_counts.py <策略测试的完整输出文件>
"""
import pathlib
import re
import sys

PASS_LINE = re.compile(r'^\s*\[PASS\]', re.M)
SUMMARY = re.compile(r'播放策略：(\d+)\s*/\s*(\d+)\s*通过')

# 文档里的措辞不完全统一：README 写「57 项断言 / 265 条源码级守卫」，
# AGENTS.md 写「57 断言 / 265 源码级守卫」。量词「项」可有可无，容错匹配。
#
# 但**刻意不允许「条」**：README 里另有「4 条断言立刻变红」这类描述**证伪实验**
# 的句子，那是"破坏几条断言去验证测试本身是活的"，跟总数不是一回事 ——
# 放开「条」会把它们当成声明值，报一堆假阳性（喊狼来了跟不检查一样糟）。
DOC_ASSERTS = re.compile(r'(\d+)\s*(?:项)?\s*断言')
DOC_GUARDS = re.compile(r'(\d+)\s*(?:条)?\s*源码级守卫')

DOCS = ('README.md', '.agent/AGENTS.md')

BUILD = 'tools/build.sh'
PROTOCOL_RUN = 'tools/protocol-test/run.sh'

# (闸门, 文档, 窄正则) —— 正则里的**每个捕获组**都是一个被声明的数。
# 单值闸门：匹配到的每个数都必须等于规范值。
# 双态闸门（probe）：匹配到的每个数都必须是合法态之一（33 / 34）。
ANCHORS = (
    # ---- 协议 237 ----
    ('protocol', 'README.md',        r'协议一致性：(\d+) / \d+ 通过'),
    ('protocol', 'README.md',        r'drive\.py\s+(\d+) 项一致性'),
    ('protocol', 'README.md',        r'协议 (\d+) 项通过'),
    ('protocol', '.agent/AGENTS.md', r'协议一致性 (\d+) 项'),
    ('protocol', '.agent/AGENTS.md', r'期望 (\d+)/\d+'),
    # ---- 代理 11 ----
    ('proxy', 'README.md',        r'代理字节一致性测试（(\d+) 项）'),
    ('proxy', 'README.md',        r'代理字节一致性 (\d+) 项通过'),
    ('proxy', '.agent/AGENTS.md', r'代理字节一致性（(\d+) 项）'),
    ('proxy', '.agent/AGENTS.md', r'字节一致性 (\d+) 项'),
    # ---- probe 33/34（双态）----
    ('probe', 'README.md',        r'自检脚本 (\d+) 或 (\d+) 项'),
    ('probe', '.agent/AGENTS.md', r'直连降级，(\d+)/(\d+) 项'),
    ('probe', '.agent/AGENTS.md', r'自检（\*\*(\d+) 或 (\d+) 双态'),
    # ---- 网页 32 ----
    ('web', 'README.md',          r'网页逻辑一致性测试（(\d+) 项）'),
    ('web', 'README.md',          r'网页逻辑一致性 (\d+) 项通过'),
    ('web', '.agent/AGENTS.md',   r'网页逻辑一致性（(\d+) 项）'),
    ('web', '.agent/AGENTS.md',   r'网页逻辑一致性 (\d+) 项'),
)


def gate_expectations():
    """从闸门脚本里读出规范值（唯一出处，本脚本不自己写数字）。"""
    build = pathlib.Path(BUILD).read_text(encoding='utf-8')
    m = re.search(r'协议一致性：[0-9]+ / ([0-9]+)', build)
    protocol = int(m.group(1)) if m else None
    m = re.search(r'代理一致性：[0-9]+ / ([0-9]+)', build)
    proxy = int(m.group(1)) if m else None

    m = re.search(r'网页逻辑：[0-9]+ / ([0-9]+)', build)
    web = int(m.group(1)) if m else None

    run = pathlib.Path(PROTOCOL_RUN).read_text(encoding='utf-8')
    probe = sorted({int(x) for x in re.findall(r'控制点自检：[0-9]+ / ([0-9]+) 通过', run)})
    return {'protocol': protocol, 'proxy': proxy, 'web': web, 'probe': probe}


def stated_policy(path):
    """从文档里抽出所有被声明过的断言数 / 守卫数（去重后的集合）。"""
    text = pathlib.Path(path).read_text(encoding='utf-8')
    asserts = {int(x) for x in DOC_ASSERTS.findall(text)}
    guards = {int(x) for x in DOC_GUARDS.findall(text)}
    return asserts, guards


def check_policy(out, drift):
    total = len(PASS_LINE.findall(out))
    m = SUMMARY.search(out)
    if not m:
        print('找不到「播放策略：N / N 通过」汇总行 —— 无法核对计数', file=sys.stderr)
        return None
    asserts = int(m.group(1))
    guards = total - asserts
    if asserts <= 0 or guards <= 0:
        print('解析异常：断言 %d，守卫 %d，总 [PASS] %d' % (asserts, guards, total),
              file=sys.stderr)
        return None

    for doc in DOCS:
        if not pathlib.Path(doc).exists():
            drift.append('%s 不存在 —— 计数没有单一事实来源' % doc)
            continue
        a, g = stated_policy(doc)
        if not a:
            drift.append('%s 里找不到「N 项断言」可核对' % doc)
        if not g:
            drift.append('%s 里找不到「N 条源码级守卫」可核对' % doc)
        for v in sorted(a):
            if v != asserts:
                drift.append('%s 写了 %d 项断言，实际 %d（漂移 %+d）'
                             % (doc, v, asserts, v - asserts))
        for v in sorted(g):
            if v != guards:
                drift.append('%s 写了 %d 条源码级守卫，实际 %d（漂移 %+d）'
                             % (doc, v, guards, v - guards))
    return asserts, guards


def check_totals(expect, drift):
    """协议 / 代理 / probe 的总数：文档锚点 vs 闸门期望值。"""
    lines = []
    for gate, doc, pattern in ANCHORS:
        raw = expect[gate]
        if not raw:
            drift.append('读不出 %s 的期望值（%s 里那句比较字符串可能被改了）'
                         % (gate, BUILD))
            continue
        # 单值闸门存的是 int，双态闸门（probe）存的是 list —— 统一成 list 再判
        allowed = raw if isinstance(raw, list) else [raw]
        if not pathlib.Path(doc).exists():
            drift.append('%s 不存在' % doc)
            continue
        text = pathlib.Path(doc).read_text(encoding='utf-8')
        hits = re.findall(pattern, text)
        if not hits:
            drift.append('%s 里找不到锚点 %r —— 措辞改了就把这条锚点一起改' % (doc, pattern))
            continue
        for h in hits:
            groups = h if isinstance(h, tuple) else (h,)
            for g in groups:
                v = int(g)
                if v not in allowed:
                    drift.append('%s 锚点 %r 写了 %d，期望 %s（漂移）'
                                 % (doc, pattern, v,
                                    '/'.join(str(x) for x in allowed)))
    lines.append('  总数: 协议 %s · 代理 %s · 网页 %s · probe %s'
                 % (expect['protocol'], expect['proxy'], expect['web'],
                    '/'.join(str(x) for x in expect['probe']) or '?'))
    return lines


def main():
    if len(sys.argv) < 2 or not sys.argv[1]:
        print('用法：check_gate_counts.py <策略测试输出文件>', file=sys.stderr)
        return 2

    out = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace')
    drift = []

    expect = gate_expectations()
    extra = check_totals(expect, drift)

    policy = check_policy(out, drift)
    if policy is None:
        return 1
    asserts, guards = policy

    if drift:
        print('!! 计数漂移 —— 文档里写的用例总数与闸门期望值不符：', file=sys.stderr)
        for d in drift:
            print('     %s' % d, file=sys.stderr)
        print('   改法：同步 README.md 与 .agent/AGENTS.md 里的数字'
              '（用例只会越写越多，数字变小几乎必然是有一条被静默删掉）；'
              '若确实是措辞变了，连锚点一起改。', file=sys.stderr)
        return 1

    print('  计数: 策略 %d 断言 + %d 守卫 = %d（与 README.md / .agent/AGENTS.md 一致）'
          % (asserts, guards, asserts + guards))
    for line in extra:
        print(line)
    return 0


if __name__ == '__main__':
    sys.exit(main())