#!/usr/bin/env python3
"""真机测试日志分析 —— 从后台落盘的 logcat 文件里按测试项提取关键判据。

用法：
    ./tools/device-log-analyze.py                 # 自动用最新的 /tmp/juping-live-*.log
    ./tools/device-log-analyze.py <logfile>
    ./tools/device-log-analyze.py <logfile> --group G2

为什么要有它：真机测试是「用户在电视上点、日志在后台落盘」，
测完要一眼看出「哪几项真的发生了、哪几项一次都没出现」。
**「一次都没出现」本身就是结论** —— 比如「0 条 ANR」就是 seek 修复成立的证据。
手工 grep 十几次既慢又容易漏。

判据来源：`.agent/device-test-0213.md` 的分组。
"""
import glob
import os
import re
import sys

# (组, 判据名, 正则, 期望)  期望 = 'should'（该出现）/ 'must0'（必须为 0）/ 'info'
CHECKS = [
    # ── G1 §7.20 seek 挂起 ──────────────────────────────────────
    ('G1', 'seek 已落地',        r'seek 到 \d+ ?ms 已落地',              'should'),
    ('G1', 'native 已挂起（兜底分支）', r'native 已挂起',                 'info'),
    ('G1', '后台 release 结束',   r'释放播放器: 后台 release 结束',        'info'),
    ('G1', 'seek 重建补发',       r'shouldRebuildOnSeekTimeout|重建后补发', 'info'),
    ('G1', 'ANR',                r'\bANR\b|Application Not Responding', 'must0'),
    # ── G2 §7.6 音量 ────────────────────────────────────────────
    ('G2', '系统音量已下发',      r'取证 系统音量已下发',                 'should'),
    ('G2', '系统音量未下发',      r'取证 系统音量未下发',                 'info'),
    ('G2', '设置系统音量失败',    r'设置系统音量失败',                    'must0'),
    ('G2', 'SetVolume 指令',      r'action=SetVolume',                  'info'),
    # ── G3 §7.21 DPB ───────────────────────────────────────────
    ('G3', 'DPB 超限提示',        r'请在手机上降低清晰度',                'should'),
    ('G3', 'DPB 解析结果',        r'DPB|max_num_ref_frames|参考帧',       'info'),
    # ── G4 形态机 ───────────────────────────────────────────────
    ('G4', '形态=图片',           r'MODE_IMAGE|形态.*图片',              'info'),
    ('G4', '形态=音乐',           r'MODE_AUDIO|音乐卡片',                'info'),
    ('G4', '形态=视频',           r'MODE_VIDEO|视频画面',                'info'),
    ('G4', '回到空闲',            r'MODE_IDLE|空闲面板',                 'info'),
    # ── G5 web-cast ─────────────────────────────────────────────
    ('G5', '上传请求',            r'POST /upload',                      'info'),
    ('G5', '投屏请求',            r'POST /cast',                        'info'),
    ('G5', 'APK 列表',            r'GET /apk/list',                     'info'),
    ('G5', 'APK 安装',            r'POST /apk/install',                 'info'),
    # ── G6 §7.2 回归 ────────────────────────────────────────────
    ('G6', '重建 SSDP',           r'已重建 SSDP',                       'info'),
    ('G6', '重建 HTTP',           r'已重建 HTTP',                       'info'),
    ('G6', 'SSDP 组播已加入',     r'组播=已加入',                        'should'),
    # ── 全局 ────────────────────────────────────────────────────
    ('全局', '播放错误',          r'ERROR_OCCURRED|error \(-?\d',        'must0'),
    ('全局', '无法播放',          r'无法播放|解不了|不支持',              'info'),
    # 注：不要用 AndroidRuntime 当判据 —— 每次进程启动都会打
    # `>>>>>> AndroidRuntime START <<<<<<`，那是正常噪音，会淹掉真正的崩溃。
    ('全局', '崩溃（FATAL EXCEPTION）', r'FATAL EXCEPTION', 'must0'),
]

COLORS = {'should': '\033[36m', 'must0': '\033[31m', 'info': '\033[2m'}
RESET = '\033[0m'


def newest_log():
    cands = glob.glob('/tmp/juping-live-*.log')
    if not cands:
        return None
    return max(cands, key=os.path.getmtime)


def main():
    args = [a for a in sys.argv[1:]]
    group = None
    if '--group' in args:
        i = args.index('--group')
        group = args[i + 1]
        del args[i:i + 2]
    path = args[0] if args else newest_log()
    if not path or not os.path.exists(path):
        sys.exit('找不到日志文件。先起后台 logcat，或显式传路径。')

    with open(path, encoding='utf-8', errors='replace') as f:
        lines = f.readlines()

    print('日志: %s（%d 行）' % (path, len(lines)))
    print('=' * 66)

    cur = None
    for g, name, pat, want in CHECKS:
        if group and g != group:
            continue
        if g != cur:
            print('\n── %s ──' % g)
            cur = g
        rx = re.compile(pat)
        hits = [ln.rstrip() for ln in lines if rx.search(ln)]
        n = len(hits)
        if want == 'must0':
            mark = '[ OK ]' if n == 0 else '[ !! ]'
            tail = '必须为 0' if n == 0 else '**出现了 %d 条，要查**' % n
        elif want == 'should':
            mark = '[ OK ]' if n else '[ ?? ]'
            tail = '出现 %d 条' % n if n else '**一条都没有 —— 要么没测到，要么没生效**'
        else:
            mark = '[ -- ]'
            tail = '出现 %d 条' % n
        print('  %s %-24s %s' % (mark, name, tail))
        if n and want != 'must0':
            for ln in hits[-2:]:
                print('        %s' % ln[-150:])
        elif n:
            for ln in hits[:2]:
                print('        %s' % ln[-150:])


if __name__ == '__main__':
    main()
