package com.example.cdc.listener;

import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.service.MultiConfigCdcPipelineManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 数据源配置变更监听器
 * 监听配置的启用/停用事件，自动启动/停止对应的 CDC 管道
 */
@Slf4j
@Component
public class DataSourceConfigChangeListener {

    private MultiConfigCdcPipelineManager pipelineManager;

    @Autowired
    public void setPipelineManager(MultiConfigCdcPipelineManager pipelineManager) {
        this.pipelineManager = pipelineManager;
    }

    /**
     * 配置启用时调用
     */
    public void onConfigEnabled(DataSourceConfig config) {
        if (pipelineManager == null) {
            log.warn("管道管理器未初始化，跳过启用事件");
            return;
        }
        log.info("配置已启用: {} (ID: {})", config.getName(), config.getId());
        try {
            pipelineManager.startPipeline(config);
        } catch (Exception e) {
            log.error("启动 CDC 管道失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 配置停用时调用
     */
    public void onConfigDisabled(Long configId) {
        if (pipelineManager == null) {
            log.warn("管道管理器未初始化，跳过停用事件");
            return;
        }
        log.info("配置已停用: ID: {}", configId);
        try {
            pipelineManager.stopPipeline(configId);
        } catch (Exception e) {
            log.error("停止 CDC 管道失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 配置删除时调用
     */
    public void onConfigDeleted(Long configId) {
        if (pipelineManager == null) {
            log.warn("管道管理器未初始化，跳过删除事件");
            return;
        }
        log.info("配置已删除: ID: {}", configId);
        try {
            pipelineManager.stopPipeline(configId);
        } catch (Exception e) {
            log.error("停止 CDC 管道失败: {}", e.getMessage(), e);
        }
    }
}
