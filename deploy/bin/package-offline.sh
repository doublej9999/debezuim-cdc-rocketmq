#!/usr/bin/env bash
set -euo pipefail

# 用法:
#   ./deploy/bin/package-offline.sh 1.0.0
# 说明:
#   在联网构建机执行，生成 JAR 离线包目录与压缩包。

VERSION="${1:-1.0.0}"
APP_NAME="debezium-cdc-rocketmq"
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUTPUT_DIR="$ROOT_DIR/target/${APP_NAME}-offline-${VERSION}"
PACKAGE_DIR="$OUTPUT_DIR/${APP_NAME}-offline"

cd "$ROOT_DIR"

echo "[INFO] 开始 Maven 打包"
mvn clean package -DskipTests

mkdir -p "$PACKAGE_DIR"/{bin,config,lib,logs,offsets,run,systemd,docker}

echo "[INFO] 复制可执行文件与模板"
JAR_CANDIDATE="$(ls "$ROOT_DIR"/target/${APP_NAME}-*.jar 2>/dev/null | grep -v '\.original\.jar' | head -n 1 || true)"
if [[ -z "$JAR_CANDIDATE" ]]; then
  echo "[ERROR] 未找到可用 JAR，请确认 Maven 打包成功"
  exit 1
fi

cp "$JAR_CANDIDATE" "$PACKAGE_DIR/lib/${APP_NAME}.jar"
cp "$ROOT_DIR/deploy/config/application-prod.yml" "$PACKAGE_DIR/config/"
cp "$ROOT_DIR/deploy/bin/start.sh" "$PACKAGE_DIR/bin/"
cp "$ROOT_DIR/deploy/bin/stop.sh" "$PACKAGE_DIR/bin/"
cp "$ROOT_DIR/deploy/bin/status.sh" "$PACKAGE_DIR/bin/"
cp "$ROOT_DIR/deploy/systemd/debezium-cdc-rocketmq.service" "$PACKAGE_DIR/systemd/"
cp "$ROOT_DIR/deploy/systemd/debezium-cdc-rocketmq.env" "$PACKAGE_DIR/systemd/"
cp "$ROOT_DIR/deploy/docker/Dockerfile" "$PACKAGE_DIR/docker/"
cp "$ROOT_DIR/deploy/docker/import-image.sh" "$PACKAGE_DIR/docker/"
cp "$ROOT_DIR/deploy/docker/run.sh" "$PACKAGE_DIR/docker/"
cp "$ROOT_DIR/deploy/DEPLOYMENT_OFFLINE.md" "$PACKAGE_DIR/"
cp "$ROOT_DIR/deploy/DELIVERY_CHECKLIST.md" "$PACKAGE_DIR/"

chmod +x "$PACKAGE_DIR"/bin/*.sh "$PACKAGE_DIR"/docker/*.sh

cd "$OUTPUT_DIR"
tar -czf "${APP_NAME}-offline-${VERSION}.tar.gz" "${APP_NAME}-offline"

echo "[INFO] 离线包生成完成:"
echo "       目录: $PACKAGE_DIR"
echo "       压缩包: $OUTPUT_DIR/${APP_NAME}-offline-${VERSION}.tar.gz"
