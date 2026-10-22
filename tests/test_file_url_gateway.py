"""Exercise the tunnel's exposed surface with two local HTTP servers, without ngrok or real files."""
import http.client
import http.server
import importlib.util
from pathlib import Path
import threading
import unittest

spec = importlib.util.spec_from_file_location('file_gateway', Path(__file__).resolve().parents[1] / 'scripts/file-url-gateway.py')
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)
PAYLOAD = b'original\x00\xffbytes'
SIGNED = '/webui/file-analysis/model-files/1/12345678-1234-1234-1234-123456789abc/9999999999/' + 'A' * 43 + '/file.png'

class Backend(http.server.BaseHTTPRequestHandler):
    requests = []
    def log_message(self, *_): pass
    def do_GET(self): self.reply()
    def do_HEAD(self): self.reply()
    def reply(self):
        self.requests.append((self.command, self.path, self.headers.get('Authorization')))
        self.send_response(200)
        self.send_header('Content-Type', 'image/png')
        self.send_header('Content-Length', str(len(PAYLOAD)))
        self.send_header('Cache-Control', 'private, no-store')
        self.end_headers()
        if self.command != 'HEAD': self.wfile.write(PAYLOAD)

class GatewayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.backend = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Backend)
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), gateway.Gateway)
        original_connection = http.client.HTTPConnection
        gateway.UPSTREAM = lambda host, port, timeout: original_connection('127.0.0.1', cls.backend.server_port, timeout=timeout)
        for server in (cls.backend, cls.server):
            threading.Thread(target=server.serve_forever, daemon=True).start()
    @classmethod
    def tearDownClass(cls):
        for server in (cls.server, cls.backend): server.shutdown(); server.server_close()
    def setUp(self): Backend.requests.clear()
    def request(self, method, path):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port)
        connection.request(method, path, headers={'Authorization': 'Bearer must-not-forward'})
        response = connection.getresponse()
        result = response.status, response.getheader('Content-Type'), response.read()
        connection.close()
        return result
    def test_signed_download_preserves_bytes_and_drops_business_jwt(self):
        self.assertEqual((200, 'image/png', PAYLOAD), self.request('GET', SIGNED))
        self.assertEqual([('GET', SIGNED, None)], Backend.requests)
    def test_head_allows_model_to_probe_file(self):
        self.assertEqual((200, 'image/png', b''), self.request('HEAD', SIGNED))
        self.assertEqual([('HEAD', SIGNED, None)], Backend.requests)
    def test_business_and_private_routes_never_reach_backend(self):
        for path in ['/login', '/getInfo', '/webui/config/current', '/webui/file-analysis', '/profile/a.png', '/webui/file-analysis/files/1234']:
            self.assertEqual(404, self.request('GET', path)[0])
        self.assertEqual([], Backend.requests)
    def test_malformed_signature_query_and_other_methods_never_reach_backend(self):
        for path in [SIGNED + '?userId=2', SIGNED.replace('A' * 43, 'bad'), SIGNED.replace('/1/', '/../'), SIGNED + '/other']:
            self.assertEqual(404, self.request('GET', path)[0])
        self.assertEqual(501, self.request('POST', SIGNED)[0])
        self.assertEqual([], Backend.requests)

if __name__ == '__main__': unittest.main()
