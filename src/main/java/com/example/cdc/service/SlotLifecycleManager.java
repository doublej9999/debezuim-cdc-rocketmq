package com.example.cdc.service;

import com.example.cdc.dto.SlotStatusDTO;
import com.example.cdc.dto.SlotStatusDTO.ConfigResourceStatus;
import com.example.cdc.dto.SlotStatusDTO.OrphanResource;
import com.example.cdc.model.DataSourceConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 复制槽生命周期治理
 * 
 * 功能点：
 * 1. 启动时巡检孤立复制槽 (Orphan Slots) 和 发布 (Orphan Publications)
 * 2. 周期性（每小时）检查 WAL 堆积情况并告警
 * 3. 周期性（每天）清理超过停用阈值的配置资源，或发出强告警
 * 4. (由 DataSourceConfigService 调用) 配置删除时清理对应资源
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotLifecycleManager {

    private final DataSourceConfigService configService;
    private final PgReplicationService pgReplicationService;
    
    // WAL 堆积告警阈值（从配置读取，默认 1GB）
    @org.springframework.beans.factory.annotation.Value("${cdc.wal.warning.threshold-bytes:1073741824}")
    private long walRetainedThresholdBytes;

    private static final int INACTIVE_ALERT_THRESHOLD_DAYS = 3;
    private static final int INACTIVE_CLEANUP_THRESHOLD_DAYS = 7;

    /**
     * 启动时自动执行巡检
     */
    @PostConstruct
    public void onStartup() {
        scanOrphanResources();
    }

    /**
     * 巡检孤立资源 (Orphan Resources)
     */
    public void scanOrphanResources() {
        log.info("【生命周期治理】开始巡检孤立复制资源...");
        List<DataSourceConfig> allConfigs = configService.getAllConfigs();
        
        // 预定义已知的资源名
        Set<String> knownSlotNames = allConfigs.stream()
                .map(config -> "debezium_slot_" + config.getId())
                .collect(Collectors.toSet());
        Set<String> knownPubNames = allConfigs.stream()
                .map(config -> "debezium_publication_" + config.getId())
                .collect(Collectors.toSet());

        // 针对每个不同的数据库服务器进行巡检
        allConfigs.stream()
                .collect(Collectors.groupingBy(c -> c.getDbHostname() + ":" + c.getDbPort() + "/" + c.getDbName()))
                .forEach((dbUrl, configs) -> {
                    DataSourceConfig sampleConfig = configs.get(0);
                    try {
                        log.info("检查数据库 {} 的资源...", dbUrl);
                        
                        // 1. 巡检复制槽
                        List<PgReplicationService.ReplicationSlotInfo> slots = pgReplicationService.getReplicationSlots(sampleConfig);
                        for (PgReplicationService.ReplicationSlotInfo slot : slots) {
                            if (slot.getSlotName().startsWith("debezium_slot_") && !knownSlotNames.contains(slot.getSlotName())) {
                                log.error("【强告警】发现孤立复制槽: {} 在数据库 {}。该槽不属于任何现有配置，请人工清理或确认！", 
                                        slot.getSlotName(), dbUrl);
                                // 此处可增加逻辑，如：pgReplicationService.dropReplicationSlot(sampleConfig, slot.getSlotName());
                            }
                        }

                        // 2. 巡检发布
                        List<String> publications = pgReplicationService.getPublications(sampleConfig);
                        for (String pubName : publications) {
                            if (pubName.startsWith("debezium_publication_") && !knownPubNames.contains(pubName)) {
                                log.warn("发现孤立发布: {} 在数据库 {}。该发布不属于任何现有配置。", pubName, dbUrl);
                                // 此处可增加逻辑，如：pgReplicationService.dropPublication(sampleConfig, pubName);
                            }
                        }
                    } catch (Exception e) {
                        log.error("检查数据库 {} 的资源时发生错误: {}", dbUrl, e.getMessage());
                    }
                });
    }

    /**
     * 周期性检查 WAL 堆积情况
     * 每小时执行一次
     */
    @Scheduled(fixedRate = 3600000)
    public void checkWalRetention() {
        log.info("【生命周期治理】开始周期性检查 WAL 堆积情况...");
        List<DataSourceConfig> activeConfigs = configService.getActiveConfigs();
        
        activeConfigs.forEach(config -> {
            try {
                List<PgReplicationService.ReplicationSlotInfo> slots = pgReplicationService.getReplicationSlots(config);
                String targetSlotName = "debezium_slot_" + config.getId();
                
                for (PgReplicationService.ReplicationSlotInfo slot : slots) {
                    if (slot.getSlotName().equals(targetSlotName)) {
                        if (slot.getRetainedWalBytes() > walRetainedThresholdBytes) {
                            log.error("【强告警】配置 [{}] (ID: {}) 的复制槽 {} 堆积 WAL 过多: {} MB。请检查订阅端消费情况！", 
                                    config.getName(), config.getId(), targetSlotName, slot.getRetainedWalBytes() / 1024 / 1024);
                        } else if (slot.getRetainedWalBytes() > walRetainedThresholdBytes / 2) {
                            log.warn("【预警】配置 [{}] (ID: {}) 的复制槽 {} WAL 堆积超过 50%: {} MB",
                                    config.getName(), config.getId(), targetSlotName, slot.getRetainedWalBytes() / 1024 / 1024);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("检查配置 {} 的 WAL 堆积时错误: {}", config.getName(), e.getMessage());
            }
        });
    }

    /**
     * 周期性治理停用的配置资源
     * 每天凌晨执行一次
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void governanceInactiveConfigs() {
        log.info("【生命周期治理】开始检查超期停用的配置资源...");
        List<DataSourceConfig> allConfigs = configService.getAllConfigs();
        LocalDateTime now = LocalDateTime.now();

        for (DataSourceConfig config : allConfigs) {
            if (Boolean.TRUE.equals(config.getIsActive())) continue;
            if (config.getDeactivatedAt() == null) continue;

            long daysDiff = ChronoUnit.DAYS.between(config.getDeactivatedAt(), now);

            if (daysDiff >= INACTIVE_CLEANUP_THRESHOLD_DAYS) {
                log.info("【生命周期治理】配置 [{}] 已停用超过 {} 天，自动清理底层复制资源...", 
                        config.getName(), INACTIVE_CLEANUP_THRESHOLD_DAYS);
                pgReplicationService.cleanupReplicationResources(config);
            } else if (daysDiff >= INACTIVE_ALERT_THRESHOLD_DAYS) {
                log.error("【强告警】配置 [{}] 已停用超过 {} 天，但复制资源（复制槽、发布）仍然存在。这可能导致主库 WAL 堆积！请尽快启用或删除配置。", 
                        config.getName(), daysDiff);
            }
        }
    }

    /**
     * 获取全局 Slot 治理状态，供 UI 展示
     */
    public SlotStatusDTO getGlobalStatus() {
        List<DataSourceConfig> allConfigs = configService.getAllConfigs();
        List<OrphanResource> orphanSlots = new ArrayList<>();
        List<OrphanResource> orphanPublications = new ArrayList<>();
        List<ConfigResourceStatus> configResources = new ArrayList<>();

        Set<String> knownSlotNames = allConfigs.stream()
                .map(config -> "debezium_slot_" + config.getId())
                .collect(Collectors.toSet());
        Set<String> knownPubNames = allConfigs.stream()
                .map(config -> "debezium_publication_" + config.getId())
                .collect(Collectors.toSet());

        // 针对每个不同的数据库服务器进行巡检
        allConfigs.stream()
                .collect(Collectors.groupingBy(c -> c.getDbHostname() + ":" + c.getDbPort() + "/" + c.getDbName()))
                .forEach((dbUrl, configs) -> {
                    DataSourceConfig sampleConfig = configs.get(0);
                    try {
                        // 1. 巡检复制槽
                        List<PgReplicationService.ReplicationSlotInfo> slots = pgReplicationService.getReplicationSlots(sampleConfig);
                        for (PgReplicationService.ReplicationSlotInfo slot : slots) {
                            if (slot.getSlotName().startsWith("debezium_slot_")) {
                                if (!knownSlotNames.contains(slot.getSlotName())) {
                                    orphanSlots.add(OrphanResource.builder()
                                            .name(slot.getSlotName())
                                            .dbUrl(dbUrl)
                                            .hostname(sampleConfig.getDbHostname())
                                            .port(sampleConfig.getDbPort())
                                            .dbName(sampleConfig.getDbName())
                                            .dbUser(sampleConfig.getDbUser())
                                            .dbPassword(sampleConfig.getDbPassword())
                                            .build());
                                }
                            }
                        }

                        // 2. 巡检发布
                        List<String> publications = pgReplicationService.getPublications(sampleConfig);
                        for (String pubName : publications) {
                            if (pubName.startsWith("debezium_publication_") && !knownPubNames.contains(pubName)) {
                                orphanPublications.add(OrphanResource.builder()
                                        .name(pubName)
                                        .dbUrl(dbUrl)
                                        .hostname(sampleConfig.getDbHostname())
                                        .port(sampleConfig.getDbPort())
                                        .dbName(sampleConfig.getDbName())
                                        .dbUser(sampleConfig.getDbUser())
                                        .dbPassword(sampleConfig.getDbPassword())
                                        .build());
                            }
                        }
                    } catch (Exception e) {
                        log.error("检查数据库 {} 的资源时发生错误: {}", dbUrl, e.getMessage());
                    }
                });

        // 整理每个配置的状态
        LocalDateTime now = LocalDateTime.now();
        for (DataSourceConfig config : allConfigs) {
            ConfigResourceStatus.ConfigResourceStatusBuilder builder = ConfigResourceStatus.builder()
                    .configId(config.getId())
                    .configName(config.getName())
                    .isActive(Boolean.TRUE.equals(config.getIsActive()))
                    .slotName("debezium_slot_" + config.getId());

            String alertLevel = "NORMAL";
            long daysInactive = 0;

            if (!Boolean.TRUE.equals(config.getIsActive()) && config.getDeactivatedAt() != null) {
                daysInactive = ChronoUnit.DAYS.between(config.getDeactivatedAt(), now);
                if (daysInactive >= INACTIVE_CLEANUP_THRESHOLD_DAYS) alertLevel = "ERROR";
                else if (daysInactive >= INACTIVE_ALERT_THRESHOLD_DAYS) alertLevel = "WARN";
            }

            // 获取 WAL retained 大小（如果活跃）
            if (Boolean.TRUE.equals(config.getIsActive())) {
                try {
                    List<PgReplicationService.ReplicationSlotInfo> slots = pgReplicationService.getReplicationSlots(config);
                    for (PgReplicationService.ReplicationSlotInfo slot : slots) {
                        if (slot.getSlotName().equals("debezium_slot_" + config.getId())) {
                            builder.retainedWalBytes(slot.getRetainedWalBytes());
                            if (slot.getRetainedWalBytes() >= walRetainedThresholdBytes) alertLevel = "ERROR";
                            else if (slot.getRetainedWalBytes() >= walRetainedThresholdBytes / 2) alertLevel = "WARN";
                        }
                    }
                } catch (Exception e) {
                   log.warn("无法查询配置 {} 的复制槽状态", config.getName());
                }
            }

            builder.daysInactive(daysInactive);
            builder.alertLevel(alertLevel);
            configResources.add(builder.build());
        }

        return SlotStatusDTO.builder()
                .orphanSlots(orphanSlots)
                .orphanPublications(orphanPublications)
                .configResources(configResources)
                .build();
    }
}
