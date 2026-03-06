# 离线部署交付清单

## 1. 必交文件

- `lib/debezium-cdc-rocketmq.jar`
- `config/application-prod.yml`
- `bin/start.sh`
- `bin/stop.sh`
- `bin/status.sh`
- `systemd/debezium-cdc-rocketmq.service`
- `systemd/debezium-cdc-rocketmq.env`
- `docker/Dockerfile`
- `docker/import-image.sh`
- `docker/run.sh`
- `DEPLOYMENT_OFFLINE.md`

## 2. 交付前检查

- JAR 可启动：`java -jar lib/debezium-cdc-rocketmq.jar --spring.config.location=file:config/application-prod.yml`
- 脚本可执行：`chmod +x bin/*.sh docker/*.sh`
- 外置配置生效：修改端口后启动，监听端口应变化。
- 日志独立目录：`logs/` 下生成 `console.out` 与 `app.log`。
- offsets 可写：`offsets/` 目录可自动生成偏移文件。

## 3. 上线验收

- 启停验收：`start.sh/stop.sh/status.sh` 行为正确。
- systemd 验收：`systemctl enable/start/status` 正常。
- Docker 验收：镜像可 `docker load`，容器可成功运行。
- 健康验收：`/actuator/health` 返回 `UP`。
- 业务验收：CDC 变更事件可发布到 RocketMQ 指定 Topic。
