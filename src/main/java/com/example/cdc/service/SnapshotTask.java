package com.example.cdc.service;

import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.model.SnapshotJob;
import com.example.cdc.repository.SnapshotJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全量快照定时任务服务
 *
 * <p>职责：定时（或手动触发）将被监控表的全量数据以统一格式推送到 {topic}_ALL Topic。
 * 下游系统可通过订阅 _ALL Topic 获取历史全量数据，结合增量 Topic 实现完整数据消费。
 *
 * <h3>消息协议</h3>
 * <ul>
 *   <li>每次快照由三类消息构成：BEGIN → DATA(N条) → END</li>
 *   <li>所有消息的 Tag = 表名，Key = 主键值（BEGIN/END 为 batchId）</li>
 *   <li>BEGIN/END 消息体为元数据 JSON，DATA 消息体与增量 CDC 格式对齐（op="r"）</li>
 * </ul>
 *
 * <h3>消息 Header</h3>
 * <pre>
 *   X-Snapshot-Type:     FULL
 *   X-Snapshot-Batch-Id: {configId}-{timestamp}
 *   X-Snapshot-Lsn:      {pg_current_wal_lsn 值}
 *   X-Snapshot-Status:   BEGIN | DATA | END
 * </pre>
 *
 * <h3>下游消费对齐逻辑</h3>
 * <ol>
 *   <li>订阅 _ALL，消费至 END 消息，记录其中的 snapshotLsn</li>
 *   <li>订阅增量 Topic，跳过 lsn &lt;= snapshotLsn 的消息</li>
 *   <li>之后正常消费增量</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotTask {

    private final DataSourceConfigService configService;
    private final SnapshotJobRepository snapshotJobRepository;
    private final RocketMQProducerService rocketMQProducerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Topic 后缀 */
    @Value("${snapshot.topic-suffix:_ALL}")
    private String topicSuffix;

    /** 每批推送行数 */
    @Value("${snapshot.batch.size:500}")
    private int batchSize;

    /** 批次间暂停毫秒数，避免打垮 Broker */
    @Value("${snapshot.batch.pause-ms:50}")
    private long batchPauseMs;

    /** 是否启用定时任务 */
    @Value("${snapshot.schedule.enabled:true}")
    private boolean scheduleEnabled;

    /** 运行中的快照任务集合（configId → running 标志），防止同一配置并发执行 */
    private final ConcurrentHashMap<Long, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────────

    /**
     * 启动时将遗留的 RUNNING 状态快照标记为 FAILED（防止上次异常退出留下僵死记录）
     */
    @PostConstruct
    @Transactional
    public void recoverStaleJobs() {
        List<SnapshotJob> staleJobs = snapshotJobRepository.findByStatus(SnapshotJob.JobStatus.RUNNING);
        if (!staleJobs.isEmpty()) {
            log.warn("发现 {} 个僵死快照任务，标记为 FAILED", staleJobs.size());
            for (SnapshotJob job : staleJobs) {
                snapshotJobRepository.markAsFailed(job.getId(),
                        "应用重启，任务被强制终止", LocalDateTime.now());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 定时触发入口
    // ─────────────────────────────────────────────────────────────────

    /**
     * 定时触发所有活跃配置的全量快照
     * 默认每天凌晨 3 点执行，可通过 snapshot.schedule.cron 覆盖
     */
    @Scheduled(cron = "${snapshot.schedule.cron:0 0 3 * * ?}")
    public void scheduledSnapshotAll() {
        if (!scheduleEnabled) {
            log.debug("全量快照定时任务已禁用，跳过本次执行");
            return;
        }

        log.info("触发全量快照定时任务...");
        List<DataSourceConfig> activeConfigs = configService.getActiveConfigs();
        log.info("共 {} 个活跃数据源配置需要执行快照", activeConfigs.size());

        for (DataSourceConfig config : activeConfigs) {
            try {
                triggerSnapshot(config.getId());
            } catch (Exception e) {
                log.error("配置 {} 快照任务失败: {}", config.getId(), e.getMessage(), e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 核心快照逻辑（手动触发/定时复用）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 触发指定配置的全量快照任务（异步执行，不阻塞调用方）
     *
     * @param configId 数据源配置 ID
     * @return 创建的快照任务 batchId
     */
    public String triggerSnapshot(Long configId) {
        DataSourceConfig config = configService.getConfigById(configId)
                .orElseThrow(() -> new IllegalArgumentException("数据源配置不存在: " + configId));

        // 防止同一配置并发执行
        AtomicBoolean running = runningFlags.computeIfAbsent(configId, id -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("配置 " + configId + " 的快照任务正在运行中，请稍后再试");
        }

        // 生成批次 ID
        String batchId = configId + "-" + System.currentTimeMillis();
        String allTopic = config.getRocketmqTopic() + topicSuffix;

        // 持久化创建任务记录
        SnapshotJob job = SnapshotJob.builder()
                .configId(configId)
                .batchId(batchId)
                .topic(allTopic)
                .status(SnapshotJob.JobStatus.PENDING)
                .processedRows(0L)
                .build();
        SnapshotJob savedJob = snapshotJobRepository.save(job);

        // 使用虚拟线程异步执行，不阻塞 HTTP 响应
        Thread.ofVirtual().name("snapshot-" + batchId).start(() -> {
            try {
                executeSnapshot(savedJob, config);
            } finally {
                running.set(false);
            }
        });

        log.info("快照任务已提交 - ConfigId: {}, BatchId: {}, Topic: {}", configId, batchId, allTopic);
        return batchId;
    }

    /**
     * 快照核心执行逻辑（在虚拟线程中运行）
     */
    private void executeSnapshot(SnapshotJob job, DataSourceConfig config) {
        Long jobId = job.getId();
        String batchId = job.getBatchId();
        String allTopic = job.getTopic();
        String tag = config.getRocketmqTag() != null && !config.getRocketmqTag().isBlank()
                ? config.getRocketmqTag() : config.getTableName();
        String namesrvAddr = config.getRocketmqNamesrvAddr();
        String producerGroup = config.getRocketmqProducerGroup();

        log.info("开始执行全量快照 - ConfigId: {}, BatchId: {}, Table: {}.{}",
                config.getId(), batchId, config.getSchemaName(), config.getTableName());

        // 更新任务状态为 RUNNING
        snapshotJobRepository.save(SnapshotJob.builder()
                .id(jobId)
                .configId(job.getConfigId())
                .batchId(batchId)
                .topic(allTopic)
                .status(SnapshotJob.JobStatus.RUNNING)
                .processedRows(0L)
                .startedAt(LocalDateTime.now())
                .build());

        // 构建源库 JDBC URL
        String jdbcUrl = buildJdbcUrl(config);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.getDbUser(), config.getDbPassword())) {

            // ① 快照启动前读取当前 PostgreSQL WAL LSN，作为增量对齐锚点
            String snapshotLsn = readCurrentLsn(conn);
            log.info("快照 LSN 锚点 - BatchId: {}, snapshotLsn: {}", batchId, snapshotLsn);

            // 更新 LSN 到任务记录
            SnapshotJob runningJob = snapshotJobRepository.findById(jobId).orElseThrow();
            runningJob.setSnapshotLsn(snapshotLsn);
            snapshotJobRepository.save(runningJob);

            // ② 估算总行数（用于进度计算，允许近似值）
            long totalRows = countRows(conn, config.getSchemaName(), config.getTableName());
            runningJob.setTotalRows(totalRows);
            snapshotJobRepository.save(runningJob);
            log.info("快照表行数估算 - BatchId: {}, totalRows: {}", batchId, totalRows);

            // ③ 推送 BEGIN 标记消息
            sendMarkerMessage(namesrvAddr, producerGroup, allTopic, tag, batchId,
                    snapshotLsn, "BEGIN", totalRows, 0L);

            // ④ 流式全量扫描并分批推送 DATA 消息
            long processedRows = streamAndPush(conn, config, allTopic, tag, namesrvAddr, producerGroup,
                    batchId, snapshotLsn, jobId);

            // ⑤ 推送 END 标记消息
            sendMarkerMessage(namesrvAddr, producerGroup, allTopic, tag, batchId,
                    snapshotLsn, "END", totalRows, processedRows);

            // ⑥ 标记任务完成
            snapshotJobRepository.markAsDone(jobId, processedRows, LocalDateTime.now());
            log.info("全量快照完成 - BatchId: {}, processedRows: {}, snapshotLsn: {}",
                    batchId, processedRows, snapshotLsn);

        } catch (Exception e) {
            log.error("全量快照执行失败 - BatchId: {}, Error: {}", batchId, e.getMessage(), e);
            snapshotJobRepository.markAsFailed(jobId, truncateError(e.getMessage()), LocalDateTime.now());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 流式扫描 + 推送
    // ─────────────────────────────────────────────────────────────────

    /**
     * 流式扫描全表并批量推送到 RocketMQ
     *
     * <p>使用 JDBC fetchSize=batchSize 实现服务端流式游标，避免将整张表加载到内存。
     * 每积累 batchSize 条消息后批量发送，并在批次间 sleep 以降低 Broker 压力。
     *
     * @return 实际推送的行数
     */
    private long streamAndPush(Connection conn, DataSourceConfig config,
                               String allTopic, String tag,
                               String namesrvAddr, String producerGroup,
                               String batchId, String snapshotLsn, Long jobId) throws Exception {

        String sql = String.format("SELECT * FROM %s.%s ORDER BY id",
                config.getSchemaName(), config.getTableName());

        // 关闭自动提交，开启服务端游标（PostgreSQL 要求）
        conn.setAutoCommit(false);

        try (PreparedStatement ps = conn.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            // 每次从数据库取 batchSize 行到 JVM，避免一次性加载全表
            ps.setFetchSize(batchSize);

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();

                List<Message> batch = new ArrayList<>(batchSize);
                long processedRows = 0;

                while (rs.next()) {
                    // 将行数据转换为 Map，再序列化为 CDC 格式 JSON
                    Map<String, Object> rowData = new LinkedHashMap<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        rowData.put(meta.getColumnName(i), rs.getObject(i));
                    }

                    String primaryKey = extractPrimaryKey(rowData);
                    String messageBody = buildDataMessageBody(rowData, config, batchId, snapshotLsn);

                    // 构建 RocketMQ Message，并附加 Header
                    Message msg = new Message(allTopic, tag, primaryKey,
                            messageBody.getBytes(StandardCharsets.UTF_8));
                    attachSnapshotHeaders(msg, batchId, snapshotLsn, "DATA");
                    batch.add(msg);
                    processedRows++;

                    // 积累到批次大小后批量发送
                    if (batch.size() >= batchSize) {
                        sendBatch(namesrvAddr, producerGroup, batch);
                        batch.clear();

                        // 更新进度到数据库
                        snapshotJobRepository.updateProcessedRows(jobId, processedRows);

                        // 批次间短暂暂停，避免打垮 Broker
                        if (batchPauseMs > 0) {
                            Thread.sleep(batchPauseMs);
                        }
                        log.debug("快照进度 - BatchId: {}, processedRows: {}", batchId, processedRows);
                    }
                }

                // 发送最后一批（不满 batchSize 的尾部数据）
                if (!batch.isEmpty()) {
                    sendBatch(namesrvAddr, producerGroup, batch);
                }

                return processedRows;
            }
        } finally {
            // 恢复自动提交
            try { conn.setAutoCommit(true); } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 消息构建工具方法
    // ─────────────────────────────────────────────────────────────────

    /**
     * 推送 BEGIN / END 标记消息
     * Body 为 JSON 元数据，供消费端识别快照边界和 LSN 锚点
     */
    private void sendMarkerMessage(String namesrvAddr, String producerGroup,
                                   String topic, String tag,
                                   String batchId, String snapshotLsn,
                                   String markerType, long totalRows, long processedRows) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("batchId", batchId);
            meta.put("snapshotLsn", snapshotLsn);
            meta.put("markerType", markerType);
            meta.put("totalRows", totalRows);
            meta.put("processedRows", processedRows);
            meta.put("tsMs", System.currentTimeMillis());

            String body = objectMapper.writeValueAsString(meta);
            Message msg = new Message(topic, tag, batchId, body.getBytes(StandardCharsets.UTF_8));
            attachSnapshotHeaders(msg, batchId, snapshotLsn, markerType);

            rocketMQProducerService.sendMessage(namesrvAddr, producerGroup, topic, tag, batchId, body);
            log.info("快照 {} 标记消息已发送 - BatchId: {}, snapshotLsn: {}", markerType, batchId, snapshotLsn);
        } catch (Exception e) {
            log.error("发送快照 {} 标记消息失败 - BatchId: {}, Error: {}", markerType, batchId, e.getMessage(), e);
            throw new RuntimeException("发送快照标记消息失败", e);
        }
    }

    /**
     * 构建与增量 CDC 格式对齐的 DATA 消息体
     *
     * <p>采用 Debezium JSON 格式兼容结构：
     * <pre>
     * {
     *   "payload": {
     *     "before": null,
     *     "after": { ...行数据... },
     *     "op": "r",           // r = read（全量快照专用操作类型）
     *     "ts_ms": 时间戳,
     *     "source": {
     *       "snapshot": "true",
     *       "table": "表名",
     *       "snapshotBatchId": "批次ID",
     *       "snapshotLsn": "LSN锚点"
     *     }
     *   }
     * }
     * </pre>
     */
    private String buildDataMessageBody(Map<String, Object> rowData, DataSourceConfig config,
                                        String batchId, String snapshotLsn) throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("snapshot", "true");
        source.put("db", config.getDbName());
        source.put("schema", config.getSchemaName());
        source.put("table", config.getTableName());
        source.put("snapshotBatchId", batchId);
        source.put("snapshotLsn", snapshotLsn);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", null);
        payload.put("after", rowData);
        payload.put("op", "r");  // "r" = read，与增量的 c/u/d 区分
        payload.put("ts_ms", System.currentTimeMillis());
        payload.put("source", source);

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("payload", payload);

        return objectMapper.writeValueAsString(wrapper);
    }

    /**
     * 为 RocketMQ 消息附加快照相关的 UserProperty Header
     */
    private void attachSnapshotHeaders(Message msg, String batchId, String snapshotLsn, String status) {
        msg.putUserProperty("X-Snapshot-Type", "FULL");
        msg.putUserProperty("X-Snapshot-Batch-Id", batchId);
        msg.putUserProperty("X-Snapshot-Lsn", snapshotLsn != null ? snapshotLsn : "N/A");
        msg.putUserProperty("X-Snapshot-Status", status);
    }

    /**
     * 批量发送到 RocketMQ（RocketMQ 单批限制：消息数 ≤ 32，总大小 ≤ 4MB）
     * 此处 batchSize 默认 500，拆分成子批次发送
     */
    private void sendBatch(String namesrvAddr, String producerGroup, List<Message> batch) {
        // RocketMQ 批量发送限制：单次最多 32 条
        int subBatchSize = 32;
        for (int i = 0; i < batch.size(); i += subBatchSize) {
            List<Message> subBatch = batch.subList(i, Math.min(i + subBatchSize, batch.size()));
            rocketMQProducerService.sendBatchMessages(namesrvAddr, producerGroup, subBatch);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 数据库工具方法
    // ─────────────────────────────────────────────────────────────────

    /**
     * 从源库读取当前 WAL LSN 作为快照锚点
     */
    private String readCurrentLsn(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_current_wal_lsn()::text");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (Exception e) {
            log.warn("读取当前 LSN 失败，将使用空值: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 估算表行数（使用 PostgreSQL 统计信息，性能远优于 COUNT(*)）
     */
    private long countRows(Connection conn, String schema, String table) {
        String sql = """
            SELECT reltuples::bigint AS estimate
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = ? AND c.relname = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long estimate = rs.getLong(1);
                    return estimate > 0 ? estimate : 0L;
                }
            }
        } catch (Exception e) {
            log.warn("估算表行数失败，返回 0: {}", e.getMessage());
        }
        return 0L;
    }

    /**
     * 动态构建源库 JDBC URL
     */
    private String buildJdbcUrl(DataSourceConfig config) {
        return String.format("jdbc:postgresql://%s:%d/%s?sslmode=disable",
                config.getDbHostname(), config.getDbPort(), config.getDbName());
    }

    /**
     * 从行数据中提取主键值（优先取 "id" 字段）
     */
    private String extractPrimaryKey(Map<String, Object> rowData) {
        Object id = rowData.get("id");
        return id != null ? id.toString() : "UNKNOWN";
    }

    /**
     * 截断过长的错误信息（error_message 字段有长度限制）
     */
    private String truncateError(String message) {
        if (message == null) return "Unknown error";
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }
}
