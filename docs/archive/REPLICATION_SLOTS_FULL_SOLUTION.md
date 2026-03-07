# 🔧 Replication Slot 已满错误解决方案

## 问题

启动应用时出现错误：
```
io.debezium.DebeziumException: Creation of replication slot failed
Caused by: org.postgresql.util.PSQLException: ERROR: all replication slots are in use
```

这表示 PostgreSQL 的 Replication Slot 数量已达到上限。

---

## ✅ 解决方案

### 方案 1：增加 PostgreSQL 的 Replication Slot 数量（推荐）

#### 步骤 1：找到 PostgreSQL 配置文件

**Linux:**
```bash
sudo find / -name "postgresql.conf" 2>/dev/null
# 通常在 /etc/postgresql/*/main/postgresql.conf
```

**macOS:**
```bash
# 通常在 /usr/local/var/postgres/postgresql.conf
```

**Windows:**
```
C:\Program Files\PostgreSQL\*\data\postgresql.conf
```

#### 步骤 2：编辑配置文件

找到以下配置项（如果不存在则添加）：

```ini
# 增加 Replication Slot 数量
max_replication_slots = 20      # 默认是 10，改为 20 或更多

# 增加 WAL 发送者数量
max_wal_senders = 20            # 应该 >= max_replication_slots

# 增加 WAL 保留大小
wal_keep_size = 1GB             # 保留 1GB 的 WAL 日志
```

#### 步骤 3：重启 PostgreSQL

**Linux:**
```bash
sudo systemctl restart postgresql
```

**macOS:**
```bash
brew services restart postgresql
```

**Windows:**
```bash
net stop PostgreSQL14
net start PostgreSQL14
```

或者在 Windows 服务管理器中重启 PostgreSQL 服务。

#### 步骤 4：验证配置

```bash
psql -U postgres -d postgres -c "
SHOW max_replication_slots;
SHOW max_wal_senders;
SHOW wal_keep_size;
"
```

---

### 方案 2：清理未使用的 Replication Slot

#### 步骤 1：连接到 PostgreSQL

```bash
psql -U postgres -d postgres
```

#### 步骤 2：查看所有 Replication Slot

```sql
SELECT slot_name, slot_type, active, restart_lsn FROM pg_replication_slots;
```

#### 步骤 3：删除不活跃的 Slot

```sql
-- 删除所有不活跃的 Debezium Slot
SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots
WHERE NOT active AND slot_name LIKE 'debezium_slot_%';

-- 删除所有 Debezium Publication
DROP PUBLICATION IF EXISTS debezium_publication_1;
DROP PUBLICATION IF EXISTS debezium_publication_2;
DROP PUBLICATION IF EXISTS debezium_publication_3;
```

#### 步骤 4：验证清理结果

```sql
SELECT slot_name, active FROM pg_replication_slots;
```

应该返回空结果或只有活跃的 Slot。

---

### 方案 3：使用一键清理脚本

运行提供的清理脚本：

```bash
psql -U postgres -d postgres -f cleanup-replication-slots.sql
```

---

### 方案 4：优化应用配置

已在代码中应用以下优化：

```java
// 允许重用已存在的 Replication Slot
props.setProperty("slot.drop.on.stop", "false");

// 智能快照模式
props.setProperty("snapshot.mode", "when_needed");

// 减少 WAL 日志保留时间
props.setProperty("wal.lsn.flush.interval.ms", "10000");
```

---

## 🚀 快速修复步骤

### 如果立即需要启动应用：

#### 步骤 1：清理现有 Slot

```bash
psql -U postgres -d postgres << EOF
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
WHERE application_name LIKE '%debezium%';

SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots
WHERE NOT active AND slot_name LIKE 'debezium_slot_%';

DROP PUBLICATION IF EXISTS debezium_publication_1;
DROP PUBLICATION IF EXISTS debezium_publication_2;
DROP PUBLICATION IF EXISTS debezium_publication_3;
EOF
```

#### 步骤 2：重启应用

```bash
mvn clean install
mvn spring-boot:run
```

---

## 📊 检查 PostgreSQL 配置

### 查看当前配置

```bash
psql -U postgres -d postgres -c "
SELECT name, setting FROM pg_settings
WHERE name IN ('max_replication_slots', 'max_wal_senders', 'wal_keep_size');
"
```

### 查看 Replication Slot 使用情况

```bash
psql -U postgres -d postgres -c "
SELECT
    slot_name,
    slot_type,
    active,
    restart_lsn,
    confirmed_flush_lsn,
    CASE WHEN active THEN '活跃' ELSE '不活跃' END as status
FROM pg_replication_slots
ORDER BY slot_name;
"
```

---

## 🎯 最佳实践

### 1. 定期清理

创建一个定时任务来清理不活跃的 Slot：

```bash
# crontab -e
# 每天凌晨 2 点清理一次
0 2 * * * psql -U postgres -d postgres -f /path/to/cleanup-replication-slots.sql
```

### 2. 监控 Slot 数量

定期检查 Slot 使用情况：

```bash
# 每小时检查一次
0 * * * * psql -U postgres -d postgres -c "SELECT COUNT(*) as slot_count FROM pg_replication_slots;" >> /var/log/pg_slots.log
```

### 3. 正确关闭应用

确保应用正确关闭，释放 Slot：

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

### 4. 配置告警

当 Slot 数量接近上限时发送告警：

```sql
-- 创建告警函数
CREATE OR REPLACE FUNCTION check_replication_slots()
RETURNS TABLE(slot_count INT, max_slots INT, usage_percent NUMERIC) AS $$
SELECT
    COUNT(*)::INT,
    (SELECT setting::INT FROM pg_settings WHERE name = 'max_replication_slots'),
    ROUND(COUNT(*) * 100.0 / (SELECT setting::INT FROM pg_settings WHERE name = 'max_replication_slots'), 2)
FROM pg_replication_slots;
$$ LANGUAGE SQL;

-- 查询告警
SELECT * FROM check_replication_slots();
```

---

## 📋 故障排查

### 问题 1：删除 Slot 时出现 "slot is active" 错误

**解决方案：**
```sql
-- 查找占用 Slot 的进程
SELECT pid, usename, application_name FROM pg_stat_activity
WHERE application_name LIKE '%debezium%';

-- 终止进程
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
WHERE application_name LIKE '%debezium%';

-- 等待 5-10 秒后删除 Slot
SELECT pg_drop_replication_slot('debezium_slot_1');
```

### 问题 2：重启后仍然出现错误

**解决方案：**
1. 检查 PostgreSQL 是否正确重启
2. 验证配置是否生效：`SHOW max_replication_slots;`
3. 清理所有旧的 Slot 和 Publication
4. 重新启动应用

### 问题 3：Slot 数量不断增加

**解决方案：**
1. 检查应用是否正确关闭
2. 检查是否有僵尸进程占用 Slot
3. 启用定时清理任务
4. 监控应用日志

---

## 📈 性能优化建议

### 1. 调整 Replication Slot 数量

根据你的需求调整：

```ini
# 单个应用，少量配置
max_replication_slots = 10

# 中等规模应用
max_replication_slots = 20

# 大规模应用
max_replication_slots = 50
```

### 2. 调整 WAL 保留大小

```ini
# 根据你的磁盘空间调整
wal_keep_size = 1GB      # 1GB
wal_keep_size = 5GB      # 5GB
wal_keep_size = 10GB     # 10GB
```

### 3. 调整 Debezium 配置

```java
// 减少快照延迟
props.setProperty("snapshot.delay.ms", "1000");

// 增加批处理大小
props.setProperty("max.batch.size", "4096");

// 减少轮询间隔
props.setProperty("poll.interval.ms", "500");
```

---

## 🎉 总结

解决 "all replication slots are in use" 错误的关键步骤：

1. ✅ **增加 Replication Slot 数量** - 修改 `max_replication_slots`
2. ✅ **清理不活跃的 Slot** - 运行清理脚本
3. ✅ **优化应用配置** - 使用 `slot.drop.on.stop=false`
4. ✅ **定期维护** - 创建定时清理任务
5. ✅ **监控告警** - 及时发现问题

---

**现在可以重新启动应用了！** 🚀
