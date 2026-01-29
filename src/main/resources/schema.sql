-- 创建 datasource_config 表
CREATE TABLE IF NOT EXISTS datasource_config (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    db_hostname VARCHAR(255) NOT NULL,
    db_port INTEGER NOT NULL,
    db_name VARCHAR(255) NOT NULL,
    db_user VARCHAR(255) NOT NULL,
    db_password VARCHAR(255) NOT NULL,
    schema_name VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    rocketmq_topic VARCHAR(255) NOT NULL,
    rocketmq_tag VARCHAR(255),
    is_active BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 创建索引以提高查询性能
CREATE INDEX IF NOT EXISTS idx_datasource_is_active ON datasource_config(is_active);
CREATE INDEX IF NOT EXISTS idx_datasource_created_at ON datasource_config(created_at DESC);

-- 插入示例数据（可选）
INSERT INTO datasource_config (name, db_hostname, db_port, db_name, db_user, db_password, schema_name, table_name, rocketmq_topic, rocketmq_tag, is_active)
VALUES ('示例配置', 'localhost', 5432, 'postgres', 'postgres', '123456', 'flink', 'users', 'cdc-events', 'users', false)
ON CONFLICT DO NOTHING;
