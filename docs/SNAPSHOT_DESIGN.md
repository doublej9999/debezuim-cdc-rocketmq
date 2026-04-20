# 全量快照推送方案（Snapshot to _ALL Topic）

> 文档版本：2026-04 | 适用版本：debezium-cdc-rocketmq-2.x

---

## 背景与问题

RocketMQ Broker 默认只保留 **48 小时**的消息（`fileReservedTime=48`）。  
Debezium CDC 捕获的增量事件实时推送到 RocketMQ 后，下游系统如果超过 48h 才接入，历史消息已被清理，**无法消费到完整数据**。

---

## 解决方案：定时全量快照

引入**定时全量快照任务**，定期将源库表的完整数据推送到 RocketMQ 的独立 Topic（后缀 `_ALL`）。下游系统接入时先消费一次 `_ALL` 获取全量基线，再衔接增量 Topic 消费后续变更。

---

## 核心设计原则

| 原则 | 说明 |
|------|------|
| **增量 Topic 不变** | 原有 CDC 增量事件继续推送到原 Topic，不加 `_INC` 后缀，不影响现有消费者 |
| **全量专用 Topic** | 快照推送到 `{rocketmqTopic}_ALL`，与增量完全隔离 |
| **LSN 对齐** | 快照启动前读取 `pg_current_wal_lsn()`，写入消息 Header，下游凭此精确接续增量 |
| **BEGIN/END 边界标记** | 每次快照由 BEGIN → DATA × N → END 三类消息组成，消费端知道完整边界 |
| **流式 JDBC** | 使用 `fetchSize` + 服务端游标，大表不 OOM |

---

## Topic 规划

```
{rocketmqTopic}       ← 增量 CDC 事件（保持不变）
{rocketmqTopic}_ALL   ← 定时全量快照
```

---

## 消息格式

### 1. BEGIN 标记消息

```
Header:
  X-Snapshot-Type:     FULL
  X-Snapshot-Batch-Id: {configId}-{timestamp}   // 本次快照唯一批次 ID
  X-Snapshot-Lsn:      0/1A2B3C4D               // 快照启动前读取的 WAL LSN
  X-Snapshot-Status:   BEGIN

Body (JSON):
{
  "batchId":       "1-1713657600000",
  "snapshotLsn":   "0/1A2B3C4D",
  "markerType":    "BEGIN",
  "totalRows":     50000,       // pg_class 估算值
  "processedRows": 0,
  "tsMs":          1713657600000
}
```

### 2. DATA 消息（与增量 CDC 格式对齐）

```
Header:
  X-Snapshot-Type:     FULL
  X-Snapshot-Batch-Id: {batchId}
  X-Snapshot-Lsn:      {snapshotLsn}
  X-Snapshot-Status:   DATA

Body (JSON):
{
  "payload": {
    "before": null,
    "after":  { ...行数据... },
    "op":     "r",             // "r" = read（全量专用，与增量 c/u/d 区分）
    "ts_ms":  1713657600000,
    "source": {
      "snapshot":        "true",
      "db":              "mydb",
      "schema":          "public",
      "table":           "orders",
      "snapshotBatchId": "1-1713657600000",
      "snapshotLsn":     "0/1A2B3C4D"
    }
  }
}
```

### 3. END 标记消息

```
Header:
  X-Snapshot-Type:     FULL
  X-Snapshot-Batch-Id: {batchId}
  X-Snapshot-Lsn:      {snapshotLsn}
  X-Snapshot-Status:   END

Body (JSON):
{
  "batchId":       "1-1713657600000",
  "snapshotLsn":   "0/1A2B3C4D",
  "markerType":    "END",
  "totalRows":     50000,
  "processedRows": 50000,
  "tsMs":          1713659200000
}
```

---

## 数据流

```
[定时任务 / 手动触发]
        ↓
① SELECT pg_current_wal_lsn() → 记录 snapshotLsn
        ↓
② 推送 BEGIN 消息到 {topic}_ALL
        ↓
③ 流式 SELECT * FROM {schema}.{table} ORDER BY id
   (JDBC fetchSize=500, 服务端游标)
        ↓ 每 500 行一批
④ 推送 DATA 消息到 {topic}_ALL（批次间 sleep 50ms）
        ↓
⑤ 推送 END 消息到 {topic}_ALL
        ↓
⑥ 更新 snapshot_job 表 → status=DONE, snapshotLsn, processedRows
```

---

## 新增组件

### `SnapshotJob.java` (model)

JPA 实体，持久化快照任务执行状态。

| 字段 | 类型 | 说明 |
|------|------|------|
| `batchId` | String | 唯一批次 ID（`{configId}-{timestamp}`） |
| `configId` | Long | 关联数据源配置 |
| `topic` | String | 目标 Topic（`{rocketmqTopic}_ALL`） |
| `snapshotLsn` | String | 快照启动前 WAL LSN |
| `status` | Enum | `PENDING / RUNNING / DONE / FAILED` |
| `totalRows` | Long | 表行数估算（pg_class） |
| `processedRows` | Long | 已推送行数 |
| `startedAt` | DateTime | 任务开始时间 |
| `finishedAt` | DateTime | 任务完成时间 |

### `SnapshotTask.java` (service)

核心定时任务服务：
- `@Scheduled(cron)` 定时触发所有活跃配置的快照
- `triggerSnapshot(configId)` 异步触发单个配置的快照（虚拟线程执行）
- `executeSnapshot()` 核心流程：读 LSN → BEGIN → 流式扫描 → DATA批量推 → END
- `@PostConstruct` 启动时清理僵死 `RUNNING` 状态记录

### `SnapshotController.java` (controller)

REST API：

| 端点 | 说明 |
|------|------|
| `POST /api/snapshot/trigger/{configId}` | 手动触发快照，返回 batchId |
| `GET /api/snapshot/jobs` | 所有快照历史（分页） |
| `GET /api/snapshot/jobs/{batchId}` | 批次详情（含进度） |
| `GET /api/snapshot/jobs/config/{configId}` | 指定配置的快照历史 |
| `GET /api/snapshot/jobs/config/{configId}/latest` | 最新成功快照信息（含 consumeGuide） |

---

## 配置项（application.yml）

```yaml
snapshot:
  schedule:
    enabled: true           # true=定时，false=只允许手动触发
    cron: "0 0 3 * * ?"     # 每天凌晨 3 点
  topic-suffix: "_ALL"      # 全量 Topic 后缀
  batch:
    size: 500               # 每批行数（同时控制 JDBC fetchSize）
    pause-ms: 50            # 批次间暂停，单位毫秒
```

---

## 下游消费端接入流程

### 首次接入（晚接入场景）

```
1. 调用 GET /api/snapshot/jobs/config/{configId}/latest
   ← 获取 batchId 和 snapshotLsn

2. 订阅 {topic}_ALL
   - 消费 Header X-Snapshot-Batch-Id = batchId 的所有消息
   - 遇到 X-Snapshot-Status: BEGIN → 初始化接收状态
   - 遇到 X-Snapshot-Status: DATA  → 建立全量数据基线
   - 遇到 X-Snapshot-Status: END   → 全量消费完成，记录 snapshotLsn

3. 切换订阅原增量 Topic
   - 设置 consumeFromWhere = CONSUME_FROM_FIRST_OFFSET
   - 跳过 source.lsn <= snapshotLsn 的消息（已在快照中覆盖）

4. 之后正常消费增量（op = c/u/d）
```

### 消费端区分消息来源

```java
// 通过 op 字段区分
String op = payload.get("op").asText();
switch (op) {
    case "r" -> handleSnapshotRow(after);   // 全量快照行
    case "c" -> handleInsert(after);        // 增量新增
    case "u" -> handleUpdate(before, after); // 增量修改
    case "d" -> handleDelete(before);       // 增量删除
}
```

---

## 注意事项

### RocketMQ Broker 配置建议

配合快照方案，建议调整 Broker 保留时间：

```properties
# 消息保留时间（小时），调至 168（7天），确保快照有效期内下游可消费
fileReservedTime=168

# 磁盘使用率告警阈值
diskMaxUsedSpaceRatio=85
```

> 快照每天跑一次，Broker 保留 7 天 → 下游任何时候接入都能找到最近一次完整快照。

### 大表性能

- 建议在业务低峰期（默认凌晨 3 点）执行
- `batch.size=500` + `pause-ms=50` 可根据机器性能和 Broker 压力调整
- JDBC 流式游标要求关闭自动提交（`autoCommit=false`），任务结束后恢复

### 幂等性保障

- 每次快照有唯一 `batchId`，消费端按 `batchId` 消费，可重复触发不影响存量数据
- 快照消息的 Message Key = 行主键，RocketMQ 顺序队列保证同主键有序

---

## 前端快照监控页面

访问 `http://localhost:8082` → 点击 **📸 全量快照** 标签：

- **触发面板**：选择数据源配置，点击"立即触发快照"，返回 batchId
- **任务概览**：总任务数、成功/运行中/失败统计、最新 LSN
- **任务历史**：分页展示所有快照记录（BatchId、状态、进度、LSN、开始/完成时间）
- **接入指南**：点击"查询最新快照 LSN"获取完整的消费端接入信息

---

## 相关文件

| 文件 | 路径 |
|------|------|
| 任务实体 | `src/.../model/SnapshotJob.java` |
| 数据访问 | `src/.../repository/SnapshotJobRepository.java` |
| 核心服务 | `src/.../service/SnapshotTask.java` |
| REST API | `src/.../controller/SnapshotController.java` |
| 配置项 | `src/main/resources/application.yml` → `snapshot.*` |
| 前端页面 | `src/main/resources/static/index.html` → 📸 全量快照 标签 |
