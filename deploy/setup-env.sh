#!/usr/bin/env bash
# 编辑下方配置区，与 ruoyi-admin.jar 放在同一目录后执行：bash setup-env.sh
set -euo pipefail
umask 077

task_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_jar_file="${BACKEND_JAR:-$task_script_dir/ruoyi-admin.jar}"
[[ "$task_jar_file" = /* ]] || task_jar_file="$PWD/$task_jar_file"

# Docker 等外部注入的同名变量优先，未设置时使用下方默认值；空值也会保留。
default_env() {
    local task_name="$1"
    if [[ -z "${!task_name+x}" ]]; then
        printf -v "$task_name" '%s' "$2"
    fi
    export "$task_name"
}

# ==================== 配置区开始 ====================
# 直接修改每行第二个参数。含 $、空格等字符的密码请保持单引号包裹。
# 密码含单引号时按 Bash 规则转义。填写真实密钥后的脚本不要提交到仓库。

# 服务及 Java：JAVA_HOME 留空时使用 PATH 中的 java。
default_env JAVA_HOME ''
default_env SERVER_PORT '18080'
default_env SERVER_ADDRESS '0.0.0.0'
default_env SPRING_PROFILES_ACTIVE 'druid'

# MySQL：容器部署时将主机改为数据库服务名或实际可达地址。
default_env SPRING_DATASOURCE_DRUID_MASTER_URL 'jdbc:mysql://127.0.0.1:3306/ry-vue?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
default_env SPRING_DATASOURCE_DRUID_MASTER_USERNAME 'ai_stock'
default_env SPRING_DATASOURCE_DRUID_MASTER_PASSWORD 'replace-with-database-password'

# Redis：无密码时保留空字符串。
default_env SPRING_REDIS_HOST '127.0.0.1'
default_env SPRING_REDIS_PORT '6379'
default_env SPRING_REDIS_DATABASE '0'
default_env SPRING_REDIS_PASSWORD ''

# JWT：填写固定随机密钥，各实例保持一致；修改后旧 JWT 失效。
default_env TOKEN_SECRET 'replace-with-a-long-random-secret'
default_env TOKEN_EXPIRETIME '30'

# 上传和日志目录需可写；在 Docker 中可挂载到持久化目录。
default_env RUOYI_PROFILE "$task_script_dir/uploads"
default_env RUOYI_LOG_PATH "$task_script_dir/logs"
default_env LOGGING_LEVEL_COM_RUOYI 'info'
default_env SWAGGER_ENABLED 'false'

# MinIO 对象接口地址（不是控制台端口），对外地址留空时使用上传地址。
default_env FILE_ANALYSIS_STORAGE_TYPE 'minio'
default_env MINIO_ENDPOINT 'http://121.37.69.26:9000'
default_env MINIO_PUBLIC_ENDPOINT ''
default_env MINIO_ACCESS_KEY 'replace-with-minio-access-key'
default_env MINIO_SECRET_KEY 'replace-with-minio-secret-key'
default_env MINIO_BUCKET 'ai-file'
default_env MINIO_REGION 'us-east-1'
default_env MINIO_PREFIX 'file-analysis/'
# ==================== 配置区结束 ====================

fail() { printf '错误：%s\n' "$*" >&2; exit 1; }

validate_port() {
    local task_port="$1"
    [[ "$task_port" =~ ^[0-9]{1,5}$ ]] || fail '端口必须为 1～65535 的整数。'
    (( 10#$task_port >= 1 && 10#$task_port <= 65535 )) || fail '端口必须为 1～65535 的整数。'
}

validate_config() {
    local task_name
    for task_name in SERVER_PORT SPRING_DATASOURCE_DRUID_MASTER_URL \
        SPRING_DATASOURCE_DRUID_MASTER_USERNAME SPRING_DATASOURCE_DRUID_MASTER_PASSWORD \
        SPRING_REDIS_HOST SPRING_REDIS_PORT SPRING_REDIS_DATABASE TOKEN_SECRET \
        RUOYI_PROFILE RUOYI_LOG_PATH FILE_ANALYSIS_STORAGE_TYPE \
        MINIO_ENDPOINT MINIO_ACCESS_KEY MINIO_SECRET_KEY MINIO_BUCKET; do
        [[ -n "${!task_name:-}" && "${!task_name}" != replace-with-* ]] || fail "请在脚本配置区或容器环境变量中配置 $task_name。"
    done
    validate_port "$SERVER_PORT"
    validate_port "$SPRING_REDIS_PORT"
    [[ "$SPRING_REDIS_DATABASE" =~ ^[0-9]+$ ]] || fail 'Redis 数据库编号必须是非负整数。'
    [[ "$RUOYI_PROFILE" = /* && "$RUOYI_LOG_PATH" = /* ]] || fail '上传目录和日志目录必须填写绝对路径。'
    [[ "$SPRING_DATASOURCE_DRUID_MASTER_URL" = jdbc:mysql://* ]] || fail 'MySQL 地址必须以 jdbc:mysql:// 开头。'
    [[ "$MINIO_ENDPOINT" =~ ^https?://[^[:space:]]+$ ]] || fail 'MinIO 地址必须是完整的 HTTP 或 HTTPS 地址。'
    if [[ -n "${MINIO_PUBLIC_ENDPOINT:-}" ]]; then
        [[ "$MINIO_PUBLIC_ENDPOINT" =~ ^https?://[^[:space:]]+$ ]] || fail 'MinIO 对外地址必须是完整的 HTTP 或 HTTPS 地址。'
    fi
}

run_backend() {
    validate_config
    [[ -f "$task_jar_file" ]] || fail "找不到 JAR：$task_jar_file。"
    local task_java
    if [[ -n "$JAVA_HOME" ]]; then
        task_java="$JAVA_HOME/bin/java"
    else
        task_java="$(command -v java)" || fail '找不到 Java，请配置 PATH 或 JAVA_HOME。'
    fi
    [[ -x "$task_java" ]] || fail "Java 无法执行：$task_java"
    mkdir -p -- "$RUOYI_PROFILE" "$RUOYI_LOG_PATH"
    cd -- "$task_script_dir"
    printf '启动后端，端口：%s，应用日志目录：%s\n' "$SERVER_PORT" "$RUOYI_LOG_PATH"
    # 前台运行，容器停止信号直接交给 Java。
    exec "$task_java" -jar "$task_jar_file"
}

[[ $# -le 1 ]] || fail '最多接受一个操作参数：run、check 或 --help。'
case "${1:-run}" in
    run) run_backend ;;
    check) validate_config; printf '环境变量配置检查通过。\n' ;;
    -h|--help|help)
        printf '用法：bash setup-env.sh [run|check]\n\n直接编辑脚本配置区，或通过 Docker 等方式注入同名环境变量。\nrun    启动 JAR（默认），不读取独立 env 文件\ncheck  仅检查环境变量配置，不启动服务\n\n默认使用脚本同目录的 ruoyi-admin.jar，可通过 BACKEND_JAR 覆盖。\n请执行此脚本，不要 source 此脚本。\n'
        ;;
    *) fail '未知操作，请使用 run、check 或 --help。' ;;
esac
