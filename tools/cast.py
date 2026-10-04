#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cast.py —— 把任意 URL 投到盒子上，并盯住它。

为什么需要它（而不是直接用 dlna-probe.py --play）：
probe 的 --play 是**自检**用途 —— 投上去、确认 3 秒内进入播放态、然后**发 Stop
收尾**。它验证的是"投屏这条链路通不通"，验证完就恢复原状。

但排障要的是另一件事：**让它一直播下去，看它播到结尾会怎样**。
本项目真机踩到的坑正是「播到片尾 → 位置冻住 → 看门狗判卡死 → 重连 → 从头再放」，
这个循环只有在"不 Stop"的前提下才看得见。

用法：
    python3 tools/cast.py <盒子IP> <URL> [--watch 秒] [--class videoItem|audioItem|imageItem]
                                     [--title 标题] [--http-port 端口]

例：
    python3 tools/cast.py 192.168.1.8 http://192.168.1.11:8080/test60.mp4 --watch 90

--watch N：投完之后每 2 秒读一次 /status，把「状态 / 位置 / 时长」打成时间线，
           并在状态变化或位置回退时标出来。默认 0（投完即返回）。
"""

import argparse
import importlib.util
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def _load_probe():
    """把 dlna-probe.py 当模块加载（文件名带连字符，不能直接 import）。"""
    path = os.path.join(HERE, 'dlna-probe.py')
    spec = importlib.util.spec_from_file_location('dlna_probe', path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# 扩展名 → upnp:class。判据故意只认扩展名：
# 控制点真实发出的 DIDL 里 class 与 URL 常常对不上（B站投视频却报 musicTrack），
# 这里要的是"一个确定的、可复现的输入"，不是"猜控制点想干嘛"。
CLASS_BY_EXT = {
    '.mp4': 'object.item.videoItem', '.mkv': 'object.item.videoItem',
    '.avi': 'object.item.videoItem', '.webm': 'object.item.videoItem',
    '.ts': 'object.item.videoItem', '.mov': 'object.item.videoItem',
    '.mp3': 'object.item.audioItem.musicTrack',
    '.flac': 'object.item.audioItem.musicTrack',
    '.m4a': 'object.item.audioItem.musicTrack',
    '.wav': 'object.item.audioItem.musicTrack',
    '.jpg': 'object.item.imageItem.photo',
    '.jpeg': 'object.item.imageItem.photo',
    '.png': 'object.item.imageItem.photo',
}


def guess_class(url):
    base = url.split('?', 1)[0].lower()
    for ext, cls in CLASS_BY_EXT.items():
        if base.endswith(ext):
            return cls
    return 'object.item.videoItem'


def build_didl(url, title, cls):
    didl = ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
            'xmlns:dc="http://purl.org/dc/elements/1.1/" '
            'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
            '<item id="0" parentID="-1" restricted="1">'
            '<dc:title>%s</dc:title>'
            '<upnp:class>%s</upnp:class>'
            '<res protocolInfo="http-get:*:*:*">%s</res>'
            '</item></DIDL-Lite>') % (title, cls, url)
    # 整段 DIDL 要作为 CurrentURIMetaData 的**文本**塞进 SOAP，
    # 所以 & < > 必须转义 —— 这正是 probe 里那条"回读必须一致"的断言在守的东西。
    return (didl.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;'))


def find_control_url(probe, host, http_port):
    """读 device.xml → 取 AVTransport 的 controlURL。

    刻意**不走 SSDP**：这里已经知道盒子在哪，SSDP 只是多一层可能失败的东西
    （海信这类自带投屏服务的电视会把 1900 上的单播截走，见 probe 里的说明）。
    """
    import re
    base = 'http://%s:%d' % (host, http_port)
    _, body = probe.http_get(base + '/upnp/device.xml')
    text = body.decode('utf-8', 'replace')
    # serviceList 里可能有多个 <service>，必须挑 AVTransport 那一段 ——
    # 直接取第一个 controlURL 会拿到 ConnectionManager。
    for block in re.findall(r'<service>.*?</service>', text, re.S):
        if 'AVTransport' in block:
            m = re.search(r'<controlURL>(.*?)</controlURL>', block, re.S)
            if m:
                return base + m.group(1).strip()
    raise RuntimeError('device.xml 里没找到 AVTransport 的 controlURL')


def status(host, http_port):
    with urllib.request.urlopen('http://%s:%d/status' % (host, http_port), timeout=4) as r:
        return json.load(r)


def watch(host, http_port, seconds, interval=2.0):
    t0 = time.time()
    prev = None
    print('\n-- 盯住 /status（%ds，每 %.0fs 一次）--' % (seconds, interval))
    while time.time() - t0 < seconds:
        try:
            d = status(host, http_port)
            st, pos, dur = d['state'], d['positionMs'], d['durationMs']
        except Exception as e:
            st, pos, dur = 'ERR', -1, -1
        mark = ''
        if prev:
            if st != prev[0]:
                mark += '  <<< 状态 %s → %s' % (prev[0], st)
            if pos < prev[1] - 1000:
                mark += '  <<< 位置回退 %d → %d（从头再来）' % (prev[1], pos)
        print('%6.1fs  %-12s pos=%-7s dur=%s%s' % (time.time() - t0, st, pos, dur, mark))
        prev = (st, pos)
        time.sleep(interval)
    print('-- 盯守结束 --')


def main():
    ap = argparse.ArgumentParser(description='把 URL 投到盒子上并盯住它')
    ap.add_argument('host', help='盒子 IP')
    ap.add_argument('url', help='要投的媒体地址')
    ap.add_argument('--http-port', type=int, default=49152, help='盒子 HTTP 端口（默认 49152）')
    ap.add_argument('--watch', type=float, default=0, help='投完盯 N 秒')
    ap.add_argument('--title', default=None, help='DIDL 里的标题')
    ap.add_argument('--class', dest='cls', default=None,
                    help='upnp:class（默认按扩展名推断）')
    args = ap.parse_args()

    probe = _load_probe()
    cls = args.cls or guess_class(args.url)
    title = args.title or os.path.basename(args.url.split('?', 1)[0]) or '聚屏投屏'

    ctrl = find_control_url(probe, args.host, args.http_port)
    print('控制地址: %s' % ctrl)
    print('投: %s' % args.url)
    print('   class=%s  title=%s' % (cls, title))

    svc = probe.SVC['AVTransport']
    status_code, _ = probe.http_soap(
        ctrl, svc, 'SetAVTransportURI',
        '<CurrentURI>%s</CurrentURI><CurrentURIMetaData>%s</CurrentURIMetaData>'
        % (probe.xml_escape(args.url), build_didl(args.url, title, cls)))
    print('SetAVTransportURI → HTTP %s' % status_code)
    status_code, _ = probe.http_soap(ctrl, svc, 'Play', '<Speed>1</Speed>')
    print('Play              → HTTP %s' % status_code)

    if args.watch > 0:
        watch(args.host, args.http_port, args.watch)
    else:
        time.sleep(3)
        try:
            d = status(args.host, args.http_port)
            print('3 秒后: state=%s pos=%s dur=%s' % (d['state'], d['positionMs'], d['durationMs']))
        except Exception as e:
            print('读 /status 失败: %s' % e)


if __name__ == '__main__':
    main()
