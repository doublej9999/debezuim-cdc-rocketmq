package com.example.cdc.config;

import com.example.cdc.service.AsyncEventSenderService;
import com.example.cdc.service.MultiConfigCdcPipelineManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CDC 管道初始化器
 * 在应用启动时初始化异步发送服务和所有活跃的 CDC 管道
 * 在应用关闭时优雅关闭所有管道和异步发送服务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CdcPipelineInitializer {

    private final MultiConfigCdcPipelineManager pipelineManager;
    private final AsyncEventSenderService asyncEventSenderService;

    /**
     * 应用启动时初始化异步发送服务和所有活跃的 CDC 管道
     */
    @PostConstruct
    public void initializePipelines() {
        log.info("========================================");
        log.info("应用启动，初始化异步发送服务和 CDC 管道...");
        log.info("========================================");

        try {
            // 异步发送服务已在 @PostConstruct 中初始化
            // 这里只需初始化 CDC 管道
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
     * 应用关闭时优雅关闭所有 CDC 管道和异步发送服务
     */
    @PreDestroy
    public void shutdownPipelines() {
        log.info("========================================");
        log.info("应用关闭，关闭所有 CDC 管道和异步发送服务...");
        log.info("========================================");

        try {
            // 先关闭 CDC 管道，停止产生新事件
            pipelineManager.shutdownAll();
            log.info("所有 CDC 管道已关闭");

            // 异步发送服务会在其 @PreDestroy 中处理剩余事件并关闭
            log.info("========================================");
            log.info("所有服务已关闭");
            log.info("========================================");
        } catch (Exception e) {
            log.error("关闭服务失败: {}", e.getMessage(), e);
        }
    }
}

