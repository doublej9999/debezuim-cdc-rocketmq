package com.example.cdc.service;

import com.example.cdc.model.DataSourceConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class MultiConfigCdcPipelineManager {

    private final DataSourceConfigService configService;
    private final AsyncEventSenderService asyncEventSenderService;
    private final CdcMessageKeyExtractor messageKeyExtractor;
    private final DataSourceProperties springDataSourceProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cdc.heartbeat.interval.ms:5000}")
    private int heartbeatIntervalMs;

    @Value("${cdc.heartbeat.action.query:SELECT 1}")
    private String heartbeatActionQuery;

    private final Map<Long, CdcPipeline> pipelines = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> lastRestartAttempts = new ConcurrentHashMap<>();
    private static final Duration RESTART_COOLDOWN = Duration.ofMinutes(5);

    private ExecutorService virtualThreadExecutor;

    public MultiConfigCdcPipelineManager(DataSourceConfigService configService,
                                         AsyncEventSenderService asyncEventSenderService,
                                         CdcMessageKeyExtractor messageKeyExtractor,
                                         DataSourceProperties springDataSourceProperties) {
        this.configService = configService;
        this.asyncEventSenderService = asyncEventSenderService;
        this.messageKeyExtractor = messageKeyExtractor;
        this.springDataSourceProperties = springDataSourceProperties;
    }

    public void initializeActivePipelines() {
        ensureExecutorReady();
        List<DataSourceConfig> activeConfigs = configService.getActiveConfigs();
        for (DataSourceConfig config : activeConfigs) {
            startPipeline(config);
        }
        log.info("Initialized {} active CDC pipelines", activeConfigs.size());
    }

    public synchronized void startPipeline(DataSourceConfig config) {
        if (config == null || !Boolean.TRUE.equals(config.getIsActive())) {
            return;
        }

        ensureExecutorReady();
        Long configId = config.getId();
        if (pipelines.containsKey(configId)) {
            return;
        }

        CdcPipeline pipeline = new CdcPipeline(
                config,
                virtualThreadExecutor,
                asyncEventSenderService,
                messageKeyExtractor,
                objectMapper,
                resolveOffsetJdbcUrl(),
                resolveOffsetJdbcUser(),
                resolveOffsetJdbcPassword(),
                heartbeatIntervalMs,
                heartbeatActionQuery
        );
        pipeline.start();
        pipelines.put(configId, pipeline);
        log.info("Started CDC pipeline for configId={}", configId);
    }

    public synchronized void stopPipeline(Long configId) {
        CdcPipeline pipeline = pipelines.get(configId);
        if (pipeline == null) {
            return;
        }
        try {
            pipeline.stop();
        } catch (Exception e) {
            log.warn("Stop pipeline failed, configId={}, error={}", configId, e.getMessage());
        } finally {
            pipelines.remove(configId);
        }
    }

    public synchronized void restartPipeline(Long configId) {
        stopPipeline(configId);
        DataSourceConfig config = configService.getConfigById(configId)
                .orElseThrow(() -> new RuntimeException("Config not found: " + configId));
        startPipeline(config);
    }

    public Map<Long, PipelineStatus> getAllPipelineStatus() {
        Map<Long, PipelineStatus> statusMap = new LinkedHashMap<>();
        for (Map.Entry<Long, CdcPipeline> entry : pipelines.entrySet()) {
            statusMap.put(entry.getKey(), entry.getValue().getStatus());
        }
        return statusMap;
    }

    public PipelineStatus getPipelineStatus(Long configId) {
        CdcPipeline pipeline = pipelines.get(configId);
        return pipeline == null ? null : pipeline.getStatus();
    }

    public int getActivePipelineCount() {
        return pipelines.size();
    }

    @Scheduled(fixedDelayString = "${cdc.watchdog.interval.ms:60000}")
    public void checkAndRestartPipelines() {
        List<DataSourceConfig> activeConfigs = configService.getActiveConfigs();
        for (DataSourceConfig config : activeConfigs) {
            Long configId = config.getId();
            CdcPipeline pipeline = pipelines.get(configId);
            if (pipeline != null && pipeline.isRunning()) {
                continue;
            }
            if (!isCooldownExpired(configId)) {
                continue;
            }
            try {
                lastRestartAttempts.put(configId, LocalDateTime.now());
                restartPipeline(configId);
                log.warn("Watchdog restarted CDC pipeline for configId={}", configId);
            } catch (Exception e) {
                log.error("Watchdog restart failed for configId={}, error={}", configId, e.getMessage(), e);
            }
        }
    }

    public synchronized void shutdownAll() {
        for (Long configId : new ArrayList<>(pipelines.keySet())) {
            stopPipeline(configId);
        }

        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdown();
            try {
                if (!virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    virtualThreadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                virtualThreadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isCooldownExpired(Long configId) {
        LocalDateTime lastAttempt = lastRestartAttempts.get(configId);
        if (lastAttempt == null) {
            return true;
        }
        return Duration.between(lastAttempt, LocalDateTime.now()).compareTo(RESTART_COOLDOWN) >= 0;
    }

    private void ensureExecutorReady() {
        if (virtualThreadExecutor == null) {
            virtualThreadExecutor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("cdc-vt-", 0).factory());
        }
    }

    private String resolveOffsetJdbcUrl() {
        String value = springDataSourceProperties.getUrl();
        return value == null ? "" : value.trim();
    }

    private String resolveOffsetJdbcUser() {
        String value = springDataSourceProperties.getUsername();
        return value == null ? "" : value.trim();
    }

    private String resolveOffsetJdbcPassword() {
        String value = springDataSourceProperties.getPassword();
        return value == null ? "" : value.trim();
    }

    @Data
    public static class PipelineStatus {
        private Long configId;
        private String configName;
        private boolean running;
        private LocalDateTime startTime;
        private long processedEvents;
        private String currentLsn;
        private String dbHostname;
        private String schemaName;
        private String tableName;
        private String rocketmqTopic;
        private String rocketmqTag;
        private String lastError;
        private LocalDateTime lastProcessedTime;
    }

    private static class CdcPipeline {
        private final DataSourceConfig config;
        private final ExecutorService executor;
        private final AsyncEventSenderService asyncEventSenderService;
        private final CdcMessageKeyExtractor messageKeyExtractor;
        private final ObjectMapper objectMapper;
        private final String offsetJdbcUrl;
        private final String offsetJdbcUser;
        private final String offsetJdbcPassword;
        private final int heartbeatIntervalMs;
        private final String heartbeatActionQuery;

        private DebeziumEngine<ChangeEvent<String, String>> engine;
        private Future<?> engineFuture;
        private LocalDateTime startTime;
        private final AtomicLong processedEventCount = new AtomicLong(0);
        private volatile String currentLsn = "N/A";
        private volatile boolean running = false;
        private volatile String lastError;
        private volatile LocalDateTime lastProcessedTime;

        private CdcPipeline(DataSourceConfig config,
                            ExecutorService executor,
                            AsyncEventSenderService asyncEventSenderService,
                            CdcMessageKeyExtractor messageKeyExtractor,
                            ObjectMapper objectMapper,
                            String offsetJdbcUrl,
                            String offsetJdbcUser,
                            String offsetJdbcPassword,
                            int heartbeatIntervalMs,
                            String heartbeatActionQuery) {
            this.config = config;
            this.executor = executor;
            this.asyncEventSenderService = asyncEventSenderService;
            this.messageKeyExtractor = messageKeyExtractor;
            this.objectMapper = objectMapper;
            this.offsetJdbcUrl = offsetJdbcUrl;
            this.offsetJdbcUser = offsetJdbcUser;
            this.offsetJdbcPassword = offsetJdbcPassword;
            this.heartbeatIntervalMs = heartbeatIntervalMs;
            this.heartbeatActionQuery = heartbeatActionQuery;
        }

        public boolean isRunning() {
            return running;
        }

        public void start() {
            Properties props = buildDebeziumProperties();

            engine = DebeziumEngine.create(Json.class)
                    .using(props)
                    .notifying(this::handleChangeEvent)
                    .using((success, message, error) -> {
                        if (success) {
                            lastError = null;
                        } else {
                            lastError = error != null ? error.getMessage() : message;
                        }
                    })
                    .build();

            engineFuture = executor.submit(() -> {
                try {
                    running = true;
                    startTime = LocalDateTime.now();
                    engine.run();
                } catch (Exception e) {
                    lastError = e.getMessage();
                    log.error("CDC pipeline runtime error, configId={}, error={}", config.getId(), e.getMessage(), e);
                } finally {
                    running = false;
                }
            });
        }

        public void stop() throws IOException {
            try {
                if (engine != null) {
                    engine.close();
                }
                if (engineFuture != null) {
                    try {
                        engineFuture.get(10, TimeUnit.SECONDS);
                    } catch (TimeoutException ignored) {
                        log.warn("Timeout waiting engine to stop, configId={}", config.getId());
                    }
                }
            } catch (Exception e) {
                log.warn("Close engine failed, configId={}, error={}", config.getId(), e.getMessage());
            } finally {
                running = false;
            }
        }

        private void handleChangeEvent(ChangeEvent<String, String> event) {
            try {
                String destination = event.destination();
                if (destination != null && destination.startsWith("__debezium-heartbeat")) {
                    return;
                }

                String value = event.value();
                if (value == null) {
                    return;
                }

                String lsn = extractLsn(value);
                if (lsn != null) {
                    currentLsn = lsn;
                }

                String topic = config.getRocketmqTopic();
                String tag = config.getRocketmqTag() != null ? config.getRocketmqTag() : config.getTableName();
                String messageKey = messageKeyExtractor.extractPrimaryKey(value, event.key());

                asyncEventSenderService.enqueueEvent(
                        topic,
                        tag,
                        messageKey,
                        value,
                        config.getId(),
                        config.getRocketmqNamesrvAddr(),
                        config.getRocketmqProducerGroup(),
                        lsn
                );

                processedEventCount.incrementAndGet();
                lastProcessedTime = LocalDateTime.now();
            } catch (Exception e) {
                log.error("Handle change event failed, configId={}, error={}", config.getId(), e.getMessage(), e);
            }
        }

        private Properties buildDebeziumProperties() {
            Properties props = new Properties();
            props.setProperty("name", "debezium-" + config.getId());
            props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");

            props.setProperty("offset.storage", "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore");
            props.setProperty("offset.storage.jdbc.url", offsetJdbcUrl);
            props.setProperty("offset.storage.jdbc.user", offsetJdbcUser);
            props.setProperty("offset.storage.jdbc.password", offsetJdbcPassword);
            props.setProperty("offset.storage.jdbc.offset.table.name", "debezium_offset_storage_" + config.getId());
            props.setProperty("offset.storage.jdbc.offset.table.ddl",
                    "CREATE TABLE %s (id VARCHAR(36) NOT NULL, offset_key TEXT, offset_val TEXT, record_insert_ts TIMESTAMP NOT NULL, record_insert_seq INTEGER NOT NULL, PRIMARY KEY(id))");
            props.setProperty("offset.flush.interval.ms", "2000");

            props.setProperty("topic.prefix", "dbserver-" + config.getId());
            props.setProperty("key.converter.schemas.enable", "false");
            props.setProperty("value.converter.schemas.enable", "false");
            props.setProperty("heartbeat.interval.ms", String.valueOf(Math.max(1000, heartbeatIntervalMs)));
            props.setProperty("heartbeat.action.query", heartbeatActionQuery);

            props.setProperty("database.hostname", config.getDbHostname());
            props.setProperty("database.port", String.valueOf(config.getDbPort()));
            props.setProperty("database.user", config.getDbUser());
            props.setProperty("database.password", config.getDbPassword());
            props.setProperty("database.dbname", config.getDbName());
            props.setProperty("database.server.name", "dbserver-" + config.getId());

            props.setProperty("schema.include.list", config.getSchemaName());
            props.setProperty("table.include.list", config.getSchemaName() + "." + config.getTableName());

            props.setProperty("plugin.name", "pgoutput");
            props.setProperty("slot.name", "debezium_slot_" + config.getId());
            props.setProperty("publication.name", "debezium_publication_" + config.getId());
            props.setProperty("slot.drop.on.stop", "false");

            props.setProperty("snapshot.mode", "when_needed");
            props.setProperty("snapshot.delay.ms", "5000");
            props.setProperty("snapshot.fetch.size", "2048");

            props.setProperty("schema.history.internal", "io.debezium.storage.jdbc.history.JdbcSchemaHistory");
            props.setProperty("schema.history.internal.jdbc.url", offsetJdbcUrl);
            props.setProperty("schema.history.internal.jdbc.user", offsetJdbcUser);
            props.setProperty("schema.history.internal.jdbc.password", offsetJdbcPassword);
            props.setProperty("schema.history.internal.jdbc.schema.history.table.name", "debezium_database_history_" + config.getId());
            props.setProperty("schema.history.internal.jdbc.schema.history.table.ddl",
                    "CREATE TABLE %s (id VARCHAR(36) NOT NULL, history_record TEXT, history_record_seq INTEGER, PRIMARY KEY(id))");

            props.setProperty("max.batch.size", "2048");
            props.setProperty("max.queue.size", "8192");
            props.setProperty("poll.interval.ms", "1000");
            props.setProperty("database.connect.timeout.ms", "30000");
            props.setProperty("database.statement.timeout.ms", "30000");
            return props;
        }

        private String extractLsn(String value) {
            try {
                JsonNode jsonNode = objectMapper.readTree(value);
                JsonNode source = jsonNode.get("source");
                if (source != null && source.get("lsn") != null) {
                    return source.get("lsn").asText();
                }
            } catch (Exception e) {
                log.warn("Extract LSN failed: {}", e.getMessage());
            }
            return null;
        }

        public PipelineStatus getStatus() {
            PipelineStatus status = new PipelineStatus();
            status.setConfigId(config.getId());
            status.setConfigName(config.getName());
            status.setRunning(running);
            status.setStartTime(startTime);
            status.setProcessedEvents(processedEventCount.get());
            status.setCurrentLsn(currentLsn);
            status.setDbHostname(config.getDbHostname());
            status.setSchemaName(config.getSchemaName());
            status.setTableName(config.getTableName());
            status.setRocketmqTopic(config.getRocketmqTopic());
            status.setRocketmqTag(config.getRocketmqTag());
            status.setLastError(lastError);
            status.setLastProcessedTime(lastProcessedTime);
            return status;
        }
    }
}
