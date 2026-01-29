package com.example.cdc.config;

import com.example.cdc.service.MultiConfigCdcPipelineManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CDC 管道初始化器
 * 在应用启动时初始化所有活跃的 CDC 管道，在应用关闭时优雅关闭所有管道
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CdcPipelineInitializer {

    private final MultiConfigCdcPipelineManager pipelineManager;

    /**
     * 应用启动时初始化所有活跃的 CDC 管道
     */
    @PostConstruct
    public void initializePipelines() {
        log.info("========================================");
        log.info("应用启动，初始化 CDC 管道...");
        log.info("========================================");

        try {
            pipelineManager.initializeActivePipelines();
            log.info("========================================");
            log.info("CDC 管道初始化完成");
            log.info("========================================");
        } catch (Exception e) {
            log.error("初始化 CDC 管道失败: {}", e.getMessage(), e);
            throw new RuntimeException("初始化 CDC 管道失败", e);
        }
    }

    /**
     * 应用关闭时优雅关闭所有 CDC 管道
     */
    @PreDestroy
    public void shutdownPipelines() {
        log.info("========================================");
        log.info("应用关闭，关闭所有 CDC 管道...");
        log.info("========================================");

        try {
            pipelineManager.shutdownAll();
            log.info("========================================");
            log.info("所有 CDC 管道已关闭");
            log.info("========================================");
        } catch (Exception e) {
            log.error("关闭 CDC 管道失败: {}", e.getMessage(), e);
        }
    }
}
