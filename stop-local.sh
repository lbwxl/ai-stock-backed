#!/bin/bash
# Stop this checkout backend; --all also stops shared Homebrew services.
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
# --all also stops the shared, Homebrew-managed local dependencies.
brew_bin="${RUOYI_BREW:-$(command -v brew || true)}"
[[ -n "$brew_bin" ]] || brew_bin=/opt/homebrew/bin/brew
[[ -x "$brew_bin" ]] || { echo "Homebrew missing; set RUOYI_BREW." >&2; exit 1; }
HOMEBREW_NO_AUTO_UPDATE=1 "$brew_bin" services stop redis
HOMEBREW_NO_AUTO_UPDATE=1 "$brew_bin" services stop mysql@8.4
echo "Homebrew MySQL and Redis stopped; global data preserved."
