package com.example.cdc.service;

import com.example.cdc.model.DataSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PgReplicationService {

    /**
     * 删除复制槽和发布
     */
    public void cleanupReplicationResources(DataSourceConfig config) {
        String slotName = "debezium_slot_" + config.getId();
        String publicationName = "debezium_publication_" + config.getId();
        
        dropReplicationSlot(config, slotName);
        dropPublication(config, publicationName);
    }

    /**
     * 删除指定的复制槽
     */
    public void dropReplicationSlot(DataSourceConfig config, String slotName) {
        try (Connection conn = getConnection(config);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT pg_drop_replication_slot('" + slotName + "')");
            log.info("成功删除复制槽: {}", slotName);
        } catch (Exception e) {
            log.warn("无法删除复制槽 {} (可能已不存在或正在使用): {}", slotName, e.getMessage());
        }
    }

    /**
     * 删除指定的发布
     */
    public void dropPublication(DataSourceConfig config, String publicationName) {
        try (Connection conn = getConnection(config);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP PUBLICATION IF EXISTS " + publicationName);
            log.info("成功删除发布: {}", publicationName);
        } catch (Exception e) {
            log.warn("无法删除发布 {}: {}", publicationName, e.getMessage());
        }
    }

    /**
     * 获取所有复制槽信息
     */
    public List<ReplicationSlotInfo> getReplicationSlots(DataSourceConfig config) {
        List<ReplicationSlotInfo> slots = new ArrayList<>();
        String sql = "SELECT slot_name, slot_type, active, restart_lsn, confirmed_flush_lsn, " +
                     "pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) as retained_wal_bytes " +
                     "FROM pg_replication_slots";
        
        try (Connection conn = getConnection(config);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                ReplicationSlotInfo info = new ReplicationSlotInfo();
                info.setSlotName(rs.getString("slot_name"));
                info.setSlotType(rs.getString("slot_type"));
                info.setActive(rs.getBoolean("active"));
                info.setRetainedWalBytes(rs.getLong("retained_wal_bytes"));
                slots.add(info);
            }
            
        } catch (Exception e) {
            log.error("获取复制槽信息失败: {}", e.getMessage());
        }
        return slots;
    }

    /**
     * 获取所有发布名称
     */
    public List<String> getPublications(DataSourceConfig config) {
        List<String> publications = new ArrayList<>();
        String sql = "SELECT pubname FROM pg_publication";
        
        try (Connection conn = getConnection(config);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                publications.add(rs.getString("pubname"));
            }
            
        } catch (Exception e) {
            log.error("获取发布信息失败: {}", e.getMessage());
        }
        return publications;
    }

    private Connection getConnection(DataSourceConfig config) throws Exception {
        String url = String.format("jdbc:postgresql://%s:%d/%s", 
                config.getDbHostname(), config.getDbPort(), config.getDbName());
        return DriverManager.getConnection(url, config.getDbUser(), config.getDbPassword());
    }

    @lombok.Data
    public static class ReplicationSlotInfo {
        private String slotName;
        private String slotType;
        private boolean active;
        private long retainedWalBytes;
    }
}
