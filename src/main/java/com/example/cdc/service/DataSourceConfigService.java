package com.example.cdc.service;

import com.example.cdc.dto.DataSourceConfigDTO;
import com.example.cdc.dto.DataSourceConfigUpsertRequest;
import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.repository.DataSourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceConfigService {

    private final DataSourceConfigRepository repository;

    public List<DataSourceConfig> getAllConfigs() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<DataSourceConfig> getConfigById(Long id) {
        return repository.findById(id);
    }

    public List<DataSourceConfig> getActiveConfigs() {
        return repository.findByIsActive(true);
    }

    @Transactional
    public DataSourceConfig createConfig(DataSourceConfigUpsertRequest request) {
        DataSourceConfig config = new DataSourceConfig();
        applyRequest(config, request);
        log.info("创建数据源配置: {}", config.getName());
        return repository.save(config);
    }

    @Transactional
    public DataSourceConfig updateConfig(Long id, DataSourceConfigUpsertRequest request) {
        return repository.findById(id)
                .map(existing -> {
                    applyRequest(existing, request);
                    log.info("更新数据源配置: {}", existing.getName());
                    return repository.save(existing);
                })
                .orElseThrow(() -> new RuntimeException("配置不存在: " + id));
    }

    @Transactional
    public void deleteConfig(Long id) {
        log.info("删除数据源配置: {}", id);
        repository.deleteById(id);
    }

    @Transactional
    public DataSourceConfig toggleActive(Long id) {
        return repository.findById(id)
                .map(config -> {
                    config.setIsActive(!config.getIsActive());
                    log.info("切换数据源配置状态: {} -> {}", config.getName(), config.getIsActive());
                    return repository.save(config);
                })
                .orElseThrow(() -> new RuntimeException("配置不存在: " + id));
    }

    public DataSourceConfigDTO toDTO(DataSourceConfig config) {
        String masked = null;
        if (config.getDbPassword() != null && !config.getDbPassword().isEmpty()) {
            masked = "******";
        }

        return DataSourceConfigDTO.builder()
                .id(config.getId())
                .name(config.getName())
                .dbHostname(config.getDbHostname())
                .dbPort(config.getDbPort())
                .dbName(config.getDbName())
                .dbUser(config.getDbUser())
                .dbPasswordMasked(masked)
                .schemaName(config.getSchemaName())
                .tableName(config.getTableName())
                .rocketmqTopic(config.getRocketmqTopic())
                .rocketmqTag(config.getRocketmqTag())
                .isActive(config.getIsActive())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    private void applyRequest(DataSourceConfig existing, DataSourceConfigUpsertRequest request) {
        existing.setName(request.getName());
        existing.setDbHostname(request.getDbHostname());
        existing.setDbPort(request.getDbPort());
        existing.setDbName(request.getDbName());
        existing.setDbUser(request.getDbUser());
        if (request.getDbPassword() != null && !request.getDbPassword().isBlank()) {
            existing.setDbPassword(request.getDbPassword());
        }
        existing.setSchemaName(request.getSchemaName());
        existing.setTableName(request.getTableName());
        existing.setRocketmqTopic(request.getRocketmqTopic());
        existing.setRocketmqTag(request.getRocketmqTag());
        existing.setIsActive(request.getIsActive() != null ? request.getIsActive() : Boolean.FALSE);
    }

}
