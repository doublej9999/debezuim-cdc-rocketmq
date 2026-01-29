package com.example.cdc.service;

import com.example.cdc.config.DebeziumConfig;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debezium 引擎服务
 * 负责 Debezium Engine 的生命周期管理，使用 Java 21 虚拟线程运行
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DebeziumEngineService {

    private final DebeziumConfig debeziumConfig;
    private final ChangeEventHandler changeEventHandler;

    private DebeziumEngine<ChangeEvent<String, String>> engine;
    private ExecutorService virtualThreadExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private LocalDateTime startTime;

    /**
     * 启动 Debezium 引擎（使用虚拟线程）
     * 注意：此方法已禁用，改为使用 MultiConfigCdcPipelineManager 进行多配置管理
     */
    @PostConstruct
    public void start() {
        log.info("原有的单一 Debezium 引擎已禁用，使用 MultiConfigCdcPipelineManager 进行多配置管理");
        // 不再自动启动单一引擎
    }

    /**
     * 优雅关闭 Debezium 引擎
     */
    @PreDestroy
    public void stop() {
        log.info("准备关闭 Debezium 引擎...");

        try {
            if (engine != null) {
                // 关闭引擎（会等待当前事件处理完成）
                engine.close();
                log.info("Debezium 引擎已关闭");
            }

            if (virtualThreadExecutor != null) {
                // 关闭虚拟线程执行器
                virtualThreadExecutor.shutdown();
                if (!virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("虚拟线程执行器未能在 30 秒内关闭，强制关闭");
                    virtualThreadExecutor.shutdownNow();
                }
                log.info("虚拟线程执行器已关闭");
            }

            running.set(false);

        } catch (IOException e) {
            log.error("关闭 Debezium 引擎失败: {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            log.error("等待虚拟线程执行器关闭时被中断: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 检查引擎是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 获取引擎启动时间
     */
    public LocalDateTime getStartTime() {
        return startTime;
    }

    /**
     * 获取当前 LSN
     */
    public String getCurrentLsn() {
        return changeEventHandler.getCurrentLsn();
    }

    /**
     * 获取已处理的事件数量
     */
    public long getProcessedEventCount() {
        return changeEventHandler.getProcessedEventCount();
    }
}
