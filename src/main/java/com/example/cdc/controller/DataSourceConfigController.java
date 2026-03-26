package com.example.cdc.controller;

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
    public ResponseEntity<List<DataSourceConfig>> getAllConfigs() {
        return ResponseEntity.ok(configService.getAllConfigs());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataSourceConfig> getConfigById(@PathVariable Long id) {
        return configService.getConfigById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/active")
    public ResponseEntity<List<DataSourceConfig>> getActiveConfigs() {
        return ResponseEntity.ok(configService.getActiveConfigs());
    }

    @PostMapping
    public ResponseEntity<DataSourceConfig> createConfig(@RequestBody DataSourceConfig config) {
        return ResponseEntity.ok(configService.createConfig(config));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DataSourceConfig> updateConfig(
            @PathVariable Long id,
            @RequestBody DataSourceConfig config) {
        try {
            return ResponseEntity.ok(configService.updateConfig(id, config));
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
    public ResponseEntity<DataSourceConfig> toggleActive(@PathVariable Long id) {
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

            return ResponseEntity.ok(config);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
