#!/bin/bash
# Stop only processes verified to belong to this checkout. Keep data on disk.
set -euo pipefail
cd "$(dirname "$0")"
project_dir="$PWD"
local_dir="$project_dir/.local"
mode="${1:-}"
if [[ -n "$mode" && "$mode" != "--all" ]]; then
  echo "Usage: ./stop-local.sh [--all]" >&2
  exit 1
fi
backend_pid="$(lsof -nP -t -iTCP:18080 -sTCP:LISTEN 2>/dev/null || true)"
if [[ -n "$backend_pid" ]]; then
  backend_command="$(ps -p "$backend_pid" -o command=)"
  case "$backend_command" in
    *"$project_dir/ruoyi-admin/target/"*)
      kill "$backend_pid"
      for attempt in {1..20}; do
        kill -0 "$backend_pid" 2>/dev/null || break
        sleep 1
      done
      if kill -0 "$backend_pid" 2>/dev/null; then echo "Backend still shutting down; no other process was stopped." >&2; exit 1; fi
      echo "Current backend stopped."
      ;;
    *) echo "Port 18080 belongs to another project; nothing was stopped." >&2; exit 1 ;;
  esac
fi
[[ "$mode" == "--all" ]] || exit 0
redis_bin="${RUOYI_REDIS_BIN:-/opt/homebrew/opt/redis/bin}"
if [[ "$("$redis_bin/redis-cli" -h 127.0.0.1 -p 6379 PING 2>/dev/null || true)" == "PONG" ]]; then
  redis_data_dir="$("$redis_bin/redis-cli" -h 127.0.0.1 -p 6379 --raw CONFIG GET dir | tail -n 1)"
  [[ "$redis_data_dir" == "$local_dir/redis" ]] || { echo "Redis belongs to another project; nothing else was stopped." >&2; exit 1; }
  "$redis_bin/redis-cli" -h 127.0.0.1 -p 6379 SHUTDOWN SAVE
  echo "Current Redis saved and stopped."
fi
mysql_bin="${RUOYI_MYSQL_BIN:-/opt/homebrew/opt/mysql@8.4/bin}"
if "$mysql_bin/mysqladmin" --no-defaults --socket="$local_dir/mysql.sock" -u root ping --silent >/dev/null 2>&1; then
  mysql_data_dir="$("$mysql_bin/mysql" --no-defaults --socket="$local_dir/mysql.sock" -u root --batch --skip-column-names -e 'SELECT @@datadir')"
  [[ "${mysql_data_dir%/}" == "$local_dir/mysql" ]] || { echo "MySQL belongs to another project; nothing else was stopped." >&2; exit 1; }
  "$mysql_bin/mysqladmin" --no-defaults --socket="$local_dir/mysql.sock" -u root shutdown
  echo "Current MySQL saved and stopped."
fi
