# Debezium CDC RocketMQ 集成方案

基于 Java 21 和 Spring Boot 3.4 的 Embedded Debezium 3.0 监听 PostgreSQL 数据库变更，并将变更事件推送到 RocketMQ 4.9.7 的集成方案。

## 技术栈

- **Java**: 21 (使用虚拟线程)
- **Spring Boot**: 3.4.1
- **Debezium**: 3.0.0.Final
- **RocketMQ**: 4.9.7
- **PostgreSQL**: 支持逻辑复制的版本

## 核心特性

### 1. 虚拟线程驱动
- 使用 `Executors.newVirtualThreadPerTaskExecutor()` 运行 Debezium 引擎
- 充分利用 Java 21 的虚拟线程特性，提高并发性能

### 2. 消息路由策略
- **Tag**: 根据表名设置不同的 Tag
- **Key**: 提取数据主键作为 Message Key，保证同一记录的消息顺序性
- **Body**: Debezium 原始 JSON，不做任何处理

### 3. 生命周期管理
- `@PostConstruct`: 异步启动 Debezium 引擎和 RocketMQ 生产者
- `@PreDestroy`: 优雅关闭，等待当前事件处理完成

### 4. 状态监控
- 内存存储 LSN (Log Sequence Number)
- 提供 REST 接口查询引擎状态、LSN 和处理统计

## 项目结构

```
debezium-cdc-rocketmq-2/
├── pom.xml                                          # Maven 配置
├── src/main/
│   ├── java/com/example/cdc/
│   │   ├── DebeziumCdcApplication.java              # 主启动类
│   │   ├── config/
│   │   │   ├── DebeziumConfig.java                  # Debezium 配置
│   │   │   └── RocketMQConfig.java                  # RocketMQ 配置
│   │   ├── service/
│   │   │   ├── DebeziumEngineService.java           # Debezium 引擎服务
│   │   │   ├── RocketMQProducerService.java         # RocketMQ 生产者服务
│   │   │   └── ChangeEventHandler.java              # 变更事件处理器
│   │   ├── model/
│   │   │   └── EngineStatus.java                    # 状态模型
│   │   └── controller/
│   │       └── MonitorController.java               # 监控接口
│   └── resources/
│       └── application.yml                          # 应用配置
└── README.md
```

## 快速开始

### 前置条件

1. **Java 21**
   ```bash
   java -version
   # 确保版本为 21 或更高
   ```

2. **PostgreSQL 配置**

   启用逻辑复制：
   ```sql
   -- 修改 postgresql.conf
   wal_level = logical
   max_replication_slots = 4
   max_wal_senders = 4
   ```

   创建 Publication：
   ```sql
   CREATE PUBLICATION debezium_publication FOR ALL TABLES;
   ```

3. **RocketMQ 服务**

   启动 NameServer 和 Broker：
   ```bash
   # 启动 NameServer
   nohup sh bin/mqnamesrv &

   # 启动 Broker
   nohup sh bin/mqbroker -n localhost:9876 &
   ```

### 配置文件

编辑 `src/main/resources/application.yml`：

```yaml
debezium:
  database:
    hostname: localhost          # PostgreSQL 主机
    port: 5432                   # PostgreSQL 端口
    user: postgres               # 数据库用户
    password: postgres           # 数据库密码
    dbname: testdb               # 数据库名
    table-include-list: public.test_table,public.users  # 监听的表

rocketmq:
  namesrv-addr: localhost:9876   # RocketMQ NameServer 地址
  topic: cdc-events              # 目标 Topic
```

### 编译和运行

1. **编译项目**
   ```bash
   mvn clean package
   ```

2. **运行应用**
   ```bash
   mvn spring-boot:run
   ```

   或者运行 JAR：
   ```bash
   java -jar target/debezium-cdc-rocketmq-1.0.0-SNAPSHOT.jar
   ```

## 监控接口

### 1. 获取引擎状态
```bash
curl http://localhost:8080/api/monitor/status
```

响应示例：
```json
{
  "running": true,
  "currentLsn": "0/1234567",
  "processedEvents": 150,
  "startTime": "2026-01-27T10:30:00",
  "uptimeSeconds": 3600,
  "rocketmqProducerRunning": true,
  "databaseHost": "localhost",
  "rocketmqNameServer": "localhost:9876",
  "rocketmqTopic": "cdc-events"
}
```

### 2. 获取当前 LSN
```bash
curl http://localhost:8080/api/monitor/lsn
```

响应示例：
```json
{
  "currentLsn": "0/1234567",
  "timestamp": "2026-01-27T14:30:00"
}
```

### 3. 健康检查
```bash
curl http://localhost:8080/api/monitor/health
```

响应示例：
```json
{
  "status": "UP",
  "debeziumEngine": "UP",
  "rocketmqProducer": "UP",
  "timestamp": "2026-01-27T14:30:00"
}
```

### 4. 获取处理统计
```bash
curl http://localhost:8080/api/monitor/stats
```

响应示例：
```json
{
  "processedEvents": 150,
  "currentLsn": "0/1234567",
  "running": true,
  "startTime": "2026-01-27T10:30:00",
  "uptimeSeconds": 3600
}
```

## 测试验证

### 1. 在 PostgreSQL 中执行数据变更

```sql
-- 插入数据
INSERT INTO test_table (id, name) VALUES (1, 'test');

-- 更新数据
UPDATE test_table SET name = 'updated' WHERE id = 1;

-- 删除数据
DELETE FROM test_table WHERE id = 1;
```

### 2. 查看应用日志

```
处理变更事件 #1 - Table: test_table, Key: 1, LSN: 0/1234567
消息发送成功 - Topic: cdc-events, Tag: test_table, Key: 1, MsgId: xxx
```

### 3. 验证 RocketMQ 消息

使用 RocketMQ 控制台或命令行工具查看消息：

```bash
# 使用 RocketMQ 工具查看消息
sh bin/tools.sh org.apache.rocketmq.example.quickstart.Consumer
```

验证点：
- ✅ Tag 是否为表名 (test_table)
- ✅ Key 是否为主键 (1)
- ✅ Body 是否为 Debezium 原始 JSON

## 消息格式

### RocketMQ 消息结构

```
Topic: cdc-events
Tag: test_table
Key: 1
Body: {
  "before": null,
  "after": {
    "id": 1,
    "name": "test"
  },
  "source": {
    "version": "3.0.0.Final",
    "connector": "postgresql",
    "name": "dbserver1",
    "ts_ms": 1706349000000,
    "snapshot": "false",
    "db": "testdb",
    "schema": "public",
    "table": "test_table",
    "lsn": 123456789
  },
  "op": "c",
  "ts_ms": 1706349000000
}
```

### 操作类型 (op)

- `c`: CREATE (插入)
- `u`: UPDATE (更新)
- `d`: DELETE (删除)
- `r`: READ (快照读取)

## 配置说明

### Debezium 配置项

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `debezium.database.hostname` | PostgreSQL 主机 | localhost |
| `debezium.database.port` | PostgreSQL 端口 | 5432 |
| `debezium.database.user` | 数据库用户 | postgres |
| `debezium.database.password` | 数据库密码 | postgres |
| `debezium.database.dbname` | 数据库名 | testdb |
| `debezium.database.table-include-list` | 监听的表列表 | - |
| `debezium.database.plugin-name` | PostgreSQL 插件 | pgoutput |
| `debezium.database.slot-name` | Replication Slot 名称 | debezium_slot |
| `debezium.offset-storage-file-name` | Offset 存储文件 | ./offsets.dat |

### RocketMQ 配置项

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `rocketmq.namesrv-addr` | NameServer 地址 | localhost:9876 |
| `rocketmq.producer-group` | 生产者组名 | debezium-cdc-producer |
| `rocketmq.topic` | 目标 Topic | cdc-events |
| `rocketmq.send-msg-timeout` | 发送超时(毫秒) | 3000 |
| `rocketmq.retry-times-when-send-failed` | 失败重试次数 | 2 |

## 注意事项

### 1. PostgreSQL 配置

- 确保 `wal_level = logical`
- 创建 Publication: `CREATE PUBLICATION debezium_publication FOR ALL TABLES;`
- 用户需要 REPLICATION 权限

### 2. Offset 存储

- 默认使用文件存储 (`offsets.dat`)
- 生产环境建议使用数据库存储
- 删除 offset 文件会导致重新消费所有数据

### 3. 消息顺序性

- 使用主键作为 Message Key 保证同一记录的顺序性
- RocketMQ 会将相同 Key 的消息发送到同一队列

### 4. 性能优化

- 调整 `offset.flush.interval.ms` 控制 Offset 刷新频率
- 配置 RocketMQ 批量发送参数
- 监控虚拟线程使用情况

### 5. 错误处理

- Debezium 引擎异常会记录日志
- RocketMQ 发送失败会自动重试
- 数据库连接失败 Debezium 会自动重连

## 故障排查

### 问题 1: Debezium 无法连接数据库

**解决方案**:
- 检查 PostgreSQL 是否启用逻辑复制
- 验证数据库用户权限
- 检查网络连接

### 问题 2: RocketMQ 消息发送失败

**解决方案**:
- 检查 NameServer 是否运行
- 验证 Topic 是否存在
- 检查网络连接

### 问题 3: Offset 文件损坏

**解决方案**:
- 删除 `offsets.dat` 和 `schema-history.dat`
- 重启应用（会重新消费所有数据）

## 许可证

本项目仅供学习和参考使用。

## 联系方式

如有问题，请提交 Issue 或联系开发团队。
