package com.example.cdc.controller;

import com.example.cdc.dto.DataSourceConfigDTO;
import com.example.cdc.dto.DataSourceConfigUpsertRequest;
import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.service.DataSourceConfigService;
import com.example.cdc.service.MultiConfigCdcPipelineManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
public class DataSourceConfigController {

    private final DataSourceConfigService configService;
    private final MultiConfigCdcPipelineManager pipelineManager;

    @GetMapping
    public ResponseEntity<List<DataSourceConfigDTO>> getAllConfigs() {
        return ResponseEntity.ok(configService.getAllConfigs().stream().map(configService::toDTO).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataSourceConfigDTO> getConfigById(@PathVariable Long id) {
        return configService.getConfigById(id)
                .map(configService::toDTO)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/active")
    public ResponseEntity<List<DataSourceConfigDTO>> getActiveConfigs() {
        return ResponseEntity.ok(configService.getActiveConfigs().stream().map(configService::toDTO).toList());
    }

    @PostMapping
    public ResponseEntity<DataSourceConfigDTO> createConfig(@RequestBody DataSourceConfigUpsertRequest config) {
        return ResponseEntity.ok(configService.toDTO(configService.createConfig(config)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DataSourceConfigDTO> updateConfig(
            @PathVariable Long id,
            @RequestBody DataSourceConfigUpsertRequest config) {
        try {
            return ResponseEntity.ok(configService.toDTO(configService.updateConfig(id, config)));
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
    public ResponseEntity<DataSourceConfigDTO> toggleActive(@PathVariable Long id) {
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

            return ResponseEntity.ok(configService.toDTO(config));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
