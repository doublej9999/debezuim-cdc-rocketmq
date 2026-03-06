#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_NAME="${APP_NAME:-debezium-cdc-rocketmq}"
RUN_DIR="${RUN_DIR:-$BASE_DIR/run}"
PID_FILE="$RUN_DIR/${APP_NAME}.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "[INFO] 服务未运行（无 PID 文件）"
  exit 1
fi

pid="$(cat "$PID_FILE" || true)"
if [[ -z "$pid" ]]; then
  echo "[WARN] PID 文件为空"
  exit 1
fi

if kill -0 "$pid" >/dev/null 2>&1; then
  echo "[INFO] 服务运行中, PID=$pid"
  exit 0
fi

echo "[WARN] PID 文件存在但进程不存在, 请清理: $PID_FILE"
exit 1

