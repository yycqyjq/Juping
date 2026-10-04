#!/usr/bin/env python3
"""最小 HTTP 媒体服务：支持 Range/206 + HEAD。

为什么不用 `python3 -m http.server`：它**不支持 Range**，而海信那套老
MediaPlayer（AwesomePlayer/CmpbPlayer）prepare 时会发 `Range: bytes=0-`，
拿不到 206 就直接 `error (32769)` —— 看着像"编解码不兼容"，其实是服务端不支持分段。

用法：
    python3 tools/serve-media.py <目录> [端口]

配合投屏自测：
    python3 tools/dlna-probe.py 192.168.1.8 --http-port 49152 \\
        --play http://<本机IP>:8080/test60.mp4
"""
import http.server
import os
import re
import socketserver
import sys

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else '.')
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8080


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def log_message(self, fmt, *args):
        sys.stderr.write('%s - %s\n' % (self.address_string(), fmt % args))

    def send_head(self):
        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            self.send_error(404)
            return None
        size = os.path.getsize(path)
        ctype = 'video/mp4'
        rng = self.headers.get('Range')
        f = open(path, 'rb')
        if rng:
            m = re.match(r'bytes=(\d*)-(\d*)', rng.strip())
            if m:
                start = int(m.group(1)) if m.group(1) else 0
                end = int(m.group(2)) if m.group(2) else size - 1
                end = min(end, size - 1)
                if start > end:
                    self.send_error(416)
                    f.close()
                    return None
                self.send_response(206)
                self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, size))
                self.send_header('Content-Length', str(end - start + 1))
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Content-Type', ctype)
                self.end_headers()
                f.seek(start)
                self._limit = end - start + 1
                return f
        self.send_response(200)
        self.send_header('Content-Length', str(size))
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Type', ctype)
        self.end_headers()
        self._limit = None
        return f

    def copyfile(self, src, dst):
        limit = getattr(self, '_limit', None)
        if limit is None:
            return super().copyfile(src, dst)
        left = limit
        while left > 0:
            buf = src.read(min(65536, left))
            if not buf:
                break
            dst.write(buf)
            left -= len(buf)


# 必须用 ThreadingTCPServer，不能用单线程的 TCPServer。
#
# 播放中盒子会**长时间占着一条 Range 连接**（边下边解），单线程服务在它读完之前
# 不会再 accept 下一个连接 —— 于是应用侧的宽高探测（VideoAspectProbe 会发一个
# HEAD/GET）只能排队等，日志里表现为「SetAVTransportURI 卡了 24 秒」
# 「prepare 耗时 53 秒」。那是**服务端的排队**，不是应用卡死 ——
# 单线程版本让 2026-10-04 那次排查差点误判成应用引入了死锁（线程转储里其实
# 干干净净）。改成多线程后，并发的 Range/探测请求互不阻塞。
socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer.daemon_threads = True
with socketserver.ThreadingTCPServer(('0.0.0.0', PORT), Handler) as httpd:
    sys.stderr.write('serving %s on :%d (threaded)\n' % (ROOT, PORT))
    sys.stderr.flush()
    httpd.serve_forever()
