# Debezium CDC RocketMQ 2.0 (High Performance & Reliable)

基于 `Java 21` (虚拟线程)、`Spring Boot 3.4`、`Debezium 3.0`、`RocketMQ 4.9` 构建的生产级多数据源 CDC 管道管理系统。

## 🌟 核心特性 (2026-04-01 优化版)

本项目在基础 CDC 功能之上，针对大数据量同步、严格顺序性、系统自愈能力进行了全方位的工业级加固：

### 1. 严格顺序性保障 (Strict Ordering)
- **Stripe-based Multi-Queue**：采用分片多队列方案，根据数据的 Primary Key 进行 Hash 路由。
- **单线程定向消费**：每个 Key 路由到固定的异步分片队列，并由专职线程顺序发送，彻底解决网络重试或多线程竞态导致的变更乱序问题。

### 2. 系统自愈与高可用 (Self-healing)
- **Watchdog 看门狗任务**：内置巡检机制，每分钟自动检测管道健康度。若发现 Debezium 引擎异常终止，将触发自动重启。
- **重启冷却机制**：针对永久性错误（如配置错误、权限不足）设有 5 分钟重启冷却期，防止无效重试导致服务器雪崩。

### 3. 高性能异步发送架构 (High Performance)
- **生产消费解耦**：Debezium 引擎仅负责读取 WAL，并通过内存队列解耦 RocketMQ 发送。
- **虚拟线程 (Virtual Threads)**：利用 Java 21 虚拟线程运行 Debezium 引擎，极低资源消耗下支持上百个并发同步管道。

### 4. 资源治理与生命周期 (Governance)
- **连接池化管理**：为每个数据源配置独立的 **HikariDataSource**，支持按需动态创建与优雅销毁。
- **Replication Slot 生命周期治理**：
    - **孤立资源清理**：启动时自动扫描并警示不再属于任何配置的“孤立”复制槽。
    - **WAL 堆积监控**：每小时检查 WAL 留存大小，超过阈值（默认 1GB，可配置）触发强告警。
    - **停用回收**：配置停用过久（>7天）自动清理复制资源，防止撑爆主库磁盘。

### 5. 数据可靠性加固 (Reliability)
- **TEXT 存储方案**：Offset 与 Schema History 存储 DDL 采用 `TEXT` 类型，解决大容量位移记录的溢出风险。
- **优雅停机优化**：在关停管道时，主线程会严格等待 Debezium 引擎 Flush 完最后的位点信息，最大程度减少重复消费。

### 6. 全栈监控看板 (Unified Monitoring)
- **实时 LSN 追踪**：直观查看每个管道的读取位置。
- **分片队列可视化**：图表化展示 4 个（或 N 个）发送分片的队列积压状态。
- **异常堆栈回显**：直接在 Web 界面查看 Debezium 引擎的实时报错信息。

---

## 🚀 快速开始

### 1. 运行环境
- JDK 21+
- PostgreSQL 12+ (需开启逻辑复制 `wal_level = logical`)
- RocketMQ 4.9+

### 2. 启动项目
```bash
mvn clean package
mvn spring-boot:run
```
默认管理台地址：`http://localhost:8082`

---

## 🛠 配置说明 (`application.yml`)

- **cdc.watchdog.interval.ms**: 看门狗巡检间隔，默认 60000ms。
- **cdc.wal.warning.threshold-bytes**: WAL 堆积告警阈值，默认 1GB。
- **async.event.sender.threads**: 发送分片数，增加此值可提升总吞吐，但会增加 CPU 开销。

---

## 📄 文档索引
- 文档总导航：`docs/README.md`
- 架构设计深挖：`ARCHITECTURE.md`
- 位移存储优化：`REPLICATION_SLOT_OPTIMIZATION.md`
