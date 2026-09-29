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
import xml.etree.ElementTree as ET

HOST = '127.0.0.1'
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 49152
CALL_LOG = sys.argv[2] if len(sys.argv) > 2 else '/tmp/juping-calls.log'
# SSDP 用临时端口，由服务端实际绑上后回传（见 run.sh 解析 READY 行）
SSDP_PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 0

NS_SOAP = '{http://schemas.xmlsoap.org/soap/envelope/}'
SVC_AVT = 'urn:schemas-upnp-org:service:AVTransport:1'
SVC_CMS = 'urn:schemas-upnp-org:service:ConnectionManager:1'
SVC_RCS = 'urn:schemas-upnp-org:service:RenderingControl:1'

# 必须与 ProtocolTestServer.java 里的 UUID 一致
DEV_UUID = '11111111-2222-3333-4444-555555555555'
DEV_TYPE = 'urn:schemas-upnp-org:device:MediaRenderer:1'
ALL_SERVICE_TYPES = (SVC_AVT, SVC_CMS, SVC_RCS)

results = []


def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))
    mark = 'PASS' if ok else 'FAIL'
    line = '  [%s] %s' % (mark, name)
    if detail:
        line += '\n         %s' % detail.replace('\n', '\n         ')
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

soap_post('Stop', SVC_AVT, '')
st, hd, body = soap_post('GetTransportInfo', SVC_AVT, '')
check('Stop 之后状态是 STOPPED',
      find_xml_text(body, 'CurrentTransportState') == 'STOPPED',
      'got=%r' % find_xml_text(body, 'CurrentTransportState'))


# ══════════════════════════════════════════════════════════════ 6. 健壮性

print('\n── 6. 健壮性与边界 ──')

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


print('\n── 7. SSDP 设备发现（手机搜不搜得到这台设备）──')

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

print('\n── 8. 发现链路闭环（SSDP → 设备描述 → 服务描述）──')
print('   控制点真实走的就是这条链：搜到设备 → 按 LOCATION 抓描述 → 按 SCPDURL 抓服务。')
print('   任何一环断开，表现都是「搜到了却投不了屏」。')

loc = first.get('location', '')
loc_path = '/' + loc.split('/', 3)[3] if loc.count('/') >= 3 else '/upnp/device.xml'

st_line, _, dev_xml = raw_request('GET', loc_path)
check('顺着 LOCATION 抓得到设备描述', st_line.startswith('HTTP/1.1 200'), '%s → %s' % (loc_path, st_line))

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


# ══════════════════════════════════════════════════════════════ 请求行变体

print('\n── 9. 请求行变体（RFC 7230 §5.3 要求服务端都接受）──')


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
