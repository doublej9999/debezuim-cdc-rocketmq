package com.example.cdc.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DataSourceConfigDTO {
    private Long id;
    private String name;
    private String dbHostname;
    private Integer dbPort;
    private String dbName;
    private String dbUser;
    private String dbPasswordMasked;
    private String schemaName;
    private String tableName;
    private String rocketmqTopic;
    private String rocketmqTag;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
