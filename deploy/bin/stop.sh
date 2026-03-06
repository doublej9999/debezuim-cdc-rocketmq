#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_NAME="${APP_NAME:-debezium-cdc-rocketmq}"
RUN_DIR="${RUN_DIR:-$BASE_DIR/run}"
PID_FILE="$RUN_DIR/${APP_NAME}.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "[INFO] 未找到 PID 文件, 视为服务未运行"
  exit 0
fi

pid="$(cat "$PID_FILE" || true)"
if [[ -z "$pid" ]]; then
  echo "[WARN] PID 文件为空, 删除后退出"
  rm -f "$PID_FILE"
  exit 0
fi

if ! kill -0 "$pid" >/dev/null 2>&1; then
  echo "[WARN] 进程不存在, 清理 PID 文件"
  rm -f "$PID_FILE"
  exit 0
fi

echo "[INFO] 正在停止服务, PID=$pid"
kill "$pid"

for _ in {1..30}; do
  if kill -0 "$pid" >/dev/null 2>&1; then
    sleep 1
  else
    rm -f "$PID_FILE"
    echo "[INFO] 停止成功"
    exit 0
  fi
done

echo "[WARN] 优雅停止超时, 执行强制停止"
kill -9 "$pid" >/dev/null 2>&1 || true
rm -f "$PID_FILE"
echo "[INFO] 已强制停止"

