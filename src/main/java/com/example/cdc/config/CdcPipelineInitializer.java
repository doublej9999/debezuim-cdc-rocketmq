package com.example.cdc.config;

import com.example.cdc.service.AsyncEventSenderService;
import com.example.cdc.service.MultiConfigCdcPipelineManager;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CdcPipelineInitializer {

    private final MultiConfigCdcPipelineManager pipelineManager;
    private final AsyncEventSenderService asyncEventSenderService;

    @EventListener(ApplicationReadyEvent.class)
    public void initializePipelines() {
        log.info("========================================");
        log.info("Application ready, starting async sender and CDC pipelines...");
        log.info("========================================");

        try {
            asyncEventSenderService.start();
            pipelineManager.initializeActivePipelines();
            log.info("========================================");
            log.info("CDC pipelines initialized");
            log.info("========================================");
        } catch (Exception e) {
            log.error("Failed to initialize CDC pipelines: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize CDC pipelines", e);
        }
    }

    @PreDestroy
    public void shutdownPipelines() {
        log.info("========================================");
        log.info("Application shutting down, stopping CDC pipelines...");
        log.info("========================================");

        try {
            pipelineManager.shutdownAll();
            log.info("All CDC pipelines stopped");
            log.info("========================================");
            log.info("Shutdown completed");
            log.info("========================================");
        } catch (Exception e) {
            log.error("Failed to shutdown services: {}", e.getMessage(), e);
        }
    }
}
