# Debezium CDC RocketMQ 2.0

基于 `Java 21`、`Spring Boot 3.4`、`Debezium 3.0`、`RocketMQ 4.9` 的多数据源 CDC 管道管理系统。  
支持高可靠异步发送、顺序保障、**全量快照推送**、WAL 治理与可视化管理。

---

## 主要能力

- **多数据源 CDC 管道管理**：启动、停止、重启、自动 Watchdog 巡检
- **Debezium 增量事件异步发送**：分片队列 + 顺序发送 + 指数退避重试
- **本地事件日志（`event_log`）**：可靠投递保障与手动补偿
- **全量快照定时推送（NEW）**：定时将源库全量数据推送到 `{Topic}_ALL`，下游任意时刻接入均可获取完整数据
- **复制槽（Slot）与 Publication 生命周期治理**：孤立资源检测与清理
- **WAL 留存监控与告警**
- **Web 管理页面**：配置管理、管道状态、事件日志、全量快照监控、治理面板

---

## 快速开始

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 21+ |
| PostgreSQL | 12+（需启用逻辑复制） |
| RocketMQ | 4.9+ |

### 启动

```bash
mvn clean package
mvn spring-boot:run
```

**管理页面**：`http://localhost:8082`

---

## 关键配置（application.yml）

```yaml
spring:
  datasource:              # 系统库（存储 event_log、offset、schema history）
    url: jdbc:postgresql://localhost:5432/postgres

rocketmq:
  namesrv-addr: localhost:9876
  producer-group: debezium-cdc-producer
  topic: cdc-events2

cdc:
  watchdog.interval.ms: 60000      # 管道看门狗巡检间隔
  heartbeat.interval.ms: 5000      # Debezium 心跳间隔

async:
  event:
    queue.size: 10000              # 异步发送队列大小
    sender.threads: 4              # 发送线程数

# 全量快照配置
snapshot:
  schedule:
    enabled: true
    cron: "0 0 3 * * ?"            # 每天凌晨 3 点全量快照
  topic-suffix: "_ALL"
  batch:
    size: 500
    pause-ms: 50

event:
  cleanup.days: 3                  # event_log 保留天数
```

---

## 全量快照机制

解决 RocketMQ 消息 48h 过期导致下游晚接入无法消费的问题。

```
增量 CDC  →  {topic}       （保持不变，实时捕获）
全量快照  →  {topic}_ALL   （定时全扫，下游全量基线）
```

**下游首次接入流程：**

1. `GET /api/snapshot/jobs/config/{configId}/latest` → 获取 `snapshotLsn`
2. 消费 `{topic}_ALL` 直到收到 `X-Snapshot-Status: END`
3. 从增量 Topic 中跳过 `lsn ≤ snapshotLsn` 的消息，正常消费

详见 [`docs/SNAPSHOT_DESIGN.md`](docs/SNAPSHOT_DESIGN.md)

---

## REST API 概览

| 分类 | 端点示例 |
|------|---------|
| 数据源配置 | `GET/POST /api/datasource` |
| 管道管理 | `GET /api/pipeline/status`，`POST /api/pipeline/{id}/restart` |
| 异步发送统计 | `GET /api/pipeline/async-stats` |
| 事件日志 | `GET /api/event-log`，`POST /api/event-log/retry` |
| **全量快照** | `POST /api/snapshot/trigger/{id}`，`GET /api/snapshot/jobs` |
| 生命周期治理 | `GET /api/lifecycle/status` |
| 密码加密公钥 | `GET /api/security/password-public-key` |

---

## 安全说明

- 数据源密码：数据库字段级加密（`AesEncryptor` / AES-256）
- 前端提交：RSA-OAEP(SHA-256) 传输加密，密文前缀 `ENC_RSA:`
- 兼容性：旧客户端明文请求可正常工作

---

## 最近更新（2026-04）

| # | 更新内容 |
|---|---------|
| 1 | **全量快照推送**：新增定时快照任务，推送全量数据到 `_ALL` Topic，附带 LSN 锚点 |
| 2 | **快照监控页面**：前端新增 📸 全量快照标签，支持手动触发、进度查询、接入指南 |
| 3 | Debezium 心跳过滤：`handleChangeEvent` 显式过滤 `__debezium-heartbeat` 事件 |
| 4 | 重试状态机修复：达到最大重试次数后状态正确落为 `FAILED` |
| 5 | 重试配置动态感知：`markForRetry` 优先读取数据库最新配置（Fallback 原配置） |
| 6 | 前端密码 RSA 加密传输 |
| 7 | 批量发送按 `namesrvAddr + producerGroup` 分组路由修复 |

---

## 文档索引

| 文档 | 说明 |
|------|------|
| [`docs/SNAPSHOT_DESIGN.md`](docs/SNAPSHOT_DESIGN.md) | 全量快照方案设计（消息格式、LSN 对齐、消费端接入） |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 整体架构说明 |
| [`MULTI_PIPELINE_GUIDE.md`](MULTI_PIPELINE_GUIDE.md) | 多管道操作指南 |
| [`REPLICATION_SLOT_OPTIMIZATION.md`](REPLICATION_SLOT_OPTIMIZATION.md) | 复制槽优化与治理 |
| [`QUICK_START.md`](QUICK_START.md) | 快速上手 |
