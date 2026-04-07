package com.example.cdc.service;

import com.example.cdc.model.DataSourceConfig;
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

    private final RocketMQProducerService rocketMQProducerService;
    private final DataSourceConfigService configService;
    private final AsyncEventSenderService asyncEventSenderService;
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

    public MultiConfigCdcPipelineManager(RocketMQProducerService rocketMQProducerService,
                                         DataSourceConfigService configService,
                                         AsyncEventSenderService asyncEventSenderService,
                                         DataSourceProperties springDataSourceProperties) {
        this.rocketMQProducerService = rocketMQProducerService;
        this.configService = configService;
        this.asyncEventSenderService = asyncEventSenderService;
        this.springDataSourceProperties = springDataSourceProperties;
    }

    public void initializeActivePipelines() {
        log.info("初始化所有活跃 CDC 管道...");
        ensureExecutorReady();

        try {
            List<DataSourceConfig> activeConfigs = configService.getActiveConfigs();
            log.info("发现 {} 个活跃配置", activeConfigs.size());
            for (DataSourceConfig config : activeConfigs) {
                startPipeline(config);
            }
            log.info("活跃 CDC 管道初始化完成");
        } catch (Exception e) {
            log.error("初始化 CDC 管道失败: {}", e.getMessage(), e);
            throw new RuntimeException("初始化 CDC 管道失败", e);
        }
    }

    public synchronized void startPipeline(DataSourceConfig config) {
        if (config == null || !Boolean.TRUE.equals(config.getIsActive())) {
            log.warn("配置无效或未启用: {}", config != null ? config.getId() : null);
            return;
        }

        ensureExecutorReady();

        Long configId = config.getId();
        if (pipelines.containsKey(configId)) {
            log.warn("配置 {} 的管道已存在，跳过启动", configId);
            return;
        }

        try {
            CdcPipeline pipeline = new CdcPipeline(
                    config,
                    virtualThreadExecutor,
                    rocketMQProducerService,
                    asyncEventSenderService,
                    objectMapper,
                    resolveOffsetJdbcUrl(),
                    resolveOffsetJdbcUser(),
                    resolveOffsetJdbcPassword(),
                    heartbeatIntervalMs,
                    heartbeatActionQuery
            );
            pipeline.start();
            pipelines.put(configId, pipeline);
            log.info("配置 {} 的 CDC 管道已启动", configId);
        } catch (Exception e) {
            log.error("启动配置 {} 的 CDC 管道失败: {}", configId, e.getMessage(), e);
            throw new RuntimeException("启动 CDC 管道失败", e);
        }
    }

    public synchronized void stopPipeline(Long configId) {
        CdcPipeline pipeline = pipelines.get(configId);
        if (pipeline == null) {
            log.warn("配置 {} 的管道不存在", configId);
            return;
        }

        try {
            pipeline.stop();
        } catch (Exception e) {
            log.error("停止配置 {} 的 CDC 管道失败: {}", configId, e.getMessage(), e);
        } finally {
            pipelines.remove(configId);
        }
    }

    public synchronized void restartPipeline(Long configId) {
        stopPipeline(configId);
        DataSourceConfig config = configService.getConfigById(configId)
                .orElseThrow(() -> new RuntimeException("配置不存在: " + configId));
        startPipeline(config);
    }

    public Map<Long, PipelineStatus> getAllPipelineStatus() {
        Map<Long, PipelineStatus> result = new LinkedHashMap<>();
        for (Map.Entry<Long, CdcPipeline> entry : pipelines.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getStatus());
        }
        return result;
    }

    @Scheduled(fixedDelayString = "${cdc.watchdog.interval.ms:60000}")
    public void checkAndRestartPipelines() {
        try {
            List<DataSourceConfig> activeConfigs = configService.getActiveConfigs();
            for (DataSourceConfig config : activeConfigs) {
                Long configId = config.getId();
                CdcPipeline pipeline = pipelines.get(configId);
                if (pipeline == null || !pipeline.isRunning()) {
                    if (!isCooldownExpired(configId)) {
                        continue;
                    }
                    try {
                        lastRestartAttempts.put(configId, LocalDateTime.now());
                        restartPipeline(configId);
                        log.warn("Watchdog 触发了配置 {} 的自动重启", configId);
                    } catch (Exception e) {
                        log.error("Watchdog 重启配置 {} 失败: {}", configId, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Watchdog 巡检失败: {}", e.getMessage(), e);
        }
    }

    public PipelineStatus getPipelineStatus(Long configId) {
        CdcPipeline pipeline = pipelines.get(configId);
        return pipeline == null ? null : pipeline.getStatus();
    }

    public synchronized void shutdownAll() {
        log.info("关闭所有 CDC 管道...");

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

    public int getActivePipelineCount() {
        return pipelines.size();
    }

    private boolean isCooldownExpired(Long configId) {
        LocalDateTime lastAttempt = lastRestartAttempts.get(configId);
        if (lastAttempt == null) {
            return true;
        }
        return Duration.between(lastAttempt, LocalDateTime.now()).compareTo(RESTART_COOLDOWN) >= 0;
    }

    private void ensureExecutorReady() {
        if (virtualThreadExecutor != null) {
            return;
        }
        virtualThreadExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cdc-vt-", 0).factory()
        );
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
        private final RocketMQProducerService rocketMQProducerService;
        private final AsyncEventSenderService asyncEventSenderService;
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
        private volatile String lastError = null;
        private volatile LocalDateTime lastProcessedTime = null;

        private CdcPipeline(DataSourceConfig config,
                            ExecutorService executor,
                            RocketMQProducerService rocketMQProducerService,
                            AsyncEventSenderService asyncEventSenderService,
                            ObjectMapper objectMapper,
                            String offsetJdbcUrl,
                            String offsetJdbcUser,
                            String offsetJdbcPassword,
                            int heartbeatIntervalMs,
                            String heartbeatActionQuery) {
            this.config = config;
            this.executor = executor;
            this.rocketMQProducerService = rocketMQProducerService;
            this.asyncEventSenderService = asyncEventSenderService;
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
                    log.error("配置 {} 的 CDC 管道运行异常: {}", config.getId(), e.getMessage(), e);
                } finally {
                    running = false;
                }
            });
        }

        public void stop() throws IOException {
            try {
                if (engine != null) {
                    engine.close();
                    if (engineFuture != null) {
                        try {
                            engineFuture.get(10, TimeUnit.SECONDS);
                        } catch (TimeoutException ignored) {
                            log.warn("等待配置 {} 的引擎线程退出超时", config.getId());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("关闭配置 {} 的 Debezium 引擎失败: {}", config.getId(), e.getMessage());
            } finally {
                running = false;
            }
        }

        private void handleChangeEvent(ChangeEvent<String, String> event) {
            try {
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
                String messageKey = extractPrimaryKey(value, event.key());

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
                log.error("配置 {} 处理变更事件失败: {}", config.getId(), e.getMessage(), e);
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
                var jsonNode = objectMapper.readTree(value);
                var source = jsonNode.get("source");
                if (source != null && source.get("lsn") != null) {
                    return source.get("lsn").asText();
                }
            } catch (Exception e) {
                log.warn("提取 LSN 失败: {}", e.getMessage());
            }
            return null;
        }

        private String extractPrimaryKey(String value, String key) {
            try {
                if (key != null && !key.isEmpty()) {
                    var keyNode = objectMapper.readTree(key);
                    if (keyNode.isObject()) {
                        var fields = keyNode.fields();
                        if (fields.hasNext()) {
                            return fields.next().getValue().asText();
                        }
                    } else {
                        return keyNode.asText();
                    }
                }

                var jsonNode = objectMapper.readTree(value);
                var after = jsonNode.get("after");
                if (after != null && after.has("id")) {
                    return after.get("id").asText();
                }
                var before = jsonNode.get("before");
                if (before != null && before.has("id")) {
                    return before.get("id").asText();
                }
            } catch (Exception e) {
                log.warn("提取主键失败: {}", e.getMessage());
            }
            return "UNKNOWN";
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

