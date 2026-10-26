#!/usr/bin/env bash
# 使用现成的 JAR 构建镜像；构建上下文只包含 Dockerfile 和 JAR。
set -euo pipefail

task_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_context=''
trap 'if [[ -n "$task_context" ]]; then rm -rf -- "$task_context"; fi' EXIT
fail() { printf '错误：%s\n' "$*" >&2; exit 1; }

if [[ "${1:-}" = --help || "${1:-}" = -h ]]; then
    printf '用法：bash build-docker.sh [JAR路径] [镜像名:标签]\n默认使用脚本同目录的 ruoyi-admin.jar，镜像名为 ai-stock-backend:latest。\n仓库内也可读取 ruoyi-admin/target/ruoyi-admin.jar。\n'
    exit 0
fi
[[ $# -le 2 ]] || fail '最多接受 JAR 路径、镜像名两个参数。'
task_jar="${1:-$task_script_dir/ruoyi-admin.jar}"
if [[ $# -eq 0 && ! -f "$task_jar" ]]; then
    task_jar="$task_script_dir/../../ruoyi-admin/target/ruoyi-admin.jar"
fi
task_image="${2:-ai-stock-backend:latest}"
[[ -s "$task_jar" ]] || fail '找不到非空 JAR，请传入已构建的 ruoyi-admin.jar 路径。'
[[ -n "$task_image" && "$task_image" != -* ]] || fail '镜像名不能为空或以 - 开头。'
command -v docker >/dev/null 2>&1 || fail '未安装 Docker，请在已安装 Docker 的服务器上执行。'
docker info >/dev/null 2>&1 || fail 'Docker 服务不可用，请检查服务状态和当前用户权限。'

task_context="$(mktemp -d "${TMPDIR:-/tmp}/ai-stock-docker.XXXXXX")"
cp -- "$task_script_dir/Dockerfile" "$task_context/Dockerfile"
cp -- "$task_script_dir/.dockerignore" "$task_context/.dockerignore"
cp -- "$task_jar" "$task_context/ruoyi-admin.jar"
docker build --pull --tag "$task_image" "$task_context"
printf '镜像构建成功：%s\n请按 README.md 配置运行环境并启动容器。\n' "$task_image"
