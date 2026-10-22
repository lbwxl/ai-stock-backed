#!/bin/bash
# Supervised local file tunnel. No login/admin/analysis routes are exposed.
set -euo pipefail
cd "$(dirname "$0")"
umask 077
provider="${1:-ngrok}"
case "$provider" in ngrok) tunnel_program=ngrok ;; cloudflare) tunnel_program=cloudflared ;; *) echo 'Usage: ./start-file-tunnel.sh [ngrok|cloudflare]' >&2; exit 1 ;; esac
command -v "$tunnel_program" >/dev/null || { echo "Install $tunnel_program first." >&2; exit 1; }
python3 - "$PWD" "$provider" <<'PY'
import json
from pathlib import Path
import socket
import subprocess
import sys
import time

project = Path(sys.argv[1])
provider = sys.argv[2]
local = project / '.local'
logs = local / 'logs'
logs.mkdir(parents=True, exist_ok=True)
for port in ((18081, 4040) if provider == 'ngrok' else (18081,)):
    with socket.socket() as sock:
        if sock.connect_ex(('127.0.0.1', port)) == 0:
            raise SystemExit(f'Port {port} is occupied; stop the prior file tunnel first.')
status_file = local / 'file-tunnel-status.json'
status_file.unlink(missing_ok=True)
with (logs / 'file-tunnel-supervisor.log').open('ab') as output:
    process = subprocess.Popen([sys.executable, str(project / 'scripts/file-tunnel-supervisor.py'), '--provider', provider],
                               cwd=project, stdin=subprocess.DEVNULL, stdout=output, stderr=output, start_new_session=True)
(local / 'file-tunnel-supervisor.pid').write_text(str(process.pid))
for _ in range(100):
    if process.poll() is not None:
        raise SystemExit('File tunnel supervisor failed; check .local/logs/file-tunnel-supervisor.log.')
    try:
        status = json.loads(status_file.read_text())
        if status['state'] == 'ready':
            print('File download tunnel ready:', status['url'])
            print('Supervisor will recreate expired tunnels and update the backend URL automatically.')
            break
    except (OSError, ValueError, KeyError):
        pass
    time.sleep(0.5)
else:
    print('File tunnel is still connecting in the background; check .local/file-tunnel-status.json.')
PY
