package com.example.cdc.dto;

import com.example.cdc.model.DataSourceConfig;
import org.springframework.stereotype.Component;

/**
 * DataSourceConfig DTO 转换器
 * 将 Controller 中的转换逻辑集中管理
 */
@Component
public class DataSourceConfigMapper {

    public DataSourceConfigResponse toResponse(DataSourceConfig config) {
        return DataSourceConfigResponse.builder()
                .id(config.getId())
                .name(config.getName())
                .dbHostname(config.getDbHostname())
                .dbPort(config.getDbPort())
                .dbName(config.getDbName())
                .dbUser(config.getDbUser())
                .schemaName(config.getSchemaName())
                .tableName(config.getTableName())
                .rocketmqTopic(config.getRocketmqTopic())
                .rocketmqTag(config.getRocketmqTag())
                .rocketmqNamesrvAddr(config.getRocketmqNamesrvAddr())
                .rocketmqProducerGroup(config.getRocketmqProducerGroup())
                .isActive(config.getIsActive())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    public DataSourceConfig toEntity(DataSourceConfigRequest request) {
        DataSourceConfig config = new DataSourceConfig();
        config.setName(request.getName());
        config.setDbHostname(request.getDbHostname());
        config.setDbPort(request.getDbPort());
        config.setDbName(request.getDbName());
        config.setDbUser(request.getDbUser());
        config.setDbPassword(request.getDbPassword());
        config.setSchemaName(request.getSchemaName());
        config.setTableName(request.getTableName());
        config.setRocketmqTopic(request.getRocketmqTopic());
        config.setRocketmqTag(request.getRocketmqTag());
        config.setRocketmqNamesrvAddr(request.getRocketmqNamesrvAddr());
        config.setRocketmqProducerGroup(request.getRocketmqProducerGroup());
        config.setIsActive(request.getIsActive() != null && request.getIsActive());
        return config;
    }
}
