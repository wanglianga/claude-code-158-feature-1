#!/usr/bin/env bash
# 本地一键启动：PostgreSQL(5433) + Kafka(9092) + 应用(8080)
# 适用于没有 Docker 的 Linux 环境（本仓库验证环境即以此方式运行）。
# 有 Docker 的环境建议直接使用 docker-compose.yml。
set -euo pipefail
cd "$(dirname "$0")/.."

PG_BIN=${PG_BIN:-/usr/lib/postgresql/15/bin}
PGDATA=${PGDATA:-$HOME/.wasteoil-pgdata}
KAFKA_DIR=${KAFKA_DIR:-$HOME/kafka}
KAFKA_VER=3.8.0

echo "== 1/3 PostgreSQL =="
if ! pg_isready -h localhost -p 5433 >/dev/null 2>&1; then
  if [ ! -d "$PGDATA" ]; then
    "$PG_BIN/initdb" -D "$PGDATA" -U wasteoil --auth=trust --encoding=UTF8
    {
      echo "port = 5433"
      echo "listen_addresses = 'localhost'"
      echo "unix_socket_directories = '$PGDATA'"
    } >> "$PGDATA/postgresql.conf"
  fi
  "$PG_BIN/pg_ctl" -D "$PGDATA" -l "$PGDATA/server.log" start
  sleep 2
fi
psql -h localhost -p 5433 -U wasteoil -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='wasteoil'" | grep -q 1 \
  || psql -h localhost -p 5433 -U wasteoil -d postgres -c "CREATE DATABASE wasteoil"
psql -h localhost -p 5433 -U wasteoil -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='wasteoil_test'" | grep -q 1 \
  || psql -h localhost -p 5433 -U wasteoil -d postgres -c "CREATE DATABASE wasteoil_test"
echo "PostgreSQL 就绪: localhost:5433 (db: wasteoil / wasteoil_test)"

echo "== 2/3 Kafka =="
if ! (echo > /dev/tcp/localhost/9092) 2>/dev/null; then
  if [ ! -d "$KAFKA_DIR" ]; then
    echo "下载 Kafka $KAFKA_VER ..."
    curl -sSL -o /tmp/kafka.tgz "https://archive.apache.org/dist/kafka/$KAFKA_VER/kafka_2.13-$KAFKA_VER.tgz"
    tar xzf /tmp/kafka.tgz -C "$HOME" && mv "$HOME/kafka_2.13-$KAFKA_VER" "$KAFKA_DIR" && rm /tmp/kafka.tgz
  fi
  export KAFKA_HEAP_OPTS="-Xmx512M -Xms512M"
  UUID=$("$KAFKA_DIR/bin/kafka-storage.sh" random-uuid)
  "$KAFKA_DIR/bin/kafka-storage.sh" format -t "$UUID" -c "$KAFKA_DIR/config/kraft/server.properties" >/dev/null 2>&1 || true
  "$KAFKA_DIR/bin/kafka-server-start.sh" -daemon "$KAFKA_DIR/config/kraft/server.properties"
  sleep 8
fi
echo "Kafka 就绪: localhost:9092"

echo "== 3/3 应用 =="
mvn package -DskipTests -q
echo "启动应用（日志: /tmp/wasteoil-app.log）..."
nohup java -jar target/waste-oil-tracking-1.0.0.jar > /tmp/wasteoil-app.log 2>&1 &
echo $! > /tmp/wasteoil-app.pid
for i in $(seq 1 45); do
  sleep 2
  if [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/merchants)" == "200" ]; then
    echo "应用就绪: http://localhost:8080"
    exit 0
  fi
done
echo "应用启动超时，请查看 /tmp/wasteoil-app.log" >&2
exit 1
