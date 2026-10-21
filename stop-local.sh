#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
for service in backend redis mysql; do
  pid_file=".local/$service.pid"
  if [[ -f "$pid_file" ]]; then
    service_pid="$(cat "$pid_file")"
    if kill -0 "$service_pid" 2>/dev/null; then
      service_command="$(ps -p "$service_pid" -o command=)"
      case "$service:$service_command" in
        backend:*"$PWD/ruoyi-admin/target/ruoyi-admin.jar"*|redis:*redis-server*|mysql:*"$PWD/.local/mysql"*)
          kill "$service_pid"
          echo "Stopped $service (PID $service_pid)"
          ;;
        *) echo "PID $service_pid does not match $service; skipped" ;;
      esac
    fi
  fi
done
