#!/bin/bash
# Start this checkout's existing local data; never initialize or re-import a database.
set -euo pipefail
cd "$(dirname "$0")"
project_dir="$PWD"
local_dir="$project_dir/.local"
mode="${1:-}"
if [[ -n "$mode" && "$mode" != "--deps-only" ]]; then
  echo "Usage: ./start-local.sh [--deps-only]" >&2
  exit 1
fi
fail() { echo "$*" >&2; exit 1; }
[[ -f "$local_dir/application-local.yml" && -f "$local_dir/mysql/mysql.ibd" && -f "$local_dir/redis.conf" ]] ||
  fail "Existing local configuration/data missing. See LOCAL_SETUP.md; no database was initialized."
mkdir -p "$local_dir/logs"
umask 077
mysql_bin="${RUOYI_MYSQL_BIN:-/opt/homebrew/opt/mysql@8.4/bin}"
redis_bin="${RUOYI_REDIS_BIN:-/opt/homebrew/opt/redis/bin}"
[[ -x "$mysql_bin/mysqld" && -x "$redis_bin/redis-server" ]] || fail "MySQL/Redis binaries missing; set RUOYI_MYSQL_BIN and RUOYI_REDIS_BIN."

mysql_ready() { "$mysql_bin/mysqladmin" --no-defaults --socket="$local_dir/mysql.sock" -u root ping --silent >/dev/null 2>&1; }
if ! mysql_ready; then
  if lsof -nP -iTCP:3306 -sTCP:LISTEN >/dev/null 2>&1; then fail "Port 3306 belongs to another MySQL instance; nothing was stopped."; fi
  "$mysql_bin/mysqld" --no-defaults --basedir="$(dirname "$mysql_bin")" --datadir="$local_dir/mysql" \
    --bind-address=127.0.0.1 --port=3306 --mysqlx=0 --socket="$local_dir/mysql.sock" \
    --pid-file="$local_dir/mysql.pid" --log-error="$local_dir/logs/mysql.log" --daemonize
  for attempt in {1..20}; do mysql_ready && break; sleep 1; done
fi
mysql_ready || fail "MySQL startup failed; see .local/logs/mysql.log."
mysql_data_dir="$("$mysql_bin/mysql" --no-defaults --socket="$local_dir/mysql.sock" -u root --batch --skip-column-names -e 'SELECT @@datadir')"
[[ "${mysql_data_dir%/}" == "$local_dir/mysql" ]] || fail "MySQL uses another data directory; no data changed."

redis_ready() { [[ "$("$redis_bin/redis-cli" -h 127.0.0.1 -p 6379 PING 2>/dev/null || true)" == "PONG" ]]; }
if ! redis_ready; then
  if lsof -nP -iTCP:6379 -sTCP:LISTEN >/dev/null 2>&1; then fail "Port 6379 is already occupied; nothing was stopped."; fi
  "$redis_bin/redis-server" "$local_dir/redis.conf"
  for attempt in {1..20}; do redis_ready && break; sleep 1; done
fi
redis_ready || fail "Redis startup failed; see .local/logs/redis.log."
redis_data_dir="$("$redis_bin/redis-cli" -h 127.0.0.1 -p 6379 --raw CONFIG GET dir | tail -n 1)"
[[ "$redis_data_dir" == "$local_dir/redis" ]] || fail "Redis uses another data directory; nothing was stopped."
echo "MySQL and Redis ready; data belongs to this project."
[[ "$mode" == "--deps-only" ]] && exit 0

backend_pid="$(lsof -nP -t -iTCP:18080 -sTCP:LISTEN 2>/dev/null || true)"
if [[ -n "$backend_pid" ]]; then
  backend_command="$(ps -p "$backend_pid" -o command=)"
  case "$backend_command" in
    *"$project_dir/ruoyi-admin/target/"*) echo "Current backend already running (PID $backend_pid). Stop it before rebuilding."; exit 0 ;;
    *) fail "Port 18080 belongs to a different backend (PID $backend_pid). Stop that instance before starting this project." ;;
  esac
fi
if [[ -n "${RUOYI_JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$RUOYI_JAVA_HOME"
elif [[ -x /Applications/PyCharm.app/Contents/jbr/Contents/Home/bin/java ]]; then
  export JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home
fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
maven_bin="${RUOYI_MAVEN:-$HOME/.local/share/aigupiao-tools/apache-maven-3.9.9/bin/mvn}"
[[ -x "$maven_bin" ]] || fail "Maven missing; set RUOYI_MAVEN."
"$maven_bin" -B -ntp -s local-maven-settings.xml -pl ruoyi-admin -am -DskipTests -Dmaven.jar.forceCreation=true package
export RUOYI_LOG_PATH="$local_dir/logs"
command -v python3 >/dev/null || fail "Python 3 is required to detach the backend from the launching terminal."
backend_pid="$(python3 - "$java_bin" "$project_dir" "$local_dir" <<'PY'
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path

java, project, local = sys.argv[1:]
# Local dotenv defaults become real environment variables; already exported values take priority.
backend_env = os.environ.copy()
env_path = Path(local) / 'backend.env'
if env_path.is_file():
    for line_number, raw in enumerate(env_path.read_text().splitlines(), 1):
        parts = shlex.split(raw, comments=True)
        if not parts:
            continue
        if parts[0] == 'export':
            parts = parts[1:]
        if len(parts) != 1 or '=' not in parts[0]:
            raise SystemExit('Invalid backend.env assignment on line ' + str(line_number))
        name, value = parts[0].split('=', 1)
        if not re.fullmatch(r'[A-Z][A-Z0-9_]*', name):
            raise SystemExit('Invalid backend.env variable on line ' + str(line_number))
        backend_env.setdefault(name, value)
with (Path(local) / 'logs/backend-console.log').open('ab') as log:
    process = subprocess.Popen([
        java, '-Djava.awt.headless=true', '-jar', project + '/ruoyi-admin/target/ruoyi-admin.jar',
        '--spring.profiles.active=druid,local', '--spring.config.additional-location=file:' + local + '/',
    ], cwd=project, env=backend_env, stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
print(process.pid)
PY
)"
echo "$backend_pid" > "$local_dir/backend.pid"
for attempt in {1..30}; do
  kill -0 "$backend_pid" 2>/dev/null || fail "Backend startup failed; see .local/logs/backend-console.log."
  listening_pid="$(lsof -nP -t -iTCP:18080 -sTCP:LISTEN 2>/dev/null || true)"
  if [[ "$listening_pid" == "$backend_pid" ]]; then
    echo "Backend ready: http://127.0.0.1:18080 (PID $backend_pid)"
    exit 0
  fi
  sleep 1
done
fail "Backend not ready yet; see .local/logs/backend-console.log (PID $backend_pid)."
