package com.example.cdc.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * WAL 优化配置
 * 在应用启动后将 event_log 表转换为 UNLOGGED TABLE，
 * 消除 event_log 写入产生的 WAL，大幅减少 PostgreSQL WAL 膨胀。
 *
 * UNLOGGED TABLE 特性：
 * - 写入不产生 WAL 日志，显著减少磁盘 I/O
 * - 写入性能提升约 2-3 倍
 * - 代价：PostgreSQL 崩溃后表数据会被清空
 * - 对 event_log 场景可接受：丢失的事件会通过重试机制重新发送
 */
@Slf4j
@Component
public class WalOptimizationConfig {

    private final JdbcTemplate jdbcTemplate;

    @Value("${wal.optimization.unlogged-event-log:true}")
    private boolean unloggedEventLog;

    @Value("${wal.optimization.cleanup-inactive-slots:true}")
    private boolean cleanupInactiveSlots;

    public WalOptimizationConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 应用完全启动后执行 WAL 优化
     * 使用 ApplicationReadyEvent 确保 Hibernate ddl-auto 已完成表创建
     */
    @EventListener(ApplicationReadyEvent.class)
    public void optimizeWal() {
        log.info("========================================");
        log.info("开始执行 WAL 优化...");
        log.info("========================================");

        if (unloggedEventLog) {
            convertEventLogToUnlogged();
        }

        if (cleanupInactiveSlots) {
            cleanupInactiveReplicationSlots();
        }

        logWalStatus();

        log.info("========================================");
        log.info("WAL 优化完成");
        log.info("========================================");
    }

    /**
     * 将 event_log 表转换为 UNLOGGED TABLE
     * pg_class.relpersistence: 'p' = permanent (LOGGED), 'u' = unlogged, 't' = temporary
     */
    private void convertEventLogToUnlogged() {
        try {
            Boolean isLogged = jdbcTemplate.queryForObject(
                "SELECT relpersistence = 'p' FROM pg_class WHERE relname = 'event_log'",
                Boolean.class
            );

            if (Boolean.TRUE.equals(isLogged)) {
                log.info("将 event_log 表转换为 UNLOGGED TABLE 以减少 WAL 写入...");
                jdbcTemplate.execute("ALTER TABLE event_log SET UNLOGGED");
                log.info("✅ event_log 表已成功转换为 UNLOGGED TABLE");
            } else if (Boolean.FALSE.equals(isLogged)) {
                log.info("✅ event_log 表已经是 UNLOGGED TABLE，无需转换");
            } else {
                log.warn("event_log 表不存在，跳过 UNLOGGED 转换（Hibernate 可能尚未创建该表）");
            }
        } catch (Exception e) {
            log.warn("转换 event_log 为 UNLOGGED 失败: {}（非致命错误，表写入仍正常但会产生 WAL）",
                    e.getMessage());
        }
    }

    /**
     * 清理不活跃的 Debezium Replication Slot
     * 不活跃的 slot 会阻止 PostgreSQL 回收 WAL 文件
     */
    private void cleanupInactiveReplicationSlots() {
        try {
            var inactiveSlots = jdbcTemplate.queryForList(
                "SELECT slot_name, " +
                "       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS wal_lag " +
                "FROM pg_replication_slots " +
                "WHERE slot_name LIKE 'debezium_slot_%' AND NOT active"
            );

            if (inactiveSlots.isEmpty()) {
                log.info("✅ 没有发现不活跃的 Debezium Replication Slot");
                return;
            }

            for (var slot : inactiveSlots) {
                String slotName = (String) slot.get("slot_name");
                String walLag = (String) slot.get("wal_lag");
                log.warn("发现不活跃的 Replication Slot: {} (WAL 延迟: {})", slotName, walLag);

                try {
                    jdbcTemplate.execute("SELECT pg_drop_replication_slot('" + slotName + "')");
                    log.info("✅ 已删除不活跃的 Replication Slot: {}", slotName);
                } catch (Exception e) {
                    log.warn("删除 Replication Slot {} 失败: {}", slotName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("检查 Replication Slot 失败: {}（可能无复制权限）", e.getMessage());
        }
    }

    /**
     * 记录当前 WAL 状态信息，用于诊断
     */
    private void logWalStatus() {
        try {
            // 当前 WAL 位置
            String currentLsn = jdbcTemplate.queryForObject(
                "SELECT pg_current_wal_lsn()::text", String.class);
            log.info("当前 WAL 位置: {}", currentLsn);

            // WAL 目录大小（仅 PostgreSQL superuser 可执行）
            try {
                String walSize = jdbcTemplate.queryForObject(
                    "SELECT pg_size_pretty(sum(size)) FROM pg_ls_waldir()", String.class);
                log.info("当前 WAL 目录大小: {}", walSize);
            } catch (Exception e) {
                log.debug("无法获取 WAL 目录大小（需要 superuser 权限）");
            }

            // 活跃的 replication slots
            var activeSlots = jdbcTemplate.queryForList(
                "SELECT slot_name, " +
                "       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS wal_lag " +
                "FROM pg_replication_slots " +
                "WHERE slot_name LIKE 'debezium_slot_%' AND active"
            );

            if (!activeSlots.isEmpty()) {
                for (var slot : activeSlots) {
                    log.info("活跃 Slot: {} (WAL 延迟: {})",
                            slot.get("slot_name"), slot.get("wal_lag"));
                }
            }
        } catch (Exception e) {
            log.debug("获取 WAL 状态信息失败: {}", e.getMessage());
        }
    }
}
