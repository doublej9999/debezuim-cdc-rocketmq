#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_NAME="${APP_NAME:-debezium-cdc-rocketmq}"
JAR_PATH="${JAR_PATH:-$BASE_DIR/lib/debezium-cdc-rocketmq.jar}"
CONF_PATH="${CONF_PATH:-$BASE_DIR/config/application-prod.yml}"
LOG_DIR="${LOG_DIR:-$BASE_DIR/logs}"
RUN_DIR="${RUN_DIR:-$BASE_DIR/run}"
PID_FILE="$RUN_DIR/${APP_NAME}.pid"
CONSOLE_LOG="$LOG_DIR/console.out"

JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai}"

mkdir -p "$LOG_DIR" "$RUN_DIR" "$BASE_DIR/offsets"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "[ERROR] JAR 不存在: $JAR_PATH"
  exit 1
fi

if [[ ! -f "$CONF_PATH" ]]; then
  echo "[ERROR] 配置文件不存在: $CONF_PATH"
  exit 1
fi

if [[ -f "$PID_FILE" ]]; then
  old_pid="$(cat "$PID_FILE" || true)"
  if [[ -n "${old_pid}" ]] && kill -0 "$old_pid" >/dev/null 2>&1; then
    echo "[INFO] 服务已启动, PID=$old_pid"
    exit 0
  else
    echo "[WARN] 发现无效 PID 文件, 自动清理: $PID_FILE"
    rm -f "$PID_FILE"
  fi
fi

cd "$BASE_DIR"

nohup java $JAVA_OPTS \
  -jar "$JAR_PATH" \
  --spring.profiles.active=prod \
  --spring.config.location="file:$CONF_PATH" \
  >> "$CONSOLE_LOG" 2>&1 &

new_pid=$!
echo "$new_pid" > "$PID_FILE"
sleep 1

if kill -0 "$new_pid" >/dev/null 2>&1; then
  echo "[INFO] 启动成功, PID=$new_pid"
  echo "[INFO] 控制台日志: $CONSOLE_LOG"
else
  echo "[ERROR] 启动失败, 请检查日志: $CONSOLE_LOG"
  rm -f "$PID_FILE"
  exit 1
fi

