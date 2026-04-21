# 全量快照设计（Snapshot to `_ALL` Topic）
> 文档版本：2026-04-21  
> 适用版本：debezium-cdc-rocketmq-2.x

## 1. 目标与原则

### 目标
- 在保留增量 CDC 方案不变的前提下，提供可周期执行的全量快照能力。
- 全量快照通过 Debezium 快照机制触发数据库快照，不再使用 JDBC `SELECT *` 扫表作为主流程。
- 快照推送到 RocketMQ 时，消息 Key 与增量推送逻辑保持一致。

### 核心原则
- 增量 Topic 不变：`{rocketmqTopic}`。
- 全量专用 Topic：`{rocketmqTopic}_ALL`。
- 批次边界明确：`BEGIN -> DATA -> END`。
- Key 提取统一：增量与快照统一复用 `CdcMessageKeyExtractor`（规则来源于原 `MultiConfigCdcPipelineManager.extractPrimaryKey`）。

---

## 2. Topic 与消息协议

## Topic 规划
```text
{rocketmqTopic}      -> 增量 CDC 消息（不变）
{rocketmqTopic}_ALL  -> 全量快照消息
```

## Header 约定（快照消息）
- `X-Snapshot-Type: FULL`
- `X-Snapshot-Batch-Id: {configId}-{timestamp}`
- `X-Snapshot-Lsn: {lsn or N/A}`
- `X-Snapshot-Status: BEGIN | DATA | END`

## BEGIN/END 消息体
```json
{
  "batchId": "1-1713657600000",
  "snapshotLsn": "0/1A2B3C4D",
  "markerType": "BEGIN|END",
  "totalRows": 50000,
  "processedRows": 0,
  "tsMs": 1713657600000
}
```

## DATA 消息体
- DATA 消息体直接使用 Debezium 事件 JSON（快照事件 `op = "r"`）。
- Key 按统一提取器从 `event.key`/`after.id`/`before.id` 规则提取，和增量一致。

---

## 3. 执行流程（已实现）

```text
定时任务/手动触发
  -> 创建 SnapshotJob(PENDING)
  -> 标记 RUNNING，读取 fallback LSN（pg_current_wal_lsn）
  -> 发送 BEGIN 到 {topic}_ALL
  -> 启动一次性 Debezium Engine（snapshot.mode=initial_only）
  -> 处理快照 DATA(op=r)，逐条发送到 {topic}_ALL
  -> 更新 processedRows（周期性）
  -> 发送 END 到 {topic}_ALL
  -> SnapshotJob -> DONE（失败则 FAILED）
```

### LSN 策略
- `snapshotLsn` 优先使用快照 DATA 事件中的 `source.lsn`。
- 若取不到事件 LSN，则回退到任务启动前读取的 `pg_current_wal_lsn()`。

---

## 4. 关键实现点

## 统一 Key 提取器
- 文件：`src/main/java/com/example/cdc/service/CdcMessageKeyExtractor.java`
- 规则：
  1. 若 `event.key` 非空且为对象，取第一个字段值；
  2. 若 `event.key` 为标量，直接取标量；
  3. 否则回退 `after.id`；
  4. 再回退 `before.id`；
  5. 最后返回 `UNKNOWN`。

## 增量路径
- 文件：`src/main/java/com/example/cdc/service/MultiConfigCdcPipelineManager.java`
- `handleChangeEvent` 中改为调用 `CdcMessageKeyExtractor.extractPrimaryKey(...)`。

## 快照路径
- 文件：`src/main/java/com/example/cdc/service/SnapshotTask.java`
- 定时任务与手动触发共用 `triggerSnapshot(configId)`。
- 核心执行改为 Debezium 一次性快照引擎（`initial_only`）。
- 快照 `BEGIN/DATA/END` 均发送到 `{topic}_ALL`。

## RocketMQ 发送扩展
- 文件：`src/main/java/com/example/cdc/service/RocketMQProducerService.java`
- 新增按 `Message` 对象发送的重载，支持携带快照 Header（UserProperty）。

---

## 5. 配置说明

`application.yml` 已使用/支持：

```yaml
snapshot:
  schedule:
    enabled: true
    cron: "0 0 3 * * ?"
  topic-suffix: "_ALL"
  debezium:
    snapshot-fetch-size: 2048
    max-queue-size: 8192
    max-batch-size: 2048
```

说明：
- `snapshot.schedule.*`：定时控制。
- `snapshot.topic-suffix`：全量 Topic 后缀。
- `snapshot.debezium.*`：快照专用 Debezium 执行参数。

---

## 6. 下游消费建议

1. 先消费 `{topic}_ALL` 某个 `batchId` 的完整批次（BEGIN 到 END）。
2. 记录该批次 `snapshotLsn`。
3. 切到增量 `{topic}`，从 `lsn > snapshotLsn` 继续消费。
4. 按 `op` 区分：
   - `r`：快照行
   - `c/u/d`：增量变更

---

## 7. 测试与验证

已覆盖：
- `CdcMessageKeyExtractorTest`：验证对象 key、标量 key、after/before 回退、UNKNOWN。
- 编译通过：`mvn -DskipTests compile`
- 单测通过：`mvn test -Dtest=CdcMessageKeyExtractorTest`

