#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - "$PWD" <<'PY'
from pathlib import Path
import os
import signal
import subprocess
import sys
import time
project = Path(sys.argv[1])
local = project / '.local'
supervisor = local / 'file-tunnel-supervisor.pid'
if supervisor.exists():
    pid = int(supervisor.read_text().strip())
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], text=True, capture_output=True)
    command = result.stdout.strip()
    if command and str(project / 'scripts/file-tunnel-supervisor.py') not in command:
        raise SystemExit('Supervisor PID belongs to another process; nothing stopped.')
    if command:
        os.kill(pid, signal.SIGTERM)
        for _ in range(80):
            result = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], text=True, capture_output=True)
            if not result.stdout.strip() or result.stdout.strip().startswith('Z'):
                break
            time.sleep(0.1)
        else:
            os.kill(pid, signal.SIGKILL)
    supervisor.unlink(missing_ok=True)
for name in ('file-ngrok', 'file-cloudflared', 'file-gateway'):
    pidfile = local / (name + '.pid')
    if not pidfile.exists():
        continue
    pid = int(pidfile.read_text().strip())
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], text=True, capture_output=True)
    command = result.stdout.strip()
    expected = {'file-ngrok': 'ngrok http http://127.0.0.1:18081', 'file-cloudflared': 'cloudflared tunnel --no-autoupdate --no-prechecks --edge-ip-version 4 --protocol http2 --url http://127.0.0.1:18081', 'file-gateway': str(project / 'scripts/file-url-gateway.py')}[name]
    if command and expected not in command:
        raise SystemExit(f'PID {pid} does not belong to the file tunnel; nothing stopped.')
    if command:
        os.kill(pid, signal.SIGTERM)
        for _ in range(50):
            result = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], text=True, capture_output=True)
            if not result.stdout.strip() or result.stdout.strip().startswith('Z'): break
            time.sleep(0.1)
        else:
            current = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], text=True, capture_output=True).stdout.strip()
            if current and expected not in current:
                raise SystemExit('PID changed ownership while stopping; nothing more stopped.')
            if current: os.kill(pid, signal.SIGKILL)
            time.sleep(0.2)
    pidfile.unlink(missing_ok=True)
(local / 'file-tunnel-url').unlink(missing_ok=True)
print('File download tunnel stopped.')
PY
