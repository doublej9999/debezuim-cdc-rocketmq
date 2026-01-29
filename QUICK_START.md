# 快速启动指南

## 一、环境准备

### 1. 确保 PostgreSQL 运行
```bash
# 如果使用 Docker
docker run --name postgres -e POSTGRES_PASSWORD=123456 -p 5432:5432 -d postgres:15
```

### 2. 确保 RocketMQ 运行
```bash
# 使用项目提供的 docker-compose
docker-compose up -d
```

## 二、数据库初始化

### 方式一：自动初始化（推荐）
应用启动时，Hibernate 会自动创建 `datasource_config` 表（因为 `ddl-auto: update`）。

### 方式二：手动初始化
连接到 PostgreSQL 并执行 `schema.sql`：
```bash
psql -U postgres -d postgres -f src/main/resources/schema.sql
```

## 三、启动应用

### 使用 Maven
```bash
mvn clean install
mvn spring-boot:run
```

### 使用 IDE
在 IDE 中运行 `DebeziumCdcApplication.java` 的 main 方法

## 四、访问管理平台

打开浏览器访问：
```
http://localhost:8082
```

## 五、首次使用

### 步骤 1：添加数据源配置
1. 在"新增数据源配置"表单中填入信息
2. 示例配置：
   - 配置名称：`生产库用户表`
   - 数据库主机：`localhost`
   - 数据库端口：`5432`
   - 数据库名：`postgres`
   - 数据库用户：`postgres`
   - 数据库密码：`123456`
   - Schema 名称：`flink`
   - 表名：`users`
   - RocketMQ Topic：`cdc-events`
   - RocketMQ Tag：`users`
3. 点击"保存配置"

### 步骤 2：启用配置
1. 在"已保存的配置"表中找到刚创建的配置
2. 点击"▶ 启用"按钮激活配置

### 步骤 3：监控系统
1. 查看右侧"系统监控"面板
2. 观察各项指标：
   - Debezium 引擎状态
   - RocketMQ 生产者状态
   - 已处理事件数
   - 当前 LSN

## 六、API 测试

### 使用 curl 测试

#### 获取所有配置
```bash
curl http://localhost:8082/api/datasource
```

#### 创建配置
```bash
curl -X POST http://localhost:8082/api/datasource \
  -H "Content-Type: application/json" \
  -d '{
    "name": "测试配置",
    "dbHostname": "localhost",
    "dbPort": 5432,
    "dbName": "postgres",
    "dbUser": "postgres",
    "dbPassword": "123456",
    "schemaName": "flink",
    "tableName": "users",
    "rocketmqTopic": "cdc-events",
    "rocketmqTag": "users",
    "isActive": false
  }'
```

#### 获取监控状态
```bash
curl http://localhost:8082/api/monitor/status
```

#### 切换配置状态
```bash
curl -X POST http://localhost:8082/api/datasource/1/toggle
```

#### 删除配置
```bash
curl -X DELETE http://localhost:8082/api/datasource/1
```

## 七、故障排查

### 问题 1：无法连接到数据库
**症状**：应用启动失败，提示数据库连接错误

**解决方案**：
1. 检查 PostgreSQL 是否运行：`psql -U postgres`
2. 检查 `application.yml` 中的数据库配置
3. 确保密码正确

### 问题 2：前端页面无法加载
**症状**：访问 `http://localhost:8082` 显示 404

**解决方案**：
1. 确保 `src/main/resources/static/index.html` 存在
2. 检查应用是否正常启动
3. 查看应用日志

### 问题 3：API 请求失败
**症状**：前端显示"网络错误"

**解决方案**：
1. 检查浏览器控制台的错误信息
2. 确保后端 API 正常运行
3. 检查 CORS 配置（已添加 `@CrossOrigin(origins = "*")`)

### 问题 4：Debezium 引擎未运行
**症状**：监控面板显示"✗ 停止"

**解决方案**：
1. 检查 PostgreSQL 是否运行
2. 检查 `application.yml` 中的 Debezium 配置
3. 查看应用日志中的错误信息
4. 确保 PostgreSQL 已启用逻辑复制

## 八、性能优化建议

### 1. 数据库优化
```sql
-- 为常用查询字段添加索引
CREATE INDEX idx_datasource_is_active ON datasource_config(is_active);
CREATE INDEX idx_datasource_created_at ON datasource_config(created_at DESC);
```

### 2. 应用配置优化
```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 20
          fetch_size: 50
```

### 3. 监控刷新频率
在 `index.html` 中调整刷新间隔：
```javascript
setInterval(refreshMonitor, 5000); // 改为需要的间隔（毫秒）
```

## 九、生产环境部署

### 1. 安全加固
- [ ] 添加用户认证
- [ ] 启用 HTTPS
- [ ] 加密存储密码
- [ ] 限制 CORS 来源

### 2. 监控和日志
- [ ] 配置日志聚合
- [ ] 添加应用监控
- [ ] 设置告警规则

### 3. 备份和恢复
- [ ] 定期备份数据库
- [ ] 测试恢复流程

## 十、联系和支持

如有问题，请查看：
- 应用日志：`logs/` 目录
- 详细文档：`FRONTEND_README.md`
- 项目源码：`src/main/java/com/example/cdc/`
