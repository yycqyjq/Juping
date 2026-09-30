#!/usr/bin/env python3
"""策略闸门的计数守卫：文档里手写的断言/守卫数，必须和实际跑出来的对得上。

为什么需要它
-----------
策略的断言数（PolicyTest）与源码级守卫数（policy-test/run.sh 里的 report()）
散落在 README.md 与 .agent/AGENTS.md 里**手写同步**，靠人肉同步必然漂 ——
本轮之前就出过「proxy 用例 10→11，README 与 AGENTS 各漏改一处」的漂移，
是 QA 跑测试才发现的。「全部通过」和「该测的都测了」是两回事：
断言被静默删掉，闸门照样全绿，而文档里的数字还停在旧值上。

判据
----
- 实际总 [PASS] 数 = policy-test 输出里 `[PASS]` 行数；
- 断言数 = PolicyTest 打印的「播放策略：N / N 通过」；
- 守卫数 = 总数 - 断言数；
三者与 README.md / .agent/AGENTS.md 里写的数字逐一核对，不一致就红。

用法：check_policy_counts.py <policy-test 的完整输出文件>
"""
import pathlib
import re
import sys

PASS_LINE = re.compile(r'^\s*\[PASS\]', re.M)
SUMMARY = re.compile(r'播放策略：(\d+)\s*/\s*(\d+)\s*通过')

# 文档里的措辞不完全统一：README 写「57 项断言 / 261 条源码级守卫」，
# AGENTS.md 写「57 断言 / 261 源码级守卫」。量词「项」可有可无，容错匹配。
#
# 但**刻意不允许「条」**：README 里另有「4 条断言立刻变红」这类描述**证伪实验**
# 的句子，那是"破坏几条断言去验证测试本身是活的"，跟总数不是一回事 ——
# 放开「条」会把它们当成声明值，报一堆假阳性（喊狼来了跟不检查一样糟）。
DOC_ASSERTS = re.compile(r'(\d+)\s*(?:项)?\s*断言')
DOC_GUARDS = re.compile(r'(\d+)\s*(?:条)?\s*源码级守卫')

DOCS = ('README.md', '.agent/AGENTS.md')


def stated(path):
    """从文档里抽出所有被声明过的断言数 / 守卫数（去重后的集合）。"""
    text = pathlib.Path(path).read_text(encoding='utf-8')
    asserts = {int(x) for x in DOC_ASSERTS.findall(text)}
    guards = {int(x) for x in DOC_GUARDS.findall(text)}
    return asserts, guards


def main():
    if len(sys.argv) < 2 or not sys.argv[1]:
        print('用法：check_policy_counts.py <policy-test 输出文件>', file=sys.stderr)
        return 2

    out = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace')
    total = len(PASS_LINE.findall(out))
    m = SUMMARY.search(out)
    if not m:
        print('找不到「播放策略：N / N 通过」汇总行 —— 无法核对计数', file=sys.stderr)
        return 1
    asserts = int(m.group(1))
    guards = total - asserts
    if asserts <= 0 or guards <= 0:
        print('解析异常：断言 %d，守卫 %d，总 [PASS] %d' % (asserts, guards, total),
              file=sys.stderr)
        return 1

    drift = []
    for doc in DOCS:
        if not pathlib.Path(doc).exists():
            drift.append('%s 不存在 —— 计数没有单一事实来源' % doc)
            continue
        a, g = stated(doc)
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

    if drift:
        print('!! 计数漂移 —— 文档里写的断言/守卫数与实际不符：', file=sys.stderr)
        for d in drift:
            print('     %s' % d, file=sys.stderr)
        print('   改法：同步 README.md 与 .agent/AGENTS.md 里的数字'
              '（断言只会越写越多，数字变小几乎必然是有一条被静默删掉）。', file=sys.stderr)
        return 1

    print('  计数: %d 断言 + %d 守卫 = %d（与 README.md / .agent/AGENTS.md 一致）'
          % (asserts, guards, total))
    return 0


if __name__ == '__main__':
    sys.exit(main())