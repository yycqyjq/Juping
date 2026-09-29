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
