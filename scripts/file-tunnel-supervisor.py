#!/usr/bin/env python3
"""Supervise the local file gateway and temporary tunnel; never log signed URLs or tokens."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request


def public_gateway_ready(url, timeout=5):
    try:
        with urllib.request.urlopen(url + '/__file_gateway_probe', timeout=timeout):
            return False
    except urllib.error.HTTPError as error:
        return error.code == 404 and b'Error response' in error.read(2048)
    except (OSError, ValueError):
        return False


def cloudflare_url(log):
    if 'Registered tunnel connection' not in log:
        return None
    urls = re.findall(r'https://[a-z0-9-]+\.trycloudflare\.com', log)
    return urls[0] if urls else None


class Supervisor:
    def __init__(self, project, provider):
        self.project = project
        self.local = project / '.local'
        self.logs = self.local / 'logs'
        self.provider = provider
        self.stop = threading.Event()
        self.gateway = None
        self.agent = None
        self.agent_name = 'file-ngrok' if provider == 'ngrok' else 'file-cloudflared'
        self.agent_log = self.logs / (self.agent_name + '.log')
        self.log_offset = 0
        self.url = None
        self.restarts = 0

    def status(self, state, message=''):
        value = {'provider': self.provider, 'state': state, 'url': self.url,
                 'restarts': self.restarts, 'updatedAt': int(time.time() * 1000), 'message': message}
        temporary = self.local / 'file-tunnel-status.tmp'
        temporary.write_text(json.dumps(value))
        temporary.replace(self.local / 'file-tunnel-status.json')

    def launch(self, name, command):
        with (self.logs / (name + '.log')).open('ab') as output:
            child = subprocess.Popen(command, cwd=self.project, stdin=subprocess.DEVNULL,
                                     stdout=output, stderr=output, start_new_session=True)
        (self.local / (name + '.pid')).write_text(str(child.pid))
        return child

    def terminate(self, name, child):
        if child is not None and child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=3)
        (self.local / (name + '.pid')).unlink(missing_ok=True)

    def clear_url(self):
        self.url = None
        (self.local / 'file-tunnel-url').unlink(missing_ok=True)

    def start_agent(self):
        self.clear_url()
        self.status('recovering' if self.restarts else 'starting')
        self.log_offset = self.agent_log.stat().st_size if self.agent_log.exists() else 0
        command = ['ngrok', 'http', 'http://127.0.0.1:18081', '--inspect=false', '--log=stdout', '--log-format=json']
        if self.provider == 'cloudflare':
            command = ['cloudflared', 'tunnel', '--no-autoupdate', '--no-prechecks', '--edge-ip-version', '4',
                       '--protocol', 'http2', '--url', 'http://127.0.0.1:18081']
        self.agent = self.launch(self.agent_name, command)

    def agent_output(self):
        with self.agent_log.open('rb') as log:
            log.seek(self.log_offset)
            return log.read().decode('utf-8', errors='replace')

    def candidate_url(self, output):
        if self.provider == 'cloudflare':
            return cloudflare_url(output)
        try:
            with urllib.request.urlopen('http://127.0.0.1:4040/api/tunnels', timeout=2) as response:
                tunnels = json.load(response).get('tunnels', [])
            return next((t['public_url'] for t in tunnels if t.get('public_url', '').startswith('https://')), None)
        except (OSError, ValueError):
            return None

    def restart_agent(self, reason):
        self.clear_url()
        self.restarts += 1
        self.status('recovering', reason)
        print('File tunnel recovery:', reason, flush=True)
        self.terminate(self.agent_name, self.agent)
        self.agent = None
        if not self.stop.wait(min(2 * self.restarts, 15)):
            self.start_agent()

    def run(self):
        self.logs.mkdir(parents=True, exist_ok=True)
        with (self.local / 'file-tunnel.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            signal.signal(signal.SIGTERM, lambda *_: self.stop.set())
            signal.signal(signal.SIGINT, lambda *_: self.stop.set())
            try:
                for port in ((18081, 4040) if self.provider == 'ngrok' else (18081,)):
                    with socket.socket() as sock:
                        if sock.connect_ex(('127.0.0.1', port)) == 0:
                            raise RuntimeError('File tunnel port already occupied')
                self.gateway = self.launch('file-gateway', [sys.executable, str(self.project / 'scripts/file-url-gateway.py')])
                self.start_agent()
                started = time.monotonic()
                last_probe = 0
                failed_probes = 0
                while not self.stop.wait(1):
                    if self.gateway.poll() is not None:
                        raise RuntimeError('File download gateway stopped unexpectedly')
                    output = self.agent_output()
                    if self.agent.poll() is not None or (self.provider == 'cloudflare' and 'Unauthorized: Tunnel not found' in output):
                        self.restart_agent('Tunnel process exited or temporary tunnel was revoked')
                        started = time.monotonic()
                        failed_probes = 0
                        continue
                    candidate = self.url or self.candidate_url(output)
                    now = time.monotonic()
                    if candidate and (self.url is None or now - last_probe >= 30):
                        last_probe = now
                        if public_gateway_ready(candidate):
                            failed_probes = 0
                            if self.url is None:
                                self.url = candidate
                                temporary = self.local / 'file-tunnel-url.tmp'
                                temporary.write_text(candidate + '\n')
                                temporary.replace(self.local / 'file-tunnel-url')
                                self.status('ready')
                                print('File tunnel ready:', candidate, flush=True)
                        else:
                            failed_probes += 1
                            if self.url and failed_probes >= 2:
                                self.restart_agent('Public file gateway health check failed')
                                started = time.monotonic()
                                failed_probes = 0
                    if self.url is None and time.monotonic() - started > 90:
                        self.restart_agent('Temporary tunnel did not become reachable')
                        started = time.monotonic()
            except Exception as error:
                self.status('failed', type(error).__name__)
                raise
            finally:
                self.clear_url()
                self.terminate(self.agent_name, self.agent)
                self.terminate('file-gateway', self.gateway)
                self.status('stopped')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--provider', choices=['ngrok', 'cloudflare'], required=True)
    args = parser.parse_args()
    os.umask(0o077)
    Supervisor(Path(__file__).resolve().parents[1], args.provider).run()
