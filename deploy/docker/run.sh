#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="${IMAGE_NAME:-debezium-cdc-rocketmq:1.0.0}"
CONTAINER_NAME="${CONTAINER_NAME:-debezium-cdc-rocketmq}"
HOST_BASE_DIR="${HOST_BASE_DIR:-/data/debezium-cdc-rocketmq}"
HOST_PORT="${HOST_PORT:-8082}"

mkdir -p "$HOST_BASE_DIR/config" "$HOST_BASE_DIR/logs" "$HOST_BASE_DIR/offsets"

if docker ps -a --format '{{.Names}}' | grep -w "$CONTAINER_NAME" >/dev/null 2>&1; then
  echo "[INFO] 已存在同名容器，先删除旧容器: $CONTAINER_NAME"
  docker rm -f "$CONTAINER_NAME"
fi

docker run -d \
  --name "$CONTAINER_NAME" \
  --restart always \
  -p "${HOST_PORT}:8082" \
  -v "$HOST_BASE_DIR/config:/opt/debezium-cdc-rocketmq/config" \
  -v "$HOST_BASE_DIR/logs:/opt/debezium-cdc-rocketmq/logs" \
  -v "$HOST_BASE_DIR/offsets:/opt/debezium-cdc-rocketmq/offsets" \
  -e JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g}" \
  "$IMAGE_NAME"

echo "[INFO] 容器启动完成: $CONTAINER_NAME"
docker ps --filter "name=$CONTAINER_NAME"

