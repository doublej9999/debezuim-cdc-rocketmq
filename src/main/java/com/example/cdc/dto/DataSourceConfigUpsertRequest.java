package com.example.cdc.dto;

import lombok.Data;

@Data
public class DataSourceConfigUpsertRequest {
    private String name;
    private String dbHostname;
    private Integer dbPort;
    private String dbName;
    private String dbUser;
    private String dbPassword;
    private String schemaName;
    private String tableName;
    private String rocketmqTopic;
    private String rocketmqTag;
    private Boolean isActive;
}
