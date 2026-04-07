package com.example.cdc.controller;

import com.example.cdc.dto.DataSourceConfigMapper;
import com.example.cdc.dto.DataSourceConfigRequest;
import com.example.cdc.dto.DataSourceConfigResponse;
import com.example.cdc.exception.EntityNotFoundException;
import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.service.DataSourceConfigService;
import com.example.cdc.service.MultiConfigCdcPipelineManager;
import com.example.cdc.service.PasswordCryptoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
public class DataSourceConfigController {

    private final DataSourceConfigService configService;
    private final MultiConfigCdcPipelineManager pipelineManager;
    private final DataSourceConfigMapper configMapper;
    private final PasswordCryptoService passwordCryptoService;

    @GetMapping
    public ResponseEntity<List<DataSourceConfigResponse>> getAllConfigs() {
        return ResponseEntity.ok(configService.getAllConfigs().stream().map(configMapper::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataSourceConfigResponse> getConfigById(@PathVariable Long id) {
        return configService.getConfigById(id)
                .map(configMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new EntityNotFoundException("配置不存在: " + id));
    }

    @GetMapping("/active")
    public ResponseEntity<List<DataSourceConfigResponse>> getActiveConfigs() {
        return ResponseEntity.ok(configService.getActiveConfigs().stream().map(configMapper::toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<DataSourceConfigResponse> createConfig(@Valid @RequestBody DataSourceConfigRequest request) {
        request.setDbPassword(passwordCryptoService.decodeIfEncrypted(request.getDbPassword()));
        DataSourceConfig saved = configService.createConfig(configMapper.toEntity(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(configMapper.toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DataSourceConfigResponse> updateConfig(
            @PathVariable Long id,
            @Valid @RequestBody DataSourceConfigRequest request) {
        var existingOpt = configService.getConfigById(id);
        if (existingOpt.isEmpty()) {
            throw new EntityNotFoundException("配置不存在: " + id);
        }
        DataSourceConfig existing = existingOpt.get();
        boolean wasActive = Boolean.TRUE.equals(existing.getIsActive());

        request.setDbPassword(passwordCryptoService.decodeIfEncrypted(request.getDbPassword()));
        DataSourceConfig requestEntity = configMapper.toEntity(request);
        if (request.getIsActive() == null) {
            requestEntity.setIsActive(existing.getIsActive());
        }
        DataSourceConfig updated = configService.updateConfig(id, requestEntity);

        boolean isActive = Boolean.TRUE.equals(updated.getIsActive());
        if (wasActive != isActive) {
            if (isActive) {
                log.info("更新配置后启用 CDC 管道: {}", id);
                pipelineManager.startPipeline(updated);
            } else {
                log.info("更新配置后停用 CDC 管道: {}", id);
                pipelineManager.stopPipeline(id);
            }
        } else if (isActive && hasPipelineSensitiveChanges(existing, updated)) {
            log.info("配置 {} 已生效字段发生变化，重启 CDC 管道使新配置生效", id);
            pipelineManager.restartPipeline(id);
        }

        return ResponseEntity.ok(configMapper.toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        configService.getConfigById(id)
                .orElseThrow(() -> new EntityNotFoundException("配置不存在: " + id));

        try {
            pipelineManager.stopPipeline(id);
        } catch (Exception e) {
            log.warn("停止管道失败: {}", e.getMessage());
        }

        configService.deleteConfig(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<DataSourceConfigResponse> toggleActive(@PathVariable Long id) {
        DataSourceConfig config = configService.toggleActive(id);

        if (config.getIsActive()) {
            log.info("启用配置并启动 CDC 管道: {}", id);
            pipelineManager.startPipeline(config);
        } else {
            log.info("停用配置并停止 CDC 管道: {}", id);
            pipelineManager.stopPipeline(id);
        }

        return ResponseEntity.ok(configMapper.toResponse(config));
    }

    private boolean hasPipelineSensitiveChanges(DataSourceConfig oldConfig, DataSourceConfig newConfig) {
        return !Objects.equals(oldConfig.getDbHostname(), newConfig.getDbHostname())
                || !Objects.equals(oldConfig.getDbPort(), newConfig.getDbPort())
                || !Objects.equals(oldConfig.getDbName(), newConfig.getDbName())
                || !Objects.equals(oldConfig.getDbUser(), newConfig.getDbUser())
                || !Objects.equals(oldConfig.getDbPassword(), newConfig.getDbPassword())
                || !Objects.equals(oldConfig.getSchemaName(), newConfig.getSchemaName())
                || !Objects.equals(oldConfig.getTableName(), newConfig.getTableName())
                || !Objects.equals(oldConfig.getRocketmqTopic(), newConfig.getRocketmqTopic())
                || !Objects.equals(oldConfig.getRocketmqTag(), newConfig.getRocketmqTag())
                || !Objects.equals(oldConfig.getRocketmqNamesrvAddr(), newConfig.getRocketmqNamesrvAddr())
                || !Objects.equals(oldConfig.getRocketmqProducerGroup(), newConfig.getRocketmqProducerGroup());
    }
}
