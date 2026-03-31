# Debezium CDC RocketMQ 项目

基于 `Java 21`、`Spring Boot 3.4`、`Debezium 3.0`、`RocketMQ 4.9` 的多数据源 CDC 管道管理项目，支持：

- 数据源配置管理（增删改查 + 启停）
- 多管道并发运行与状态监控
- 事件日志记录、失败重试与清理
- **复制槽 (Slot) 生命周期治理 (启动巡检、定期 WAL 检查、停用自动清理)**
- Web 管理界面（`src/main/resources/static/index.html`）

## 快速开始

1. 启动 PostgreSQL 与 RocketMQ。
2. 检查并修改配置文件：`src/main/resources/application.yml`。
3. 启动项目：

```bash
mvn clean package
mvn spring-boot:run
```

默认访问地址：`http://localhost:8082`

## 文档入口

- 快速启动与常见问题：`QUICK_START.md`
- 架构设计：`ARCHITECTURE.md`
- 多管道使用：`MULTI_PIPELINE_GUIDE.md`
- Replication Slot 优化：`REPLICATION_SLOT_OPTIMIZATION.md`
- 离线部署：`deploy/DEPLOYMENT_OFFLINE.md`
- 文档总导航（含归档）：`docs/README.md`

## 主要 API

- 配置管理：`/api/datasource`
- 管道管理：`/api/pipeline`
- 事件日志：`/api/event-log`

## 文档整理说明（2026-03-07）

已将历史“阶段性报告/重复说明/旧版索引”迁移到 `docs/archive/`，减少根目录冗余文件，保留核心可维护文档。

## 复制槽 (Slot) 生命周期治理 (2026-03-31)

项目提供自动化的复制槽和发布 (Publication) 管理，防止主库 WAL 堆积及失效资源占用：
- **配置删除清理**：删除数据源配置时，实时自动清理对应的 Slot 和 Publication。
- **启动巡检**：应用启动时自动检测不再属于现有配置的“孤立”资源并发出告警。
- **配置停用治理**：配置停用超过 3 天发出强告警，超过 7 天自动回收资源。
- **WAL 监控告警**：每小时检查 Slot 的 `retained_wal_bytes`，超过 1GB 时发出强告警。
