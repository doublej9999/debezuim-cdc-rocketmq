# 下游系统接入文档（CDC + 全量快照）
> 适用项目：`debezium-cdc-rocketmq-2`  
> 更新时间：2026-04-21

## 1. 接入目标

下游系统需要同时消费：
- 增量变更 Topic：`{rocketmqTopic}`
- 全量快照 Topic：`{rocketmqTopic}_ALL`

推荐流程：
1. 先消费一次 `_ALL` 获取基线数据；
2. 再切换到增量 Topic 追平后续变更。

---

## 2. Topic 与消息约定

## 2.1 Topic 规划
- 增量：`{rocketmqTopic}`
- 全量：`{rocketmqTopic}_ALL`

## 2.2 快照消息 Header（`_ALL`）
- `X-Snapshot-Type`: `FULL`
- `X-Snapshot-Batch-Id`: 快照批次号（如 `1-1713657600000`）
- `X-Snapshot-Lsn`: 快照对齐 LSN
- `X-Snapshot-Status`: `BEGIN` / `DATA` / `END`

## 2.3 快照消息类型
1. `BEGIN`
- 表示批次开始，消息体包含 `batchId`、`snapshotLsn`、`totalRows` 等元信息。

2. `DATA`
- 业务数据行，JSON 结构与 Debezium 一致，`op = "r"`。

3. `END`
- 表示批次结束，消息体包含最终 `processedRows` 和 `snapshotLsn`。

## 2.4 增量消息类型（`{rocketmqTopic}`）
- `op = "c"`：新增
- `op = "u"`：更新
- `op = "d"`：删除

---

## 3. 推荐消费流程

## 3.1 首次接入或长时间离线后重建

1. 订阅 `{topic}_ALL`，按时间顺序消费并识别最新完整批次：
- 按 `X-Snapshot-Batch-Id` 分组。
- 只有同时看到同一批次的 `BEGIN` 和 `END`，才视为完整可用批次。
- 优先选择“最新的完整批次”作为重建基线。

2. 回放该完整批次的 `_ALL` 消息：
- 收到 `BEGIN`：初始化本地加载状态。
- 收到 `DATA`：写入/覆盖本地基线。
- 收到 `END`：标记全量完成，记录 `snapshotLsn`（优先 Header `X-Snapshot-Lsn`，或 END body 的 `snapshotLsn`）。

3. 切换到增量 Topic `{topic}`：
- 从可追溯位置开始消费（依赖你方消费位点策略）
- 丢弃 `source.lsn <= snapshotLsn` 的消息
- 应用 `source.lsn > snapshotLsn` 的消息

4. 可选优化（非必需）：
- 可调用 `GET /api/snapshot/jobs/config/{configId}/latest` 快速定位候选批次，减少 `_ALL` 扫描范围。

## 3.2 正常运行
- 持续消费增量 Topic。
- 如需重建可重复执行“3.1 首次接入流程”。

---

## 4. 数据解析建议

## 4.1 操作类型判断
优先按 `op` 字段分流：
- `r` -> 快照行
- `c/u/d` -> 增量变更

## 4.2 主键（Message Key）说明
- 快照与增量已统一 Key 提取逻辑。
- 相同业务主键应映射到同一类 Key，用于顺序处理和幂等。
- 仍建议下游以业务主键 + 版本字段（如 LSN / ts）做最终幂等判定。

## 4.3 LSN 读取
`source.lsn` 可能在以下路径之一：
- `source.lsn`
- `payload.source.lsn`

下游应兼容两种路径。

---

## 5. 幂等与一致性建议

1. 幂等键
- 建议：`业务主键 + op + lsn`（或 `业务主键 + ts_ms`）。

2. 快照批次幂等
- 使用 `batchId` 作为快照任务唯一标识。
- 已完成的 `batchId` 再次到达时应可安全忽略或覆盖。

3. 顺序性
- RocketMQ 在同 Key 路由下可保证队列内有序。
- 下游消费线程模型应避免同 Key 并发写冲突。

4. 异常恢复
- 快照消费中断时，优先按 `batchId` 重新拉齐到 `END`。
- 若无法确认状态，重新从 `latest` 接口拉取最新成功批次重建。

---

## 6. 最小实现伪代码

```java
if (topic.endsWith("_ALL")) {
    String status = headers.get("X-Snapshot-Status");
    String batchId = headers.get("X-Snapshot-Batch-Id");
    if (!batchId.equals(targetBatchId)) return;

    switch (status) {
        case "BEGIN" -> beginSnapshot(batchId);
        case "DATA"  -> applySnapshotRow(parseBody(body)); // op=r
        case "END"   -> finishSnapshot(batchId, headers.get("X-Snapshot-Lsn"));
    }
    return;
}

JsonNode event = parseBody(body);
String op = readOp(event); // c/u/d
String lsn = readLsn(event);
if (lsn != null && lsn.compareTo(snapshotLsn) <= 0) return;
applyIncremental(op, event);
```

---

## 7. 联调检查清单

1. `_ALL` 能看到完整 `BEGIN -> DATA -> END`。  
2. 能从 `_ALL` 自主识别出最新完整批次。  
3. `DATA` 中 `op` 为 `r`。  
4. 增量中 `op` 为 `c/u/d`。  
5. 切换后不会重复应用 `lsn <= snapshotLsn` 的消息。  
6. 重启消费者后幂等仍成立。  

---

## 8. 常见问题

## Q1：为什么要先消费 `_ALL` 再消费增量？
因为 Broker 消息保留期有限，先拿到完整基线再追增量可以避免历史丢失导致的数据缺口。

## Q2：必须调用 `latest` 接口吗？
不必须。下游可以仅依赖 `_ALL` 中的 `BEGIN/END` 边界和 `batchId` 自主识别最新完整快照批次。接口只用于加速定位。

## Q3：如果 `_ALL` 消费到一半服务重启怎么办？
按 `batchId` 继续消费，直到收到对应 `END`；若状态不确定，重新走最新成功快照流程。

## Q4：快照和增量会不会 key 不一致？
不会。两者已经统一使用同一套 Key 提取逻辑。
