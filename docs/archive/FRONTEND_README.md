# Debezium CDC 管理平台

这是一个为 Debezium CDC RocketMQ 集成项目开发的 Web 管理平台，提供了完整的数据源配置管理和系统监控功能。

## 功能特性

### 1. 数据源配置管理
- ✅ **新增配置**：支持配置多个数据源，包括：
  - 数据库连接信息（主机、端口、用户、密码等）
  - 监听的 Schema 和表名
  - RocketMQ Topic 和 Tag 设置

- ✅ **配置列表**：查看所有已保存的配置
  - 显示配置详细信息
  - 支持启用/停用配置
  - 支持删除配置

- ✅ **配置持久化**：所有配置自动保存到 PostgreSQL 数据库
  - 下次启动应用时自动加载
  - 支持配置版本管理

### 2. 系统监控
- 📊 **实时监控面板**：
  - Debezium 引擎运行状态
  - RocketMQ 生产者状态
  - 数据库连接信息
  - 当前处理的 Topic
  - 应用启动时间

- 📈 **性能指标**：
  - 已处理事件数
  - 当前 LSN（Log Sequence Number）
  - 应用运行时长
  - 自动刷新（每 5 秒）

## 快速开始

### 前置条件
- Java 21+
- Spring Boot 3.4.1
- PostgreSQL 12+
- RocketMQ 4.9.7+

### 安装步骤

1. **添加依赖**（已完成）
   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-data-jpa</artifactId>
   </dependency>
   ```

2. **配置数据库连接**
   编辑 `application.yml`：
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://localhost:5432/postgres
       username: postgres
       password: 123456
     jpa:
       hibernate:
         ddl-auto: update
   ```

3. **初始化数据库**
   - 运行 `schema.sql` 创建表结构
   - 或让 Hibernate 自动创建（`ddl-auto: update`）

4. **启动应用**
   ```bash
   mvn spring-boot:run
   ```

5. **访问管理平台**
   打开浏览器访问：`http://localhost:8082`

## API 文档

### 数据源配置 API

#### 获取所有配置
```
GET /api/datasource
```
**响应示例**：
```json
[
  {
    "id": 1,
    "name": "生产库用户表",
    "dbHostname": "localhost",
    "dbPort": 5432,
    "dbName": "postgres",
    "dbUser": "postgres",
    "schemaName": "flink",
    "tableName": "users",
    "rocketmqTopic": "cdc-events",
    "rocketmqTag": "users",
    "isActive": true,
    "createdAt": "2026-01-29T10:30:00",
    "updatedAt": "2026-01-29T10:30:00"
  }
]
```

#### 获取单个配置
```
GET /api/datasource/{id}
```

#### 获取活跃配置
```
GET /api/datasource/active
```

#### 创建配置
```
POST /api/datasource
Content-Type: application/json

{
  "name": "生产库用户表",
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
}
```

#### 更新配置
```
PUT /api/datasource/{id}
Content-Type: application/json

{
  "name": "更新后的名称",
  ...
}
```

#### 删除配置
```
DELETE /api/datasource/{id}
```

#### 切换配置状态
```
POST /api/datasource/{id}/toggle
```

### 监控 API

#### 获取引擎状态
```
GET /api/monitor/status
```
**响应示例**：
```json
{
  "running": true,
  "currentLsn": "0/1234567",
  "processedEvents": 150,
  "startTime": "2026-01-29T10:30:00",
  "uptimeSeconds": 3600,
  "rocketmqProducerRunning": true,
  "databaseHost": "localhost",
  "rocketmqNameServer": "45.128.210.102:9876",
  "rocketmqTopic": "cdc-events"
}
```

#### 获取当前 LSN
```
GET /api/monitor/lsn
```

#### 健康检查
```
GET /api/monitor/health
```

#### 获取统计信息
```
GET /api/monitor/stats
```

## 项目结构

```
src/main/java/com/example/cdc/
├── controller/
│   ├── MonitorController.java          # 监控 API
│   └── DataSourceConfigController.java # 配置管理 API
├── service/
│   ├── DebeziumEngineService.java
│   ├── DataSourceConfigService.java    # 配置业务逻辑
│   └── RocketMQProducerService.java
├── model/
│   ├── EngineStatus.java
│   └── DataSourceConfig.java           # 配置实体
├── repository/
│   └── DataSourceConfigRepository.java # 数据访问层
└── config/
    ├── DebeziumConfig.java
    └── RocketMQConfig.java

src/main/resources/
├── application.yml                     # 应用配置
├── schema.sql                          # 数据库初始化脚本
└── static/
    └── index.html                      # 前端管理界面
```

## 使用场景

### 场景 1：配置单个数据源
1. 打开管理平台
2. 在"新增数据源配置"表单中填入数据库信息
3. 指定要监听的 Schema 和表
4. 设置 RocketMQ Topic 和 Tag
5. 点击"保存配置"
6. 在配置列表中点击"启用"激活配置

### 场景 2：监控多个数据源
1. 添加多个配置
2. 在配置列表中分别启用需要的配置
3. 监控面板实时显示系统状态
4. 查看已处理事件数和当前 LSN

### 场景 3：故障排查
1. 查看监控面板的各项状态指标
2. 检查 Debezium 引擎和 RocketMQ 生产者状态
3. 查看数据库连接和 NameServer 地址
4. 根据状态判断故障原因

## 数据库表结构

### datasource_config 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | SERIAL | 主键 |
| name | VARCHAR(255) | 配置名称 |
| db_hostname | VARCHAR(255) | 数据库主机 |
| db_port | INTEGER | 数据库端口 |
| db_name | VARCHAR(255) | 数据库名 |
| db_user | VARCHAR(255) | 数据库用户 |
| db_password | VARCHAR(255) | 数据库密码 |
| schema_name | VARCHAR(255) | Schema 名称 |
| table_name | VARCHAR(255) | 表名 |
| rocketmq_topic | VARCHAR(255) | RocketMQ Topic |
| rocketmq_tag | VARCHAR(255) | RocketMQ Tag（可选） |
| is_active | BOOLEAN | 是否活跃 |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

## 常见问题

### Q: 如何修改已保存的配置？
A: 目前需要删除后重新创建。后续可以添加编辑功能。

### Q: 配置是否支持加密存储？
A: 当前密码以明文存储。生产环境建议使用加密存储或密钥管理服务。

### Q: 监控数据是否持久化？
A: 监控数据仅实时显示，不持久化。如需历史数据，可扩展添加时间序列数据库。

### Q: 支持多用户并发操作吗？
A: 当前版本不支持用户认证。生产环境建议添加认证和授权机制。

## 后续改进方向

- [ ] 添加用户认证和授权
- [ ] 支持配置编辑功能
- [ ] 密码加密存储
- [ ] 监控数据持久化和历史查询
- [ ] 告警和通知功能
- [ ] 配置导入/导出
- [ ] 多语言支持
- [ ] 深色主题

## 许可证

MIT License
