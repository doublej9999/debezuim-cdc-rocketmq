# PostgreSQL Replication Slot 和 Publication 自动创建机制

## 📌 简短答案

**是的，Debezium 会自动创建 Replication Slot 和 Publication。**

当你在 Debezium 配置中指定 `slot.name` 和 `publication.name` 时，Debezium 会在启动时自动创建这些资源（如果它们不存在）。

---

## 🔍 详细说明

### 当前配置中的自动创建

你的项目中已经配置了自动创建：

```java
// MultiConfigCdcPipelineManager.java 中的配置
props.setProperty("slot.name", "debezium_slot_" + config.getId());
props.setProperty("publication.name", "debezium_publication_" + config.getId());
props.setProperty("slot.drop.on.stop", "false");  // 停止时不删除 Slot
```

### 工作流程

```
Debezium 启动
    ↓
检查 Replication Slot 是否存在
    ↓
不存在 → 自动创建 Slot
    ↓
检查 Publication 是否存在
    ↓
不存在 → 自动创建 Publication
    ↓
开始监听数据库变更
```

---

## ✅ 自动创建的条件

### 1. 必要的权限

PostgreSQL 用户需要以下权限：

```sql
-- 创建 Replication Slot 需要的权限
ALTER ROLE postgres WITH REPLICATION;

-- 创建 Publication 需要的权限
GRANT CREATE ON DATABASE postgres TO postgres;
```

### 2. 必要的配置

```java
// 这些配置会触发自动创建
props.setProperty("slot.name", "debezium_slot_1");
props.setProperty("publication.name", "debezium_publication_1");
props.setProperty("plugin.name", "pgoutput");  // 必须使用 pgoutput 插件
```

### 3. PostgreSQL 配置

```ini
# postgresql.conf 中需要启用逻辑复制
wal_level = logical
max_replication_slots = 10
max_wal_senders = 10
```

---

## 🔄 自动创建的详细过程

### 步骤 1：创建 Replication Slot

Debezium 会执行类似的 SQL：

```sql
SELECT * FROM pg_create_logical_replication_slot(
    'debezium_slot_1',
    'pgoutput'
);
```

### 步骤 2：创建 Publication

Debezium 会执行类似的 SQL：

```sql
CREATE PUBLICATION debezium_publication_1 FOR TABLE flink.users;
```

### 步骤 3：关联 Slot 和 Publication

Debezium 内部会关联这两个资源，开始监听变更。

---

## 📊 验证自动创建

### 查看已创建的 Slot

```bash
psql -U postgres -d postgres -c "
SELECT slot_name, slot_type, active FROM pg_replication_slots;
"
```

应该看到：
```
      slot_name      | slot_type | active
---------------------+-----------+--------
 debezium_slot_1     | logical   | t
 debezium_slot_2     | logical   | t
```

### 查看已创建的 Publication

```bash
psql -U postgres -d postgres -c "
SELECT pubname FROM pg_publication;
"
```

应该看到：
```
         pubname
------------------------
 debezium_publication_1
 debezium_publication_2
```

---

## ⚙️ 配置选项

### 选项 1：完全自动创建（推荐）

```java
// 让 Debezium 自动创建所有资源
props.setProperty("slot.name", "debezium_slot_" + config.getId());
props.setProperty("publication.name", "debezium_publication_" + config.getId());
props.setProperty("slot.drop.on.stop", "false");  // 停止时保留 Slot
```

**优点：**
- 无需手动干预
- 自动处理资源创建
- 支持多个配置并发运行

**缺点：**
- 需要足够的权限
- Slot 会持续占用资源

### 选项 2：手动创建 + Debezium 使用

先手动创建资源：

```sql
-- 创建 Replication Slot
SELECT * FROM pg_create_logical_replication_slot(
    'debezium_slot_1',
    'pgoutput'
);

-- 创建 Publication
CREATE PUBLICATION debezium_publication_1 FOR TABLE flink.users;
```

然后配置 Debezium 使用现有资源：

```java
props.setProperty("slot.name", "debezium_slot_1");
props.setProperty("publication.name", "debezium_publication_1");
props.setProperty("slot.drop.on.stop", "false");
```

**优点：**
- 更好的控制
- 可以预先验证权限
- 便于调试

**缺点：**
- 需要手动管理
- 容易遗漏

---

## 🚨 常见问题

### 问题 1：Slot 创建失败

**错误信息：**
```
ERROR: permission denied to create replication slot
```

**解决方案：**
```sql
-- 授予 REPLICATION 权限
ALTER ROLE postgres WITH REPLICATION;
```

### 问题 2：Publication 创建失败

**错误信息：**
```
ERROR: permission denied to create publication
```

**解决方案：**
```sql
-- 授予 CREATE 权限
GRANT CREATE ON DATABASE postgres TO postgres;
```

### 问题 3：Slot 已存在

**错误信息：**
```
ERROR: replication slot "debezium_slot_1" already exists
```

**解决方案：**
```java
// 配置允许重用现有 Slot
props.setProperty("slot.drop.on.stop", "false");
```

---

## 🔧 当前项目的配置

你的项目已经正确配置了自动创建：

```java
// MultiConfigCdcPipelineManager.java 中的配置
private Properties buildDebeziumProperties() {
    Properties props = new Properties();

    // ... 其他配置 ...

    // 自动创建配置
    props.setProperty("slot.name", "debezium_slot_" + config.getId());
    props.setProperty("publication.name", "debezium_publication_" + config.getId());
    props.setProperty("slot.drop.on.stop", "false");
    props.setProperty("snapshot.mode", "when_needed");

    return props;
}
```

### 工作流程

1. **应用启动** → Debezium 初始化
2. **检查 Slot** → 不存在则自动创建 `debezium_slot_1`
3. **检查 Publication** → 不存在则自动创建 `debezium_publication_1`
4. **开始监听** → 捕获数据库变更
5. **应用关闭** → Slot 保留（因为 `slot.drop.on.stop=false`）

---

## 📋 权限检查清单

确保 PostgreSQL 用户有以下权限：

```bash
psql -U postgres -d postgres -c "
-- 检查 REPLICATION 权限
SELECT usename, usecanrepl FROM pg_user WHERE usename = 'postgres';

-- 检查 CREATE 权限
SELECT has_database_privilege('postgres', 'postgres', 'CREATE');

-- 检查 Slot 创建权限
SELECT * FROM pg_create_logical_replication_slot('test_slot', 'pgoutput');
SELECT * FROM pg_drop_replication_slot('test_slot');
"
```

---

## 🎯 最佳实践

### 1. 启用自动创建

```java
// 推荐配置
props.setProperty("slot.drop.on.stop", "false");  // 保留 Slot
props.setProperty("snapshot.mode", "when_needed");  // 智能快照
```

### 2. 定期清理

```bash
# 定期清理不活跃的 Slot
0 2 * * * psql -U postgres -d postgres -f cleanup-replication-slots.sql
```

### 3. 监控 Slot 状态

```bash
# 定期检查 Slot 使用情况
psql -U postgres -d postgres -c "
SELECT slot_name, active, restart_lsn FROM pg_replication_slots;
"
```

---

## 📚 相关文档

- **REPLICATION_SLOTS_FULL_SOLUTION.md** - 完整的 Slot 管理指南
- **cleanup-replication-slots.sql** - Slot 清理脚本
- **QUICK_FIX_SLOTS_FULL.md** - 快速修复指南

---

## 🎉 总结

| 问题 | 答案 |
|------|------|
| Debezium 会自动创建 Slot 吗？ | ✅ 是的，如果配置了 `slot.name` |
| Debezium 会自动创建 Publication 吗？ | ✅ 是的，如果配置了 `publication.name` |
| 需要手动创建吗？ | ❌ 不需要，但可以手动创建后让 Debezium 使用 |
| 当前项目是否已启用自动创建？ | ✅ 是的，已正确配置 |
| 需要什么权限？ | REPLICATION 和 CREATE 权限 |

---

**你的项目已经正确配置了自动创建机制！** ✨
