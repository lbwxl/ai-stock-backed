#!/usr/bin/env python3
"""Local ngrok upstream: exposes only signed model download URLs, with no URL access logging."""
import http.client
import http.server
import re
import urllib.parse

UPSTREAM = http.client.HTTPConnection
ALLOWED = re.compile(r'/webui/file-analysis/model-files/[1-9][0-9]*/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9]+/[A-Za-z0-9_-]{43}/[^/?#]+')

class Gateway(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Signed URLs are credentials; never log them.

    def do_GET(self):
        self.forward()

    def do_HEAD(self):
        self.forward()

    def forward(self):
        url = urllib.parse.urlsplit(self.path)
        if url.scheme or url.netloc or url.query or url.fragment or not ALLOWED.fullmatch(url.path):
            self.send_error(404)
            return
        upstream = UPSTREAM('127.0.0.1', 18080, timeout=120)
        started = False
        try:
            upstream.request(self.command, url.path)
            response = upstream.getresponse()
            print(f"file_download method={self.command} status={response.status} bytes={response.getheader('Content-Length', 'unknown')}", flush=True)
            self.send_response(response.status)
            for key in ['Content-Type', 'Content-Length', 'Content-Disposition', 'Cache-Control', 'X-Content-Type-Options']:
                value = response.getheader(key)
                if value:
                    self.send_header(key, value)
            self.end_headers()
            started = True
            if self.command != 'HEAD':
                while True:
                    data = response.read(65536)
                    if not data:
                        break
                    self.wfile.write(data)
        except (OSError, http.client.HTTPException):
            if not started:
                self.send_error(502)
        finally:
            upstream.close()

if __name__ == '__main__':
    http.server.ThreadingHTTPServer(('127.0.0.1', 18081), Gateway).serve_forever()
