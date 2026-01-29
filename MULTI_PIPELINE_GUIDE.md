# 🚀 多线程 CDC 管道使用指南

## 📌 概述

本指南说明如何使用**多线程 CDC 管道**功能，支持同时处理多个数据源配置，每个配置独立运行一个 Debezium 引擎。

---

## ✨ 核心特性

### 1. 多配置并发处理
- ✅ 支持配置多个数据源
- ✅ 每个配置独立的 Debezium 引擎
- ✅ 使用 Java 21 虚拟线程，高效并发
- ✅ 自动隔离和错误处理

### 2. 动态管道管理
- ✅ 启用配置时自动启动 CDC 管道
- ✅ 停用配置时自动停止 CDC 管道
- ✅ 删除配置时自动清理资源
- ✅ 支持动态重启管道

### 3. 实时监控
- ✅ 实时显示所有活跃管道状态
- ✅ 显示每个管道的事件处理数
- ✅ 显示当前 LSN（Log Sequence Number）
- ✅ 自动刷新（每 5 秒）

---

## 🏗️ 架构设计

### 多线程 CDC 管道架构

```
┌─────────────────────────────────────────────────────────┐
│         MultiConfigCdcPipelineManager                   │
│  (多配置 CDC 管道管理器)                                 │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ CDC Pipeline │  │ CDC Pipeline │  │ CDC Pipeline │  │
│  │  (配置 1)    │  │  (配置 2)    │  │  (配置 3)    │  │
│  ├──────────────┤  ├──────────────┤  ├──────────────┤  │
│  │ Debezium     │  │ Debezium     │  │ Debezium     │  │
│  │ Engine 1     │  │ Engine 2     │  │ Engine 3     │  │
│  │              │  │              │  │              │  │
│  │ 虚拟线程 1   │  │ 虚拟线程 2   │  │ 虚拟线程 3   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│         │                  │                  │         │
│         └──────────────────┼──────────────────┘         │
│                            │                            │
│                    RocketMQ Producer                    │
│                            │                            │
└────────────────────────────┼────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │   RocketMQ      │
                    │   (多个 Topic)  │
                    └─────────────────┘
```

### 工作流程

```
1. 应用启动
   ↓
2. CdcPipelineInitializer 初始化
   ↓
3. 加载所有活跃配置
   ↓
4. 为每个配置创建独立的 CDC 管道
   ↓
5. 在虚拟线程中并发运行所有管道
   ↓
6. 监听配置变更事件
   ↓
7. 动态启动/停止管道
   ↓
8. 应用关闭时优雅关闭所有管道
```

---

## 🚀 快速开始

### 步骤 1：启动应用

```bash
mvn spring-boot:run
```

### 步骤 2：访问管理平台

打开浏览器访问新的多管道管理界面：

```
http://localhost:8082/index-multi.html
```

### 步骤 3：添加第一个数据源配置

1. 点击"⚙️ 配置管理"标签页
2. 填写"新增数据源配置"表单：
   - 配置名称：`用户表 CDC`
   - 数据库主机：`localhost`
   - 数据库端口：`5432`
   - 数据库名：`postgres`
   - 数据库用户：`postgres`
   - 数据库密码：`123456`
   - Schema 名称：`flink`
   - 表名：`users`
   - RocketMQ Topic：`cdc-users`
   - RocketMQ Tag：`users`
3. 点击"💾 保存配置"

### 步骤 4：启用配置

1. 在"已保存的配置"表中找到刚创建的配置
2. 点击"▶ 启用"按钮
3. 系统会自动启动对应的 CDC 管道

### 步骤 5：查看管道状态

1. 点击"📊 CDC 管道监控"标签页
2. 查看实时的管道状态卡片
3. 观察事件处理数和 LSN

### 步骤 6：添加更多配置

重复步骤 3-5，添加更多数据源配置。所有配置的 CDC 管道会**并发运行**。

---

## 📊 管道监控面板

### 实时统计信息

```
┌─────────────────────────────────────────┐
│ 活跃管道: 3                              │
│ 总事件数: 1,250                          │
│ 最后更新: 14:30:45                       │
└─────────────────────────────────────────┘
```

### 管道卡片信息

每个管道卡片显示：

```
┌──────────────────────────────────────┐
│ 用户表 CDC              ✓ 运行中      │
├──────────────────────────────────────┤
│ 数据库: localhost                    │
│ 表: flink.users                      │
│ Topic: cdc-users                     │
│ 事件数: 450                          │
│ LSN: 0/1A2B3C4D                      │
├──────────────────────────────────────┤
│ [🔄 重启]  [⏹ 停止]                  │
└──────────────────────────────────────┘
```

---

## 🔌 REST API 接口

### 获取所有管道状态

```bash
curl http://localhost:8082/api/pipeline/status
```

**响应示例**：

```json
{
  "1": {
    "configId": 1,
    "configName": "用户表 CDC",
    "running": true,
    "startTime": "2026-01-29T14:30:00",
    "processedEvents": 450,
    "currentLsn": "0/1A2B3C4D",
    "dbHostname": "localhost",
    "schemaName": "flink",
    "tableName": "users",
    "rocketmqTopic": "cdc-users",
    "rocketmqTag": "users"
  },
  "2": {
    "configId": 2,
    "configName": "订单表 CDC",
    "running": true,
    "startTime": "2026-01-29T14:30:05",
    "processedEvents": 320,
    "currentLsn": "0/2B3C4D5E",
    "dbHostname": "localhost",
    "schemaName": "flink",
    "tableName": "orders",
    "rocketmqTopic": "cdc-orders",
    "rocketmqTag": "orders"
  }
}
```

### 获取活跃管道数量

```bash
curl http://localhost:8082/api/pipeline/count
```

### 获取指定管道状态

```bash
curl http://localhost:8082/api/pipeline/status/{configId}
```

### 启动管道

```bash
curl -X POST http://localhost:8082/api/pipeline/{configId}/start
```

### 停止管道

```bash
curl -X POST http://localhost:8082/api/pipeline/{configId}/stop
```

### 重启管道

```bash
curl -X POST http://localhost:8082/api/pipeline/{configId}/restart
```

---

## 📁 新增文件清单

### 后端代码

```
✅ MultiConfigCdcPipelineManager.java    - 多配置 CDC 管道管理器
✅ DataSourceConfigChangeListener.java   - 配置变更监听器
✅ CdcPipelineInitializer.java          - CDC 管道初始化器
✅ CdcPipelineController.java           - CDC 管道 REST API
```

### 前端代码

```
✅ index-multi.html                     - 多管道管理界面
```

### 修改的文件

```
✅ DataSourceConfigService.java         - 集成配置变更监听器
```

---

## 🔄 配置生命周期

### 配置启用流程

```
用户点击"启用"按钮
    ↓
DataSourceConfigService.toggleActive()
    ↓
配置状态更新为 true
    ↓
触发 DataSourceConfigChangeListener.onConfigEnabled()
    ↓
MultiConfigCdcPipelineManager.startPipeline()
    ↓
创建新的 CdcPipeline 实例
    ↓
构建 Debezium 配置
    ↓
创建 DebeziumEngine
    ↓
在虚拟线程中启动引擎
    ↓
开始监听数据库变更
    ↓
事件推送到 RocketMQ
```

### 配置停用流程

```
用户点击"停用"按钮
    ↓
DataSourceConfigService.toggleActive()
    ↓
配置状态更新为 false
    ↓
触发 DataSourceConfigChangeListener.onConfigDisabled()
    ↓
MultiConfigCdcPipelineManager.stopPipeline()
    ↓
关闭 DebeziumEngine
    ↓
停止虚拟线程
    ↓
释放资源
    ↓
从管道映射中移除
```

---

## 💡 使用场景

### 场景 1：监听多个表

```
配置 1: 用户表 → cdc-users topic
配置 2: 订单表 → cdc-orders topic
配置 3: 商品表 → cdc-products topic

所有配置同时运行，并发处理所有表的变更
```

### 场景 2：监听多个数据库

```
配置 1: 生产库 users 表 → cdc-prod-users topic
配置 2: 测试库 users 表 → cdc-test-users topic
配置 3: 开发库 users 表 → cdc-dev-users topic

每个配置独立连接到不同的数据库
```

### 场景 3：动态添加新配置

```
1. 应用已运行，有 2 个活跃配置
2. 用户添加新配置并启用
3. 系统自动启动新的 CDC 管道
4. 无需重启应用
```

---

## 🔍 故障排查

### 问题 1：管道未启动

**症状**：配置已启用，但管道状态显示"已停止"

**解决方案**：
1. 检查数据库连接是否正常
2. 查看应用日志：`tail -f logs/application.log`
3. 确保 PostgreSQL 已启用逻辑复制
4. 检查 Replication Slot 是否存在

### 问题 2：事件数不增加

**症状**：管道运行中，但事件数为 0

**解决方案**：
1. 检查数据库中是否有数据变更
2. 手动修改表数据进行测试
3. 查看 RocketMQ 是否收到消息
4. 检查 Topic 和 Tag 配置是否正确

### 问题 3：管道频繁重启

**症状**：管道状态不稳定，频繁重启

**解决方案**：
1. 检查数据库连接稳定性
2. 查看应用日志中的错误信息
3. 增加虚拟线程执行器的资源
4. 检查 RocketMQ 连接状态

### 问题 4：内存占用过高

**症状**：运行多个管道后，内存占用持续增加

**解决方案**：
1. 检查是否有管道泄漏
2. 确保停用的管道已正确释放资源
3. 调整 JVM 堆内存大小
4. 检查 Debezium 缓冲区配置

---

## 📈 性能优化建议

### 1. 虚拟线程配置

```java
// 虚拟线程执行器会自动创建所需的线程
// 无需手动配置线程池大小
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

### 2. 数据库连接优化

```yaml
# application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
```

### 3. Debezium 缓冲区配置

```properties
# 在 MultiConfigCdcPipelineManager 中调整
max.batch.size=2048
max.queue.size=8192
```

### 4. RocketMQ 生产者配置

```yaml
# application.yml
rocketmq:
  send-msg-timeout: 3000
  retry-times-when-send-failed: 2
  max-message-size: 4194304
```

---

## 🧪 测试建议

### 单元测试

```java
@Test
void testStartPipeline() {
    DataSourceConfig config = createTestConfig();
    pipelineManager.startPipeline(config);

    PipelineStatus status = pipelineManager.getPipelineStatus(config.getId());
    assertTrue(status.isRunning());
}

@Test
void testStopPipeline() {
    // ... 启动管道
    pipelineManager.stopPipeline(configId);

    PipelineStatus status = pipelineManager.getPipelineStatus(configId);
    assertFalse(status.isRunning());
}
```

### 集成测试

```java
@Test
void testMultiplePipelinesRunning() {
    // 创建 3 个配置
    DataSourceConfig config1 = createTestConfig(1);
    DataSourceConfig config2 = createTestConfig(2);
    DataSourceConfig config3 = createTestConfig(3);

    // 启动所有管道
    pipelineManager.startPipeline(config1);
    pipelineManager.startPipeline(config2);
    pipelineManager.startPipeline(config3);

    // 验证所有管道都在运行
    assertEquals(3, pipelineManager.getActivePipelineCount());
}
```

### 压力测试

```bash
# 使用 Apache JMeter 或 Locust 进行压力测试
# 模拟多个并发请求
# 监控内存和 CPU 使用情况
```

---

## 📝 日志示例

### 应用启动日志

```
========================================
应用启动，初始化 CDC 管道...
========================================
初始化所有活跃的 CDC 管道...
发现 3 个活跃配置
启动配置 1 的 CDC 管道: 用户表 CDC
启动配置 2 的 CDC 管道: 订单表 CDC
启动配置 3 的 CDC 管道: 商品表 CDC
所有活跃 CDC 管道已初始化
========================================
CDC 管道初始化完成
========================================
```

### 配置启用日志

```
切换数据源配置状态: 用户表 CDC -> true
配置已启用: 用户表 CDC (ID: 1)
启动配置 1 的 CDC 管道: 用户表 CDC
启动 CDC 管道: 配置ID=1, 名称=用户表 CDC, 数据库=postgres, 表=users
配置 1 的 CDC 管道在虚拟线程中启动
配置 1 的 CDC 管道已启动
```

### 事件处理日志

```
配置 1 处理变更事件 #1 - LSN: 0/1A2B3C4D
配置 1 处理变更事件 #2 - LSN: 0/1A2B3C4E
配置 2 处理变更事件 #1 - LSN: 0/2B3C4D5E
配置 1 处理变更事件 #3 - LSN: 0/1A2B3C4F
```

---

## 🎯 最佳实践

### 1. 配置命名规范

```
格式: {环境}-{表名}-CDC
示例: prod-users-cdc, test-orders-cdc, dev-products-cdc
```

### 2. Topic 命名规范

```
格式: cdc-{环境}-{表名}
示例: cdc-prod-users, cdc-test-orders, cdc-dev-products
```

### 3. 监控和告警

```
- 监控活跃管道数量
- 监控事件处理速率
- 监控 LSN 进度
- 设置告警阈值
```

### 4. 定期维护

```
- 定期检查管道状态
- 清理过期的 Replication Slot
- 备份 Schema History 文件
- 监控磁盘空间
```

---

## 📞 常见问题

### Q: 支持多少个并发管道？

A: 理论上支持数百个并发管道。实际限制取决于：
- 数据库连接数
- 虚拟线程数量
- 系统内存
- RocketMQ 吞吐量

### Q: 管道之间是否会相互影响？

A: 不会。每个管道独立运行，使用独立的：
- Debezium 引擎
- 虚拟线程
- 数据库连接
- Replication Slot

### Q: 如何处理管道故障？

A: 系统会自动：
1. 记录错误日志
2. 标记管道为停止状态
3. 保留配置信息
4. 支持手动重启

### Q: 是否支持热更新配置？

A: 是的。可以在应用运行时：
1. 添加新配置
2. 启用/停用配置
3. 删除配置
4. 无需重启应用

---

## 🚀 下一步

1. **启动应用**：`mvn spring-boot:run`
2. **访问管理平台**：`http://localhost:8082/index-multi.html`
3. **添加配置**：创建第一个数据源配置
4. **启用配置**：启动 CDC 管道
5. **监控状态**：查看实时管道状态
6. **测试数据**：修改数据库数据，观察事件处理

---

**祝你使用愉快！** 🎉
