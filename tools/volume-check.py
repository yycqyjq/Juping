#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""volume-check.py —— 音量三层取证的发令通道（一次性工具）。

三层判据（todo §7.6 观察项 1）里，这一把尺子量的是 ①② 两层：
  ① 控制点发没发  → 本脚本就是"控制点"，发的动作名固定是 SetVolume/GetVolume
                    （若设备对 VolumeDB 的需求存在，会在日志里以 401 呈现）
  ② 我们存没存对  → 发完 SetVolume 立刻 GetVolume 回读，看是不是跟随
  ③ 系统音量层    → 由桌面侧同时抓的 logcat 判（取证 系统音量已下发/未下发/失败）

用法：
    python3 tools/volume-check.py <IP> <端口> <0-100>
    依次：GetVolume(基线) → SetVolume(N) → GetVolume(回读) → SetMute(1) → GetMute
          → SetMute(0) → 再 GetVolume（静音恢复后音量不该被吃掉，见控制器注释）
"""
import sys
import urllib.request
import xml.etree.ElementTree as ET

SVC = 'urn:schemas-upnp-org:service:RenderingControl:1'


def soap(url, action, args_xml):
    body = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">\n'
            '<s:Body>\n<u:%s xmlns:u="%s">\n<InstanceID>0</InstanceID>\n%s\n'
            '</u:%s>\n</s:Body>\n</s:Envelope>\n'
            % (action, SVC, args_xml, action)).encode('utf-8')
    req = urllib.request.Request(url, data=body, method='POST', headers={
        'Content-Type': 'text/xml; charset="utf-8"',
        'SOAPAction': '"%s#%s"' % (SVC, action),
    })
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def find_tag(body, tag):
    try:
        root = ET.fromstring(body)
    except Exception:
        return '(XML 解析失败)'
    for el in root.iter():
        if el.tag.split('}')[-1] == tag:
            return el.text
    return '(无此节点)'


def main():
    ip, port, want = sys.argv[1], sys.argv[2], sys.argv[3]
    url = 'http://%s:%s/upnp/control/RenderingControl' % (ip, port)
    print('端点:', url)

    st, b = soap(url, 'GetVolume', '<Channel>Master</Channel>')
    print('1) GetVolume 基线     → HTTP %s CurrentVolume=%s' % (st, find_tag(b, 'CurrentVolume')))

    st, b = soap(url, 'SetVolume', '<DesiredVolume>%s</DesiredVolume>' % want)
    print('2) SetVolume(%s)      → HTTP %s%s' % (want, st, (' 响应体: ' + b.decode('utf-8', 'replace')[:120]) if st != 200 else ''))

    st, b = soap(url, 'GetVolume', '<Channel>Master</Channel>')
    got = find_tag(b, 'CurrentVolume')
    print('3) GetVolume 回读     → HTTP %s CurrentVolume=%s   【判据②：%s】'
          % (st, got, '跟随 ✓' if got == want else '不跟随 ✗'))

    st, b = soap(url, 'SetMute', '<DesiredMute>1</DesiredMute>')
    print('4) SetMute(1)         → HTTP %s' % st)
    st, b = soap(url, 'GetMute', '<Channel>Master</Channel>')
    print('   GetMute 回读       → HTTP %s CurrentMute=%s' % (st, find_tag(b, 'CurrentMute')))

    st, b = soap(url, 'SetMute', '<DesiredMute>0</DesiredMute>')
    print('5) SetMute(0)         → HTTP %s' % st)
    st, b = soap(url, 'GetVolume', '<Channel>Master</Channel>')
    print('6) 取消静音后 GetVolume → HTTP %s CurrentVolume=%s   【静音不该吃掉音量：%s】'
          % (st, find_tag(b, 'CurrentVolume'), '保持 ✓' if find_tag(b, 'CurrentVolume') == want else '被吃 ✗'))


if __name__ == '__main__':
    main()
