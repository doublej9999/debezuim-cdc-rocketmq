package com.example.cdc.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class DataSourceConfigResponse {
    Long id;
    String name;
    String dbHostname;
    Integer dbPort;
    String dbName;
    String dbUser;
    String schemaName;
    String tableName;
    String rocketmqTopic;
    String rocketmqTag;
    Boolean isActive;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
