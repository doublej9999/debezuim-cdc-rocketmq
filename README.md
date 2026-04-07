# Debezium CDC RocketMQ 2.0

基于 `Java 21`、`Spring Boot 3.4`、`Debezium 3.0`、`RocketMQ 4.9` 的多数据源 CDC 管道管理系统，支持高可靠异步发送、顺序保障、WAL 治理与可视化管理。

## 主要能力

- 多数据源 CDC 管道管理（启动、停止、重启、自动巡检）
- Debezium 事件异步发送 RocketMQ，支持分片队列与顺序发送
- 本地事件日志（`event_log`）保障可靠投递与补偿
- 复制槽（slot）与 publication 生命周期治理
- WAL 留存监控与告警
- Web 管理页面（配置管理、管道状态、事件日志、治理面板）

## 最近更新（2026-04）

本次版本已完成以下关键更新：

1. Debezium 心跳机制
- 在 Debezium 属性中增加 `heartbeat.interval.ms` 与 `heartbeat.action.query`。
- 配置项：
  - `cdc.heartbeat.interval.ms`（默认 `5000`）
  - `cdc.heartbeat.action.query`（默认 `SELECT 1`）

2. WAL 留存显示优化
- 生命周期治理状态中，无论配置是启用还是停用，都会尝试查询并展示对应 slot 的 `retainedWalBytes`。

3. 数据源配置字段清理
- `datasource_config` 移除 `offset_key` 字段（模型/DTO/SQL 初始化脚本同步移除）。

4. Debezium offset/schema-history 落表连接调整
- `offset.storage.jdbc.*` 与 `schema.history.internal.jdbc.*` 改为直接读取 `spring.datasource`，不再使用业务数据源配置中的库连接信息。

5. 重试状态机修复
- 重试达到上限后状态会正确落为 `FAILED`（不再一直停留 `RETRY`）。
- 手动重试支持 `FAILED/RETRY` 事件重置并重新入队。

6. 前端请求密码传输加密
- 增加密码公钥接口：`GET /api/security/password-public-key`
- 前端提交数据源配置时对 `dbPassword` 进行 `RSA-OAEP(SHA-256)` 加密，后端解密后入库。
- 传输密文前缀：`ENC_RSA:`

7. 批量发送生产者组路由修复
- 批量发送按 `namesrvAddr + producerGroup + topic` 分组，不再固定使用默认生产者组。
- 缺省时可按 `configId` 回查 `datasource_config` 的 `rocketmqProducerGroup/rocketmqNamesrvAddr`。

## 快速开始

## 1. 环境要求

- JDK 21+
- PostgreSQL 12+（需启用逻辑复制）
- RocketMQ 4.9+

## 2. 启动

```bash
mvn clean package
mvn spring-boot:run
```

默认访问地址：
- 管理页面：`http://localhost:8082`

## 3. 关键配置

配置文件：`src/main/resources/application.yml`

重点参数：

- `spring.datasource.*`
  - 系统库连接（用于 JPA、event_log、offset、schema history 存储）
- `cdc.watchdog.interval.ms`
  - 管道看门狗巡检间隔（默认 60s）
- `cdc.heartbeat.interval.ms`
  - Debezium 心跳间隔（默认 5000ms）
- `cdc.heartbeat.action.query`
  - Debezium 心跳 SQL（默认 `SELECT 1`）
- `cdc.wal.warning.threshold-bytes`
  - WAL 告警阈值（默认 1GB）
- `async.event.*`
  - 异步发送队列、线程、批量发送、重试参数

## 安全说明

- 数据源密码数据库落库仍由 `AesEncryptor` 负责字段级加密。
- 新增“前端到后端”链路加密，仅用于传输阶段保护明文密码。
- 若前端未加密（旧客户端），后端也兼容明文请求。

## 升级说明

若你从旧版本升级，请确认数据库结构：

- `datasource_config` 表中已删除/不再依赖 `offset_key`。
- 如历史库仍有该列，不影响新版本运行，但建议后续通过 DDL 清理。

## 常用接口

- 数据源配置：`/api/datasource`
- 管道状态：`/api/pipeline/status`
- 事件日志：`/api/event-log`
- 生命周期治理：`/api/lifecycle/status`
- 密码加密公钥：`/api/security/password-public-key`

## 文档索引

- 架构说明：`ARCHITECTURE.md`
- 多管道指南：`MULTI_PIPELINE_GUIDE.md`
- 复制槽优化：`REPLICATION_SLOT_OPTIMIZATION.md`
- 其他文档：`docs/README.md`

