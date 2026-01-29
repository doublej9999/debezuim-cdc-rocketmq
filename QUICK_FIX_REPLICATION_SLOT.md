# 🚀 快速修复 Replication Slot 错误

## 问题

启动应用时出现错误：
```
ERROR: replication slot "debezium_slot_1" is active for PID 383
```

## ⚡ 快速修复（3 步）

### 步骤 1：停止应用
```bash
# 按 Ctrl+C 停止应用
```

### 步骤 2：清理 PostgreSQL Slot

连接到 PostgreSQL：
```bash
psql -U postgres -d postgres
```

执行清理命令：
```sql
-- 终止所有 Debezium 连接
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
WHERE application_name LIKE '%debezium%';

-- 等待 5 秒后删除 Slot
SELECT pg_drop_replication_slot('debezium_slot_1');
SELECT pg_drop_replication_slot('debezium_slot_2');
SELECT pg_drop_replication_slot('debezium_slot_3');

-- 删除 Publication
DROP PUBLICATION IF EXISTS debezium_publication_1;
DROP PUBLICATION IF EXISTS debezium_publication_2;
DROP PUBLICATION IF EXISTS debezium_publication_3;
```

或者使用一键清理脚本：
```bash
psql -U postgres -d postgres -f cleanup-replication-slots.sql
```

### 步骤 3：重启应用
```bash
mvn clean install
mvn spring-boot:run
```

---

## ✅ 已应用的代码优化

代码已自动优化，支持 Slot 重用：

```java
// 允许重用已存在的 Replication Slot
props.setProperty("slot.drop.on.stop", "false");

// 智能快照模式（如果已有偏移量，跳过快照）
props.setProperty("snapshot.mode", "when_needed");

// 性能优化
props.setProperty("max.batch.size", "2048");
props.setProperty("max.queue.size", "8192");
```

---

## 📋 验证修复

修复后，查看 Slot 状态：

```bash
psql -U postgres -d postgres -c "
SELECT slot_name, slot_type, active FROM pg_replication_slots
WHERE slot_name LIKE 'debezium_slot_%';
"
```

应该返回空结果（所有 Slot 已清理）。

---

## 🎯 预防措施

### 1. 正确关闭应用

使用 `Ctrl+C` 或 `kill -TERM` 正确关闭应用，而不是强制杀死。

### 2. 定期清理

如果频繁遇到此问题，可以定期清理：

```bash
# 每次启动前清理
psql -U postgres -d postgres -f cleanup-replication-slots.sql
mvn spring-boot:run
```

### 3. 监控 Slot 状态

定期检查 Slot 状态：

```bash
psql -U postgres -d postgres -c "
SELECT slot_name, active, restart_lsn FROM pg_replication_slots
WHERE slot_name LIKE 'debezium_slot_%';
"
```

---

## 📚 详细文档

更多信息请查看：
- **REPLICATION_SLOT_OPTIMIZATION.md** - 详细优化指南
- **cleanup-replication-slots.sql** - 完整清理脚本

---

**现在可以重新启动应用了！** 🎉
