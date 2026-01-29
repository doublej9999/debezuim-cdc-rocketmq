# 🔧 Replication Slot 错误优化指南

## 问题描述

启动 CDC 管道时出现错误：
```
ERROR: replication slot "debezium_slot_1" is active for PID 383
```

这表示 Replication Slot 已经被占用，无法重新使用。

---

## 🔍 问题原因

1. **Slot 仍然活跃** - 上一个进程未正确关闭 Slot
2. **进程未清理** - PostgreSQL 中仍有占用 Slot 的连接
3. **Slot 配置冲突** - 多个管道使用相同的 Slot 名称

---

## ✅ 解决方案

### 方案 1：自动清理（推荐）

已在代码中实现以下优化：

```java
// 允许重用已存在的 Replication Slot
props.setProperty("slot.drop.on.stop", "false");

// 优化：如果 Slot 已存在，直接使用
props.setProperty("replication.slot.name", "debezium_slot_" + config.getId());

// 优化：如果已有偏移量，跳过快照
props.setProperty("snapshot.mode", "when_needed");
```

### 方案 2：手动清理 Replication Slot

#### 步骤 1：连接到 PostgreSQL

```bash
psql -U postgres -d postgres
```

#### 步骤 2：查看所有 Replication Slot

```sql
SELECT slot_name, slot_type, active, restart_lsn FROM pg_replication_slots;
```

#### 步骤 3：查找占用 Slot 的进程

```sql
SELECT pid, usename, application_name, state FROM pg_stat_activity
WHERE application_name LIKE '%debezium%';
```

#### 步骤 4：终止占用的进程

```sql
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
WHERE application_name LIKE '%debezium%';
```

#### 步骤 5：删除不活跃的 Slot

```sql
SELECT pg_drop_replication_slot('debezium_slot_1');
SELECT pg_drop_replication_slot('debezium_slot_2');
```

#### 步骤 6：删除 Publication

```sql
DROP PUBLICATION IF EXISTS debezium_publication_1;
DROP PUBLICATION IF EXISTS debezium_publication_2;
```

### 方案 3：使用清理脚本

运行提供的清理脚本：

```bash
psql -U postgres -d postgres -f cleanup-replication-slots.sql
```

---

## 🚀 最佳实践

### 1. 启动应用前清理

```bash
# 清理所有旧的 Slot 和 Publication
psql -U postgres -d postgres -f cleanup-replication-slots.sql

# 然后启动应用
mvn spring-boot:run
```

### 2. 优雅关闭

确保应用正确关闭所有管道：

```java
// 应用关闭时自动清理
@PreDestroy
public void shutdownAll() {
    for (Long configId : new ArrayList<>(pipelines.keySet())) {
        try {
            stopPipeline(configId);
        } catch (Exception e) {
            log.error("停止管道时出错: {}", e.getMessage());
        }
    }
}
```

### 3. 监控 Slot 状态

定期检查 Slot 状态：

```sql
-- 监控脚本
SELECT
    slot_name,
    slot_type,
    active,
    restart_lsn,
    confirmed_flush_lsn
FROM pg_replication_slots
WHERE slot_name LIKE 'debezium_slot_%'
ORDER BY slot_name;
```

---

## 📋 故障排查步骤

### 问题 1：Slot 仍然活跃

**症状**：删除 Slot 时出现 "slot is active" 错误

**解决方案**：
1. 查找占用进程：`SELECT * FROM pg_stat_activity WHERE application_name LIKE '%debezium%';`
2. 终止进程：`SELECT pg_terminate_backend(pid) FROM ...`
3. 等待 5-10 秒
4. 删除 Slot：`SELECT pg_drop_replication_slot('debezium_slot_1');`

### 问题 2：Publication 不存在

**症状**：启动时出现 "publication does not exist" 错误

**解决方案**：
1. 检查 Publication：`SELECT * FROM pg_publication;`
2. 如果不存在，Debezium 会自动创建
3. 如果存在但损坏，删除后重新创建：`DROP PUBLICATION IF EXISTS debezium_publication_1;`

### 问题 3：Slot 损坏

**症状**：Slot 存在但无法使用

**解决方案**：
1. 删除损坏的 Slot：`SELECT pg_drop_replication_slot('debezium_slot_1');`
2. 删除相关 Publication：`DROP PUBLICATION IF EXISTS debezium_publication_1;`
3. 重启应用，Debezium 会自动重建

---

## 🔧 配置优化

### 已应用的优化

```properties
# 允许重用 Slot
slot.drop.on.stop=false

# 智能快照模式
snapshot.mode=when_needed
snapshot.delay.ms=5000

# 性能优化
max.batch.size=2048
max.queue.size=8192
poll.interval.ms=1000

# 连接超时
database.connect.timeout.ms=30000
database.statement.timeout.ms=30000
```

### 推荐的 PostgreSQL 配置

```sql
-- 增加 WAL 保留时间
ALTER SYSTEM SET wal_keep_size = '1GB';

-- 增加最大连接数
ALTER SYSTEM SET max_connections = 200;

-- 增加逻辑复制槽数
ALTER SYSTEM SET max_replication_slots = 10;

-- 重新加载配置
SELECT pg_reload_conf();
```

---

## 📊 监控和维护

### 定期检查脚本

```sql
-- 每天运行一次，检查 Slot 健康状态
SELECT
    slot_name,
    slot_type,
    active,
    restart_lsn,
    confirmed_flush_lsn,
    CASE
        WHEN active THEN '活跃'
        ELSE '不活跃'
    END as status
FROM pg_replication_slots
WHERE slot_name LIKE 'debezium_slot_%'
ORDER BY slot_name;
```

### 自动清理任务

可以创建一个定时任务来清理不活跃的 Slot：

```bash
# crontab -e
# 每天凌晨 2 点清理一次
0 2 * * * psql -U postgres -d postgres -f /path/to/cleanup-replication-slots.sql
```

---

## 🎯 快速修复步骤

如果遇到 "replication slot is active" 错误，按以下步骤快速修复：

### 步骤 1：停止应用
```bash
# 按 Ctrl+C 停止应用
```

### 步骤 2：清理 Slot
```bash
psql -U postgres -d postgres -c "
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
WHERE application_name LIKE '%debezium%';
"

sleep 5

psql -U postgres -d postgres -c "
SELECT pg_drop_replication_slot('debezium_slot_1');
DROP PUBLICATION IF EXISTS debezium_publication_1;
"
```

### 步骤 3：重启应用
```bash
mvn spring-boot:run
```

---

## 📝 日志示例

### 正常启动日志
```
启动 CDC 管道: 配置ID=1, 名称=用户表 CDC, 数据库=postgres, 表=users
配置 1 的 CDC 管道在虚拟线程中启动
配置 1 的 Debezium 引擎完成: Snapshot completed
配置 1 处理变更事件 #1 - LSN: 0/1A2B3C4D
```

### 错误日志
```
ERROR: replication slot "debezium_slot_1" is active for PID 383
解决方案：运行清理脚本或手动删除 Slot
```

---

## 🚀 预防措施

### 1. 正确关闭应用

确保应用在关闭时正确清理资源：

```java
@PreDestroy
public void shutdownAll() {
    log.info("关闭所有 CDC 管道...");
    for (Long configId : new ArrayList<>(pipelines.keySet())) {
        try {
            stopPipeline(configId);
        } catch (Exception e) {
            log.error("停止管道时出错: {}", e.getMessage());
        }
    }
}
```

### 2. 使用不同的 Slot 名称

每个配置使用不同的 Slot 名称：

```java
props.setProperty("slot.name", "debezium_slot_" + config.getId());
```

### 3. 定期监控

定期检查 Slot 状态，及时发现问题。

### 4. 备份配置

保存 Debezium 配置，便于快速恢复。

---

## 📞 常见问题

### Q: 如何完全重置 Replication Slot？

A: 运行以下命令：
```sql
-- 终止所有 Debezium 连接
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
WHERE application_name LIKE '%debezium%';

-- 删除所有 Slot
SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots
WHERE slot_name LIKE 'debezium_slot_%' AND NOT active;

-- 删除所有 Publication
DROP PUBLICATION IF EXISTS debezium_publication_1;
DROP PUBLICATION IF EXISTS debezium_publication_2;
```

### Q: 如何避免 Slot 冲突？

A: 使用配置 ID 作为 Slot 名称的一部分，确保每个配置有唯一的 Slot。

### Q: Slot 可以被多个进程共享吗？

A: 不可以。每个 Replication Slot 只能被一个进程使用。

---

## 🎉 总结

通过以上优化和最佳实践，可以有效解决 Replication Slot 相关的错误。关键是：

1. ✅ 使用 `slot.drop.on.stop=false` 允许重用 Slot
2. ✅ 使用 `snapshot.mode=when_needed` 优化快照
3. ✅ 定期清理不活跃的 Slot
4. ✅ 正确关闭应用，释放资源
5. ✅ 监控 Slot 状态，及时发现问题

---

**祝你使用愉快！** 🚀
