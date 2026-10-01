#!/usr/bin/env python3
"""DLNA 协议一致性驱动。

对运行中的 UpnpHttpServer 发真实的 HTTP / SOAP 报文，逐项核对响应是否符合
DLNA / UPnP 规范，以及指令是否带着正确的参数到达了业务层。

为什么值得单独测这一层：
  整套 UPnP（SSDP 发现、设备描述、SOAP 控制、事件订阅）是手写的，没有用任何库。
  这是全项目最可能出错、也最难在真机上调的部分 —— 而它对 Android 的依赖只有
  android.util.Log 一个类，所以可以完整地在桌面上端到端跑起来。

用原始 socket 而不是 http.client，是为了精确控制 Content-Length 等字节级细节 ——
真实控制点（手机 App）的行为就在这里，用高级封装会把问题掩盖掉。
"""
import os
import re
import socket
import sys
import threading
import time
import xml.etree.ElementTree as ET

HOST = '127.0.0.1'
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 49152
CALL_LOG = sys.argv[2] if len(sys.argv) > 2 else '/tmp/juping-calls.log'
# SSDP 用临时端口，由服务端实际绑上后回传（见 run.sh 解析 READY 行）
SSDP_PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 0
# SSDP 实际绑定的那张网卡的地址与名字。LOCATION 里的 IP 必须由它算出来 ——
# 用另一个网卡的 IP 就会变成「组播从 A 收、告诉手机去 B 取描述」。
BOUND_IP = sys.argv[4] if len(sys.argv) > 4 else ''
BOUND_IFACE = sys.argv[5] if len(sys.argv) > 5 else ''

NS_SOAP = '{http://schemas.xmlsoap.org/soap/envelope/}'
SVC_AVT = 'urn:schemas-upnp-org:service:AVTransport:1'
SVC_CMS = 'urn:schemas-upnp-org:service:ConnectionManager:1'
SVC_RCS = 'urn:schemas-upnp-org:service:RenderingControl:1'

# 必须与 ProtocolTestServer.java 里的 UUID 一致
DEV_UUID = '11111111-2222-3333-4444-555555555555'
DEV_TYPE = 'urn:schemas-upnp-org:device:MediaRenderer:1'
ALL_SERVICE_TYPES = (SVC_AVT, SVC_CMS, SVC_RCS)

results = []


def check(name, ok, detail='', note=''):
    """记一条断言。

    detail 与 note 是**两种不同的东西**，别混：

      · detail —— 诊断信息（实测值、"收到了几条"）。失败时必须打出来，
        因为那就是定位问题的第一手证据。默认情况下通过时也会打，
        当作"这条确实验到了"的凭据。
      · note —— 「这条为什么重要」的说明。**只在通过时**打出来。

    为什么要分开：把一段"缺了它控制点就不认设备"的话放在一个 PASS 行后面，
    读起来像在报错 —— 上一轮在 tools/check_dex_entrypoints.py 里
    就踩过一模一样的坑（通过却打印失败话术）。这里补上 note 这个出口，
    原有的调用行为完全不变（不传 note 就还是老样子）。
    """
    results.append((name, bool(ok), detail))
    mark = 'PASS' if ok else 'FAIL'
    line = '  [%s] %s' % (mark, name)
    extra = note if (ok and note) else detail
    if extra:
        line += '\n         %s' % extra.replace('\n', '\n         ')
    print(line, flush=True)


def raw_request(method, path, headers=None, body=b'', timeout=15):
    """发一个原始 HTTP 请求，返回 (status_line, headers_dict, body_bytes)"""
    if isinstance(body, str):
        body = body.encode('utf-8')
    lines = ['%s %s HTTP/1.1' % (method, path), 'Host: %s:%d' % (HOST, PORT)]
    for k, v in (headers or {}).items():
        lines.append('%s: %s' % (k, v))
    if body:
        lines.append('Content-Length: %d' % len(body))   # 字节数，HTTP 规范如此
    raw = ('\r\n'.join(lines) + '\r\n\r\n').encode('utf-8') + body

    s = socket.create_connection((HOST, PORT), timeout=timeout)
    try:
        s.sendall(raw)
        chunks = []
        while True:
            try:
                b = s.recv(65536)
            except socket.timeout:
                raise
            if not b:
                break
            chunks.append(b)
            # 简单起见：Content-Length 到了就收工
            joined = b''.join(chunks)
            if b'\r\n\r\n' in joined:
                head, _, rest = joined.partition(b'\r\n\r\n')
                m = re.search(rb'Content-Length:\s*(\d+)', head, re.I)
                if m and len(rest) >= int(m.group(1)):
                    break
                if m and int(m.group(1)) == 0:
                    break
        data = b''.join(chunks)
    finally:
        s.close()

    head, _, rest = data.partition(b'\r\n\r\n')
    head_lines = head.decode('iso-8859-1').split('\r\n')
    status = head_lines[0] if head_lines else ''
    hdrs = {}
    for hl in head_lines[1:]:
        if ':' in hl:
            k, _, v = hl.partition(':')
            hdrs[k.strip().lower()] = v.strip()
    return status, hdrs, rest


def soap_body(action, service, args_xml, instance_id='0'):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">\n'
        '<s:Body>\n'
        '<u:%s xmlns:u="%s">\n'
        '<InstanceID>%s</InstanceID>\n'
        '%s\n'
        '</u:%s>\n'
        '</s:Body>\n'
        '</s:Envelope>\n' % (action, service, instance_id, args_xml, action)
    )


def soap_post(action, service, args_xml, control='AVTransport', action_header=None):
    body = soap_body(action, service, args_xml)
    hdr = {
        'Content-Type': 'text/xml; charset="utf-8"',
        'SOAPAction': action_header if action_header is not None
                      else '"%s#%s"' % (service, action),
    }
    return raw_request('POST', '/upnp/control/%s' % control, hdr, body)


def read_calls():
    """读回业务回调记录"""
    if not os.path.exists(CALL_LOG):
        return []
    with open(CALL_LOG, encoding='utf-8') as f:
        return [l.rstrip('\n') for l in f if l.strip()]


def unrec(line):
    """反转记录时的转义"""
    parts = line.split('|')
    out = []
    for p in parts:
        p = p.replace('\\p', '|').replace('\\r', '\r').replace('\\n', '\n')
        p = re.sub(r'\\\\(?=[\\p])', '\\\\', p)
        out.append(p)
    return out


def find_xml_text(body_bytes, tag):
    """在 SOAP 响应里取某个标签的文本"""
    try:
        root = ET.fromstring(body_bytes.decode('utf-8'))
    except Exception as e:
        return None
    for el in root.iter():
        if el.tag.endswith('}' + tag) or el.tag == tag:
            return el.text or ''
    return None


# ══════════════════════════════════════════════════════════════ 1. 设备描述

print('\n── 1. 设备描述 (GET /upnp/device.xml) ──')
st, hd, body = raw_request('GET', '/upnp/device.xml')
check('HTTP 200', st.startswith('HTTP/1.1 200'), st)
check('Content-Type 是 xml', 'xml' in hd.get('content-type', ''), hd.get('content-type', '(缺失)'))

try:
    root = ET.fromstring(body.decode('utf-8'))
    xml_ok, xml_err = True, ''
except Exception as e:
    xml_ok, xml_err = False, str(e)
check('XML 格式合法', xml_ok, xml_err)

if xml_ok:
    def txt(tag):
        for el in root.iter():
            if el.tag.endswith('}' + tag):
                return el.text or ''
        return None

    check('deviceType 正确', txt('deviceType') == 'urn:schemas-upnp-org:device:MediaRenderer:1',
          'got=%r' % txt('deviceType'))
    check('UDN 以 uuid: 开头', (txt('UDN') or '').startswith('uuid:'), 'got=%r' % txt('UDN'))
    check('specVersion 存在', txt('major') == '1' and txt('minor') == '0')
    check('friendlyName 非空', bool((txt('friendlyName') or '').strip()), 'got=%r' % txt('friendlyName'))

    svc_types = set()
    for el in root.iter():
        if el.tag.endswith('}service'):
            for c in el:
                if c.tag.endswith('}serviceType'):
                    svc_types.add(c.text)
    need = {'urn:schemas-upnp-org:service:AVTransport:1',
            'urn:schemas-upnp-org:service:ConnectionManager:1',
            'urn:schemas-upnp-org:service:RenderingControl:1'}
    check('三个必需服务都在', need <= svc_types, 'missing=%s' % (need - svc_types))

    # 每个 service 必须有 SCPDURL / controlURL / eventSubURL
    ok_urls = True
    detail = ''
    for el in root.iter():
        if el.tag.endswith('}service'):
            kids = {c.tag.split('}')[-1] for c in el}
            for req in ('SCPDURL', 'controlURL', 'eventSubURL'):
                if req not in kids:
                    ok_urls = False
                    detail += '缺 %s; ' % req
    check('每个 service 都有三个 URL', ok_urls, detail)


# ══════════════════════════════════════════════════════════════ 2. SCPD

print('\n── 2. 服务描述 SCPD ──')
for short, action in (('AVTransport', 'Play'),
                      ('ConnectionManager', 'GetProtocolInfo'),
                      ('RenderingControl', 'GetVolume')):
    st, hd, body = raw_request('GET', '/upnp/%s.xml' % short)
    ok_status = st.startswith('HTTP/1.1 200')
    try:
        ET.fromstring(body.decode('utf-8'))
        ok_xml = True
        err = ''
    except Exception as e:
        ok_xml, err = False, str(e)
    has_action = action.encode() in body
    check('%s.xml 可取且合法' % short, ok_status and ok_xml, '%s %s' % (st, err))
    check('%s.xml 声明了 %s' % (short, action), has_action,
          '没找到 <%s>' % action if not has_action else '')


# ══════════════════════════════════════════════════════════════ 3. 基本控制

print('\n── 3. SOAP 控制指令 ──')

st, hd, body = soap_post('GetProtocolInfo', SVC_CMS, '', control='ConnectionManager')
sink = find_xml_text(body, 'Sink') or ''
check('GetProtocolInfo 返回 200', st.startswith('HTTP/1.1 200'), st)
check('声明了 video/mp4', 'video/mp4' in sink, 'Sink=%s' % sink[:90])
check('没声明 MKV（内存吃不住）', 'matroska' not in sink.lower(),
      'Sink 里出现了 matroska' if 'matroska' in sink.lower() else '')
# MPEG-PS 的 MIME 是 video/mpeg。注意不能简单地找 'mpeg' ——
# audio/mpeg 是 MP3 的合法 MIME，*mpegurl 是 HLS 的合法 MIME，都不是 MPEG-PS。
check('没声明 MPEG-PS（video/mpeg）', 'video/mpeg:' not in sink.lower(),
      'Sink 里出现了 video/mpeg' if 'video/mpeg:' in sink.lower() else '')
# 图片通道（kindOf 认 imageItem + 界面用 BitmapFactory 出画面）落地之后，
# 这份清单必须把 image/* 加回来 —— 做到了却不声明，相册/文件管理器就
# 不会把照片推过来，那条通道等于白做。
check('声明了 image/jpeg（图片通道已实现）', 'image/jpeg' in sink,
      'Sink=%s' % sink[:120])

st, hd, body = soap_post('GetTransportInfo', SVC_AVT, '')
state = find_xml_text(body, 'CurrentTransportState')
check('GetTransportInfo 返回状态', state is not None, 'state=%r' % state)

st, hd, body = soap_post('GetPositionInfo', SVC_AVT, '')
rel = find_xml_text(body, 'RelTime')
dur = find_xml_text(body, 'TrackDuration')
check('RelTime 格式为 HH:MM:SS', bool(re.fullmatch(r'\d{2}:\d{2}:\d{2}', rel or '')),
      'got=%r' % rel)
check('RelTime 值正确 (123456ms → 00:02:03)', rel == '00:02:03', 'got=%r' % rel)
check('TrackDuration 值正确 (7200000ms → 02:00:00)', dur == '02:00:00', 'got=%r' % dur)

st, hd, body = soap_post('GetVolume', SVC_RCS, '', control='RenderingControl')
vol = find_xml_text(body, 'CurrentVolume')
check('GetVolume 返回 42', vol == '42', 'got=%r' % vol)


# ══════════════════════════════════════════════════════════════ 4. 投屏入口

print('\n── 4. SetAVTransportURI（投屏入口，全项目最关键的一条指令）──')

PLAIN_URI = 'http://192.168.1.9:8080/movie.mp4'
PLAIN_META = ('&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; '
              'xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot;&gt;'
              '&lt;item id=&quot;0&quot;&gt;&lt;dc:title&gt;Test&lt;/dc:title&gt;&lt;/item&gt;'
              '&lt;/DIDL-Lite&gt;')

before = len(read_calls())
st, hd, body = soap_post('SetAVTransportURI', SVC_AVT,
                         '<CurrentURI>%s</CurrentURI>\n'
                         '<CurrentURIMetaData>%s</CurrentURIMetaData>' % (PLAIN_URI, PLAIN_META))
check('纯 ASCII 请求返回 200', st.startswith('HTTP/1.1 200'), st)

calls = read_calls()
new = calls[before:]
check('指令到达了业务层', len(new) >= 1, '新增记录 %d 条' % len(new))
if new:
    f = unrec(new[0])
    check('URI 原样送达', len(f) > 1 and f[1] == PLAIN_URI, 'got=%r' % (f[1] if len(f) > 1 else None))
    got_meta = f[2] if len(f) > 2 else None
    check('Metadata 被正确反转义',
          got_meta is not None and got_meta.startswith('<DIDL-Lite') and '<dc:title>Test</dc:title>' in got_meta,
          'got=%r' % (got_meta[:70] if got_meta else got_meta))

# ---- 中文元数据：这里最可能藏 bug ----
CN_TITLE = '琅琊榜 第01集'
CN_META = ('&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; '
           'xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot;&gt;'
           '&lt;item id=&quot;0&quot;&gt;&lt;dc:title&gt;' + CN_TITLE + '&lt;/dc:title&gt;&lt;/item&gt;'
           '&lt;/DIDL-Lite&gt;')
CN_URI = 'http://192.168.1.9:8080/%E7%90%85%E7%90%8A%E6%A6%9C.mp4'

before = len(read_calls())
try:
    st, hd, body = soap_post('SetAVTransportURI', SVC_AVT,
                             '<CurrentURI>%s</CurrentURI>\n'
                             '<CurrentURIMetaData>%s</CurrentURIMetaData>' % (CN_URI, CN_META))
    got_response = True
except socket.timeout:
    st, got_response = '(超时)', False
except (ConnectionResetError, ConnectionAbortedError) as e:
    st, got_response = '(连接被服务端关闭: %s)' % type(e).__name__, False

check('含中文的请求能拿到响应', got_response and st.startswith('HTTP/1.1 200'), st)

calls = read_calls()
new = calls[before:]
if new:
    f = unrec(new[0])
    got_meta = f[2] if len(f) > 2 else None
    check('中文标题完整送达（未被截断/未乱码）',
          got_meta is not None and CN_TITLE in got_meta,
          'got=%r' % (got_meta[-60:] if got_meta else got_meta))
else:
    check('中文请求的指令到达业务层', False, '一条记录都没有 —— 指令根本没送到')


# ════════════════════════════════════════ 4b. 参数属性/前缀 + 版本贯通 + HEAD

print('\n── 4b. SOAP 参数带 val 属性/带前缀（对齐 jUPnP）+ 版本贯通 + HEAD ──')

# 参照 jUPnP（Cling 后继）SOAPActionProcessorImpl 的解析策略：按「剥前缀的
# 标签名」匹配参数、完全无视属性。Platinum 系控制点会发带 val 属性的参数，
# 之前只认 <Tag>value</Tag> 的解析会把它们静默丢弃 —— SetAVTransportURI
# 缺 URI 投不上，日志里什么都看不出来。

ATTR_URI = 'http://192.168.1.9:8080/attr.mp4'
ATTR_META = PLAIN_META   # 复用上面那份转义好的 DIDL，语义不变

# 1) val 属性形式 —— 最常见的不合规写法
before = len(read_calls())
st, hd, body = raw_request('POST', '/upnp/control/AVTransport', {
    'Content-Type': 'text/xml; charset="utf-8"',
    'SOAPAction': '"%s#SetAVTransportURI"' % SVC_AVT,
}, soap_body('SetAVTransportURI', SVC_AVT,
             '<CurrentURI val="%s">%s</CurrentURI>\n'
             '<CurrentURIMetaData val="m">%s</CurrentURIMetaData>'
             % (ATTR_URI, ATTR_URI, ATTR_META)))
check('带 val 属性的 SetAVTransportURI 返回 200', st.startswith('HTTP/1.1 200'), st)
new = read_calls()[before:]
attr_uri_ok = False
attr_meta_ok = False
if new:
    f = unrec(new[0])
    attr_uri_ok = len(f) > 1 and f[1] == ATTR_URI
    got = f[2] if len(f) > 2 else None
    attr_meta_ok = got is not None and got.startswith('<DIDL-Lite')
check('val 属性参数的 URI 送达（不被静默丢弃）', attr_uri_ok,
      'got=%r' % (new[0][:90] if new else '没有记录'))
check('val 属性参数的 Metadata 反转义正确', attr_meta_ok,
      'got=%r' % (got[:60] if new and len(f) > 2 else None))

# 2) 参数带命名空间前缀 —— jUPnP getUnprefixedNodeName 兼容的写法
before = len(read_calls())
st, hd, body = raw_request('POST', '/upnp/control/AVTransport', {
    'Content-Type': 'text/xml; charset="utf-8"',
    'SOAPAction': '"%s#SetAVTransportURI"' % SVC_AVT,
}, soap_body('SetAVTransportURI', SVC_AVT,
             '<u:CurrentURI>%s</u:CurrentURI>' % ATTR_URI))
check('带前缀参数的 SetAVTransportURI 返回 200', st.startswith('HTTP/1.1 200'), st)
new = read_calls()[before:]
ok = False
if new:
    f = unrec(new[0])
    ok = len(f) > 1 and f[1] == ATTR_URI
check('带前缀参数的 URI 送达（剥前缀匹配）', ok,
      'got=%r' % (new[0][:90] if new else '没有记录'))

# 3) 自闭合 InstanceID + 普通 CurrentURI ——
#    守「自闭合标签不能吞掉后面同名元素的内容」这个回归
before = len(read_calls())
st, hd, body = raw_request('POST', '/upnp/control/AVTransport', {
    'Content-Type': 'text/xml; charset="utf-8"',
    'SOAPAction': '"%s#SetAVTransportURI"' % SVC_AVT,
}, '<?xml version="1.0" encoding="utf-8"?>\n'
   '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
   's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">\n'
   '<s:Body><u:SetAVTransportURI xmlns:u="%s">\n'
   '<InstanceID val="0"/>\n'
   '<CurrentURI>%s</CurrentURI>\n'
   '</u:SetAVTransportURI></s:Body></s:Envelope>' % (SVC_AVT, ATTR_URI))
new = read_calls()[before:]
ok = False
if new:
    f = unrec(new[0])
    ok = len(f) > 1 and f[1] == ATTR_URI
check('自闭合 InstanceID 不吞 CurrentURI', ok,
      'got=%r' % (new[0][:90] if new else '没有记录'))

# 4) SetVolume 带 val 属性 —— 渲染侧同样要走这条路。
#    值刻意用 42（靶机的初始音量）：若解析器丢了这个参数，parseInt 兜底成
#    100，断言即红；用 42 还能保证这里不留脏状态 —— 后面 RenderingControl
#    的订阅测试断言初始事件 Volume==42，依赖的就是这个值。
before = len(read_calls())
st, hd, body = raw_request('POST', '/upnp/control/RenderingControl', {
    'Content-Type': 'text/xml; charset="utf-8"',
    'SOAPAction': '"%s#SetVolume"' % SVC_RCS,
}, soap_body('SetVolume', SVC_RCS,
             '<DesiredVolume val="42">42</DesiredVolume>', instance_id='0'))
new = read_calls()[before:]
ok = False
if new:
    f = unrec(new[0])
    ok = f[0] == 'SetVolume' and len(f) > 1 and f[1] == '42'
check('val 属性的 SetVolume 送达（音量=42）', ok,
      'got=%r' % (new[0][:90] if new else '没有记录'))

# 5) 版本贯通：device.xml 的 modelNumber 必须等于测试喂入的版本号。
#    之前写死 "1.0"，与实际版本脱节 —— 排障时对不上版本。
TEST_VERSION = '9.9.9'
st, hd, body = raw_request('GET', '/upnp/device.xml')
check('device.xml 的 modelNumber 贯通构造参数',
      b'<modelNumber>%s</modelNumber>' % TEST_VERSION.encode() in body,
      'got=%r' % body[body.find(b'<modelNumber>'):body.find(b'</modelNumber>') + 15][:60])

# 6) HEAD 与 GET 同源（RFC 7231 §4.3.2：除无 body 外必须一致）。
#    之前 HEAD 一律回 200 + Content-Length: 0，不看路径。
st_get, hd_get, body_get = raw_request('GET', '/upnp/device.xml')
st_head, hd_head, body_head = raw_request('HEAD', '/upnp/device.xml')
cl = int(hd_head.get('content-length', '0'))
check('HEAD device.xml 状态与头正确（Content-Length>0）',
      st_head.startswith('HTTP/1.1 200') and cl > 0,
      'status=%r len=%r' % (st_head, hd_head.get('content-length')))
check('HEAD 无 body 且长度与 GET 一致',
      len(body_head) == 0 and cl == int(hd_get.get('content-length', '-1')),
      'head_len=%d get_len=%r body=%d' % (cl, hd_get.get('content-length'), len(body_head)))
st_head404, hd_head404, _ = raw_request('HEAD', '/upnp/不存在的路径')
check('HEAD 未知路径回 404（与 GET 对齐）',
      st_head404.startswith('HTTP/1.1 404'), st_head404)


# ══════════════════════════════════════════════ 4c. 播放列表 + /status 诊断页

print('\n── 4c. 播放列表（SetNextAVTransportURI）+ /status 诊断页 ──')

NEXT_URL = 'http://192.168.1.9:8080/next_track.mp4'
st, hd, body = soap_post('SetNextAVTransportURI', SVC_AVT,
                         '<InstanceID>0</InstanceID>'
                         '<NextURI>' + NEXT_URL + '</NextURI>'
                         '<NextURIMetaData></NextURIMetaData>')
check('SetNextAVTransportURI 返回 200', st.startswith('HTTP/1.1 200'), st)
calls = read_calls()
new = [c for c in calls if c.startswith('SetNextAVTransportURI')]
check('SetNext 命令到达业务层', len(new) >= 1,
      'got=%s' % (new[0][:80] if new else '无记录'))

st, hd, body = soap_post('GetMediaInfo', SVC_AVT, '<InstanceID>0</InstanceID>')
check('GetMediaInfo 返回 200', st.startswith('HTTP/1.1 200'), st)
m = re.search(rb'<NextURI>(.*?)</NextURI>', body, re.S)
next_in_media = m.group(1).decode('utf-8', 'replace') if m else '(缺失)'
check('GetMediaInfo 的 NextURI 与预告一致',
      next_in_media == NEXT_URL, 'got=%r' % next_in_media[:80])

soap_post('Stop', SVC_AVT, '<InstanceID>0</InstanceID>')
st, hd, body = soap_post('GetMediaInfo', SVC_AVT, '<InstanceID>0</InstanceID>')
m = re.search(rb'<NextURI>(.*?)</NextURI>', body, re.S)
next_in_media = m.group(1).decode('utf-8', 'replace') if m else '(缺失)'
check('Stop 清空下一曲队列', next_in_media == '', 'got=%r' % next_in_media[:60])

st, hd, body = raw_request('GET', '/status')
check('/status 返回 200', st.startswith('HTTP/1.1 200'), st)
check('/status 是 JSON 且含 state/currentUri',
      b'"state"' in body and b'currentUri' in body,
      'got=%r' % body[:80])

# 恢复 NextURI（后面的 probe 会按「有下一曲」多核一项，且不依赖 4c 的 Stop 残留）
soap_post('SetNextAVTransportURI', SVC_AVT,
          '<InstanceID>0</InstanceID>'
          '<NextURI>' + NEXT_URL + '</NextURI>'
          '<NextURIMetaData></NextURIMetaData>')

# 恢复片源（4c 的 Stop 清了 currentUri，section 5 的 Play 需要有片源才生效）
soap_post('SetAVTransportURI', SVC_AVT,
          '<InstanceID>0</InstanceID>'
          '<CurrentURI>http://192.168.1.9:8080/movie.mp4</CurrentURI>'
          '<CurrentURIMetaData></CurrentURIMetaData>')


# ══════════════════════════════════════════════════════════════ 5. 状态机

print('\n── 5. 播放状态机 ──')
soap_post('Play', SVC_AVT, '<Speed>1</Speed>')
st, hd, body = soap_post('GetTransportInfo', SVC_AVT, '')
check('Play 之后状态是 PLAYING',
      find_xml_text(body, 'CurrentTransportState') == 'PLAYING',
      'got=%r' % find_xml_text(body, 'CurrentTransportState'))

soap_post('Pause', SVC_AVT, '')
st, hd, body = soap_post('GetTransportInfo', SVC_AVT, '')
check('Pause 之后状态是 PAUSED_PLAYBACK',
      find_xml_text(body, 'CurrentTransportState') == 'PAUSED_PLAYBACK',
      'got=%r' % find_xml_text(body, 'CurrentTransportState'))

before = len(read_calls())
soap_post('Seek', SVC_AVT, '<Unit>REL_TIME</Unit><Target>00:10:30</Target>')
calls = read_calls()
new = calls[before:]
seek_ok = False
detail = '没有记录'
if new:
    f = unrec(new[0])
    seek_ok = f[0] == 'Seek' and len(f) > 1 and f[1] == '630000'
    detail = 'got=%s' % f
check('Seek 00:10:30 → 630000ms', seek_ok, detail)

# 下面几条守同一个根因：**Target 的小数部分是合法的**。
# DLNA 规范里 REL_TIME 的定义就是 `H+:MM:SS[.F+]`，安卓侧不少投屏 SDK
# 会老老实实带上 ".000"。而解析器遇到 "." 会 NumberFormatException，
# 被 `catch (Exception ignored)` 吞掉后 return 0 —— 也就是**静默跳到开头**。
# 用户看到的是"拖了进度条，位置反而回去了"，而日志里一个字都没有。
for target, want_ms, label in (
        ('00:10:30.000', 630000, '带小数 .000'),
        ('00:10:30.5',   630500, '带小数 .5'),
        ('0:10:30',      630000, '单数字小时'),
        ('01:00:00',     3600000, '整一小时'),
):
    before = len(read_calls())
    soap_post('Seek', SVC_AVT,
              '<Unit>REL_TIME</Unit><Target>%s</Target>' % target)
    calls = read_calls()
    new = calls[before:]
    ok = False
    detail = '没有记录'
    if new:
        f = unrec(new[0])
        ok = f[0] == 'Seek' and len(f) > 1 and f[1] == str(want_ms)
        detail = 'got=%s' % f
    check('Seek %s（%s）→ %dms' % (target, label, want_ms), ok, detail)

# 解析不了的 Target **必须被忽略**，绝不能退化成"跳到 0"。
# "什么都不做"和"跳回开头"对用户是两件完全不同的事。
for bad in ('', 'abc', '1', '00:xx:30'):
    before = len(read_calls())
    soap_post('Seek', SVC_AVT, '<Unit>REL_TIME</Unit><Target>%s</Target>' % bad)
    calls = read_calls()
    new = calls[before:]
    ok = True
    detail = '未记录（正确：忽略）'
    if new:
        f = unrec(new[0])
        ok = f[0] == 'Seek' and len(f) > 1 and f[1] != '0'
        detail = 'got=%s  ← 不该退化成跳到 0' % f
    check('非法 Target %r 被忽略而不是跳到 0' % bad, ok, detail)

# Unit=TRACK_NR 时 Target 是**曲目号**，不是时刻。
# 老实现把它当时间解析（"1" 拆不出三段 → 0），于是「切下一曲」变成「跳回开头」。
before = len(read_calls())
soap_post('Seek', SVC_AVT, '<Unit>TRACK_NR</Unit><Target>1</Target>')
calls = read_calls()
new = calls[before:]
ok = True
detail = '未记录（正确：忽略）'
if new:
    f = unrec(new[0])
    ok = f[0] == 'Seek' and len(f) > 1 and f[1] != '0'
    detail = 'got=%s  ← 不该退化成跳到 0' % f
check('Seek Unit=TRACK_NR 被忽略（Target 是曲目号不是时刻）', ok, detail)

soap_post('Stop', SVC_AVT, '')
st, hd, body = soap_post('GetTransportInfo', SVC_AVT, '')
check('Stop 之后状态是 STOPPED',
      find_xml_text(body, 'CurrentTransportState') == 'STOPPED',
      'got=%r' % find_xml_text(body, 'CurrentTransportState'))

# ---- CurrentTransportStatus 必须如实反映，不能写死 ----
#
# 它和 GENA 事件里的 TransportStatus 是**同一个语义**：出没出错。
# 之前 GetTransportInfo 把它写死成 OK，而事件那边已经改成如实报 ——
# 于是同一台设备、同一时刻，两个接口给出相反的答案：靠轮询的控制点
# 以为一切正常，靠事件的控制点知道在出错。控制点自己都不知道该信哪个。
#
# 三条一起看才说明问题：正常 → 出错 → 复位。只测中间那条的话，
# 一个"恒回 ERROR_OCCURRED"的实现也能通过。
st, hd, body = soap_post('GetTransportInfo', SVC_AVT, '')
check('正常态：CurrentTransportStatus = OK',
      find_xml_text(body, 'CurrentTransportStatus') == 'OK',
      'got=%r' % find_xml_text(body, 'CurrentTransportStatus'))

# 靶机约定：片源地址里带 boom 就置成出错态（真实服务里由 onError 置 lastError）
BOOM_URI = 'http://192.168.1.9:8080/boom.mp4'
soap_post('SetAVTransportURI', SVC_AVT,
          '<CurrentURI>%s</CurrentURI>\n'
          '<CurrentURIMetaData></CurrentURIMetaData>' % BOOM_URI)
st, hd, body = soap_post('GetTransportInfo', SVC_AVT, '')
check('出错态：CurrentTransportStatus = ERROR_OCCURRED（不是写死的 OK）',
      find_xml_text(body, 'CurrentTransportStatus') == 'ERROR_OCCURRED',
      'got=%r  ← 写死 OK 的实现在这里露馅' % find_xml_text(body, 'CurrentTransportStatus'))

soap_post('Stop', SVC_AVT, '')
st, hd, body = soap_post('GetTransportInfo', SVC_AVT, '')
check('Stop 之后错误态复位：CurrentTransportStatus = OK',
      find_xml_text(body, 'CurrentTransportStatus') == 'OK',
      'got=%r  ← 不复位的话，一次偶发错误会让设备永远"看起来在出错"'
      % find_xml_text(body, 'CurrentTransportStatus'))


# ══════════════════════════════════════════════════════════════ 6. 回读契约

print('\n── 6. 回读契约（CurrentURI / TrackURI / NrTracks）──')

# 这一段守的是一个很容易漏的协议义务：控制点推完 URI 之后，不少投屏 SDK
# 会回读 GetMediaInfo，拿 CurrentURI 与自己刚推的地址比对。回读为空 →
# SDK 判定"这台设备没接收成功"，画面就停在手机上不投了。
# 同时验收 XML 转义：带 & 的地址不转义，整份响应会变成非法 XML。

# 此刻刚 Stop 过，应当处于无媒体状态
st, hd, body = soap_post('GetMediaInfo', SVC_AVT, '')
mi_uri = find_xml_text(body, 'CurrentURI')
check('无媒体时 CurrentURI 节点存在且为空（不是缺节点）', mi_uri == '', 'got=%r' % mi_uri)
check('无媒体时 NrTracks 为 0（不能谎报有片）',
      find_xml_text(body, 'NrTracks') == '0',
      'got=%r' % find_xml_text(body, 'NrTracks'))

st, hd, body = soap_post('GetPositionInfo', SVC_AVT, '')
check('无媒体时 Track 为 0',
      find_xml_text(body, 'Track') == '0',
      'got=%r' % find_xml_text(body, 'Track'))

# 带 & 的地址是 CDN 的常态
AMP_URI = 'http://192.168.1.9:8080/movie.mp4?token=abc&expire=1700000000&sig=x/y+z='

before = len(read_calls())
st, hd, body = soap_post('SetAVTransportURI', SVC_AVT,
                         '<CurrentURI>%s</CurrentURI>\n'
                         '<CurrentURIMetaData>%s</CurrentURIMetaData>'
                         % (AMP_URI.replace('&', '&amp;'), PLAIN_META))
check('带 & 的 URI 请求返回 200', st.startswith('HTTP/1.1 200'), st)

new = read_calls()[before:]
check('业务层收到的是解码后的完整地址（& 没被吃掉）',
      len(new) >= 1 and unrec(new[0])[1] == AMP_URI,
      'got=%r' % (unrec(new[0])[1] if new else None))

st, hd, body = soap_post('GetMediaInfo', SVC_AVT, '')
check('GetMediaInfo 的 CurrentURI 与发出的地址一致',
      find_xml_text(body, 'CurrentURI') == AMP_URI,
      'got=%r' % find_xml_text(body, 'CurrentURI'))
check('有媒体时 NrTracks 为 1',
      find_xml_text(body, 'NrTracks') == '1',
      'got=%r' % find_xml_text(body, 'NrTracks'))

st, hd, body = soap_post('GetPositionInfo', SVC_AVT, '')
check('GetPositionInfo 的 TrackURI 与发出的地址一致',
      find_xml_text(body, 'TrackURI') == AMP_URI,
      'got=%r' % find_xml_text(body, 'TrackURI'))
check('有媒体时 Track 为 1',
      find_xml_text(body, 'Track') == '1',
      'got=%r' % find_xml_text(body, 'Track'))

# 复位，别影响后面的段落
soap_post('Stop', SVC_AVT, '')
st, hd, body = soap_post('GetMediaInfo', SVC_AVT, '')
check('Stop 之后 CurrentURI 被清空',
      find_xml_text(body, 'CurrentURI') == '',
      'got=%r' % find_xml_text(body, 'CurrentURI'))


# ══════════════════════════════════════════════════════════════ 7. 健壮性

print('\n── 7. 健壮性与边界 ──')

st, hd, body = raw_request('GET', '/upnp/nonexistent.xml')
check('未知路径返回 404', '404' in st, st)

st, hd, body = raw_request('SUBSCRIBE', '/upnp/event/AVTransport',
                           {'CALLBACK': '<http://192.168.1.9:9999/cb>', 'NT': 'upnp:event'})
check('SUBSCRIBE 返回 200', st.startswith('HTTP/1.1 200'), st)
check('SUBSCRIBE 带 SID', 'sid' in hd and hd['sid'].startswith('uuid:'), hd.get('sid', '(缺失)'))
check('SUBSCRIBE 带 TIMEOUT', 'timeout' in hd, hd.get('timeout', '(缺失)'))

# SOAPAction 不带引号 —— 部分老控制点会这么发
st, hd, body = soap_post('GetTransportInfo', SVC_AVT, '', action_header='%s#GetTransportInfo' % SVC_AVT)
check('SOAPAction 不带引号也能识别', find_xml_text(body, 'CurrentTransportState') is not None, st)

# SOAPAction 完全缺失，靠 body 里的 <u:Action> 兜底
body_xml = soap_body('GetTransportInfo', SVC_AVT, '')
st, hd, body = raw_request('POST', '/upnp/control/AVTransport',
                           {'Content-Type': 'text/xml; charset="utf-8"'}, body_xml)
check('SOAPAction 缺失时能从 body 兜底', find_xml_text(body, 'CurrentTransportState') is not None, st)

# content-length 小写
body_xml = soap_body('GetTransportInfo', SVC_AVT, '')
raw = ('POST /upnp/control/AVTransport HTTP/1.1\r\nHost: %s:%d\r\n'
       'Content-Type: text/xml; charset="utf-8"\r\n'
       'SOAPAction: "%s#GetTransportInfo"\r\n'
       'content-length: %d\r\n\r\n' % (HOST, PORT, SVC_AVT, len(body_xml.encode()))).encode() + body_xml.encode()
s = socket.create_connection((HOST, PORT), timeout=15)
try:
    s.sendall(raw)
    data = s.recv(65536)
finally:
    s.close()
check('content-length 小写也能识别', b'200 OK' in data, data[:60].decode('iso-8859-1', 'replace'))

# 畸形请求不应把服务打挂
try:
    s = socket.create_connection((HOST, PORT), timeout=5)
    s.sendall(b'GARBAGE\r\n\r\n')
    s.close()
except Exception:
    pass
st, hd, body = raw_request('GET', '/upnp/device.xml')
check('畸形请求后服务仍存活', st.startswith('HTTP/1.1 200'), st)

# 未知 action
st, hd, body = soap_post('NoSuchAction', SVC_AVT, '')
check('未知 action 返回 SOAP Fault 而不是崩溃',
      b'Fault' in body or '500' in st or '401' in st, st)


# ══════════════════════════════════════════════════════════════ SSDP 发现

def parse_ssdp(resp):
    head, _, _ = resp.partition('\r\n\r\n')
    lines = head.split('\r\n')
    hdrs = {}
    for hl in lines[1:]:
        if ':' in hl:
            k, _, v = hl.partition(':')
            hdrs[k.strip().lower()] = v.strip()
    return (lines[0] if lines else ''), hdrs


def msearch(st, mx=1, timeout=3.0, quiet=0.5):
    """发一条真实的 M-SEARCH，收齐应答后返回 [(status, headers), ...]

    用**单播**发到服务端绑定的 UDP 端口：socket 绑在通配地址上，单播包一样能收到，
    于是整套 SSDP 逻辑都能在桌面上验证，不必依赖组播 ——
    组播在不同平台/网络环境下的行为差异太大，不适合做自动化断言。
    """
    msg = ('M-SEARCH * HTTP/1.1\r\n'
           'HOST: 239.255.255.250:1900\r\n'
           'MAN: "ssdp:discover"\r\n'
           'MX: %d\r\n'
           'ST: %s\r\n'
           '\r\n' % (mx, st)).encode('utf-8')
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    out = []
    try:
        s.sendto(msg, (HOST, SSDP_PORT))
        s.settimeout(timeout)
        while True:
            try:
                data, _ = s.recvfrom(4096)
            except socket.timeout:
                break
            out.append(parse_ssdp(data.decode('utf-8', 'replace')))
            # 收到第一条之后只再等一小会儿，免得每条都干等满超时
            s.settimeout(quiet)
    finally:
        s.close()
    return out


def raw_ssdp(payload, timeout=1.5):
    """发任意 UDP 报文，返回收到的应答条数（验证「不该答的不能答」）"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.sendto(payload, (HOST, SSDP_PORT))
        s.settimeout(timeout)
        try:
            s.recvfrom(4096)
            return 1
        except socket.timeout:
            return 0
    finally:
        s.close()


def short(st):
    return st.replace('urn:schemas-upnp-org:', '').replace('uuid:' + DEV_UUID, 'uuid:<本机>')


print('\n── 8. SSDP 设备发现（手机搜不搜得到这台设备）──')

if SSDP_PORT <= 0:
    check('SSDP 端口可用', False, '服务端没报告绑上的端口（网卡选择失败？）')
    all_resp, sts, first = [], [], {}
else:
    check('SSDP 端口可用', True, 'UDP %d' % SSDP_PORT)

    # --- ssdp:all：规范要求对每个搜索目标各回一条，不是回一条就完事 ---
    all_resp = msearch('ssdp:all')
    sts = [h.get('st', '') for _, h in all_resp]
    first = all_resp[0][1] if all_resp else {}
    check('ssdp:all 收到应答', len(all_resp) > 0, '收到 %d 条' % len(all_resp))

    expected = {'upnp:rootdevice', 'uuid:' + DEV_UUID, DEV_TYPE} | set(ALL_SERVICE_TYPES)
    missing = expected - set(sts)
    check('ssdp:all 覆盖全部 6 个搜索目标', not missing,
          ('缺少 %s' % ', '.join(sorted(short(m) for m in missing))) if missing
          else 'ST = %s' % ', '.join(sorted(short(x) for x in set(sts))))

    # --- 应答头必须齐 ---
    for name, key in (('CACHE-CONTROL', 'cache-control'), ('EXT', 'ext'),
                      ('LOCATION', 'location'), ('SERVER', 'server'),
                      ('ST', 'st'), ('USN', 'usn')):
        check('应答含 %s 头' % name, key in first, first.get(key, '(缺失)'))

    status0 = all_resp[0][0] if all_resp else ''
    check('应答状态行是 200 OK', status0.startswith('HTTP/1.1 200'), status0 or '(无应答)')
    check('CACHE-CONTROL 是 max-age=1800',
          'max-age=1800' in first.get('cache-control', ''), first.get('cache-control', '(缺失)'))
    check('LOCATION 是 http:// 开头的绝对地址',
          first.get('location', '').startswith('http://'), first.get('location', '(缺失)'))

    # LOCATION 里的 IP 必须来自**实际绑定的那张网卡**。
    # 用另一个网卡的地址就是「组播从 eth0 收搜索请求、却告诉手机去 wlan0
    # 取设备描述」—— 手机搜得到设备、点进去却拉不到描述。
    #
    # 刻意不再单列一条「IP 不是 0.0.0.0」：那是这一条的**前提**，
    # 删掉那一行会让两条一起红 —— 而"红了多条"说明判据重叠，反而定位不出问题。
    loc_host = re.sub(r'^http://([^:/]+).*$', r'\1', first.get('location', ''))
    check('LOCATION 的 IP 就是 SSDP 实际绑定网卡的地址',
          bool(BOUND_IP) and loc_host == BOUND_IP,
          'LOCATION 里是 %s；实际绑定的是 %s（%s）'
          % (loc_host or '(缺失)', BOUND_IP or '(未知)', BOUND_IFACE or '?'))

    # --- ST 必须原样回给控制点，否则控制点会丢弃这条应答 ---
    #     这是「有的 App 搜得到、有的搜不到」的典型来源
    for st_val, usn_expect in (
            ('upnp:rootdevice', 'uuid:%s::upnp:rootdevice' % DEV_UUID),
            ('uuid:%s' % DEV_UUID, 'uuid:%s' % DEV_UUID),
            (DEV_TYPE, 'uuid:%s::%s' % (DEV_UUID, DEV_TYPE)),
            (SVC_AVT, 'uuid:%s::%s' % (DEV_UUID, SVC_AVT)),
            (SVC_CMS, 'uuid:%s::%s' % (DEV_UUID, SVC_CMS)),
            (SVC_RCS, 'uuid:%s::%s' % (DEV_UUID, SVC_RCS)),
    ):
        resp = msearch(st_val)
        got_sts = [h.get('st', '') for _, h in resp]
        got_usn = [h.get('usn', '') for _, h in resp]
        check('搜 %s → ST 原样回' % short(st_val), st_val in got_sts,
              '收到 ST = %s' % (', '.join(short(x) for x in got_sts) or '(无应答)'))
        check('搜 %s → USN 正确' % short(st_val), usn_expect in got_usn,
              '期望 %s，收到 %s' % (usn_expect, ', '.join(got_usn) or '(无应答)'))

    # --- 不该答的不能答，否则局域网里全是噪声 ---
    other = msearch('urn:schemas-upnp-org:device:MediaServer:1')
    check('搜 MediaServer（不是我们）→ 不应答', len(other) == 0, '收到 %d 条' % len(other))

    n = raw_ssdp(b'NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n'
                 b'NT: upnp:rootdevice\r\nNTS: ssdp:alive\r\n\r\n')
    check('NOTIFY 上线广播 → 不应答', n == 0, '收到 %d 条' % n)

    n = raw_ssdp(b'GARBAGE\r\n\r\n')
    check('畸形 UDP 报文 → 不应答且服务存活', n == 0, '收到 %d 条' % n)


# ══════════════════════════════════════════════════════════════ 发现链路闭环

print('\n── 9. 发现链路闭环（SSDP → 设备描述 → 服务描述）──')
print('   控制点真实走的就是这条链：搜到设备 → 按 LOCATION 抓描述 → 按 SCPDURL 抓服务。')
print('   任何一环断开，表现都是「搜到了却投不了屏」。')

loc = first.get('location', '')
loc_path = '/' + loc.split('/', 3)[3] if loc.count('/') >= 3 else '/upnp/device.xml'

st_line, _, dev_xml = raw_request('GET', loc_path)
check('顺着 LOCATION 抓得到设备描述', st_line.startswith('HTTP/1.1 200'), '%s → %s' % (loc_path, st_line))

# 刻意**不**在这里再按 LOCATION 的 host:port 连一次：
# 「这个地址真的可达」已经由 tools/dlna-probe.py 端到端覆盖了（同一个闸门里跑，
# 见 run.sh 第 5 步），而它给的失败提示比这里能给的详细得多
# （直接点出「盒子同时插网线和 Wi-Fi 时最容易错」）。
# 同一个语义写两遍的代价是：一处坏了两条一起红，反而定位不出问题。

NSD = '{urn:schemas-upnp-org:device-1-0}'
try:
    dev_root = ET.fromstring(dev_xml)
    dev = dev_root.find(NSD + 'device')
except Exception as e:
    dev = None
    check('设备描述是合法 XML', False, str(e))

if dev is not None:
    check('设备描述是合法 XML', True, '%d 字节' % len(dev_xml))

    udn = dev.findtext(NSD + 'UDN', '')
    check('UDN 与 SSDP 宣告的 uuid 一致', udn == 'uuid:' + DEV_UUID, udn or '(缺失)')

    dev_type = dev.findtext(NSD + 'deviceType', '')
    check('deviceType 与 SSDP 宣告的一致', dev_type == DEV_TYPE, dev_type or '(缺失)')

    check('friendlyName 非空', bool(dev.findtext(NSD + 'friendlyName', '').strip()),
          dev.findtext(NSD + 'friendlyName', '') or '(缺失)')

    # 设备描述里声明的服务，SSDP 也必须都宣告过 ——
    # 否则靠 SSDP 过滤服务类型的控制点会漏掉它们
    declared = [s.findtext(NSD + 'serviceType', '') for s in dev.iter(NSD + 'service')]
    not_announced = [t for t in declared if t and t not in sts]
    check('描述里的服务类型都在 SSDP 宣告过', not not_announced,
          ('未宣告 %s' % ', '.join(short(t) for t in not_announced)) if not_announced
          else '%d 个服务全部一致' % len(declared))

    # 每个 SCPDURL 都要真能取到，且是合法 XML
    probe = {
        'AVTransport': ('GetTransportInfo', SVC_AVT, ''),
        'ConnectionManager': ('GetProtocolInfo', SVC_CMS, ''),
        'RenderingControl': ('GetVolume', SVC_RCS, '<Channel>Master</Channel>'),
    }
    for svc in dev.iter(NSD + 'service'):
        svc_type = svc.findtext(NSD + 'serviceType', '')
        name = svc_type.split(':')[-2] if ':' in svc_type else svc_type
        scpd = svc.findtext(NSD + 'SCPDURL', '')
        ctrl = svc.findtext(NSD + 'controlURL', '')

        s_line, _, s_body = raw_request('GET', scpd)
        ok = s_line.startswith('HTTP/1.1 200')
        detail = '%s → %s' % (scpd, s_line)
        if ok:
            try:
                ET.fromstring(s_body)
            except Exception as e:
                ok, detail = False, 'XML 解析失败: %s' % e
        check('SCPD 可取且是合法 XML：%s' % name, ok, detail)

        # controlURL 路由必须存在 —— 404 说明设备描述和实际路由对不上
        if name in probe:
            action, svc_ns, args = probe[name]
            c_line, _, _ = soap_post(action, svc_ns, args, control=name)
            check('controlURL 可达：%s' % name, '404' not in c_line, '%s → %s' % (ctrl, c_line))


# ══════════════════════════════════════════════════════════════ 10. GENA 事件

print('\n── 10. GENA 事件订阅与推送 ──')


class FakeCallback:
    """假的控制点回调服务器：收 NOTIFY，回 200，把事件记下来。

    为什么必须真的起一个 socket：GENA 的坑几乎全在「发得出去吗」这一侧 ——
    NOTIFY 是个 HttpURLConnection 不允许自定义的 HTTP 方法，SEQ 要在真的发出
    事件时才递增，回调地址可能带尖括号。用假的发送器测，这些一个都测不到。
    """

    def __init__(self):
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('127.0.0.1', 0))
        self.sock.listen(8)
        self.port = self.sock.getsockname()[1]
        self.events = []
        self.lock = threading.Lock()
        self._stop = False
        threading.Thread(target=self._serve, daemon=True).start()

    def url(self):
        return 'http://127.0.0.1:%d/notify' % self.port

    def _serve(self):
        while not self._stop:
            try:
                self.sock.settimeout(0.2)
                conn, _ = self.sock.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def _handle(self, conn):
        try:
            conn.settimeout(5)
            data = b''
            while b'\r\n\r\n' not in data:
                b = conn.recv(65536)
                if not b:
                    return
                data += b
            head, _, rest = data.partition(b'\r\n\r\n')
            lines = head.decode('iso-8859-1').split('\r\n')
            hdrs = {}
            for hl in lines[1:]:
                if ':' in hl:
                    k, _, v = hl.partition(':')
                    hdrs[k.strip().lower()] = v.strip()
            need = int(hdrs.get('content-length', '0') or 0)
            while len(rest) < need:
                b = conn.recv(65536)
                if not b:
                    break
                rest += b
            with self.lock:
                self.events.append({'line': lines[0], 'headers': hdrs, 'body': rest[:need]})
            conn.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n')
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def count(self):
        with self.lock:
            return len(self.events)

    def snapshot(self):
        with self.lock:
            return list(self.events)

    def wait(self, n, timeout=6.0):
        """等到至少收到 n 条事件（超时就返回现有的）"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.count() >= n:
                break
            time.sleep(0.02)
        return self.snapshot()

    def close(self):
        self._stop = True
        try:
            self.sock.close()
        except Exception:
            pass


def drain(cb, already, timeout=6.0):
    """等新事件，只返回第 already 条之后的那些"""
    return cb.wait(already + 1, timeout)[already:]


def scpd_evented(body_bytes):
    """SCPD 里所有 sendEvents="yes" 的变量名"""
    out = set()
    try:
        root = ET.fromstring(body_bytes.decode('utf-8'))
    except Exception:
        return out
    for sv in root.iter():
        if sv.tag.endswith('stateVariable') or sv.tag == 'stateVariable':
            if (sv.get('sendEvents') or '').lower() == 'yes':
                for ch in sv:
                    if ch.tag.endswith('name') or ch.tag == 'name':
                        out.add(ch.text or '')
    return out


def propmap(body_bytes):
    """把事件体解析成 {变量名: 值}。

    用真 XML 解析器而不是字符串查找 —— 值里的 & 没转义时这里会直接抛异常，
    正是我们想抓的（转义错了控制点也是整条解析失败，不是"这一项读不到"）。
    """
    root = ET.fromstring(body_bytes.decode('utf-8'))
    out = {}
    for prop in root.iter():
        if prop.tag.endswith('property') or prop.tag == 'property':
            for ch in prop:
                out[ch.tag.split('}')[-1]] = ch.text or ''
    return out


# --- 10.1 SCPD 必须先声明"哪些变量可事件化" ---
# 没有这一节，控制点订阅到的是空事件集 —— 它不会报错，只是永远等不到东西。
st, _, scpd_body = raw_request('GET', '/upnp/RenderingControl.xml')
rcs_evented = scpd_evented(scpd_body)
check('RenderingControl SCPD 声明 Volume 可事件化', 'Volume' in rcs_evented,
      '实际: %s' % (sorted(rcs_evented) or '无'))

st, _, scpd_body = raw_request('GET', '/upnp/AVTransport.xml')
avt_evented = scpd_evented(scpd_body)
for want in ('TransportState', 'TransportStatus', 'CurrentTrackURI', 'CurrentTrackDuration'):
    check('AVTransport SCPD 声明 %s 可事件化' % want, want in avt_evented,
          '实际: %s' % (sorted(avt_evented) or '无'))

# --- 10.2 新订阅 ---
cb_avt = FakeCallback()
st, hd, _ = raw_request('SUBSCRIBE', '/upnp/event/AVTransport', {
    'CALLBACK': '<%s>' % cb_avt.url(),
    'NT': 'upnp:event',
    'TIMEOUT': 'Second-1800',
})
check('SUBSCRIBE 新订阅回 200', st.startswith('HTTP/1.1 200'), st)
sid_avt = hd.get('sid', '')
check('响应带 SID 且以 uuid: 开头', sid_avt.startswith('uuid:'), sid_avt or '(缺失)')
check('响应回 TIMEOUT', hd.get('timeout', '').startswith('Second-'),
      hd.get('timeout', '(缺失)'))

# --- 10.3 订阅后必须立刻收到初始事件（SEQ 0）---
# 规范（UDA 1.0 §4.3）硬要求。控制点不会主动来问，它只会等。
evs = cb_avt.wait(1, 6.0)
check('订阅后收到初始事件', len(evs) >= 1, '收到 %d 条' % len(evs))
if evs:
    e0 = evs[0]
    check('初始事件是 NOTIFY 方法', e0['line'].startswith('NOTIFY'), e0['line'])
    check('初始事件带 NT: upnp:event', e0['headers'].get('nt') == 'upnp:event',
          e0['headers'].get('nt', '(缺失)'))
    check('初始事件带 NTS: upnp:propchange',
          e0['headers'].get('nts') == 'upnp:propchange',
          e0['headers'].get('nts', '(缺失)'))
    check('初始事件的 SID 与订阅响应一致', e0['headers'].get('sid') == sid_avt,
          '事件=%s 响应=%s' % (e0['headers'].get('sid'), sid_avt))
    check('初始事件 SEQ 从 0 开始', e0['headers'].get('seq') == '0',
          e0['headers'].get('seq', '(缺失)'))
    check('初始事件 Content-Type 是 xml',
          'xml' in e0['headers'].get('content-type', ''),
          e0['headers'].get('content-type', '(缺失)'))
    try:
        m0 = propmap(e0['body'])
        check('初始事件含 TransportState', 'TransportState' in m0, str(sorted(m0)))
        check('初始事件含 CurrentTrackDuration',
              'CurrentTrackDuration' in m0, str(sorted(m0)))
        # 只报 SCPD 里声明过的变量：多报无益，少报会让控制点一直以为它是空的
        extra = set(m0) - avt_evented
        check('初始事件没有多报未声明的变量', not extra, '多报: %s' % sorted(extra))
    except Exception as ex:
        check('初始事件体是合法 XML', False, str(ex))

# --- 10.4 状态变化必须推出去，且 SEQ 递增 ---
n = cb_avt.count()
# URL 里带 & —— 转义错了整条事件体就是非法 XML
soap_post('SetAVTransportURI', SVC_AVT,
          '<CurrentURI>http://127.0.0.1:9/gena.mp4?token=a&amp;expire=1</CurrentURI>'
          '<CurrentURIMetaData></CurrentURIMetaData>')
evs = drain(cb_avt, n)
check('SetAVTransportURI 后收到事件', len(evs) >= 1, '收到 %d 条' % len(evs))
if evs:
    e = evs[-1]
    check('第二个事件 SEQ 递增到 1', e['headers'].get('seq') == '1',
          e['headers'].get('seq', '(缺失)'))
    try:
        m = propmap(e['body'])
        check('事件里的 CurrentTrackURI 是刚推的地址（& 已转义）',
              m.get('CurrentTrackURI') == 'http://127.0.0.1:9/gena.mp4?token=a&expire=1',
              repr(m.get('CurrentTrackURI')))
        check('事件里的 TransportState 是 TRANSITIONING',
              m.get('TransportState') == 'TRANSITIONING', repr(m.get('TransportState')))
    except Exception as ex:
        check('带 & 的 URI 事件体仍是合法 XML', False, str(ex))

n = cb_avt.count()
soap_post('Play', SVC_AVT, '<Speed>1</Speed>')
evs = drain(cb_avt, n)
check('Play 后收到事件', len(evs) >= 1, '收到 %d 条' % len(evs))
if evs:
    try:
        m = propmap(evs[-1]['body'])
        check('事件里的 TransportState 变成 PLAYING',
              m.get('TransportState') == 'PLAYING', repr(m.get('TransportState')))
    except Exception as ex:
        check('Play 事件体是合法 XML', False, str(ex))

n = cb_avt.count()
soap_post('Stop', SVC_AVT, '')
evs = drain(cb_avt, n)
check('Stop 后收到事件', len(evs) >= 1, '收到 %d 条' % len(evs))
if evs:
    try:
        m = propmap(evs[-1]['body'])
        check('Stop 后事件里 CurrentTrackURI 清空',
              m.get('CurrentTrackURI') == '', repr(m.get('CurrentTrackURI')))
        check('Stop 后事件里 TransportState 是 STOPPED',
              m.get('TransportState') == 'STOPPED', repr(m.get('TransportState')))
    except Exception as ex:
        check('Stop 事件体是合法 XML', False, str(ex))

# --- 10.5 续订 ---
st, hd2, _ = raw_request('SUBSCRIBE', '/upnp/event/AVTransport',
                         {'SID': sid_avt, 'TIMEOUT': 'Second-600'})
check('续订回 200', st.startswith('HTTP/1.1 200'), st)
check('续订回同一个 SID', hd2.get('sid') == sid_avt,
      '收到=%s 原=%s' % (hd2.get('sid'), sid_avt))
check('续订回的是新申请的 TIMEOUT', hd2.get('timeout') == 'Second-600',
      hd2.get('timeout', '(缺失)'))

# --- 10.6 非法订阅必须被拒（412），不能默默给个 SID ---
st, _, _ = raw_request('SUBSCRIBE', '/upnp/event/AVTransport', {
    'CALLBACK': '<%s>' % cb_avt.url(), 'NT': 'upnp:event', 'SID': sid_avt})
check('同时带 CALLBACK 与 SID 回 412', '412' in st, st)

st, _, _ = raw_request('SUBSCRIBE', '/upnp/event/AVTransport', {'NT': 'upnp:event'})
check('两者都不带回 412', '412' in st, st)

st, _, _ = raw_request('SUBSCRIBE', '/upnp/event/AVTransport',
                       {'SID': 'uuid:11111111-0000-0000-0000-000000000000-99',
                        'TIMEOUT': 'Second-600'})
check('续订未知 SID 回 412', '412' in st, st)

st, _, _ = raw_request('SUBSCRIBE', '/upnp/event/AVTransport', {
    'CALLBACK': '<%s>' % cb_avt.url(), 'NT': 'upnp:device'})
check('NT 不是 upnp:event 回 412', '412' in st, st)

st, _, _ = raw_request('SUBSCRIBE', '/upnp/event/AVTransport',
                       {'CALLBACK': 'no-brackets-here', 'NT': 'upnp:event'})
check('CALLBACK 里没有合法地址回 412', '412' in st, st)

# 未知服务名：给了 SID 也收不到事件，不如当场 404
st, _, _ = raw_request('SUBSCRIBE', '/upnp/event/NoSuchService',
                       {'CALLBACK': '<%s>' % cb_avt.url(), 'NT': 'upnp:event'})
check('订阅未知服务回 404', '404' in st, st)

# --- 10.7 RenderingControl：音量事件 ---
cb_rcs = FakeCallback()
st, hd3, _ = raw_request('SUBSCRIBE', '/upnp/event/RenderingControl', {
    'CALLBACK': '<%s>' % cb_rcs.url(), 'NT': 'upnp:event', 'TIMEOUT': 'Second-1800'})
check('RenderingControl 订阅回 200', st.startswith('HTTP/1.1 200'), st)
sid_rcs = hd3.get('sid', '')
evs = cb_rcs.wait(1, 6.0)
check('RenderingControl 订阅后收到初始事件', len(evs) >= 1, '收到 %d 条' % len(evs))
if evs:
    try:
        m = propmap(evs[0]['body'])
        check('音量初始事件含 Volume 且是真值 42', m.get('Volume') == '42',
              repr(m.get('Volume')))
    except Exception as ex:
        check('音量初始事件体是合法 XML', False, str(ex))

n = cb_rcs.count()
soap_post('SetVolume', SVC_RCS,
          '<Channel>Master</Channel><DesiredVolume>33</DesiredVolume>',
          control='RenderingControl')
evs = drain(cb_rcs, n)
check('改音量后收到事件', len(evs) >= 1, '收到 %d 条' % len(evs))
if evs:
    try:
        m = propmap(evs[-1]['body'])
        check('音量事件报的是刚设的 33（不是硬编码的假值）',
              m.get('Volume') == '33', repr(m.get('Volume')))
    except Exception as ex:
        check('音量事件体是合法 XML', False, str(ex))

# --- 10.8 ConnectionManager 的事件体要如实给协议声明 ---
cb_cms = FakeCallback()
st, _, _ = raw_request('SUBSCRIBE', '/upnp/event/ConnectionManager', {
    'CALLBACK': '<%s>' % cb_cms.url(), 'NT': 'upnp:event'})
check('ConnectionManager 订阅回 200', st.startswith('HTTP/1.1 200'), st)
evs = cb_cms.wait(1, 6.0)
check('ConnectionManager 订阅后收到初始事件', len(evs) >= 1, '收到 %d 条' % len(evs))
if evs:
    try:
        m = propmap(evs[0]['body'])
        check('SinkProtocolInfo 事件值与 GetProtocolInfo 一致',
              'video/mp4' in m.get('SinkProtocolInfo', ''),
              repr(m.get('SinkProtocolInfo'))[:120])
    except Exception as ex:
        check('ConnectionManager 事件体是合法 XML', False, str(ex))

# --- 10.9 退订之后不许再推 ---
st, _, _ = raw_request('UNSUBSCRIBE', '/upnp/event/AVTransport', {'SID': sid_avt})
check('UNSUBSCRIBE 回 200', st.startswith('HTTP/1.1 200'), st)

st, _, _ = raw_request('UNSUBSCRIBE', '/upnp/event/AVTransport', {'SID': sid_avt})
check('重复退订回 412', '412' in st, st)

st, _, _ = raw_request('UNSUBSCRIBE', '/upnp/event/AVTransport',
                       {'SID': 'uuid:11111111-0000-0000-0000-000000000000-98'})
check('退订未知 SID 回 412', '412' in st, st)

st, _, _ = raw_request('UNSUBSCRIBE', '/upnp/event/AVTransport', {})
check('退订不带 SID 回 412', '412' in st, st)

n = cb_avt.count()
# 用 SetAVTransportURI 而不是 Play：Play 在"没有媒体"时是空操作（忠实模拟真机），
# 状态不变就不会推事件，这条断言会变成"什么都没测到"的空过。
soap_post('SetAVTransportURI', SVC_AVT,
          '<CurrentURI>http://127.0.0.1:9/after-unsub.mp4</CurrentURI>'
          '<CurrentURIMetaData></CurrentURIMetaData>')
time.sleep(1.0)
check('退订后不再收到事件', cb_avt.count() == n,
      '退订后又收到 %d 条' % (cb_avt.count() - n))

# 退订一个、留一个：剩下的那个必须照常收
n_rcs = cb_rcs.count()
soap_post('SetVolume', SVC_RCS,
          '<Channel>Master</Channel><DesiredVolume>77</DesiredVolume>',
          control='RenderingControl')
evs = drain(cb_rcs, n_rcs)
check('未退订的 RenderingControl 仍能收到事件', len(evs) >= 1,
      '收到 %d 条' % len(evs))

raw_request('UNSUBSCRIBE', '/upnp/event/RenderingControl', {'SID': sid_rcs})

# 收尾：把靶机留在「正常投屏中」的状态。
# 后面的 dlna-probe.py 与 verify-device-selftest.sh 都从这个状态出发做自洽性
# 判断，留个半截状态会给它们制造假警报 —— 而假警报比没测更糟。
soap_post('SetAVTransportURI', SVC_AVT,
          '<CurrentURI>http://127.0.0.1:9/final.mp4</CurrentURI>'
          '<CurrentURIMetaData></CurrentURIMetaData>')
soap_post('Play', SVC_AVT, '<Speed>1</Speed>')

cb_avt.close()
cb_rcs.close()
cb_cms.close()


# ══════════════════════════════════════════════════════════════ 请求行变体

print('\n── 11. 请求行变体（RFC 7230 §5.3 要求服务端都接受）──')


def raw_line(request_line, timeout=10):
    raw = ('%s\r\nHost: %s:%d\r\n\r\n' % (request_line, HOST, PORT)).encode()
    s = socket.create_connection((HOST, PORT), timeout=timeout)
    try:
        s.sendall(raw)
        return s.recv(65536)
    finally:
        s.close()


# 绝对形式（部分嵌入式控制点与 Windows 组件会这么发）
data = raw_line('GET http://%s:%d/upnp/device.xml HTTP/1.1' % (HOST, PORT))
check('绝对形式请求行能取到设备描述', b'200 OK' in data,
      data.split(b'\r\n')[0].decode('iso-8859-1', 'replace'))

# 请求行里多余空格
data = raw_line('GET  /upnp/device.xml HTTP/1.1')
check('请求行多余空格也能取到设备描述', b'200 OK' in data,
      data.split(b'\r\n')[0].decode('iso-8859-1', 'replace'))

# 带查询串
data = raw_line('GET /upnp/device.xml?cache=1 HTTP/1.1')
check('带查询串也能取到设备描述', b'200 OK' in data,
      data.split(b'\r\n')[0].decode('iso-8859-1', 'replace'))


# ══════════════════════════════════════════════════════════════ 资源上限

print('\n── 12. 三处「无上限」的防御 ──')
print('   这一节刻意用畸形 / 异常请求去打，验的是「不被打死」，不是「正常请求能过」。')
print('   这台盒子只有 0.6GB 内存，三处里任何一处没上限都够让整个进程消失 ——')
print('   而它一消失，SSDP 一起陪葬，表现是「投着投着设备就没了」。')

# --- ① 请求体上限 ---
# 声明的长度远超上限，而且**一个字节的 body 都不发**。
# 原来的实现会先 new byte[contentLength] —— 声明这么大就当场分配，
# 而这台盒子只有 0.6GB。所以「检查必须在分配之前」是这个断言的真正内容。
#
# 长度取 100MB 而不是 Integer.MAX_VALUE：真的去 new 一个 2GB 数组的话，
# 桌面 JVM 上有可能**分配成功**，于是这台开发机被拖去读 2GB 的 body。
# 100MB 足够说明问题，又不会把跑测试的机器搭进去。
_big = socket.create_connection((HOST, PORT), timeout=8)
_big_resp = b''
try:
    _big.sendall(('POST /upnp/control/AVTransport HTTP/1.1\r\n'
                  'Host: %s:%d\r\n'
                  'SOAPAction: "urn:x#y"\r\n'
                  'Content-Length: 104857600\r\n\r\n' % (HOST, PORT)).encode())
    _big_resp = _big.recv(4096)
except Exception:
    # 没回响应（连接被关 / 超时）也当成"没被拒" —— 断言本来就是红的。
    # 不 catch 的话，这个异常会把整个驱动脚本带崩，后面所有用例都不跑了。
    _big_resp = b''
finally:
    _big.close()
check('超大 Content-Length 被拒（413），没有照着它去分配内存',
      b'413' in _big_resp,
      _big_resp.split(b'\r\n')[0].decode('iso-8859-1', 'replace') or '(无响应)')

# 服务必须还活着。这一条比上面那条更重要：
# 「拒绝了，但进程也被打死了」等于没防住。
_st, _, _ = raw_request('GET', '/upnp/device.xml')
check('被拒之后服务照常工作（没有被打死）', _st.startswith('HTTP/1.1 200'), _st)

# 反面对照：正常大小的 body 必须照常受理。
# 不做这一条的话，一个「把上限写成 0」的实现照样能让上面两条变绿。
_st, _, _ = soap_post('GetTransportInfo', SVC_AVT, '')
check('反面对照：正常大小的 body 照常受理', _st.startswith('HTTP/1.1 200'), _st)

# --- ①b body 读不满时必须回一条响应（而不是静默关连接）---
# 「芒果 TV 一直连接中」的根因候选之一就在这条路径上：控制点声明了
# Content-Length 却少发 / 不发 body，原来的实现读完就一声不响地把连接关掉 ——
# 控制点既等不到成功也等不到失败，界面就一直停在「连接中」。
# 判据见 .agent/mangotv-compat-plan.md §7 的 B。
# 手法：声明 100 字节、只发 5 字节，然后 shutdown(WR) 半关，让服务端读到 EOF
# （不半关的话服务端会阻塞到自己的 10 秒超时，那时它是走异常分支、发不出 400）。
_short = socket.create_connection((HOST, PORT), timeout=8)
_short_resp = b''
try:
    _short.sendall(('POST /upnp/control/AVTransport HTTP/1.1\r\n'
                    'Host: %s:%d\r\n'
                    'SOAPAction: "urn:x#y"\r\n'
                    'Content-Length: 100\r\n\r\n' % (HOST, PORT)).encode())
    _short.sendall(b'12345')
    _short.shutdown(socket.SHUT_WR)
    _short.settimeout(8)
    _short_resp = _short.recv(4096)
except Exception:
    _short_resp = b''
finally:
    try:
        _short.close()
    except Exception:
        pass
check('body 读不满时回一条响应（400），不静默关连接',
      b'400' in _short_resp,
      _short_resp.split(b'\r\n')[0].decode('iso-8859-1', 'replace')
      or '(无响应，连接被直接关掉)')

# --- ② 并发连接上限 ---
# 开一批连接但**一个字都不发**：服务端每条都会占住一个线程（阻塞在 read 上）。
# 无上限的话，一个端口扫描就能把 1MB/线程的栈吃光。
_idle = []
try:
    for _ in range(24):
        try:
            _c = socket.create_connection((HOST, PORT), timeout=5)
            _c.settimeout(3)
            _idle.append(_c)
        except Exception:
            break
    time.sleep(0.8)   # 让 accept 循环把该收的都收进去
    _served = 0
    for _c in _idle:
        try:
            _c.sendall(b'GET /upnp/device.xml HTTP/1.1\r\nHost: h\r\n\r\n')
            if b'200 OK' in _c.recv(4096):
                _served += 1
        except Exception:
            pass
    check('并发连接有上限：多余的连接被直接关掉，不是来者不拒',
          _served < len(_idle),
          '开了 %d 条空闲连接，其中 %d 条拿到了响应 ——'
          '全都拿到就说明一个上限都没有' % (len(_idle), _served))
    check('并发到顶的同时服务仍在正常工作（不是把自己锁死）', _served >= 1,
          '%d 条拿到了响应' % _served)
finally:
    for _c in _idle:
        try:
            _c.close()
        except Exception:
            pass

# --- ③ 订阅表上限 ---
# 连着订阅超过上限：最老的那条必须被淘汰，否则这张表可以无限涨。
_first_sid = None
_last_sid = None
for _i in range(40):
    _st, _hd, _ = raw_request('SUBSCRIBE', '/upnp/event/AVTransport',
                              {'CALLBACK': '<http://127.0.0.1:9/cb%d>' % _i,
                               'NT': 'upnp:event'})
    if not _st.startswith('HTTP/1.1 200'):
        break
    if _first_sid is None:
        _first_sid = _hd.get('sid', '')
    _last_sid = _hd.get('sid', '')

_st, _, _ = raw_request('UNSUBSCRIBE', '/upnp/event/AVTransport', {'SID': _first_sid})
check('订阅表有上限：最旧的订阅被淘汰（已不认这个 SID）', '412' in _st, _st)
_st, _, _ = raw_request('UNSUBSCRIBE', '/upnp/event/AVTransport', {'SID': _last_sid})
check('订阅表有上限：最新的订阅仍然有效', _st.startswith('HTTP/1.1 200'), _st)


# ══════════════════════════════════════════════════════════════ 13. 照成熟 DMR 补齐

print('\n── 13. 照成熟 DMR 补齐（描述字段 / SCPD 自洽 / 静音 / 错误码 / MX）──')
print('   这一节守的是两件事：「设备能被控制点认出来」和「声明了的必须做到」。')
print('   共同点是：不达标时宽松的控制点照样能用，所以本地怎么试都试不出来 ——')
print('   只有严格的控制点会**静默地**把设备划掉，连一句错误都不报。')


# --- 13.1 设备描述：字段齐不齐、顺序合不合 schema ---

st, hd, dev_body = raw_request('GET', '/upnp/device.xml')
dev_root = None
try:
    dev_root = ET.fromstring(dev_body.decode('utf-8'))
except Exception as e:
    check('13.1 设备描述可解析', False, str(e))

if dev_root is not None:
    dev_el = None
    for el in dev_root.iter():
        if el.tag.endswith('}device'):
            dev_el = el
            break

    def lname(t):
        return t.split('}')[-1]

    # --- 顺序：UDA 1.0 的 device-1-0 schema 定死了 <device> 子元素的次序。
    #     顺序错的话，按 schema 校验的控制点会**整份解析失败** ——
    #     不是"少读一个字段"，是这台设备在它眼里不存在。
    SCHEMA_ORDER = ['deviceType', 'friendlyName', 'manufacturer', 'manufacturerURL',
                    'modelDescription', 'modelName', 'modelNumber', 'modelURL',
                    'serialNumber', 'UDN', 'UPC', 'iconList', 'serviceList',
                    'deviceList', 'presentationURL']
    order = [lname(c.tag) for c in dev_el]
    # 除 dlna 扩展外，其余必须是 schema 顺序的一个子序列
    core = [x for x in order if x != 'X_DLNADOC']
    it = iter(SCHEMA_ORDER)
    in_order = all(any(x == y for y in it) for x in core)
    check('13.1 <device> 子元素顺序符合 device-1-0 schema', in_order,
          '实际顺序: %s' % order)
    check('13.1 dlna 扩展元素排在最后（schema 里属于「其它命名空间」）',
          order and order[-1] == 'X_DLNADOC', '实际顺序: %s' % order)

    def txt1(tag):
        for el in dev_root.iter():
            if lname(el.tag) == tag:
                return el.text or ''
        return None

    # --- 关键字段：一个都不能缺、也不能是空串 ---
    for tag, why in (
            ('manufacturer', '厂商标识，控制点有时按它做兼容性分支'),
            ('manufacturerURL', '厂商主页'),
            ('modelDescription', '型号描述，设备详情页显示它'),
            ('modelName', '型号名'),
            ('modelNumber', '型号版本'),
            ('modelURL', '型号主页'),
            ('serialNumber', '设备序列号'),
    ):
        v = txt1(tag)
        check('13.1 设备描述含 %s' % tag, bool(v and v.strip()),
              '%s（缺失或为空）' % why if not (v and v.strip()) else '')

    # --- DLNA 标记：这条是"控制点认不认这台设备"的分水岭 ---
    doc = txt1('X_DLNADOC')
    check('13.1 dlna:X_DLNADOC = DMR-1.50（渲染器类别声明）',
          doc == 'DMR-1.50', '实际 %r' % doc,
          note='声明了渲染器类别。缺了它，部分控制点（较新的国产投屏 SDK 尤其）'
               '根本不把设备列进投屏列表 —— 而 SSDP 那边看起来一切正常')
    check('13.1 没有误领 M-DMR-1.50（那是 DLNA Mobile 的类别）',
          'M-DMR' not in (doc or ''), '实际 %r' % doc,
          note='我们不是移动设备。领了那个标记，控制点会按移动端的规则来对待')

    # --- presentationURL：设备自带的 Web 界面（就是那张扫码上传页）---
    #
    # 这条断言原来写作「没有声明 presentationURL」，当时是对的：那会儿 "/"
    # 只会把 device.xml 本身喂给浏览器，声明出来等于画一个点开是乱码的按钮。
    # 批 2 起 "/" 真的是一张上传页了（浏览器带 Accept: text/html 才走它，
    # 见 WebCastEndpoints.handle），那条前提消失，断言跟着事实走。
    purl = (txt1('presentationURL') or '').strip()
    pm = re.match(r'^https?://([^/]+)/?$', purl)
    check('13.1 声明了 presentationURL，且是绝对 http 地址、路径为 /',
          pm is not None, '实际 %r' % purl,
          note='控制点据此在设备列表里画「打开设备页面」按钮，点开就是上传页')
    # 主机端口必须与 LOCATION 完全一致。LOCATION 用的是「组播**实际绑上**的那张
    # 网卡的 IPv4 + HTTP 真正在听的端口」（见 NetUtil / SsdpResponder）；
    # presentationURL 若自己再算一遍，端口回退（49152 被厂家自带的 DLNA 栈
    # 占了就往上移）之后就指向一个没人监听的端口 —— 现象正是
    # 「手机搜得到设备、点开设备页面却打不开」。
    lm = re.match(r'^https?://([^/]+)', loc or '')
    check('13.1 presentationURL 的主机端口与 LOCATION 完全一致（地址单一真源）',
          pm is not None and lm is not None and pm.group(1) == lm.group(1),
          'presentationURL=%r，LOCATION=%r' % (purl, loc))

    # --- 刻意不声明的字段：写上去就是撒谎 ---
    check('13.1 没有编造 UPC 码', txt1('UPC') is None,
          '实际 %r' % txt1('UPC'),
          note='UPC 是零售商品条码。我们不是商品，编一个假码没有任何好处，'
               '而 schema 里它是可选的')

    # --- 图标：声明了就必须给得出，而且尺寸必须对得上 ---
    icon_el = None
    for el in dev_root.iter():
        if lname(el.tag) == 'icon':
            icon_el = el
            break
    check('13.1 声明了 iconList（控制点设备列表里显示的图标）',
          icon_el is not None, 'device.xml 里没有 <icon>',
          note='图标是可选的，但声明了就必须给得出 —— 见下面那条')

    if icon_el is not None:
        ico = {}
        for c in icon_el:
            ico[lname(c.tag)] = (c.text or '').strip()
        check('13.1 icon 的子元素顺序符合 schema（mimetype/width/height/depth/url）',
              [lname(c.tag) for c in icon_el] ==
              ['mimetype', 'width', 'height', 'depth', 'url'],
              '实际: %s' % [lname(c.tag) for c in icon_el])
        check('13.1 icon 声明了 mimetype=image/png',
              ico.get('mimetype') == 'image/png', '实际 %r' % ico.get('mimetype'))

        i_st, i_hd, i_body = raw_request('GET', ico.get('url', '/'))
        check('13.1 iconList 里的 URL 真的取得到（声明了就要给得出）',
              i_st.startswith('HTTP/1.1 200'), '%s → %s' % (ico.get('url'), i_st))
        check('13.1 图标按二进制发（Content-Type 是 image/png）',
              'image/png' in i_hd.get('content-type', ''),
              i_hd.get('content-type', '(缺失)'))

        # 从 PNG 的 IHDR 里读出**真实**尺寸，与声明值比对。
        # 只比"声明值等于某个常量"是验不出来的：两边都写死 48 照样绿。
        def png_size(data):
            if len(data) < 24 or data[:8] != b'\x89PNG\r\n\x1a\n':
                return None
            if data[12:16] != b'IHDR':
                return None
            return (int.from_bytes(data[16:20], 'big'),
                    int.from_bytes(data[20:24], 'big'))

        real = png_size(i_body)
        check('13.1 发出去的是真 PNG（签名 + IHDR 都在）',
              real is not None, '前 16 字节: %r' % i_body[:16])
        if real is not None:
            check('13.1 iconList 声明的宽高与 PNG 实际像素一致',
                  (int(ico.get('width', '0')), int(ico.get('height', '0'))) == real,
                  '声明 %sx%s，实际 %dx%d'
                  % (ico.get('width'), ico.get('height'), real[0], real[1]),
                  note='按密度声明四档、实际却只发同一张图，就会这样对不上')


# --- 13.2 SCPD 自洽性：relatedStateVariable 必须指向表里真实存在的变量 ---

print('\n  13.2 SCPD 自洽性')


def scpd_model(body_bytes):
    """把 SCPD 解析成 (状态变量名集合, {action: [(参数名, 方向, 关联变量)]})"""
    root = ET.fromstring(body_bytes.decode('utf-8'))

    def loc(t):
        return t.split('}')[-1]

    state = set()
    for sv in root.iter():
        if loc(sv.tag) == 'stateVariable':
            for ch in sv:
                if loc(ch.tag) == 'name':
                    state.add(ch.text or '')

    actions = {}
    for a in root.iter():
        if loc(a.tag) != 'action':
            continue
        name = ''
        args = []
        for ch in a:                      # 只看直接子节点，避免把 argument 里的 name 当成 action 名
            if loc(ch.tag) == 'name':
                name = ch.text or ''
            elif loc(ch.tag) == 'argumentList':
                for ag in ch:
                    if loc(ag.tag) != 'argument':
                        continue
                    f = {'name': '', 'direction': '', 'relatedStateVariable': ''}
                    for x in ag:
                        k = loc(x.tag)
                        if k in f:
                            f[k] = x.text or ''
                    args.append((f['name'], f['direction'], f['relatedStateVariable']))
        if name:
            actions[name] = args
    return state, actions


NEED_ACTIONS = {
    'AVTransport': ['SetAVTransportURI', 'GetMediaInfo', 'GetTransportInfo',
                    'GetPositionInfo', 'GetDeviceCapabilities', 'GetTransportSettings',
                    'GetCurrentTransportActions', 'Stop', 'Play', 'Pause', 'Seek',
                    'Next', 'Previous', 'SetPlayMode'],
    'ConnectionManager': ['GetProtocolInfo', 'GetCurrentConnectionIDs',
                          'GetCurrentConnectionInfo'],
    'RenderingControl': ['ListPresets', 'SelectPreset', 'GetVolume', 'SetVolume',
                         'GetMute', 'SetMute'],
}
# 每个服务里"必须有出参"的 action —— 这是原来漏得最彻底的一处：
# 一个 out 参数都没声明，控制点拿到响应也不知道该去哪个变量取。
NEED_OUT = {
    'AVTransport': ['GetMediaInfo', 'GetTransportInfo', 'GetPositionInfo',
                    'GetDeviceCapabilities', 'GetTransportSettings',
                    'GetCurrentTransportActions'],
    'ConnectionManager': ['GetProtocolInfo', 'GetCurrentConnectionIDs',
                          'GetCurrentConnectionInfo'],
    'RenderingControl': ['ListPresets', 'GetVolume', 'GetMute'],
}

for short in ('AVTransport', 'ConnectionManager', 'RenderingControl'):
    s_st, s_hd, s_body = raw_request('GET', '/upnp/%s.xml' % short)
    try:
        state, actions = scpd_model(s_body)
    except Exception as e:
        check('13.2 %s.xml 可解析' % short, False, str(e))
        continue

    # (1) 每条 argument 的 relatedStateVariable 都必须在 serviceStateTable 里
    dangling = []
    for aname, aargs in actions.items():
        for pname, _dir, arv in aargs:
            if arv and arv not in state:
                dangling.append('%s.%s → %s' % (aname, pname, arv))
    check('13.2 %s：每个 relatedStateVariable 都指向表里存在的变量' % short,
          not dangling, '悬空引用: %s' % dangling[:6],
          note='严格校验 SCPD 的控制点会因为悬空引用**整份解析失败** ——'
               '不是少一个功能，是这台设备在它眼里不存在')

    # (2) action 清单：代码里处理了、SCPD 里却没声明的，等于死代码
    missing = [a for a in NEED_ACTIONS[short] if a not in actions]
    check('13.2 %s：SCPD 声明了实现支持的全部 action' % short, not missing,
          '缺: %s' % missing,
          note='控制点不会发 SCPD 里没有的 action，所以这些分支在真机上'
               '根本不会被执行')

    # (3) 出参
    no_out = [a for a in NEED_OUT[short]
              if a in actions and not any(d == 'out' for _, d, _ in actions[a])]
    check('13.2 %s：有返回值的 action 都声明了 out 参数' % short, not no_out,
          '缺 out 参数: %s' % no_out,
          note='控制点靠 out 参数知道响应里该有哪些字段、以及去哪个变量取值')

# (4) SetPlaySpeed 不是 AVTransport:1 的标准 action，不能出现在任何 :1 的 SCPD 里
_s_st, _s_hd, _avt_body = raw_request('GET', '/upnp/AVTransport.xml')
check('13.2 SCPD 里没有非标准的 SetPlaySpeed',
      b'SetPlaySpeed' not in _avt_body,
      'AVTransport.xml 里出现了 SetPlaySpeed',
      note='AVTransport:1 的标准 action 里没有它（可对照 Platinum 的 SCPD）。'
           '写进 :1 的 SCPD 本身就是错的 —— 控制点会以为这台设备支持变速播放')


# --- 13.3 SetMute / GetMute 必须真的接通 ---

print('\n  13.3 静音（SetMute / GetMute）')


def rcs(action, extra=''):
    return soap_post(action, SVC_RCS,
                     '<InstanceID>0</InstanceID><Channel>Master</Channel>' + extra,
                     control='RenderingControl')


def read_mute():
    _st, _hd, b = rcs('GetMute')
    return find_xml_text(b, 'CurrentMute'), _st


for raw_value, want, label in (('1', '1', '1'),
                               ('0', '0', '0'),
                               ('true', '1', 'true（UPnP 布尔的另一种合法写法）'),
                               ('false', '0', 'false'),
                               ('yes', '1', 'yes（规范允许，漏了它静音会**反向**）'),
                               ('no', '0', 'no')):
    st2, _, _ = rcs('SetMute', '<DesiredMute>%s</DesiredMute>' % raw_value)
    got, gst = read_mute()
    check('13.3 SetMute(%s) → GetMute 回 %s' % (label, want),
          st2.startswith('HTTP/1.1 200') and got == want,
          'SetMute 回 %s，GetMute 回 %r（期望 %r）' % (st2, got, want))

# 事件里的 Mute 也要跟着走 —— 否则另一台遥控设备上的开关会自己弹回来
cb_rc = FakeCallback()
_st, _hd, _ = raw_request('SUBSCRIBE', '/upnp/event/RenderingControl',
                          {'CALLBACK': '<%s>' % cb_rc.url(), 'NT': 'upnp:event'})
rc_evs = cb_rc.wait(1, 6.0)
rc_init_mute = None
if rc_evs:
    try:
        rc_init_mute = propmap(rc_evs[0]['body']).get('Mute')
    except Exception:
        rc_init_mute = None

rcs('SetMute', '<DesiredMute>1</DesiredMute>')
rc_new = drain(cb_rc, len(rc_evs), 6.0)
rc_mute_after = None
for e in rc_new:
    try:
        m = propmap(e['body'])
    except Exception:
        continue
    if 'Mute' in m:
        rc_mute_after = m['Mute']
check('13.3 事件里的 Mute 跟着 SetMute 走（不是写死的常量）',
      rc_mute_after == '1',
      '初始事件 Mute=%r，SetMute(1) 之后收到的事件 Mute=%r'
      % (rc_init_mute, rc_mute_after),
      note='写死 "0" 的话，用户按了静音，另一个遥控器上的开关会自己弹回来')
# 收尾：把静音关掉，免得影响后面的断言
rcs('SetMute', '<DesiredMute>0</DesiredMute>')
cb_rc.close()


# --- 13.4 「语法合法但做不到」必须如实回 701 ---

print('\n  13.4 错误码：做不到就回 701，不假装成功')


def fault_code(body_bytes):
    return find_xml_text(body_bytes, 'errorCode')


for act, args_xml, label in (
        ('Next', '<InstanceID>0</InstanceID>', 'Next'),
        ('Previous', '<InstanceID>0</InstanceID>', 'Previous'),
):
    _st, _hd, b = soap_post(act, SVC_AVT, args_xml)
    check('13.4 %s 回 701 Transition not available' % label,
          fault_code(b) == '701', '实际 errorCode=%r' % fault_code(b),
          note='规范里这两个是必选 action，但没有播放列表时正确回应是 701。'
               '回 200 空响应的话，控制点会以为"切歌成功"，而界面上什么都没发生')

for mode, want_code, label in (('NORMAL', None, 'NORMAL（我们本来就是它，回 200）'),
                               ('SHUFFLE', '701', 'SHUFFLE（做不到，回 701）')):
    _st, _hd, b = soap_post('SetPlayMode', SVC_AVT,
                            '<InstanceID>0</InstanceID><NewPlayMode>%s</NewPlayMode>' % mode)
    got = fault_code(b)
    check('13.4 SetPlayMode(%s)' % label, got == want_code,
          '实际 errorCode=%r（期望 %r）' % (got, want_code),
          note='判据刻意收得很紧：只放行"设成我们本来就处于的状态"。'
               '一律回 200 就是假装支持，控制点会把界面画成"已设为随机播放"')

for preset, want_code, label in (('FactoryDefaults', None, 'FactoryDefaults'),
                                 ('Night', '701', 'Night（做不到）')):
    _st, _hd, b = soap_post('SelectPreset', SVC_RCS,
                            '<InstanceID>0</InstanceID><PresetName>%s</PresetName>' % preset,
                            control='RenderingControl')
    got = fault_code(b)
    check('13.4 SelectPreset(%s)' % label, got == want_code,
          '实际 errorCode=%r（期望 %r）' % (got, want_code))


# --- 13.5 协议清单不能声明做不到的格式 ---

print('\n  13.5 协议清单与实现一致（声明了却做不到 = 对控制点撒谎）')

_st, _hd, b = soap_post('GetProtocolInfo', SVC_CMS, '', control='ConnectionManager')
sink2 = find_xml_text(b, 'Sink') or ''
check('13.5 Sink 里声明了 image/*（图片通道已实现）',
      'image/jpeg' in sink2.lower() and 'image/png' in sink2.lower(),
      'Sink=%s' % sink2[:120],
      note='这条原来是反过来的：当时实现里根本没有图片这条路，所以刻意不声明 —— '
           '声明了却做不到，控制点（相册、文件管理器）就会把图片推过来然后必然失败。'
           '现在图片通道（KIND_IMAGE + BitmapFactory）已经落地，清单必须加回来；'
           '反过来，做到了却不声明，相册就不会把照片推过来，这条通道等于白做')

cb_cm = FakeCallback()
_st, _hd, _ = raw_request('SUBSCRIBE', '/upnp/event/ConnectionManager',
                          {'CALLBACK': '<%s>' % cb_cm.url(), 'NT': 'upnp:event'})
cm_evs = cb_cm.wait(1, 6.0)
cm_sink = None
if cm_evs:
    try:
        cm_sink = propmap(cm_evs[0]['body']).get('SinkProtocolInfo')
    except Exception:
        cm_sink = None
check('13.5 事件里的 SinkProtocolInfo 与 GetProtocolInfo 是同一份',
      cm_sink is not None and cm_sink == sink2,
      '事件=%r\n         GetProtocolInfo=%r' % (cm_sink, sink2),
      note='两个出口各写一份的话，改一处忘一处，控制点会看到"声明的"和'
           '"事件报的"不一致')


# --- 13.6 M-SEARCH 必须按 MX 随机延迟 ---
#
# 规范（UDA 1.0 §1.3.2）要求设备在 0~MX 秒之间**随机**延迟之后才应答，
# 目的是把多台设备、多个搜索目标的应答在时间上错开，减少 UDP 碰撞。
# 立即连发的话，一次搜索就连发最多 6 条，几台设备同网时碰撞概率相当高 ——
# 表现是「有时搜得到、有时搜不到」，而且完全看不出规律。

print('\n  13.6 M-SEARCH 的 MX 随机延迟')


def msearch_first_delay(mx, st_target='upnp:rootdevice'):
    """发一条 M-SEARCH，返回**第一条应答**到达所用的秒数；超时返回 None"""
    msg = ('M-SEARCH * HTTP/1.1\r\n'
           'HOST: 239.255.255.250:1900\r\n'
           'MAN: "ssdp:discover"\r\n'
           'MX: %d\r\n'
           'ST: %s\r\n\r\n' % (mx, st_target)).encode('utf-8')
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        t0 = time.monotonic()
        s.sendto(msg, (HOST, SSDP_PORT))
        s.settimeout(6.0)
        try:
            s.recvfrom(4096)
        except socket.timeout:
            return None
        return time.monotonic() - t0
    finally:
        s.close()


def msearch_batch(count, mx, st_target='upnp:rootdevice', window=0.1, total=4.0):
    """一次发 count 条 M-SEARCH，返回 (收到总数, 前 window 秒内收到的条数)

    为什么这么测：延迟是随机的，逐条计时去断言"确实等了"必然变成偶发红。
    但"**8 条全都在 100ms 内回来**"的概率是 (0.101)^8 ≈ 1e-8 ——
    这就把一个随机行为变成了确定性断言。
    """
    msg = ('M-SEARCH * HTTP/1.1\r\n'
           'HOST: 239.255.255.250:1900\r\n'
           'MAN: "ssdp:discover"\r\n'
           'MX: %d\r\n'
           'ST: %s\r\n\r\n' % (mx, st_target)).encode('utf-8')
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    got = early = 0
    try:
        for _ in range(count):
            s.sendto(msg, (HOST, SSDP_PORT))
        s.settimeout(window)
        while True:
            try:
                s.recvfrom(4096)
            except socket.timeout:
                break
            got += 1
            early += 1
        s.settimeout(total)
        while True:
            try:
                s.recvfrom(4096)
            except socket.timeout:
                break
            got += 1
        return got, early
    finally:
        s.close()


if SSDP_PORT > 0:
    d0 = msearch_first_delay(0)
    check('13.6 MX=0 时立刻应答（延迟确实是按 MX 算的，不是固定睡满）',
          d0 is not None and d0 < 0.3, '用了 %r 秒' % d0,
          note='MX=0 却还等半天，说明延迟跟 MX 无关')

    d2 = msearch_first_delay(2)
    check('13.6 MX=2 时在 MX 之内应答（延迟不会超过 MX）',
          d2 is not None and d2 <= 2.6, '用了 %r 秒' % d2,
          note='超过 MX 的话，控制点自己已经等不及了')

    got8, early8 = msearch_batch(8, 1)
    check('13.6 MX=1 的 8 次搜索全部收到应答（延迟没有把应答拖没）',
          got8 == 8, '收到 %d / 8' % got8)
    check('13.6 MX=1 时不是全部立刻回（真的做了 0~MX 的随机延迟）',
          early8 < 8, '8 条里 %d 条在 100ms 内就回了' % early8,
          note='全都秒回说明压根没做延迟 —— 多台设备同网时会 UDP 碰撞，'
               '表现为「有时搜得到、有时搜不到」')
else:
    check('13.6 M-SEARCH 延迟（跳过：SSDP 没起来）', False, 'SSDP 端口不可用')


# ══════════════════════════════════════════════════════════════ 14. 媒体元数据
# 原来的实现把元数据**只**用来猜「音频还是视频」，GetMediaInfo / GetPositionInfo
# 回读时永远回空 —— 而部分控制点会拿回读值和自己刚推的比对，回空会被判成
# 「设备没接收成功」，画面留在手机上不投了。回读必须原样，且转义必须正确
# （元数据本身就是一段 XML，不转义的话整条 SOAP 响应都是非法的）。

print('\n  14. 媒体元数据：原样回读 + 正确转义')

# 一份刻意「难缠」的元数据，一次覆盖三个坑：
#   中文   —— 之前有「字符数 vs 字节数」的 body 读取 bug，中文最容易踩
#   &amp;  —— 转义链：推的时候转义一次、回读时服务端再转义一次，两头必须对称
#   &#39;  —— DIDL 里单引号几乎都长这样（数字实体），歌名带单引号非常常见
DIDL_RAW = ('<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"'
            ' xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
            '<item id="1"><dc:title>Tom &amp; Jerry 夜曲 &#39;07</dc:title>'
            '<upnp:artist>周杰伦</upnp:artist>'
            '<upnp:class>object.item.audioItem.musicTrack</upnp:class>'
            '</item></DIDL-Lite>')
# 推进 SOAP 参数时要按 XML 规则转义一次（< > " &）
DIDL_ESC = (DIDL_RAW.replace('&', '&amp;').replace('<', '&lt;')
            .replace('>', '&gt;').replace('"', '&quot;'))
META_URI = 'http://192.168.1.9:8080/meta-check.mp3'

st, hd, body = soap_post('SetAVTransportURI', SVC_AVT,
                         '<CurrentURI>%s</CurrentURI>\n'
                         '<CurrentURIMetaData>%s</CurrentURIMetaData>' % (META_URI, DIDL_ESC))
check('14.1 带元数据的投屏返回 200', st.startswith('HTTP/1.1 200'), st)

st, hd, body_gm = soap_post('GetMediaInfo', SVC_AVT, '')
got_meta = find_xml_text(body_gm, 'CurrentURIMetaData')
check('14.2 GetMediaInfo 原样回读元数据（不是恒回空）',
      got_meta == DIDL_RAW,
      'got=%r ← 恒回空的实现在这里露馅' % got_meta[:60])

st, hd, body = soap_post('GetPositionInfo', SVC_AVT, '')
got_track = find_xml_text(body, 'TrackMetaData')
check('14.3 GetPositionInfo 的 TrackMetaData 与 GetMediaInfo 同源',
      got_track == DIDL_RAW,
      'got=%r ← 两处各回各的，控制点会看到"同一媒体、两个接口给的元数据不一样"'
      % got_track[:60])

# 回读值嵌入 SOAP 时必须被正确转义 —— 整条响应要仍是合法 XML。
# 原始串里有 < > &，不转义的话这条 GetMediaInfo 响应本身就解析不出来。
#
# 检查的必须是 body_gm（14.2 那条 GetMediaInfo 响应）。**这个 bug 是证伪抓出来的**：
# 第一版写的 `body` 在此处已经被 14.3 的 GetPositionInfo 覆盖 —— 那条响应是
# 转义过的，于是「没转义」这个破坏在它上面恒为 PASS（假绿）。变量名一样、
# 拿错响应，断言看起来还在跑，其实早就不检查它该检查的东西了。
check('14.4 回读值在响应里是转义过的（&lt; 而不是裸的 <）',
      b'&lt;DIDL-Lite' in body_gm,
      '裸 < 会把 SOAP 响应变成非法 XML —— 控制点那边是「整条解析失败」，'
      '不是「少个字段」。注意裸 DIDL 恰好是**合法** XML（它自己带命名空间声明），'
      '所以 XML 解析不报错、find_xml_text 只会拿到空文本 —— 两个断言必须一起看')

soap_post('Stop', SVC_AVT, '')
st, hd, body = soap_post('GetMediaInfo', SVC_AVT, '')
got_after = find_xml_text(body, 'CurrentURIMetaData')
check('14.5 停止后元数据回空（不残留上一部片子的）',
      got_after == '',
      'got=%r ← 留着的话，下一次投屏的间隙里控制点会读到上一部片子的元数据'
      % got_after[:40])


# ══════════════════════════════════════════════════════════════ 汇总

total = len(results)
passed = sum(1 for _, ok, _ in results if ok)
print('\n' + '=' * 62)
print('协议一致性：%d / %d 通过' % (passed, total))
if passed < total:
    print('\n失败项：')
    for name, ok, detail in results:
        if not ok:
            print('  · %s' % name)
            if detail:
                print('      %s' % detail.replace('\n', '\n      '))
print('=' * 62)
sys.exit(0 if passed == total else 1)
