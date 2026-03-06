# Debezium CDC RocketMQ 离线部署文档（正式版）

## 1. 项目部署基线

- 项目名：`debezium-cdc-rocketmq`
- 当前版本：`1.0.0-SNAPSHOT`（建议发布时改为正式版本号，例如 `1.0.0`）
- JDK：`21`
- Spring Boot：`3.4.1`
- 构建工具：`Maven`
- 数据库：`PostgreSQL`
- Redis：`无`
- 服务端口：`8082`
- 文件上传：`无`
- 部署系统：`Linux`
- Docker 离线部署：`需要`

## 2. 目标与原则

- 目标机全程不能联网。
- 配置文件必须外置，不打进 JAR。
- 日志目录与程序目录解耦，独立挂载。
- 提供两种可交付方式：`JAR 离线部署` 和 `Docker 镜像离线导入部署`。

## 3. 交付物结构（推荐）

```text
debezium-cdc-rocketmq-offline/
├── bin/
│   ├── start.sh
│   ├── stop.sh
│   └── status.sh
├── config/
│   └── application-prod.yml
├── lib/
│   └── debezium-cdc-rocketmq.jar
├── logs/
├── offsets/
├── run/
├── systemd/
│   ├── debezium-cdc-rocketmq.service
│   └── debezium-cdc-rocketmq.env
└── docker/
    ├── Dockerfile
    ├── import-image.sh
    └── run.sh
```

说明：
- `logs/`：应用日志目录（独立）。
- `offsets/`：Debezium 偏移与 schema-history 文件目录（当前代码按相对路径写入）。
- `config/application-prod.yml`：外置生产配置。

## 4. 环境要求

### 4.1 JAR 部署要求

- 操作系统：`CentOS 7+/RHEL 7+/Rocky 8+/Ubuntu 20.04+`
- JDK：`OpenJDK 21`（必须）
- 资源建议：
  - 2C4G 起步（测试）
  - 4C8G（生产建议）
- 磁盘：
  - 程序目录 >= 2GB
  - 日志目录按保留策略预留 >= 10GB

### 4.2 Docker 离线部署要求

- Docker Engine：`24.x+`（建议）
- 目标机已安装 Docker 且可运行 `docker load`/`docker run`

## 5. 联网构建机制作离线包

在联网构建机执行：

```bash
mvn -U clean package -DskipTests
```

或直接使用仓库内一键脚本：

```bash
chmod +x deploy/bin/package-offline.sh
./deploy/bin/package-offline.sh 1.0.0
```

生成 JAR 后，整理离线包目录：

```bash
mkdir -p debezium-cdc-rocketmq-offline/lib
cp target/debezium-cdc-rocketmq-1.0.0-SNAPSHOT.jar debezium-cdc-rocketmq-offline/lib/debezium-cdc-rocketmq.jar
```

将本仓库 `deploy/` 目录内容复制到 `debezium-cdc-rocketmq-offline/` 对应位置，并打包：

```bash
tar -czf debezium-cdc-rocketmq-offline.tar.gz debezium-cdc-rocketmq-offline/
```

## 6. 目标机 JAR 离线部署步骤

### 6.1 上传并解压

```bash
mkdir -p /opt
tar -xzf debezium-cdc-rocketmq-offline.tar.gz -C /opt
cd /opt/debezium-cdc-rocketmq-offline
chmod +x bin/*.sh
```

### 6.2 修改外置配置

编辑：

```bash
vi /opt/debezium-cdc-rocketmq-offline/config/application-prod.yml
```

必须至少修改：
- `spring.datasource.url`
- `spring.datasource.username`
- `spring.datasource.password`
- `rocketmq.namesrv-addr`
- `rocketmq.topic`

### 6.3 启动/停止/状态

```bash
# 启动
/opt/debezium-cdc-rocketmq-offline/bin/start.sh

# 状态
/opt/debezium-cdc-rocketmq-offline/bin/status.sh

# 停止
/opt/debezium-cdc-rocketmq-offline/bin/stop.sh
```

## 7. systemd 托管部署

复制服务文件：

```bash
cp /opt/debezium-cdc-rocketmq-offline/systemd/debezium-cdc-rocketmq.service /etc/systemd/system/
cp /opt/debezium-cdc-rocketmq-offline/systemd/debezium-cdc-rocketmq.env /etc/sysconfig/debezium-cdc-rocketmq
```

按实际目录调整：
- `/etc/systemd/system/debezium-cdc-rocketmq.service`
- `/etc/sysconfig/debezium-cdc-rocketmq`

启用服务：

```bash
systemctl daemon-reload
systemctl enable debezium-cdc-rocketmq
systemctl start debezium-cdc-rocketmq
systemctl status debezium-cdc-rocketmq
```

停止/重启：

```bash
systemctl stop debezium-cdc-rocketmq
systemctl restart debezium-cdc-rocketmq
```

## 8. Docker 镜像离线导入方案

### 8.1 联网构建机构建并导出镜像

准备构建上下文（包含 `Dockerfile` 和 `lib/debezium-cdc-rocketmq.jar`）后执行：

```bash
docker build -t debezium-cdc-rocketmq:1.0.0 -f docker/Dockerfile .
docker save -o debezium-cdc-rocketmq_1.0.0.tar debezium-cdc-rocketmq:1.0.0
```

将 `debezium-cdc-rocketmq_1.0.0.tar` 拷贝到离线目标机。

### 8.2 离线目标机导入并启动

```bash
docker load -i debezium-cdc-rocketmq_1.0.0.tar
```

创建宿主机挂载目录：

```bash
mkdir -p /data/debezium-cdc-rocketmq/{config,logs,offsets}
cp /opt/debezium-cdc-rocketmq-offline/config/application-prod.yml /data/debezium-cdc-rocketmq/config/
```

运行容器：

```bash
docker run -d \
  --name debezium-cdc-rocketmq \
  --restart always \
  -p 8082:8082 \
  -v /data/debezium-cdc-rocketmq/config:/opt/debezium-cdc-rocketmq/config \
  -v /data/debezium-cdc-rocketmq/logs:/opt/debezium-cdc-rocketmq/logs \
  -v /data/debezium-cdc-rocketmq/offsets:/opt/debezium-cdc-rocketmq/offsets \
  -e JAVA_OPTS="-Xms512m -Xmx2g" \
  debezium-cdc-rocketmq:1.0.0
```

## 9. 日志查看与健康检查

### 9.1 JAR 方式

```bash
tail -f /opt/debezium-cdc-rocketmq-offline/logs/console.out
tail -f /opt/debezium-cdc-rocketmq-offline/logs/app.log
```

### 9.2 systemd 方式

```bash
journalctl -u debezium-cdc-rocketmq -f
```

### 9.3 Docker 方式

```bash
docker logs -f debezium-cdc-rocketmq
```

### 9.4 健康检查

```bash
curl http://127.0.0.1:8082/actuator/health
```

期望返回：`{"status":"UP"...}`

## 10. 常见问题（FAQ）

### 10.1 启动报错：`JAVA_HOME not set`

- 原因：未安装 JDK21 或环境变量缺失。
- 处理：安装 JDK21，并在 `/etc/profile` 或 systemd 环境文件中配置 `JAVA_HOME`。

### 10.2 启动报错：端口占用 `8082`

- 检查：`ss -lntp | grep 8082`
- 处理：释放端口或修改 `application-prod.yml` 的 `server.port`。

### 10.3 数据库连接失败

- 检查网络连通、账号权限、数据库白名单、`jdbc` 参数。
- 校验：`spring.datasource.url` 与目标库实际一致。

### 10.4 RocketMQ 发送失败

- 检查 `rocketmq.namesrv-addr` 可达性。
- 检查 topic 是否已创建、是否有写权限。

### 10.5 Debezium 偏移文件写入失败

- 检查 `offsets/` 目录写权限。
- 目标机用户必须对程序目录有读写权限。

## 11. 回滚方案

推荐目录版本化：

```text
/opt/apps/debezium-cdc-rocketmq/releases/
├── 1.0.0/
├── 1.0.1/
└── current -> /opt/apps/debezium-cdc-rocketmq/releases/1.0.1
```

回滚步骤：

1. `systemctl stop debezium-cdc-rocketmq`
2. 将 `current` 软链接切回上一个版本。
3. 保留 `config/` 与 `logs/` 外置目录不变。
4. `systemctl start debezium-cdc-rocketmq`
5. 执行健康检查与核心链路验证。

## 12. 验收项（上线前/后）

- 进程验收：服务进程存在，重启后自动拉起。
- 端口验收：`8082` 监听正常。
- 配置验收：敏感配置仅存在外置 `config/`，JAR 内无明文生产密码。
- 日志验收：日志写入独立目录，滚动策略生效。
- 业务验收：CDC 变更可被采集并投递到 RocketMQ 目标 Topic。
- 健康验收：`/actuator/health` 返回 `UP`。
- 运维验收：启停脚本、systemd、回滚步骤均可执行。
