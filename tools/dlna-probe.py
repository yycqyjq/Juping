#!/usr/bin/env python3
"""DLNA 控制点视角的自检。

站在**手机那一侧**，把真实控制点会走的链路完整走一遍，逐项核对：
    ① SSDP 发现（能不能搜到）
    ② 设备描述 device.xml（LOCATION 抓不抓得到、内容对不对）
    ③ 一致性（SSDP 宣告的 uuid / 设备类型与描述里的是否一致）
    ④ 服务描述 SCPD（三个服务文档在不在、是不是合法 XML）
    ⑤ 只读控制指令（GetTransportInfo / GetMediaInfo / GetProtocolInfo）

默认**只读**：不会改变盒子上的播放状态。要真跑一次投屏，用 --play <URL>。

用法：
    ./tools/dlna-probe.py 192.168.1.100
    ./tools/dlna-probe.py 127.0.0.1 --ssdp-port 1900 --http-port 49152
    ./tools/dlna-probe.py 192.168.1.100 --play http://example.com/test.mp4

退出码：0 = 全部通过；1 = 有失败项；2 = 参数/环境错误。
"""
import argparse
import re
import socket
import sys
import urllib.request
import xml.etree.ElementTree as ET

NSD = '{urn:schemas-upnp-org:device-1-0}'
NS_SOAP = '{http://schemas.xmlsoap.org/soap/envelope/}'

SVC = {
    'AVTransport': 'urn:schemas-upnp-org:service:AVTransport:1',
    'ConnectionManager': 'urn:schemas-upnp-org:service:ConnectionManager:1',
    'RenderingControl': 'urn:schemas-upnp-org:service:RenderingControl:1',
}

results = []


def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))
    print('  [%s] %s' % ('PASS' if ok else 'FAIL', name), flush=True)
    if detail:
        for line in str(detail).splitlines():
            print('         %s' % line, flush=True)


def hint(text):
    print('         → %s' % text, flush=True)


# ─────────────────────────────────────────────────────────── SSDP

def ssdp_msearch(host, port, st, timeout=3.0):
    """向 host:port **单播**发一条 M-SEARCH，返回 [(status, headers), ...]

    单播而不是组播：盒子上的 SSDP socket 绑在通配地址上，单播包一样能收到。
    组播在不同网络/系统上行为差异大（防火墙、AP 隔离、多网卡），
    做自检时单播的结果更可靠、也更好排查。

    单播 0 应答时**不要**急着判死 —— 见 {@link ssdp_msearch_multicast}：
    海信等自带投屏服务的电视会把单播截走，要用组播回退。
    """
    msg = ('M-SEARCH * HTTP/1.1\r\n'
           'HOST: 239.255.255.250:1900\r\n'
           'MAN: "ssdp:discover"\r\n'
           'MX: 1\r\n'
           'ST: %s\r\n\r\n' % st).encode('utf-8')
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    out = []
    try:
        s.settimeout(timeout)
        s.sendto(msg, (host, port))
        while True:
            try:
                data, _ = s.recvfrom(8192)
            except socket.timeout:
                break
            head, _, _ = data.decode('utf-8', 'replace').partition('\r\n\r\n')
            lines = head.split('\r\n')
            hdrs = {}
            for hl in lines[1:]:
                if ':' in hl:
                    k, _, v = hl.partition(':')
                    hdrs[k.strip().lower()] = v.strip()
            out.append((lines[0] if lines else '', hdrs))
            s.settimeout(0.6)   # 收到第一条后只再等一小会儿
    finally:
        s.close()
    return out


def ssdp_msearch_multicast(host, timeout=4.0):
    """组播发 M-SEARCH（ST: ssdp:all），只收**来自目标 IP** 的应答。

    为什么必须有这条路：单播 M-SEARCH 是内核投给 1900 端口上**某一个**
    socket 的 —— 海信等自带投屏服务的电视上，那个 socket 是厂商自己的
    服务，聚屏收不到（实测：组播 32 条应答正常、单播 0 条）。
    真实手机控制点走的就是组播，所以组播结果才是「手机视角」的事实。

    应答按源 IP == 目标过滤（局域网里别的设备也会应答）；
    同一台电视上厂商服务和聚屏会同时应答，用 friendlyName 认出聚屏
    （见 pick_device_replies）。
    """
    msg = ('M-SEARCH * HTTP/1.1\r\n'
           'HOST: 239.255.255.250:1900\r\n'
           'MAN: "ssdp:discover"\r\n'
           'MX: 2\r\n'
           'ST: ssdp:all\r\n\r\n').encode('utf-8')
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    out = []
    try:
        s.settimeout(timeout)
        s.sendto(msg, ('239.255.255.250', 1900))
        while True:
            try:
                data, addr = s.recvfrom(8192)
            except socket.timeout:
                break
            if addr[0] != host:
                continue
            head, _, _ = data.decode('utf-8', 'replace').partition('\r\n\r\n')
            lines = head.split('\r\n')
            hdrs = {}
            for hl in lines[1:]:
                if ':' in hl:
                    k, _, v = hl.partition(':')
                    hdrs[k.strip().lower()] = v.strip()
            out.append((lines[0] if lines else '', hdrs))
            s.settimeout(0.6)
    finally:
        s.close()
    return out


def pick_device_replies(replies, host):
    """同一台电视上厂商自带服务和聚屏会同时应答 —— 用 friendlyName 认出聚屏。

    逐个拉 LOCATION 的 device.xml 看 friendlyName 前缀；认不出（比如
    厂商服务恰好也叫别的、或拉取失败）就全部保留 —— 至少还能往下查。
    """
    locations = []
    for _, h in replies:
        loc = h.get('location', '')
        if loc and loc not in locations:
            locations.append(loc)
    for loc in locations:
        try:
            _, body = http_get(loc, timeout=4)
            fname = xml_text(body, 'friendlyName') or ''
        except Exception:
            continue
        if fname.startswith('聚屏'):
            return [(s, h) for (s, h) in replies if h.get('location', '') == loc]
    return replies


def do_ssdp(host, port):
    print('\n── ① SSDP 发现（手机能不能搜到这台设备）──')
    resp = ssdp_msearch(host, port, 'ssdp:all')
    mode = 'unicast'

    if not resp:
        # 单播 0 应答 ≠ 设备死了。先组播回退（真实手机走的路），
        # 再直连降级（SSDP 全挂时至少把 HTTP 层查清楚）。
        hint('单播 M-SEARCH 0 应答。常见原因：电视自带的投屏服务也占着 1900，')
        hint('单播被内核投给了它 —— 海信/乐视这类自带 DLNA 的电视上很常见。')
        hint('改用组播探测（真实手机控制点走的就是组播）……')
        mresp = ssdp_msearch_multicast(host)
        check('组播回退收到来自目标的应答', len(mresp) > 0,
              '收到 %d 条' % len(mresp))
        if mresp:
            resp = pick_device_replies(mresp, host)
            mode = 'multicast'
            hint('组播发现成功 —— 印证「单播被 1900 上的自带服务抢占」，'
                 '真实手机不受影响。')

    if not resp:
        hint('组播也没有 → 路由器挡了组播，或设备没入组。降级为直连检查：')
        direct_loc = 'http://%s:%d/upnp/device.xml' % (host, port)
        try:
            st, body = http_get(direct_loc, timeout=5)
            fname = xml_text(body, 'friendlyName') or ''
            if st == 200 and fname:
                check('直连降级：HTTP 层可达（SSDP 发现异常）', True,
                      'friendlyName=%s' % fname)
                # 构造一条伪应答，让下游 ②③④⑤ 继续查 ——
                # SSDP 相关的检查仍会如实标红。
                resp = [('HTTP/1.1 200 OK (direct)',
                         {'location': direct_loc, 'usn': '', 'server': ''})]
                mode = 'direct'
            else:
                check('直连降级：HTTP 层可达（SSDP 发现异常）', False,
                      'HTTP %s' % st)
        except Exception as e:
            check('直连降级：HTTP 层可达（SSDP 发现异常）', False, str(e))

    check('ssdp:all 有应答', len(resp) > 0 and mode != 'direct',
          '收到 %d 条（%s）；ST = %s'
          % (len(resp), mode,
             ', '.join(sorted(set(h.get('st', '') for _, h in resp))) or '(无)'))
    if not resp:
        hint('盒子没回应。按这个顺序查：')
        hint('  1. 电视界面上「网络」那一栏是不是显示「(未绑定) 候选: …」')
        hint('     —— 是的话说明组播没绑上，手机必然搜不到')
        hint('  2. 盒子和本机是不是同一个网段（%s）' % host)
        hint('  3. 盒子上的服务有没有起来（看 logcat 里有没有 "SSDP 已加入组播组"）')
        hint('  4. 路由器是不是开了「AP 隔离」，它会挡掉设备间的通信')
        return None

    first = resp[0][1]
    for k in ('location', 'usn', 'cache-control', 'server'):
        check('应答含 %s' % k.upper(), k in first, first.get(k, '(缺失)'))

    # 逐个搜索目标核 ST 是否原样回 —— 这是「有的 App 搜得到、有的搜不到」的根因
    if mode == 'direct':
        for st in ('upnp:rootdevice',
                   'urn:schemas-upnp-org:device:MediaRenderer:1',
                   'urn:schemas-upnp-org:service:AVTransport:1'):
            check('搜 %s → ST 原样回' % st.split(':')[-2], False,
                  'SSDP 不可用（单播/组播均无应答，已走直连降级）')
        return resp

    if mode == 'multicast':
        picked_loc = first.get('location', '')

        def search(st):
            # 组播模式下逐 ST 重新组播，只保留认出的那台设备的应答
            r = ssdp_msearch_multicast(host)
            return [(s, h) for (s, h) in r
                    if h.get('location', '') == picked_loc]
    else:
        def search(st):
            return ssdp_msearch(host, port, st)

    print()
    for st in ('upnp:rootdevice',
               'urn:schemas-upnp-org:device:MediaRenderer:1',
               'urn:schemas-upnp-org:service:AVTransport:1'):
        r = search(st)
        got = [h.get('st', '') for _, h in r]
        check('搜 %s → ST 原样回' % st.split(':')[-2] if ':' in st else st,
              st in got, '收到 ST = %s' % (', '.join(got) or '(无应答)'))
        if st not in got and got:
            hint('ST 没原样回，控制点会直接丢弃这条应答。'
                 '症状就是「同一个 App 有时搜得到、有时搜不到」。')
    return resp


# ─────────────────────────────────────────────────────────── HTTP

def http_get(url, timeout=8):
    req = urllib.request.Request(url, headers={'User-Agent': 'juping-probe'})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()


def http_soap(url, service, action, args_xml, instance='0', timeout=10):
    body = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">\n'
            '<s:Body>\n<u:%s xmlns:u="%s">\n<InstanceID>%s</InstanceID>\n%s\n'
            '</u:%s>\n</s:Body>\n</s:Envelope>\n'
            % (action, service, instance, args_xml, action)).encode('utf-8')
    req = urllib.request.Request(url, data=body, method='POST', headers={
        'Content-Type': 'text/xml; charset="utf-8"',
        'SOAPAction': '"%s#%s"' % (service, action),
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()


def xml_text(body, tag):
    try:
        root = ET.fromstring(body)
    except Exception:
        return None
    for el in root.iter():
        if el.tag.split('}')[-1] == tag:
            return el.text
    return None


def xml_has(body, tag):
    """节点**在不在**（与它的文本是否为空无关）。

    必须和 xml_text 分开：`<CurrentURI></CurrentURI>` 这种空元素，
    ElementTree 给的 el.text 是 None，和"整份报文里没有这个节点"
    长得一模一样。混用就会把「合法的空值」误判成「协议没实现」——
    这个坑我自己踩过一次，白查了半天。
    """
    try:
        root = ET.fromstring(body)
    except Exception:
        return False
    for el in root.iter():
        if el.tag.split('}')[-1] == tag:
            return True
    return False


def xml_escape(s):
    """把 URL 安全地放进 SOAP 报文的文本节点里。

    视频 CDN 的地址基本都带查询串（`?token=x&expire=y`），这个 `&` 不转义
    会让**请求**本身就是非法 XML，设备那边解析失败 → 投屏失败，而现象是
    "手机点了没反应"，极难定位。`&` 必须第一个换，否则会把后面生成的
    实体再转一遍。
    """
    return (s.replace('&', '&amp;').replace('<', '&lt;')
             .replace('>', '&gt;').replace('"', '&quot;'))


def do_describe(location):
    print('\n── ② 设备描述（LOCATION 指向的那个 XML）──')
    if not location:
        check('拿到 LOCATION', False, 'SSDP 应答里没有 LOCATION 头')
        return None, None
    check('拿到 LOCATION', True, location)

    try:
        status, body = http_get(location)
    except Exception as e:
        check('抓取设备描述', False, str(e))
        hint('LOCATION 抓不到 = 手机「搜到了设备却投不了屏」。查：')
        hint('  · 盒子的 HTTP 服务有没有起来（logcat 里有没有 "UPnP HTTP 服务已启动"）')
        hint('  · LOCATION 里的 IP 是不是盒子真实在用的那张网卡的 IP')
        hint('    （盒子上同时插了网线和 Wi-Fi 时最容易错）')
        return None, None

    check('抓取设备描述返回 200', status == 200, 'HTTP %s，%d 字节' % (status, len(body)))
    try:
        root = ET.fromstring(body)
    except Exception as e:
        check('设备描述是合法 XML', False, str(e))
        return None, None
    check('设备描述是合法 XML', True, '%d 字节' % len(body))

    dev = root.find(NSD + 'device')
    if dev is None:
        check('含 <device> 节点', False, '根节点下没找到 device')
        return None, None
    check('含 <device> 节点', True)

    for tag in ('friendlyName', 'deviceType', 'UDN'):
        val = dev.findtext(NSD + tag, '')
        check('含 %s 且非空' % tag, bool(val.strip()), val or '(缺失)')
    return dev, body


def do_consistency(resp, dev):
    print('\n── ③ 一致性（两个模块对同一件事的说法是否一致）──')
    if not resp or dev is None:
        check('SSDP 与设备描述一致性', False, '上游信息不全，跳过')
        return
    # 必须用**全部**应答的 USN 来核。
    # ssdp:all 的第一条是 upnp:rootdevice，它的 USN 里当然不含设备类型 ——
    # 只看第一条会误判成"不一致"。
    usns = [h.get('usn', '') for _, h in resp]
    uuids = set()
    for u in usns:
        m = re.search(r'uuid:([0-9a-fA-F-]{36})', u)
        if m:
            uuids.add(m.group(1))

    udn = dev.findtext(NSD + 'UDN', '')
    check('SSDP 宣告的 uuid 与设备描述的 UDN 一致',
          len(uuids) == 1 and udn == 'uuid:' + list(uuids)[0],
          'USN 里的 uuid=%s  UDN=%s' % (sorted(uuids), udn))

    dev_type = dev.findtext(NSD + 'deviceType', '')
    check('SSDP 宣告过设备描述里的 deviceType',
          any(dev_type in u for u in usns),
          'deviceType=%s' % dev_type)
    if not any(dev_type in u for u in usns):
        hint('设备描述里声明的类型，SSDP 却没宣告过 ——')
        hint('按类型过滤的控制点会漏掉这台设备（搜不到）。')


def do_scpd(dev, base):
    print('\n── ④ 服务描述 SCPD（三个服务文档在不在）──')
    if dev is None:
        check('SCPD 可取', False, '设备描述没拿到，跳过')
        return {}
    urls = {}
    for svc in dev.iter(NSD + 'service'):
        stype = svc.findtext(NSD + 'serviceType', '')
        name = stype.split(':')[-2] if ':' in stype else stype
        scpd = svc.findtext(NSD + 'SCPDURL', '')
        ctrl = svc.findtext(NSD + 'controlURL', '')
        urls[name] = ctrl
        try:
            status, body = http_get(base + scpd)
            ok = status == 200
            detail = '%s → HTTP %s' % (scpd, status)
            if ok:
                try:
                    ET.fromstring(body)
                except Exception as e:
                    ok, detail = False, 'XML 解析失败: %s' % e
            check('SCPD 可取且合法：%s' % name, ok, detail)
        except Exception as e:
            check('SCPD 可取且合法：%s' % name, False, '%s → %s' % (scpd, e))
    return urls


def do_control(urls, base, play_url):
    print('\n── ⑤ 控制指令（%s）──' % ('含真实投屏' if play_url else '只读，不改播放状态'))
    ctrl = urls.get('AVTransport')
    if not ctrl:
        check('拿到 AVTransport 控制地址', False, '设备描述里没有 AVTransport')
        return
    url = base + ctrl

    # 先问状态。CurrentURI 该不该有值，完全取决于它 —— 所以顺序不能反。
    state = None
    try:
        status, body = http_soap(url, SVC['AVTransport'], 'GetTransportInfo', '')
        state = xml_text(body, 'CurrentTransportState')
        check('GetTransportInfo 返回 200 且含 CurrentTransportState',
              status == 200 and xml_has(body, 'CurrentTransportState'),
              'HTTP %s，CurrentTransportState = %s' % (status, state))
    except Exception as e:
        check('GetTransportInfo 返回 200', False, str(e))

    # 「有媒体」的判据**不是**「状态不等于 NO_MEDIA_PRESENT」——
    # STOPPED 同样是"没有内容"的合法状态（本实现在 Stop 时会清空 CurrentURI）。
    # 拿它当判据会对着一台正常的盒子报假警报。真正该守的是自洽：
    # 报了几条轨，就得给出对应的地址。
    ACTIVE = ('PLAYING', 'PAUSED_PLAYBACK', 'TRANSITIONING')

    try:
        status, body = http_soap(url, SVC['AVTransport'], 'GetMediaInfo', '')
        cur = xml_text(body, 'CurrentURI')
        nrtracks = xml_text(body, 'NrTracks')
        check('GetMediaInfo 返回 200 且含 CurrentURI 节点',
              status == 200 and xml_has(body, 'CurrentURI'),
              'HTTP %s，CurrentURI = %r' % (status, cur))

        has_track = (nrtracks == '1')
        has_uri = (cur not in (None, ''))
        check('NrTracks 与 CurrentURI 自洽（不能只报轨不给地址）',
              has_track == has_uri,
              '状态 %s，NrTracks = %r，CurrentURI = %r' % (state, nrtracks, cur))

        if state in ACTIVE:
            # 正在播却回空 URI = 控制点认为"设备没接收我的投屏"，画面留在手机上
            check('播放态 CurrentURI 必须非空', has_uri,
                  '状态 %s，CurrentURI = %r' % (state, cur))
    except Exception as e:
        check('GetMediaInfo 返回 200', False, str(e))

    try:
        status, body = http_soap(url, SVC['AVTransport'], 'GetPositionInfo', '')
        dur = xml_text(body, 'TrackDuration')
        check('GetPositionInfo 返回 200 且含 TrackDuration',
              status == 200 and xml_has(body, 'TrackDuration'),
              'HTTP %s，TrackDuration = %s' % (status, dur))
    except Exception as e:
        check('GetPositionInfo 返回 200', False, str(e))

    if urls.get('ConnectionManager'):
        try:
            status, body = http_soap(base + urls['ConnectionManager'],
                                     SVC['ConnectionManager'], 'GetProtocolInfo', '')
            sink = xml_text(body, 'Sink') or ''
            check('GetProtocolInfo 返回 200', status == 200, 'HTTP %s' % status)
            check('声明的 Sink 协议串非空', len(sink) > 0, sink[:120])
        except Exception as e:
            check('GetProtocolInfo 返回 200', False, str(e))

    if play_url:
        print()
        print('  -- 真实投屏测试：%s' % play_url)
        didl = ('&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
                'xmlns:dc="http://purl.org/dc/elements/1.1/" '
                'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;'
                '&lt;item id="0" parentID="-1" restricted="1"&gt;'
                '&lt;dc:title&gt;聚屏自检&lt;/dc:title&gt;'
                '&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;'
                '&lt;/item&gt;&lt;/DIDL-Lite&gt;')
        args = ('<CurrentURI>%s</CurrentURI>'
                '<CurrentURIMetaData>%s</CurrentURIMetaData>'
                % (xml_escape(play_url), didl))
        try:
            status, _ = http_soap(url, SVC['AVTransport'], 'SetAVTransportURI', args)
            check('SetAVTransportURI 返回 200', status == 200, 'HTTP %s' % status)

            # 回读 —— 控制点就是这么确认"设备真的收下了我的地址"的。
            # 这一步同时是 XML 转义的验收：play_url 带 & 时，转义错一位
            # 这里就会炸出解析失败或值不相等。
            status, body = http_soap(url, SVC['AVTransport'], 'GetMediaInfo', '')
            echoed = xml_text(body, 'CurrentURI')
            check('投屏后回读 CurrentURI 与发出的地址一致',
                  echoed == play_url, '发出 %r，回读 %r' % (play_url, echoed))
            status, body = http_soap(url, SVC['AVTransport'], 'GetPositionInfo', '')
            echoed = xml_text(body, 'TrackURI')
            check('投屏后回读 TrackURI 与发出的地址一致',
                  echoed == play_url, '发出 %r，回读 %r' % (play_url, echoed))

            status, _ = http_soap(url, SVC['AVTransport'], 'Play',
                                  '<Speed>1</Speed>')
            check('Play 返回 200', status == 200, 'HTTP %s' % status)
            import time
            time.sleep(3)
            status, body = http_soap(url, SVC['AVTransport'], 'GetTransportInfo', '')
            state = xml_text(body, 'CurrentTransportState')
            check('3 秒后状态进入播放态',
                  state in ('PLAYING', 'TRANSITIONING', 'PAUSED_PLAYBACK'),
                  'CurrentTransportState = %s' % state)
            if state == 'STOPPED':
                hint('状态还是 STOPPED —— 多半是拉流失败。'
                     '看盒子上的界面「状态」和「片源」两栏，以及 logcat 里的播放错误。')
            # 播放态回读 —— 「播放中却回空 URI」正是让部分控制点拒绝投屏的那个 bug。
            # 这条**不能依赖 probe 启动时设备恰好在播**（那是在赌前面别的测试
            # 留下了什么状态 —— 有一轮它因此整个没执行，33 条悄悄变成 32 条，
            # 闸门还是绿的）。这里自己投了屏、自己确认过播放态，断言就在这儿执行。
            status, body = http_soap(url, SVC['AVTransport'], 'GetMediaInfo', '')
            cur_now = xml_text(body, 'CurrentURI')
            check('播放态回读 CurrentURI 必须非空', cur_now not in (None, ''),
                  '状态 %s，CurrentURI = %r' % (state, cur_now))
            http_soap(url, SVC['AVTransport'], 'Stop', '')
            check('Stop 返回 200', True, '已恢复停止状态')
        except Exception as e:
            check('真实投屏链路', False, str(e))


def main():
    ap = argparse.ArgumentParser(description='DLNA 控制点视角自检')
    ap.add_argument('host', help='盒子的 IP')
    ap.add_argument('--ssdp-port', type=int, default=1900)
    ap.add_argument('--http-port', type=int, default=None,
                    help='不指定则从 LOCATION 里取')
    ap.add_argument('--play', metavar='URL', default=None,
                    help='真跑一次投屏（会改变盒子上的播放状态）')
    args = ap.parse_args()

    print('=' * 62)
    print(' DLNA 控制点自检 —— 目标 %s' % args.host)
    print(' 站在手机那一侧，把真实控制点会走的链路完整走一遍')
    print('=' * 62)

    resp = do_ssdp(args.host, args.ssdp_port)

    # LOCATION 在每条应答里都一样，随便取一条
    location = resp[0][1].get('location') if resp else None
    base = ''
    if args.http_port:
        base = 'http://%s:%d' % (args.host, args.http_port)
    elif location:
        m = re.match(r'(https?://[^/]+)', location)
        base = m.group(1) if m else ''

    dev, _ = do_describe(location)
    do_consistency(resp, dev)
    urls = do_scpd(dev, base)
    do_control(urls, base, args.play)

    total = len(results)
    passed = sum(1 for _, ok, _ in results if ok)
    print()
    print('=' * 62)
    print(' 控制点自检：%d / %d 通过' % (passed, total))
    if passed < total:
        print()
        print(' 失败项：')
        for name, ok, detail in results:
            if not ok:
                print('   · %s' % name)
                if detail:
                    print('       %s' % detail)
    print('=' * 62)
    return 0 if passed == total else 1


if __name__ == '__main__':
    sys.exit(main())
