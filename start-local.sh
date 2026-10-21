#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
PROJECT_DIR="$PWD"
BREW_PREFIX="$(brew --prefix)"
if [[ -n "${RUOYI_JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$RUOYI_JAVA_HOME"
elif [[ -x "$BREW_PREFIX/opt/openjdk@17/bin/java" ]]; then
  export JAVA_HOME="$BREW_PREFIX/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
elif [[ -x /Applications/PyCharm.app/Contents/jbr/Contents/Home/bin/java ]]; then
  export JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home
else
  echo "Java runtime missing; set RUOYI_JAVA_HOME to a JDK installation" >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$BREW_PREFIX/opt/mysql@8.4/bin:$BREW_PREFIX/opt/redis/bin:$PATH"
export RUOYI_LOG_PATH="$PROJECT_DIR/.local/logs"
MAVEN="$HOME/.local/share/aigupiao-tools/apache-maven-3.9.9/bin/mvn"
SEED_SQL="${RUOYI_SEED_SQL:-$HOME/Downloads/ry-vue.sql}"
mkdir -p .local/logs .local/redis .local/uploads
umask 077

if [[ ! -f .local/database.env ]]; then
  printf 'LOCAL_DB_PASSWORD=%s\nLOCAL_TOKEN_SECRET=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 32)" > .local/database.env
fi
source .local/database.env
cat > .local/application-local.yml <<EOF
server:
  address: 127.0.0.1
  port: 18080
ruoyi:
  profile: $PROJECT_DIR/.local/uploads
spring:
  redis:
    host: 127.0.0.1
    port: 6379
  datasource:
    druid:
      master:
        url: jdbc:mysql://127.0.0.1:3306/ry-vue?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
        username: ruoyi_local
        password: $LOCAL_DB_PASSWORD
token:
  secret: $LOCAL_TOKEN_SECRET
EOF

if [[ ! -d .local/mysql/mysql ]]; then
  mysqld --no-defaults --initialize-insecure --basedir="$BREW_PREFIX/opt/mysql@8.4" --datadir="$PROJECT_DIR/.local/mysql" --log-error="$RUOYI_LOG_PATH/mysql-init.log"
fi
if ! mysqladmin --no-defaults --socket="$PROJECT_DIR/.local/mysql.sock" -u root ping --silent >/dev/null 2>&1; then
  mysqld --no-defaults --basedir="$BREW_PREFIX/opt/mysql@8.4" --datadir="$PROJECT_DIR/.local/mysql" --bind-address=127.0.0.1 --port=3306 --mysqlx=0 --socket="$PROJECT_DIR/.local/mysql.sock" --pid-file="$PROJECT_DIR/.local/mysql.pid" --log-error="$RUOYI_LOG_PATH/mysql.log" --daemonize
fi
for attempt in {1..30}; do
  if mysqladmin --no-defaults --socket="$PROJECT_DIR/.local/mysql.sock" -u root ping --silent >/dev/null 2>&1; then break; fi
  sleep 1
done
if [[ ! -f .local/database-initialized ]]; then
  if [[ ! -f "$SEED_SQL" ]]; then
    echo "SQL file missing: $SEED_SQL; set RUOYI_SEED_SQL to your local SQL file" >&2
    exit 1
  fi
  mysql --no-defaults --socket="$PROJECT_DIR/.local/mysql.sock" -u root <<EOF
CREATE DATABASE IF NOT EXISTS \`ry-vue\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'ruoyi_local'@'127.0.0.1' IDENTIFIED BY '$LOCAL_DB_PASSWORD';
GRANT ALL PRIVILEGES ON \`ry-vue\`.* TO 'ruoyi_local'@'127.0.0.1';
EOF
  cp "$SEED_SQL" .local/seed.sql
  mysql --no-defaults --socket="$PROJECT_DIR/.local/mysql.sock" -u root --default-character-set=utf8mb4 ry-vue < .local/seed.sql
  touch .local/database-initialized
fi

cat > .local/redis.conf <<EOF
bind 127.0.0.1
protected-mode yes
port 6379
daemonize yes
dir $PROJECT_DIR/.local/redis
pidfile $PROJECT_DIR/.local/redis.pid
logfile $RUOYI_LOG_PATH/redis.log
appendonly yes
EOF
if [[ ! -f .local/redis.pid ]] || ! kill -0 "$(cat .local/redis.pid)" 2>/dev/null; then
  redis-server "$PROJECT_DIR/.local/redis.conf"
fi
redis-cli -h 127.0.0.1 -p 6379 ping

if [[ ! -f ruoyi-admin/target/ruoyi-admin.jar ]]; then
  "$MAVEN" -B -ntp -s local-maven-settings.xml -DskipTests package
fi
if [[ -f .local/backend.pid ]] && kill -0 "$(cat .local/backend.pid)" 2>/dev/null; then
  echo "Backend already running: PID $(cat .local/backend.pid)"
  exit 0
fi
echo $$ > .local/backend.pid
echo "Backend starting at http://127.0.0.1:18080 (PID $(cat .local/backend.pid))"
echo "Log: $RUOYI_LOG_PATH/backend-console.log"
exec "$JAVA_HOME/bin/java" -Djava.awt.headless=true -jar "$PROJECT_DIR/ruoyi-admin/target/ruoyi-admin.jar" --spring.profiles.active=druid,local --spring.config.additional-location="file:$PROJECT_DIR/.local/" >> "$RUOYI_LOG_PATH/backend-console.log" 2>&1
