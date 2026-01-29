package com.example.cdc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Debezium 配置类
 * 从 application.yml 读取配置并转换为 Debezium Engine 所需的 Properties
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "debezium")
public class DebeziumConfig {

    private String name;
    private String connectorClass;
    private String offsetStorageClass;
    private String offsetStorageFileName;
    private String offsetFlushIntervalMs;
    private String topicPrefix;

    private Database database;

    @Data
    public static class Database {
        private String hostname;
        private Integer port;
        private String user;
        private String password;
        private String dbname;
        private String serverName;
        private String schemaIncludeList;
        private String tableIncludeList;
        private String pluginName;
        private String slotName;
        private String publicationName;
    }

    /**
     * 将配置转换为 Debezium Engine 所需的 Properties 格式
     */
    public Properties toProperties() {
        Properties props = new Properties();

        // Connector 基础配置
        props.setProperty("name", name);
        props.setProperty("connector.class", connectorClass);
        props.setProperty("offset.storage", offsetStorageClass);
        props.setProperty("offset.storage.file.filename", offsetStorageFileName);
        props.setProperty("offset.flush.interval.ms", offsetFlushIntervalMs);
        props.setProperty("key.converter.schemas.enable", "false");
        props.setProperty("value.converter.schemas.enable", "false");

        // 数据库连接配置
        props.setProperty("database.hostname", database.hostname);
        props.setProperty("database.port", String.valueOf(database.port));
        props.setProperty("database.user", database.user);
        props.setProperty("database.password", database.password);
        props.setProperty("database.dbname", database.dbname);
        props.setProperty("database.server.name", database.serverName);

        // Schema 和 Table 过滤
        if (database.schemaIncludeList != null && !database.schemaIncludeList.isEmpty()) {
            props.setProperty("schema.include.list", database.schemaIncludeList);
        }

        if (database.tableIncludeList != null && !database.tableIncludeList.isEmpty()) {
            props.setProperty("table.include.list", database.tableIncludeList);
        }

        // PostgreSQL 特定配置
        props.setProperty("plugin.name", database.pluginName);
        props.setProperty("slot.name", database.slotName);
        props.setProperty("publication.name", database.publicationName);

        // Topic 前缀
        props.setProperty("topic.prefix", topicPrefix);

        // Schema History 配置 (Embedded Engine 需要)
        props.setProperty("schema.history.internal",
            "io.debezium.storage.file.history.FileSchemaHistory");
        props.setProperty("schema.history.internal.file.filename",
            "./schema-history.dat");

        // 其他配置
        props.setProperty("tombstones.on.delete", "false");
        props.setProperty("snapshot.mode", "initial");

        return props;
    }
}
