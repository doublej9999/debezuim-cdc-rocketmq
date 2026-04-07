package com.example.cdc.service;

import com.example.cdc.dto.SlotStatusDTO;
import com.example.cdc.dto.SlotStatusDTO.ConfigResourceStatus;
import com.example.cdc.dto.SlotStatusDTO.OrphanResource;
import com.example.cdc.model.DataSourceConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotLifecycleManager {

    private final DataSourceConfigService configService;
    private final PgReplicationService pgReplicationService;

    @Value("${cdc.wal.warning.threshold-bytes:1073741824}")
    private long walRetainedThresholdBytes;

    private static final int INACTIVE_ALERT_THRESHOLD_DAYS = 3;
    private static final int INACTIVE_CLEANUP_THRESHOLD_DAYS = 7;

    @PostConstruct
    public void onStartup() {
        scanOrphanResources();
    }

    public void scanOrphanResources() {
        log.info("开始巡检孤立复制资源...");
        List<DataSourceConfig> allConfigs = configService.getAllConfigs();

        Set<String> knownSlotNames = allConfigs.stream()
                .map(config -> "debezium_slot_" + config.getId())
                .collect(Collectors.toSet());
        Set<String> knownPubNames = allConfigs.stream()
                .map(config -> "debezium_publication_" + config.getId())
                .collect(Collectors.toSet());

        allConfigs.stream()
                .collect(Collectors.groupingBy(c -> c.getDbHostname() + ":" + c.getDbPort() + "/" + c.getDbName()))
                .forEach((dbUrl, configs) -> {
                    DataSourceConfig sampleConfig = configs.get(0);
                    try {
                        List<PgReplicationService.ReplicationSlotInfo> slots = pgReplicationService.getReplicationSlots(sampleConfig);
                        for (PgReplicationService.ReplicationSlotInfo slot : slots) {
                            if (slot.getSlotName().startsWith("debezium_slot_") && !knownSlotNames.contains(slot.getSlotName())) {
                                log.warn("发现孤立 slot: {} @ {}", slot.getSlotName(), dbUrl);
                            }
                        }

                        List<String> publications = pgReplicationService.getPublications(sampleConfig);
                        for (String publication : publications) {
                            if (publication.startsWith("debezium_publication_") && !knownPubNames.contains(publication)) {
                                log.warn("发现孤立 publication: {} @ {}", publication, dbUrl);
                            }
                        }
                    } catch (Exception e) {
                        log.error("巡检数据库 {} 失败: {}", dbUrl, e.getMessage());
                    }
                });
    }

    @Scheduled(fixedRate = 3600000)
    public void checkWalRetention() {
        List<DataSourceConfig> activeConfigs = configService.getActiveConfigs();
        for (DataSourceConfig config : activeConfigs) {
            try {
                List<PgReplicationService.ReplicationSlotInfo> slots = pgReplicationService.getReplicationSlots(config);
                String targetSlotName = "debezium_slot_" + config.getId();
                for (PgReplicationService.ReplicationSlotInfo slot : slots) {
                    if (!slot.getSlotName().equals(targetSlotName)) {
                        continue;
                    }
                    if (slot.getRetainedWalBytes() > walRetainedThresholdBytes) {
                        log.error("配置 {} WAL 留存过高: {} MB",
                                config.getName(), slot.getRetainedWalBytes() / 1024 / 1024);
                    } else if (slot.getRetainedWalBytes() > walRetainedThresholdBytes / 2) {
                        log.warn("配置 {} WAL 留存预警: {} MB",
                                config.getName(), slot.getRetainedWalBytes() / 1024 / 1024);
                    }
                }
            } catch (Exception e) {
                log.error("检查配置 {} 的 WAL 留存失败: {}", config.getName(), e.getMessage());
            }
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
    public void governanceInactiveConfigs() {
        List<DataSourceConfig> allConfigs = configService.getAllConfigs();
        LocalDateTime now = LocalDateTime.now();

        for (DataSourceConfig config : allConfigs) {
            if (Boolean.TRUE.equals(config.getIsActive()) || config.getDeactivatedAt() == null) {
                continue;
            }

            long daysInactive = ChronoUnit.DAYS.between(config.getDeactivatedAt(), now);
            if (daysInactive >= INACTIVE_CLEANUP_THRESHOLD_DAYS) {
                log.info("配置 {} 停用超过 {} 天，执行资源清理", config.getName(), INACTIVE_CLEANUP_THRESHOLD_DAYS);
                pgReplicationService.cleanupReplicationResources(config);
            } else if (daysInactive >= INACTIVE_ALERT_THRESHOLD_DAYS) {
                log.warn("配置 {} 已停用 {} 天，请关注底层复制资源是否需要清理", config.getName(), daysInactive);
            }
        }
    }

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

        allConfigs.stream()
                .collect(Collectors.groupingBy(c -> c.getDbHostname() + ":" + c.getDbPort() + "/" + c.getDbName()))
                .forEach((dbUrl, configs) -> {
                    DataSourceConfig sampleConfig = configs.get(0);
                    try {
                        List<PgReplicationService.ReplicationSlotInfo> slots = pgReplicationService.getReplicationSlots(sampleConfig);
                        for (PgReplicationService.ReplicationSlotInfo slot : slots) {
                            if (slot.getSlotName().startsWith("debezium_slot_") && !knownSlotNames.contains(slot.getSlotName())) {
                                orphanSlots.add(buildOrphanResource(sampleConfig, dbUrl, slot.getSlotName()));
                            }
                        }

                        List<String> publications = pgReplicationService.getPublications(sampleConfig);
                        for (String publication : publications) {
                            if (publication.startsWith("debezium_publication_") && !knownPubNames.contains(publication)) {
                                orphanPublications.add(buildOrphanResource(sampleConfig, dbUrl, publication));
                            }
                        }
                    } catch (Exception e) {
                        log.error("读取数据库 {} 复制资源失败: {}", dbUrl, e.getMessage());
                    }
                });

        LocalDateTime now = LocalDateTime.now();
        for (DataSourceConfig config : allConfigs) {
            ConfigResourceStatus.ConfigResourceStatusBuilder builder = ConfigResourceStatus.builder()
                    .configId(config.getId())
                    .configName(config.getName())
                    .isActive(Boolean.TRUE.equals(config.getIsActive()))
                    .slotName("debezium_slot_" + config.getId())
                    .retainedWalBytes(0L);

            String alertLevel = "NORMAL";
            long daysInactive = 0;

            if (!Boolean.TRUE.equals(config.getIsActive()) && config.getDeactivatedAt() != null) {
                daysInactive = ChronoUnit.DAYS.between(config.getDeactivatedAt(), now);
                if (daysInactive >= INACTIVE_CLEANUP_THRESHOLD_DAYS) {
                    alertLevel = "ERROR";
                } else if (daysInactive >= INACTIVE_ALERT_THRESHOLD_DAYS) {
                    alertLevel = "WARN";
                }
            }

            try {
                List<PgReplicationService.ReplicationSlotInfo> slots = pgReplicationService.getReplicationSlots(config);
                for (PgReplicationService.ReplicationSlotInfo slot : slots) {
                    if (!slot.getSlotName().equals("debezium_slot_" + config.getId())) {
                        continue;
                    }
                    builder.retainedWalBytes(slot.getRetainedWalBytes());
                    if (slot.getRetainedWalBytes() >= walRetainedThresholdBytes) {
                        alertLevel = "ERROR";
                    } else if (slot.getRetainedWalBytes() >= walRetainedThresholdBytes / 2) {
                        alertLevel = "WARN";
                    }
                    break;
                }
            } catch (Exception e) {
                log.warn("查询配置 {} 的 WAL 留存失败: {}", config.getName(), e.getMessage());
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

    private OrphanResource buildOrphanResource(DataSourceConfig config, String dbUrl, String name) {
        return OrphanResource.builder()
                .name(name)
                .dbUrl(dbUrl)
                .hostname(config.getDbHostname())
                .port(config.getDbPort())
                .dbName(config.getDbName())
                .dbUser(config.getDbUser())
                .dbPassword(config.getDbPassword())
                .build();
    }
}

