#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "用法: $0 <镜像tar文件路径>"
  echo "示例: $0 ./debezium-cdc-rocketmq_1.0.0.tar"
  exit 1
fi

IMAGE_TAR="$1"

if [[ ! -f "$IMAGE_TAR" ]]; then
  echo "[ERROR] 镜像文件不存在: $IMAGE_TAR"
  exit 1
fi

docker load -i "$IMAGE_TAR"
echo "[INFO] 镜像导入完成"
docker images | grep debezium-cdc-rocketmq || true

