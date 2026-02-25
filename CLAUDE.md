# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Debezium CDC RocketMQ 集成方案 - 基于 Java 21 和 Spring Boot 3.4 的 Embedded Debezium 3.0，监听 PostgreSQL 数据库变更并将事件推送到 RocketMQ 4.9.7。

**核心特性**：
- 虚拟线程驱动（Java 21 Virtual Threads）
- 多配置 CDC 管道支持（每个数据源独立 Debezium 引擎）
- 消息路由策略：Tag=表名，Key=主键，Body=原始 JSON
- 优雅生命周期管理和状态监控

## Build & Run Commands

```bash
# 编译项目
mvn clean package

# 运行应用
mvn spring-boot:run

# 运行 JAR
java -jar target/debezium-cdc-rocketmq-1.0.0-SNAPSHOT.jar

# 运行单个测试
mvn test -Dtest=TestClassName

# 查看依赖树
mvn dependency:tree
```

## Architecture

### 核心组件

1. **AsyncEventSenderService** (`service/AsyncEventSenderService.java`) - **新增**
   - 异步事件缓冲和发送服务
   - 使用 BlockingQueue 缓冲 Debezium 事件
   - 多线程批量发送到 RocketMQ
   - 解耦 Debezium 引擎和 RocketMQ 生产者
   - 防止 RocketMQ 连接问题导致引擎停止

2. **MultiConfigCdcPipelineManager** (`service/MultiConfigCdcPipelineManager.java`)
   - 管理多个独立的 CDC 管道（每个数据源配置一个）
   - 使用虚拟线程执行器运行 Debezium 引擎
   - 提供启动、停止、重启、状态查询接口
   - 内部 `CdcPipeline` 类封装单个管道的生命周期
   - **变更**：事件处理改为异步入队，不直接发送到 RocketMQ

3. **DataSourceConfig** (`model/DataSourceConfig.java`)
   - JPA 实体，存储数据源配置
   - 字段：数据库连接信息、表名、RocketMQ Topic/Tag、激活状态
   - 支持动态配置管理（可在运行时添加/修改/删除配置）

4. **RocketMQProducerService** (`service/RocketMQProducerService.java`)
   - 单例生产者服务，管理 DefaultMQProducer 生命周期
   - 提供 `sendMessage()` 方法（支持字符串和字节数组）
   - 自动重试和超时配置

5. **CdcPipelineController** (`controller/CdcPipelineController.java`)
   - REST API 端点：获取状态、启动/停止/重启管道
   - **新增**：`/api/pipeline/async-stats` 查看异步发送统计
   - 路由前缀：`/api/pipeline`

6. **CdcPipelineInitializer** (`config/CdcPipelineInitializer.java`)
   - 应用启动时初始化异步发送服务和所有活跃配置的管道
   - 应用关闭时优雅关闭所有管道和异步发送服务

### 数据流（解耦后）

```
PostgreSQL (Replication Slot)
    ↓
Debezium Engine (虚拟线程运行)
    ↓ (ChangeEvent<String, String>)
MultiConfigCdcPipelineManager.handleChangeEvent()
    ├─ 提取 LSN
    ├─ 提取主键
    └─ 加入异步队列（非阻塞）
        ↓
AsyncEventSenderService (事件缓冲队列)
    ├─ 批量收集事件
    └─ 异步发送到 RocketMQ
        ↓
RocketMQProducerService.sendMessage()
    ↓
RocketMQ Topic (Tag=表名, Key=主键)
```

### 关键改进

**解耦优势**：
- Debezium 引擎不再直接依赖 RocketMQ 连接
- RocketMQ 连接失败不会导致引擎停止
- 事件缓冲在队列中，可以等待 RocketMQ 恢复
- 异步发送线程独立处理，不阻塞事件处理

**容错机制**：
- 队列满时记录警告但不阻塞引擎
- 发送失败时记录错误但继续处理下一条消息
- 应用关闭时处理剩余队列中的事件

### 配置管理

- **数据库存储**：`datasource_config` 表存储所有配置
- **激活机制**：`is_active=true` 的配置在应用启动时自动初始化
- **动态管理**：通过 DataSourceConfigService 可在运行时修改配置并重启对应管道

## Key Configuration

### application.yml

```yaml
server:
  port: 8082

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/postgres
    username: postgres
    password: 123456

rocketmq:
  namesrv-addr: 45.128.210.102:9876
  producer-group: debezium-cdc-producer
  topic: cdc-events2
  send-msg-timeout: 3000
  retry-times-when-send-failed: 2
  max-message-size: 4194304

# 异步事件发送配置（新增）
async:
  event:
    queue:
      size: 10000  # 事件队列大小，超过此值新事件会被丢弃
    sender:
      threads: 4  # 发送线程数，建议 2-8
      batch:
        size: 100  # 批处理大小，每次最多发送 100 条
        timeout:
          ms: 5000  # 批处理超时，5 秒无新事件则发送当前批次
```

### 异步配置调优建议

- **queue.size**: 根据内存和事件处理速度调整
  - 小值（1000-5000）：内存占用少，但容易丢弃事件
  - 大值（10000-50000）：缓冲能力强，但占用更多内存

- **sender.threads**: 根据 RocketMQ 吞吐量调整
  - 少线程（1-2）：CPU 占用低，但吞吐量受限
  - 多线程（4-8）：吞吐量高，但 CPU 占用增加

- **batch.size**: 平衡延迟和吞吐量
  - 小值（10-50）：延迟低，但网络往返次数多
  - 大值（100-500）：吞吐量高，但延迟增加

- **batch.timeout.ms**: 防止长时间等待
  - 建议 5000ms，确保即使事件少也能及时发送

### Debezium 配置（在 MultiConfigCdcPipelineManager 中动态构建）

关键配置项：
- `slot.drop.on.stop: false` - 重用已存在的 Replication Slot
- `snapshot.mode: when_needed` - 优化：如果已有偏移量，跳过快照
- `max.batch.size: 2048` - 批处理大小
- `poll.interval.ms: 1000` - 轮询间隔

## Important Implementation Details

### 虚拟线程使用

```java
// 在 MultiConfigCdcPipelineManager.initializeActivePipelines()
virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

// 在 CdcPipeline.start()
executor.submit(() -> {
    engine.run();  // 在虚拟线程中运行
});
```

### 异步事件处理流程（新增）

```java
// 1. Debezium 事件处理（快速，不阻塞）
private void handleChangeEvent(ChangeEvent<String, String> event) {
    // 提取 LSN 和主键
    // 将事件加入异步队列（非阻塞操作）
    asyncEventSenderService.enqueueEvent(topic, tag, key, value, configId);
}

// 2. 异步发送线程（独立处理）
private void processBatch() {
    while (running) {
        // 从队列中取出事件
        ChangeEventMessage firstMessage = eventQueue.poll(timeout);

        // 批量收集事件
        List<ChangeEventMessage> batch = new ArrayList<>();
        batch.add(firstMessage);
        eventQueue.drainTo(batch, batchSize - 1);

        // 批量发送到 RocketMQ
        for (ChangeEventMessage message : batch) {
            sendSingleMessage(message);
        }
    }
}
```

### 容错机制

**队列满处理**：
```java
boolean offered = eventQueue.offer(message);  // 非阻塞
if (!offered) {
    log.warn("事件队列已满，事件被丢弃");
    totalFailed.incrementAndGet();
}
```

**发送失败处理**：
```java
try {
    rocketMQProducerService.sendMessage(...);
    totalSent.incrementAndGet();
} catch (Exception e) {
    totalFailed.incrementAndGet();
    log.error("消息发送失败: {}", e.getMessage());
    // 不抛出异常，继续处理下一条消息
}
```

**优雅关闭**：
```java
@PreDestroy
public void shutdown() {
    running = false;

    // 处理剩余的事件
    while ((message = eventQueue.poll()) != null) {
        sendSingleMessage(message);
    }

    // 关闭线程池
    senderExecutor.shutdown();
    senderExecutor.awaitTermination(30, TimeUnit.SECONDS);
}
```

### LSN 和主键提取

- **LSN**：从 Debezium JSON 的 `source.lsn` 字段提取，用于追踪处理进度
- **主键**：优先从 `event.key()` 提取，其次从 `after.id` 或 `before.id` 提取

### 消息顺序性保证

- 使用主键作为 Message Key
- RocketMQ 将相同 Key 的消息发送到同一队列，保证顺序性

### Offset 存储

- 默认文件存储：`./offsets-{configId}.dat`
- Schema History：`./schema-history-{configId}.dat`
- 生产环境建议改为数据库存储

## REST API Endpoints

```bash
# 获取所有管道状态
GET /api/pipeline/status

# 获取指定管道状态
GET /api/pipeline/status/{configId}

# 获取活跃管道数量
GET /api/pipeline/count

# 启动管道
POST /api/pipeline/{configId}/start

# 停止管道
POST /api/pipeline/{configId}/stop

# 重启管道
POST /api/pipeline/{configId}/restart

# 获取异步事件发送统计信息（新增）
GET /api/pipeline/async-stats
```

### 异步发送统计响应示例

```json
{
  "totalEnqueued": 1500,
  "totalSent": 1480,
  "totalFailed": 20,
  "queuedSize": 5,
  "running": true
}
```

- `totalEnqueued`: 总入队事件数
- `totalSent`: 成功发送事件数
- `totalFailed`: 发送失败事件数
- `queuedSize`: 当前队列中待发送事件数
- `running`: 异步发送服务是否运行中

## PostgreSQL Prerequisites

```sql
-- 启用逻辑复制
ALTER SYSTEM SET wal_level = logical;
ALTER SYSTEM SET max_replication_slots = 4;
ALTER SYSTEM SET max_wal_senders = 4;

-- 创建 Publication（可选，Debezium 会自动创建）
CREATE PUBLICATION debezium_publication FOR ALL TABLES;

-- 验证用户权限
GRANT REPLICATION ON DATABASE postgres TO postgres;
```

## Common Development Tasks

### 添加新的数据源配置

1. 在 `datasource_config` 表插入新记录
2. 设置 `is_active=true`
3. 应用会自动启动对应的 CDC 管道

### 修改现有配置

1. 更新 `datasource_config` 表
2. 调用 `POST /api/pipeline/{configId}/restart` 重启管道

### 调试 Debezium 连接问题

- 检查 PostgreSQL 逻辑复制配置
- 查看日志中的 Debezium 连接错误
- 验证数据库用户权限和网络连接

### 监控消息处理

- 查看 `PipelineStatus` 中的 `processedEvents` 和 `currentLsn`
- 检查 RocketMQ 消息是否正确发送（Tag、Key、Body）

### 处理 RocketMQ 连接问题（新增）

**问题**：RocketMQ 连接失败导致 Debezium 引擎停止

**解决方案**（已实现）：
1. 异步发送服务会缓冲事件到队列
2. 发送失败时记录错误但继续处理
3. 监控 `/api/pipeline/async-stats` 查看队列状态
4. 当 RocketMQ 恢复时，队列中的事件会继续发送

**监控指标**：
```bash
# 查看异步发送统计
curl http://localhost:8082/api/pipeline/async-stats

# 响应示例
{
  "totalEnqueued": 1500,
  "totalSent": 1480,
  "totalFailed": 20,
  "queuedSize": 5,
  "running": true
}
```

**调优建议**：
- 如果 `queuedSize` 持续增长，说明发送速度跟不上，需要增加 `sender.threads`
- 如果 `totalFailed` 过高，检查 RocketMQ 连接和网络
- 如果 `queuedSize` 接近 `queue.size`，考虑增加队列大小

### 处理队列满的情况

当事件入队速度超过发送速度时，队列可能满：

1. **监控**：通过 `/api/pipeline/async-stats` 观察 `queuedSize`
2. **调优**：
   - 增加 `async.event.sender.threads`
   - 增加 `async.event.queue.size`
   - 检查 RocketMQ 性能
3. **日志**：查看 "事件队列已满，事件被丢弃" 的警告

## Dependencies

- **Spring Boot 3.4.1** - 框架
- **Debezium 3.0.0.Final** - CDC 引擎
- **RocketMQ 4.9.7** - 消息队列
- **PostgreSQL Driver** - 数据库驱动
- **Lombok** - 代码生成
- **Jackson** - JSON 处理

## Notes

- Java 21 虚拟线程通过 `--enable-preview` 编译标志启用
- 每个配置的 Offset 和 Schema History 文件独立存储，避免冲突
- Replication Slot 名称格式：`debezium_slot_{configId}`
- Publication 名称格式：`debezium_publication_{configId}`

### 解耦架构的优势

**在此版本中实现的改进**：

1. **Debezium 引擎独立性**
   - 不再直接依赖 RocketMQ 连接
   - RocketMQ 故障不会导致引擎停止
   - 引擎可以持续捕获数据库变更

2. **事件缓冲能力**
   - 事件先入队，异步发送
   - 可以应对 RocketMQ 临时故障
   - 队列满时记录警告但不阻塞引擎

3. **容错和恢复**
   - 发送失败时继续处理下一条消息
   - 应用关闭时处理剩余队列中的事件
   - 自动重试机制（由 RocketMQ 生产者提供）

4. **可观测性**
   - 通过 `/api/pipeline/async-stats` 监控队列状态
   - 统计总入队、成功发送、失败数量
   - 实时查看队列大小和服务状态

### 性能考虑

- **内存占用**：队列大小 × 平均消息大小
- **CPU 占用**：发送线程数和批处理大小
- **网络带宽**：批处理大小和发送频率
- **RocketMQ 吞吐量**：需要与发送线程数匹配
