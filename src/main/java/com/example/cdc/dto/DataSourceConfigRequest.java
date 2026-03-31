package com.example.cdc.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DataSourceConfigRequest {
    @NotBlank(message = "name 不能为空")
    private String name;

    @NotBlank(message = "dbHostname 不能为空")
    private String dbHostname;

    @NotNull(message = "dbPort 不能为空")
    @Min(value = 1, message = "dbPort 最小为 1")
    @Max(value = 65535, message = "dbPort 最大为 65535")
    private Integer dbPort;

    @NotBlank(message = "dbName 不能为空")
    private String dbName;

    @NotBlank(message = "dbUser 不能为空")
    private String dbUser;

    @NotBlank(message = "dbPassword 不能为空")
    private String dbPassword;

    @NotBlank(message = "schemaName 不能为空")
    private String schemaName;

    @NotBlank(message = "tableName 不能为空")
    private String tableName;

    @NotBlank(message = "rocketmqTopic 不能为空")
    private String rocketmqTopic;

    private String rocketmqTag;

    private String rocketmqNamesrvAddr;

    private String rocketmqProducerGroup;

    private Boolean isActive;
}
