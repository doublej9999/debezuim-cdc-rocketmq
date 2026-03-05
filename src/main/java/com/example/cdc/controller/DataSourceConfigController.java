package com.example.cdc.controller;

import com.example.cdc.dto.DataSourceConfigRequest;
import com.example.cdc.dto.DataSourceConfigResponse;
import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.service.DataSourceConfigService;
import com.example.cdc.service.MultiConfigCdcPipelineManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.function.Function;

@Slf4j
@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DataSourceConfigController {

    private final DataSourceConfigService configService;
    private final MultiConfigCdcPipelineManager pipelineManager;

    private final Function<DataSourceConfig, DataSourceConfigResponse> toResponse = config -> DataSourceConfigResponse.builder()
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
            .isActive(config.getIsActive())
            .createdAt(config.getCreatedAt())
            .updatedAt(config.getUpdatedAt())
            .build();

    private DataSourceConfig toEntity(DataSourceConfigRequest request) {
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
        config.setIsActive(request.getIsActive() != null && request.getIsActive());
        return config;
    }

    @GetMapping
    public ResponseEntity<List<DataSourceConfigResponse>> getAllConfigs() {
        return ResponseEntity.ok(configService.getAllConfigs().stream().map(toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataSourceConfigResponse> getConfigById(@PathVariable Long id) {
        return configService.getConfigById(id)
                .map(toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/active")
    public ResponseEntity<List<DataSourceConfigResponse>> getActiveConfigs() {
        return ResponseEntity.ok(configService.getActiveConfigs().stream().map(toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<DataSourceConfigResponse> createConfig(@RequestBody DataSourceConfigRequest request) {
        DataSourceConfig saved = configService.createConfig(toEntity(request));
        return ResponseEntity.ok(toResponse.apply(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DataSourceConfigResponse> updateConfig(
            @PathVariable Long id,
            @RequestBody DataSourceConfigRequest request) {
        try {
            DataSourceConfig updated = configService.updateConfig(id, toEntity(request));
            return ResponseEntity.ok(toResponse.apply(updated));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        try {
            // 停止管道
            pipelineManager.stopPipeline(id);
        } catch (Exception e) {
            log.warn("停止管道失败: {}", e.getMessage());
        }

        configService.deleteConfig(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<DataSourceConfigResponse> toggleActive(@PathVariable Long id) {
        try {
            DataSourceConfig config = configService.toggleActive(id);

            // 根据新状态启动或停止管道
            if (config.getIsActive()) {
                log.info("启用配置，启动 CDC 管道: {}", id);
                pipelineManager.startPipeline(config);
            } else {
                log.info("停用配置，停止 CDC 管道: {}", id);
                pipelineManager.stopPipeline(id);
            }

            return ResponseEntity.ok(toResponse.apply(config));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
