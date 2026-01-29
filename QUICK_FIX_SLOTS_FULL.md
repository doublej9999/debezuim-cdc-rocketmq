# ⚡ 快速修复：Replication Slots 已满

## 问题
```
ERROR: all replication slots are in use
```

## 🚀 3 步快速修复

### 步骤 1：增加 PostgreSQL Slot 数量

编辑 PostgreSQL 配置文件：

**Linux/macOS:**
```bash
# 编辑配置文件
sudo nano /etc/postgresql/*/main/postgresql.conf
# 或 /usr/local/var/postgres/postgresql.conf
```

**Windows:**
```
C:\Program Files\PostgreSQL\*\data\postgresql.conf
```

找到或添加以下配置：
```ini
max_replication_slots = 20
max_wal_senders = 20
wal_keep_size = 1GB
```

### 步骤 2：重启 PostgreSQL

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

### 步骤 3：清理旧 Slot 并重启应用

```bash
# 清理旧 Slot
psql -U postgres -d postgres -f cleanup-replication-slots.sql

# 重启应用
mvn clean install
mvn spring-boot:run
```

---

## ✅ 验证修复

```bash
psql -U postgres -d postgres -c "SHOW max_replication_slots;"
```

应该返回 `20` 或更大的数字。

---

## 📚 详细文档

更多信息请查看：
- **REPLICATION_SLOTS_FULL_SOLUTION.md** - 完整解决方案
- **cleanup-replication-slots.sql** - 清理脚本

---

**现在可以启动应用了！** 🎉
