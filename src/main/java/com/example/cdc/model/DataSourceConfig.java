package com.example.cdc.model;

import com.example.cdc.config.AesEncryptor;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "datasource_config")
public class DataSourceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "db_hostname", nullable = false)
    private String dbHostname;

    @Column(name = "db_port", nullable = false)
    private Integer dbPort;

    @Column(name = "db_name", nullable = false)
    private String dbName;

    @Column(name = "db_user", nullable = false)
    private String dbUser;

    @Convert(converter = AesEncryptor.class)
    @Column(name = "db_password", nullable = false)
    private String dbPassword;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "rocketmq_topic", nullable = false)
    private String rocketmqTopic;

    @Column(name = "rocketmq_tag")
    private String rocketmqTag;

    @Column(name = "rocketmq_namesrv_addr")
    private String rocketmqNamesrvAddr;

    @Column(name = "rocketmq_producer_group")
    private String rocketmqProducerGroup;

    @Column(name = "offset_key", length = 64)
    private String offsetKey;

    @Column(name = "is_active")
    private Boolean isActive = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
