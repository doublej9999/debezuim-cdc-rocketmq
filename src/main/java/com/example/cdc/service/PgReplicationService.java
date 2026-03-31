import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PgReplicationService {

    // 缓存各个数据源的连接池，避免重复创建连接产生的高昂代价
    private final Map<Long, HikariDataSource> dataSourceCache = new ConcurrentHashMap<>();

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
        DataSource ds = getDataSource(config);
        return ds.getConnection();
    }

    /**
     * 获取或创建指定配置的连接池
     */
    private DataSource getDataSource(DataSourceConfig config) {
        return dataSourceCache.computeIfAbsent(config.getId(), id -> {
            log.info("为配置 {} [{}] 创建 Hikari 连接池...", config.getId(), config.getName());
            
            HikariConfig hikariConfig = new HikariConfig();
            String url = String.format("jdbc:postgresql://%s:%d/%s", 
                    config.getDbHostname(), config.getDbPort(), config.getDbName());
            
            hikariConfig.setJdbcUrl(url);
            hikariConfig.setUsername(config.getDbUser());
            hikariConfig.setPassword(config.getDbPassword());
            hikariConfig.setDriverClassName("org.postgresql.Driver");
            
            // 针对管理操作（Slot 检查等），连接池不需要很大
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setMaximumPoolSize(3);
            hikariConfig.setIdleTimeout(300000); // 5 分钟闲置释放
            hikariConfig.setConnectionTimeout(30000);
            hikariConfig.setPoolName("PgMgmtPool-" + id);
            
            // 避免 WAL 检查被长时间查询阻塞
            hikariConfig.addDataSourceProperty("socketTimeout", "30");
            
            return new HikariDataSource(hikariConfig);
        });
    }

    /**
     * 当配置被删除时，清理对应的连接池
     */
    public void closeDataSource(Long configId) {
        HikariDataSource ds = dataSourceCache.remove(configId);
        if (ds != null) {
            log.info("正在关闭配置 {} 的连接池...", configId);
            ds.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("正在关闭所有管理连接池...");
        dataSourceCache.values().forEach(ds -> {
            try {
                ds.close();
            } catch (Exception e) {
                log.warn("关闭连接池时出错: {}", e.getMessage());
            }
        });
        dataSourceCache.clear();
    }

    @lombok.Data
    public static class ReplicationSlotInfo {
        private String slotName;
        private String slotType;
        private boolean active;
        private long retainedWalBytes;
    }
}
