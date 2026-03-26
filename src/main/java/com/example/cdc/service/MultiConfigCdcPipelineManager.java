package com.example.cdc.service;

import com.example.cdc.model.DataSourceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多配置 CDC 管道管理器
 * 为每个数据源配置创建独立的 Debezium 引擎，支持并发处理多个数据源
 */
@Slf4j
@Service
public class MultiConfigCdcPipelineManager {

    private final RocketMQProducerService rocketMQProducerService;
    private final DataSourceConfigService configService;
    private final AsyncEventSenderService asyncEventSenderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 存储每个配置对应的 CDC 管道
    private final Map<Long, CdcPipeline> pipelines = new ConcurrentHashMap<>();

    // 虚拟线程执行器
    private ExecutorService virtualThreadExecutor;

    public MultiConfigCdcPipelineManager(RocketMQProducerService rocketMQProducerService,
                                         DataSourceConfigService configService,
                                         AsyncEventSenderService asyncEventSenderService) {
        this.rocketMQProducerService = rocketMQProducerService;
        this.configService = configService;
        this.asyncEventSenderService = asyncEventSenderService;
    }

    /**
     * 初始化所有活跃配置的 CDC 管道
     */
    public void initializeActivePipelines() {
        log.info("初始化所有活跃的 CDC 管道...");

        try {
            // 创建虚拟线程执行器（命名便于排障）
            virtualThreadExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cdc-vt-", 0).factory()
            );

            // 获取所有活跃配置
            List<DataSourceConfig> activeConfigs = configService.getActiveConfigs();
            log.info("发现 {} 个活跃配置", activeConfigs.size());

            // 为每个活跃配置启动 CDC 管道
            for (DataSourceConfig config : activeConfigs) {
                startPipeline(config);
            }

            log.info("所有活跃 CDC 管道已初始化");

        } catch (Exception e) {
            log.error("初始化 CDC 管道失败: {}", e.getMessage(), e);
            throw new RuntimeException("初始化 CDC 管道失败", e);
        }
    }

    /**
     * 为指定配置启动 CDC 管道
     */
    public synchronized void startPipeline(DataSourceConfig config) {
        if (config == null || !config.getIsActive()) {
            log.warn("配置无效或未激活: {}", config != null ? config.getId() : "null");
            return;
        }

        Long configId = config.getId();

        // 检查是否已存在
        if (pipelines.containsKey(configId)) {
            log.warn("配置 {} 的管道已存在，跳过启动", configId);
            return;
        }

        try {
            log.info("启动配置 {} 的 CDC 管道: {}", configId, config.getName());

            // 创建 CDC 管道
            CdcPipeline pipeline = new CdcPipeline(config, virtualThreadExecutor,
                                                   rocketMQProducerService, asyncEventSenderService, objectMapper);

            // 启动管道
            pipeline.start();

            // 保存到映射
            pipelines.put(configId, pipeline);

            log.info("配置 {} 的 CDC 管道已启动", configId);

        } catch (Exception e) {
            log.error("启动配置 {} 的 CDC 管道失败: {}", configId, e.getMessage(), e);
            throw new RuntimeException("启动 CDC 管道失败", e);
        }
    }

    /**
     * 停止指定配置的 CDC 管道
     */
    public synchronized void stopPipeline(Long configId) {
        CdcPipeline pipeline = pipelines.get(configId);
        if (pipeline == null) {
            log.warn("配置 {} 的管道不存在", configId);
            return;
        }

        try {
            log.info("停止配置 {} 的 CDC 管道", configId);
            pipeline.stop();
            pipelines.remove(configId);
            log.info("配置 {} 的 CDC 管道已停止", configId);
        } catch (Exception e) {
            log.error("停止配置 {} 的 CDC 管道失败: {}", configId, e.getMessage(), e);
        }
    }

    /**
     * 重启指定配置的 CDC 管道
     */
    public synchronized void restartPipeline(Long configId) {
        stopPipeline(configId);

        DataSourceConfig config = configService.getConfigById(configId)
                .orElseThrow(() -> new RuntimeException("配置不存在: " + configId));

        startPipeline(config);
    }

    /**
     * 获取所有活跃管道的状态
     */
    public Map<Long, PipelineStatus> getAllPipelineStatus() {
        Map<Long, PipelineStatus> statusMap = new LinkedHashMap<>();

        for (Map.Entry<Long, CdcPipeline> entry : pipelines.entrySet()) {
            Long configId = entry.getKey();
            CdcPipeline pipeline = entry.getValue();
            statusMap.put(configId, pipeline.getStatus());
        }

        return statusMap;
    }

    /**
     * 获取指定配置的管道状态
     */
    public PipelineStatus getPipelineStatus(Long configId) {
        CdcPipeline pipeline = pipelines.get(configId);
        if (pipeline == null) {
            return null;
        }
        return pipeline.getStatus();
    }

    /**
     * 关闭所有 CDC 管道
     */
    public synchronized void shutdownAll() {
        log.info("关闭所有 CDC 管道...");

        // 停止所有管道
        for (Long configId : new ArrayList<>(pipelines.keySet())) {
            try {
                stopPipeline(configId);
            } catch (Exception e) {
                log.error("停止配置 {} 的管道时出错: {}", configId, e.getMessage());
            }
        }

        // 关闭虚拟线程执行器
        if (virtualThreadExecutor != null) {
            try {
                virtualThreadExecutor.shutdown();
                if (!virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("虚拟线程执行器未能在 30 秒内关闭，强制关闭");
                    virtualThreadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("等待虚拟线程执行器关闭时被中断: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }
        }

        log.info("所有 CDC 管道已关闭");
    }

    /**
     * 获取活跃管道数量
     */
    public int getActivePipelineCount() {
        return pipelines.size();
    }

    /**
     * CDC 管道状态
     */
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
    }

    /**
     * 单个 CDC 管道
     */
    private static class CdcPipeline {
        private final DataSourceConfig config;
        private final ExecutorService executor;
        private final RocketMQProducerService rocketMQProducerService;
        private final AsyncEventSenderService asyncEventSenderService;
        private final ObjectMapper objectMapper;

        private DebeziumEngine<ChangeEvent<String, String>> engine;
        private LocalDateTime startTime;
        private final AtomicLong processedEventCount = new AtomicLong(0);
        private volatile String currentLsn = "N/A";
        private volatile boolean running = false;

        public CdcPipeline(DataSourceConfig config, ExecutorService executor,
                          RocketMQProducerService rocketMQProducerService,
                          AsyncEventSenderService asyncEventSenderService,
                          ObjectMapper objectMapper) {
            this.config = config;
            this.executor = executor;
            this.rocketMQProducerService = rocketMQProducerService;
            this.asyncEventSenderService = asyncEventSenderService;
            this.objectMapper = objectMapper;
        }

        /**
         * 启动 CDC 管道
         */
        public void start() throws Exception {
            log.info("启动 CDC 管道: 配置ID={}, 名称={}, 数据库={}, 表={}",
                config.getId(), config.getName(), config.getDbName(), config.getTableName());

            // 构建 Debezium 配置
            Properties props = buildDebeziumProperties();

            // 创建 Debezium Engine
            engine = DebeziumEngine.create(Json.class)
                .using(props)
                .notifying(this::handleChangeEvent)
                .using((success, message, error) -> {
                    if (success) {
                        log.info("配置 {} 的 Debezium 引擎完成: {}", config.getId(), message);
                    } else {
                        log.error("配置 {} 的 Debezium 引擎错误: {}", config.getId(), message, error);
                    }
                })
                .build();

            // 在虚拟线程中异步启动引擎
            executor.submit(() -> {
                try {
                    running = true;
                    startTime = LocalDateTime.now();
                    log.info("配置 {} 的 CDC 管道在虚拟线程中启动", config.getId());
                    engine.run();
                } catch (Exception e) {
                    log.error("配置 {} 的 CDC 管道运行异常: {}", config.getId(), e.getMessage(), e);
                    running = false;
                } finally {
                    log.info("配置 {} 的 CDC 管道已停止", config.getId());
                    running = false;
                }
            });

            log.info("配置 {} 的 CDC 管道已在虚拟线程中启动", config.getId());
        }

        /**
         * 停止 CDC 管道
         */
        public void stop() throws IOException {
            log.info("停止配置 {} 的 CDC 管道", config.getId());

            try {
                if (engine != null) {
                    // 给引擎一些时间来优雅关闭
                    engine.close();
                    log.info("配置 {} 的 Debezium 引擎已关闭", config.getId());
                }
            } catch (Exception e) {
                log.warn("关闭 Debezium 引擎时出错: {}", e.getMessage());
            }

            running = false;
            log.info("配置 {} 的 CDC 管道已停止", config.getId());
        }

        /**
         * 处理变更事件
         * 将事件加入异步队列，不直接发送到 RocketMQ
         * 这样可以防止 RocketMQ 连接问题导致 Debezium 引擎停止
         */
        private void handleChangeEvent(ChangeEvent<String, String> event) {
            try {
                String value = event.value();
                if (value == null) {
                    return;
                }

                // 提取 LSN
                String lsn = extractLsn(value);
                if (lsn != null) {
                    currentLsn = lsn;
                }

                // 将事件加入异步队列（非阻塞）
                String topic = config.getRocketmqTopic();
                String tag = config.getRocketmqTag() != null ? config.getRocketmqTag() : config.getTableName();
                String messageKey = extractPrimaryKey(value, event.key());

                asyncEventSenderService.enqueueEvent(topic, tag, messageKey, value, config.getId());

                long count = processedEventCount.incrementAndGet();
                log.debug("配置 {} 处理变更事件 #{} - LSN: {}", config.getId(), count, lsn);

            } catch (Exception e) {
                log.error("配置 {} 处理变更事件失败: {}", config.getId(), e.getMessage(), e);
            }
        }

        /**
         * 构建 Debezium 配置
         */
        private Properties buildDebeziumProperties() {
            Properties props = new Properties();

            // 创建 offsets 目录（如果不存在）
            java.io.File offsetsDir = new java.io.File("./offsets");
            if (!offsetsDir.exists()) {
                offsetsDir.mkdirs();
                log.info("创建 offsets 目录: {}", offsetsDir.getAbsolutePath());
            }

            // 基础配置
            props.setProperty("name", "debezium-" + config.getId());
            props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
            props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
            props.setProperty("offset.storage.file.filename", "./offsets/offset-" + config.getId() + ".dat");
            props.setProperty("offset.flush.interval.ms", "2000");
            props.setProperty("topic.prefix", "dbserver-" + config.getId());
            props.setProperty("key.converter.schemas.enable", "false");
            props.setProperty("value.converter.schemas.enable", "false");

            // 数据库连接配置
            props.setProperty("database.hostname", config.getDbHostname());
            props.setProperty("database.port", config.getDbPort().toString());
            props.setProperty("database.user", config.getDbUser());
            props.setProperty("database.password", config.getDbPassword());
            props.setProperty("database.dbname", config.getDbName());
            props.setProperty("database.server.name", "dbserver-" + config.getId());

            // 监听配置
            props.setProperty("schema.include.list", config.getSchemaName());
            props.setProperty("table.include.list", config.getSchemaName() + "." + config.getTableName());

            // PostgreSQL 特定配置（仅保留 Debezium Postgres 官方支持项）
            props.setProperty("plugin.name", "pgoutput");
            props.setProperty("slot.name", "debezium_slot_" + config.getId());
            props.setProperty("publication.name", "debezium_publication_" + config.getId());

            // 允许重用 replication slot，避免 stop 时删除造成全量重放
            props.setProperty("slot.drop.on.stop", "false");

            // Snapshot 配置：有 offset 时自动跳过快照
            props.setProperty("snapshot.mode", "when_needed");
            props.setProperty("snapshot.delay.ms", "5000");
            props.setProperty("snapshot.fetch.size", "2048");

            // Schema History - 同样放到 offsets 目录
            props.setProperty("schema.history.internal", "io.debezium.relational.history.FileDatabaseHistory");
            props.setProperty("schema.history.internal.file.filename", "./offsets/schema-history-" + config.getId() + ".dat");

            // 性能优化
            props.setProperty("max.batch.size", "2048");
            props.setProperty("max.queue.size", "8192");
            props.setProperty("poll.interval.ms", "1000");

            // 连接超时优化
            props.setProperty("database.connect.timeout.ms", "30000");
            props.setProperty("database.statement.timeout.ms", "30000");


            return props;
        }

        /**
         * 提取 LSN
         */
        private String extractLsn(String value) {
            try {
                var jsonNode = objectMapper.readTree(value);
                var source = jsonNode.get("source");
                if (source != null) {
                    var lsn = source.get("lsn");
                    if (lsn != null) {
                        return lsn.asText();
                    }
                }
            } catch (Exception e) {
                log.warn("提取 LSN 失败: {}", e.getMessage());
            }
            return null;
        }

        /**
         * 提取主键
         */
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

        /**
         * 获取管道状态
         */
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
            return status;
        }
    }
}
