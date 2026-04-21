package com.example.cdc.service;

import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.model.SnapshotJob;
import com.example.cdc.repository.SnapshotJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotTask {

    private final DataSourceConfigService configService;
    private final SnapshotJobRepository snapshotJobRepository;
    private final RocketMQProducerService rocketMQProducerService;
    private final CdcMessageKeyExtractor messageKeyExtractor;
    private final DataSourceProperties dataSourceProperties;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${snapshot.topic-suffix:_ALL}")
    private String topicSuffix;

    @Value("${snapshot.schedule.enabled:true}")
    private boolean scheduleEnabled;

    @Value("${snapshot.schedule.cron:0 0 3 * * ?}")
    private String scheduleCron;

    @Value("${snapshot.debezium.snapshot-fetch-size:2048}")
    private int snapshotFetchSize;

    @Value("${snapshot.debezium.max-queue-size:8192}")
    private int snapshotMaxQueueSize;

    @Value("${snapshot.debezium.max-batch-size:2048}")
    private int snapshotMaxBatchSize;

    private final ConcurrentHashMap<Long, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverStaleJobs() {
        List<SnapshotJob> staleJobs = snapshotJobRepository.findByStatus(SnapshotJob.JobStatus.RUNNING);
        if (staleJobs.isEmpty()) {
            return;
        }
        for (SnapshotJob job : staleJobs) {
            snapshotJobRepository.markAsFailed(job.getId(), "Application restarted while snapshot was running", LocalDateTime.now());
        }
        log.warn("Recovered {} stale snapshot jobs", staleJobs.size());
    }

    @Scheduled(cron = "${snapshot.schedule.cron:0 0 3 * * ?}")
    public void scheduledSnapshotAll() {
        if (!scheduleEnabled) {
            return;
        }

        List<DataSourceConfig> activeConfigs = configService.getActiveConfigs();
        log.info("Scheduled snapshot triggered (cron={}), active configs={}", scheduleCron, activeConfigs.size());
        for (DataSourceConfig config : activeConfigs) {
            try {
                triggerSnapshot(config.getId());
            } catch (Exception e) {
                log.error("Failed to trigger scheduled snapshot for config {}: {}", config.getId(), e.getMessage(), e);
            }
        }
    }

    public String triggerSnapshot(Long configId) {
        DataSourceConfig config = configService.getConfigById(configId)
                .orElseThrow(() -> new IllegalArgumentException("DataSource config not found: " + configId));

        AtomicBoolean running = runningFlags.computeIfAbsent(configId, id -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Snapshot already running for config " + configId);
        }

        String batchId = configId + "-" + System.currentTimeMillis();
        String allTopic = config.getRocketmqTopic() + topicSuffix;

        SnapshotJob job = SnapshotJob.builder()
                .configId(configId)
                .batchId(batchId)
                .topic(allTopic)
                .status(SnapshotJob.JobStatus.PENDING)
                .processedRows(0L)
                .build();
        SnapshotJob saved = snapshotJobRepository.save(job);

        Thread.ofVirtual().name("snapshot-" + batchId).start(() -> {
            try {
                executeSnapshot(saved, config);
            } finally {
                running.set(false);
            }
        });

        return batchId;
    }

    private void executeSnapshot(SnapshotJob job, DataSourceConfig config) {
        Long jobId = job.getId();
        String batchId = job.getBatchId();
        String topic = job.getTopic();
        String tag = (config.getRocketmqTag() != null && !config.getRocketmqTag().isBlank())
                ? config.getRocketmqTag()
                : config.getTableName();

        try {
            updateJobAsRunning(jobId);

            clearSnapshotConnectorState(config.getId());

            String fallbackLsn = null;
            long totalRows = 0L;
            try (Connection conn = DriverManager.getConnection(buildJdbcUrl(config), config.getDbUser(), config.getDbPassword())) {
                fallbackLsn = readCurrentLsn(conn);
                totalRows = countRows(conn, config.getSchemaName(), config.getTableName());
            }

            updateJobSnapshotMeta(jobId, fallbackLsn, totalRows);

            sendMarkerMessage(config, topic, tag, batchId, fallbackLsn, "BEGIN", totalRows, 0L);

            AtomicLong processed = new AtomicLong(0);
            AtomicReferenceWithString snapshotLsnRef = new AtomicReferenceWithString(fallbackLsn);
            AtomicBoolean completionHandled = new AtomicBoolean(false);
            final long totalRowsForCallback = totalRows;
            AtomicReference<DebeziumEngine<ChangeEvent<String, String>>> engineRef = new AtomicReference<>();

            DebeziumEngine<ChangeEvent<String, String>> engine = DebeziumEngine.create(Json.class)
                    .using(buildSnapshotDebeziumProperties(config, batchId))
                    .notifying(event -> handleSnapshotEvent(
                            event, config, topic, tag, batchId, jobId, processed, snapshotLsnRef,
                            completionHandled, totalRowsForCallback, engineRef))
                    .using((success, message, error) -> {
                        if (!completionHandled.compareAndSet(false, true)) {
                            return;
                        }
                        long processedRows = processed.get();
                        String snapshotLsn = snapshotLsnRef.value();
                        if (success) {
                            updateJobAsDone(jobId, snapshotLsn, processedRows);
                            try {
                                sendMarkerMessage(config, topic, tag, batchId, snapshotLsn, "END", totalRowsForCallback, processedRows);
                            } catch (Exception markerError) {
                                log.error("Failed to send END marker in completion callback, configId={}, batchId={}, error={}",
                                        config.getId(), batchId, markerError.getMessage(), markerError);
                            }
                            log.info("Snapshot completion callback persisted DONE, configId={}, batchId={}, rows={}, snapshotLsn={}",
                                    config.getId(), batchId, processedRows, snapshotLsn);
                        } else {
                            String callbackError = (error != null ? error.getMessage() : message);
                            log.warn("Snapshot Debezium callback indicates failure, batchId={}, message={}, error={}",
                                    batchId, message, callbackError != null ? callbackError : "N/A");
                            updateJobAsFailed(jobId, truncateError(callbackError), config.getId(), batchId);
                        }
                    })
                    .build();
            engineRef.set(engine);

            try (engine) {
                engine.run();
            }

            long processedRows = processed.get();
            String snapshotLsn = snapshotLsnRef.value();

            if (completionHandled.compareAndSet(false, true)) {
                updateJobAsDone(jobId, snapshotLsn, processedRows);
                sendMarkerMessage(config, topic, tag, batchId, snapshotLsn, "END", totalRows, processedRows);
                log.info("Snapshot completed after engine.run return, configId={}, batchId={}, rows={}, snapshotLsn={}",
                        config.getId(), batchId, processedRows, snapshotLsn);
            } else {
                log.info("Snapshot completion already handled in callback, configId={}, batchId={}",
                        config.getId(), batchId);
            }
        } catch (Exception e) {
            updateJobAsFailed(jobId, truncateError(e.getMessage()), config.getId(), batchId);
            log.error("Snapshot failed, configId={}, batchId={}, error={}", config.getId(), batchId, e.getMessage(), e);
        }
    }

    private void handleSnapshotEvent(ChangeEvent<String, String> event,
                                     DataSourceConfig config,
                                     String topic,
                                     String tag,
                                     String batchId,
                                     Long jobId,
                                     AtomicLong processed,
                                     AtomicReferenceWithString snapshotLsnRef,
                                     AtomicBoolean completionHandled,
                                     long totalRows,
                                     AtomicReference<DebeziumEngine<ChangeEvent<String, String>>> engineRef) {
        String value = event.value();
        if (value == null || value.isBlank()) {
            return;
        }

        String op = extractOperation(value);
        if (!"r".equals(op)) {
            return;
        }

        String messageKey = messageKeyExtractor.extractPrimaryKey(value, event.key());
        String eventLsn = extractLsn(value);
        if (eventLsn != null && !eventLsn.isBlank()) {
            snapshotLsnRef.set(eventLsn);
        }

        Message message = new Message(topic, tag, messageKey, value.getBytes(StandardCharsets.UTF_8));
        attachSnapshotHeaders(message, batchId, snapshotLsnRef.value(), "DATA");
        rocketMQProducerService.sendMessage(config.getRocketmqNamesrvAddr(), config.getRocketmqProducerGroup(), message);

        long current = processed.incrementAndGet();
        if (current % 500 == 0) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                        snapshotJobRepository.updateProcessedRows(jobId, current));
            } catch (Exception e) {
                log.warn("Snapshot progress update failed, batchId={}, rows={}, error={}",
                        batchId, current, e.getMessage());
            }
        }

        String snapshotState = extractSnapshotState(value);
        if ("last".equalsIgnoreCase(snapshotState)) {
            if (completionHandled.compareAndSet(false, true)) {
                long processedRows = processed.get();
                String snapshotLsn = snapshotLsnRef.value();
                updateJobAsDone(jobId, snapshotLsn, processedRows);
                try {
                    sendMarkerMessage(config, topic, tag, batchId, snapshotLsn, "END", totalRows, processedRows);
                } catch (Exception markerError) {
                    log.error("Failed to send END marker after last snapshot event, configId={}, batchId={}, error={}",
                            config.getId(), batchId, markerError.getMessage(), markerError);
                }
                log.info("Snapshot completion detected from last snapshot event, configId={}, batchId={}, rows={}, snapshotLsn={}",
                        config.getId(), batchId, processedRows, snapshotLsn);
            }

            DebeziumEngine<ChangeEvent<String, String>> currentEngine = engineRef.get();
            if (currentEngine != null) {
                Thread.ofVirtual().name("snapshot-stop-" + batchId).start(() -> {
                    try {
                        currentEngine.close();
                    } catch (Exception closeError) {
                        log.warn("Failed to close snapshot engine after last event, configId={}, batchId={}, error={}",
                                config.getId(), batchId, closeError.getMessage());
                    }
                });
            }
        }
    }

    private Properties buildSnapshotDebeziumProperties(DataSourceConfig config, String batchId) {
        Properties props = new Properties();

        String suffix = String.valueOf(config.getId());
        props.setProperty("name", "snapshot-engine-" + batchId);
        props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        props.setProperty("topic.prefix", "snapshot-" + batchId);

        props.setProperty("database.hostname", config.getDbHostname());
        props.setProperty("database.port", String.valueOf(config.getDbPort()));
        props.setProperty("database.user", config.getDbUser());
        props.setProperty("database.password", config.getDbPassword());
        props.setProperty("database.dbname", config.getDbName());
        props.setProperty("database.server.name", "snapshot-server-" + batchId);

        props.setProperty("schema.include.list", config.getSchemaName());
        props.setProperty("table.include.list", config.getSchemaName() + "." + config.getTableName());
        props.setProperty("key.converter.schemas.enable", "false");
        props.setProperty("value.converter.schemas.enable", "false");

        props.setProperty("plugin.name", "pgoutput");
        props.setProperty("slot.name", "debezium_snapshot_slot_" + suffix);
        props.setProperty("publication.name", "debezium_snapshot_pub_" + suffix);
        props.setProperty("publication.autocreate.mode", "filtered");
        props.setProperty("slot.drop.on.stop", "true");

        props.setProperty("snapshot.mode", "initial_only");
        props.setProperty("snapshot.fetch.size", String.valueOf(snapshotFetchSize));

        props.setProperty("offset.storage", "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore");
        props.setProperty("offset.storage.jdbc.url", resolveOffsetJdbcUrl());
        props.setProperty("offset.storage.jdbc.user", resolveOffsetJdbcUser());
        props.setProperty("offset.storage.jdbc.password", resolveOffsetJdbcPassword());
        props.setProperty("offset.storage.jdbc.offset.table.name", "debezium_snapshot_offset_" + suffix);
        props.setProperty("offset.storage.jdbc.offset.table.ddl",
                "CREATE TABLE %s (id VARCHAR(36) NOT NULL, offset_key TEXT, offset_val TEXT, record_insert_ts TIMESTAMP NOT NULL, record_insert_seq INTEGER NOT NULL, PRIMARY KEY(id))");

        props.setProperty("schema.history.internal", "io.debezium.storage.jdbc.history.JdbcSchemaHistory");
        props.setProperty("schema.history.internal.jdbc.url", resolveOffsetJdbcUrl());
        props.setProperty("schema.history.internal.jdbc.user", resolveOffsetJdbcUser());
        props.setProperty("schema.history.internal.jdbc.password", resolveOffsetJdbcPassword());
        props.setProperty("schema.history.internal.jdbc.schema.history.table.name", "debezium_snapshot_history_" + suffix);
        props.setProperty("schema.history.internal.jdbc.schema.history.table.ddl",
                "CREATE TABLE %s (id VARCHAR(36) NOT NULL, history_record TEXT, history_record_seq INTEGER, PRIMARY KEY(id))");

        props.setProperty("max.batch.size", String.valueOf(snapshotMaxBatchSize));
        props.setProperty("max.queue.size", String.valueOf(snapshotMaxQueueSize));
        props.setProperty("poll.interval.ms", "1000");
        props.setProperty("database.connect.timeout.ms", "30000");
        props.setProperty("database.statement.timeout.ms", "30000");
        return props;
    }

    private void sendMarkerMessage(DataSourceConfig config,
                                   String topic,
                                   String tag,
                                   String batchId,
                                   String snapshotLsn,
                                   String markerType,
                                   long totalRows,
                                   long processedRows) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("batchId", batchId);
            body.put("snapshotLsn", snapshotLsn);
            body.put("markerType", markerType);
            body.put("totalRows", totalRows);
            body.put("processedRows", processedRows);
            body.put("tsMs", System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(body);
            Message message = new Message(topic, tag, batchId, json.getBytes(StandardCharsets.UTF_8));
            attachSnapshotHeaders(message, batchId, snapshotLsn, markerType);
            rocketMQProducerService.sendMessage(config.getRocketmqNamesrvAddr(), config.getRocketmqProducerGroup(), message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send snapshot marker " + markerType, e);
        }
    }

    private void attachSnapshotHeaders(Message message, String batchId, String snapshotLsn, String status) {
        message.putUserProperty("X-Snapshot-Type", "FULL");
        message.putUserProperty("X-Snapshot-Batch-Id", batchId);
        message.putUserProperty("X-Snapshot-Lsn", snapshotLsn != null ? snapshotLsn : "N/A");
        message.putUserProperty("X-Snapshot-Status", status);
    }

    private String extractOperation(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            JsonNode op = node.get("op");
            if (op != null && !op.isNull()) {
                return op.asText();
            }
            JsonNode payload = node.get("payload");
            if (payload != null) {
                JsonNode payloadOp = payload.get("op");
                if (payloadOp != null && !payloadOp.isNull()) {
                    return payloadOp.asText();
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private String extractLsn(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            JsonNode source = node.get("source");
            if (source != null && source.get("lsn") != null) {
                return source.get("lsn").asText();
            }
            JsonNode payload = node.get("payload");
            if (payload != null) {
                JsonNode payloadSource = payload.get("source");
                if (payloadSource != null && payloadSource.get("lsn") != null) {
                    return payloadSource.get("lsn").asText();
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private String extractSnapshotState(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            JsonNode source = node.get("source");
            if (source != null && source.get("snapshot") != null && !source.get("snapshot").isNull()) {
                return source.get("snapshot").asText();
            }
            JsonNode payload = node.get("payload");
            if (payload != null) {
                JsonNode payloadSource = payload.get("source");
                if (payloadSource != null && payloadSource.get("snapshot") != null && !payloadSource.get("snapshot").isNull()) {
                    return payloadSource.get("snapshot").asText();
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private String readCurrentLsn(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_current_wal_lsn()::text");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (Exception e) {
            log.warn("Failed to read current WAL LSN: {}", e.getMessage());
        }
        return null;
    }

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
                    return Math.max(estimate, 0L);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to estimate row count for {}.{}: {}", schema, table, e.getMessage());
        }
        return 0L;
    }

    private String buildJdbcUrl(DataSourceConfig config) {
        return String.format("jdbc:postgresql://%s:%d/%s?sslmode=disable",
                config.getDbHostname(), config.getDbPort(), config.getDbName());
    }

    private String resolveOffsetJdbcUrl() {
        String value = dataSourceProperties.getUrl();
        return value == null ? "" : value.trim();
    }

    private String resolveOffsetJdbcUser() {
        String value = dataSourceProperties.getUsername();
        return value == null ? "" : value.trim();
    }

    private String resolveOffsetJdbcPassword() {
        String value = dataSourceProperties.getPassword();
        return value == null ? "" : value.trim();
    }

    private void clearSnapshotConnectorState(Long configId) {
        String offsetTable = "debezium_snapshot_offset_" + configId;
        String historyTable = "debezium_snapshot_history_" + configId;
        String jdbcUrl = resolveOffsetJdbcUrl();
        String jdbcUser = resolveOffsetJdbcUser();
        String jdbcPassword = resolveOffsetJdbcPassword();

        if (jdbcUrl.isBlank()) {
            log.warn("Skip snapshot state cleanup because offset JDBC URL is blank, configId={}", configId);
            return;
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            truncateTableIfExists(conn, offsetTable, configId);
            truncateTableIfExists(conn, historyTable, configId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to cleanup snapshot connector state for config " + configId, e);
        }
    }

    private void truncateTableIfExists(Connection conn, String tableName, Long configId) {
        String sql = "TRUNCATE TABLE " + tableName;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
            log.info("Cleared snapshot connector table: {}, configId={}", tableName, configId);
        } catch (Exception e) {
            log.debug("Skip clearing table {} for configId={} because it may not exist yet: {}",
                    tableName, configId, e.getMessage());
        }
    }

    private void updateJobAsRunning(Long jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            SnapshotJob runningJob = snapshotJobRepository.findById(jobId).orElseThrow();
            runningJob.setStatus(SnapshotJob.JobStatus.RUNNING);
            runningJob.setStartedAt(LocalDateTime.now());
            snapshotJobRepository.saveAndFlush(runningJob);
            log.info("Snapshot job status persisted: jobId={}, status=RUNNING", jobId);
        });
    }

    private void updateJobSnapshotMeta(Long jobId, String snapshotLsn, long totalRows) {
        transactionTemplate.executeWithoutResult(status -> {
            SnapshotJob runningJob = snapshotJobRepository.findById(jobId).orElseThrow();
            runningJob.setSnapshotLsn(snapshotLsn);
            runningJob.setTotalRows(totalRows);
            snapshotJobRepository.saveAndFlush(runningJob);
        });
    }

    private void updateJobAsDone(Long jobId, String snapshotLsn, long processedRows) {
        transactionTemplate.executeWithoutResult(status -> {
            SnapshotJob finished = snapshotJobRepository.findById(jobId).orElseThrow();
            finished.setSnapshotLsn(snapshotLsn);
            finished.setProcessedRows(processedRows);
            finished.setStatus(SnapshotJob.JobStatus.DONE);
            finished.setFinishedAt(LocalDateTime.now());
            finished.setErrorMessage(null);
            snapshotJobRepository.saveAndFlush(finished);
            log.info("Snapshot job status persisted: jobId={}, status=DONE, processedRows={}", jobId, processedRows);
        });
    }

    private void updateJobAsFailed(Long jobId, String errorMessage, Long configId, String batchId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                SnapshotJob failed = snapshotJobRepository.findById(jobId).orElse(null);
                if (failed != null) {
                    failed.setStatus(SnapshotJob.JobStatus.FAILED);
                    failed.setFinishedAt(LocalDateTime.now());
                    failed.setErrorMessage(errorMessage);
                    snapshotJobRepository.saveAndFlush(failed);
                    log.info("Snapshot job status persisted: jobId={}, status=FAILED", jobId);
                }
            });
        } catch (Exception statusError) {
            log.error("Failed to persist snapshot FAILED status, configId={}, batchId={}, error={}",
                    configId, batchId, statusError.getMessage(), statusError);
        }
    }

    private String truncateError(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }

    private static final class AtomicReferenceWithString {
        private volatile String value;

        private AtomicReferenceWithString(String value) {
            this.value = value;
        }

        private String value() {
            return value;
        }

        private void set(String value) {
            this.value = value;
        }
    }
}
