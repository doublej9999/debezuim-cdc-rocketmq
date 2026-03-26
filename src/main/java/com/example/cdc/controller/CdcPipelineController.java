package com.example.cdc.controller;

import com.example.cdc.service.AsyncEventSenderService;
import com.example.cdc.service.DataSourceConfigService;
import com.example.cdc.service.MultiConfigCdcPipelineManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CDC 管道管理 API
 * 提供多配置 CDC 管道的管理和监控接口
 */
@RestController
@RequestMapping("/api/pipeline")
@RequiredArgsConstructor
public class CdcPipelineController {

    private final MultiConfigCdcPipelineManager pipelineManager;
    private final DataSourceConfigService configService;
    private final AsyncEventSenderService asyncEventSenderService;

    /**
     * 获取所有 CDC 管道的状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<Long, MultiConfigCdcPipelineManager.PipelineStatus>> getAllPipelineStatus() {
        return ResponseEntity.ok(pipelineManager.getAllPipelineStatus());
    }

    /**
     * 获取指定配置的 CDC 管道状态
     */
    @GetMapping("/status/{configId}")
    public ResponseEntity<MultiConfigCdcPipelineManager.PipelineStatus> getPipelineStatus(@PathVariable Long configId) {
        MultiConfigCdcPipelineManager.PipelineStatus status = pipelineManager.getPipelineStatus(configId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * 获取活跃管道数量
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Integer>> getActivePipelineCount() {
        return ResponseEntity.ok(Map.of("activePipelineCount", pipelineManager.getActivePipelineCount()));
    }

    /**
     * 启动指定配置的 CDC 管道
     */
    @PostMapping("/{configId}/start")
    public ResponseEntity<String> startPipeline(@PathVariable Long configId) {
        try {
            var config = configService.getConfigById(configId);
            if (config.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            pipelineManager.startPipeline(config.get());
            return ResponseEntity.ok("CDC 管道已启动");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("启动 CDC 管道失败: " + e.getMessage());
        }
    }

    /**
     * 停止指定配置的 CDC 管道
     */
    @PostMapping("/{configId}/stop")
    public ResponseEntity<String> stopPipeline(@PathVariable Long configId) {
        try {
            pipelineManager.stopPipeline(configId);
            return ResponseEntity.ok("CDC 管道已停止");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("停止 CDC 管道失败: " + e.getMessage());
        }
    }

    /**
     * 重启指定配置的 CDC 管道
     */
    @PostMapping("/{configId}/restart")
    public ResponseEntity<String> restartPipeline(@PathVariable Long configId) {
        try {
            pipelineManager.restartPipeline(configId);
            return ResponseEntity.ok("CDC 管道已重启");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("重启 CDC 管道失败: " + e.getMessage());
        }
    }

    /**
     * 获取异步事件发送服务的统计信息
     */
    @GetMapping("/async-stats")
    public ResponseEntity<AsyncEventSenderService.Statistics> getAsyncStats() {
        return ResponseEntity.ok(asyncEventSenderService.getStatistics());
    }
}

