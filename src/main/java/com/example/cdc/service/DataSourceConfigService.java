package com.example.cdc.service;

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
    public DataSourceConfig createConfig(DataSourceConfig config) {
        log.info("创建数据源配置: {}", config.getName());
        return repository.save(config);
    }

    @Transactional
    public DataSourceConfig updateConfig(Long id, DataSourceConfig config) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setName(config.getName());
                    existing.setDbHostname(config.getDbHostname());
                    existing.setDbPort(config.getDbPort());
                    existing.setDbName(config.getDbName());
                    existing.setDbUser(config.getDbUser());
                    existing.setDbPassword(config.getDbPassword());
                    existing.setSchemaName(config.getSchemaName());
                    existing.setTableName(config.getTableName());
                    existing.setRocketmqTopic(config.getRocketmqTopic());
                    existing.setRocketmqTag(config.getRocketmqTag());
                    existing.setIsActive(config.getIsActive());
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
}
