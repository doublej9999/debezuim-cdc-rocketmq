package com.example.cdc.service;

import com.example.cdc.config.RocketMQConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Debezium 变更事件处理器
 * 负责处理 Debezium 捕获的数据库变更事件，并推送到 RocketMQ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeEventHandler {

    private final RocketMQProducerService rocketMQProducerService;
    private final RocketMQConfig rocketMQConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<String> currentLsn = new AtomicReference<>("N/A");
    private final AtomicLong processedEventCount = new AtomicLong(0);

    /**
     * 处理 Debezium 变更事件
     *
     * @param event Debezium 变更事件
     */
    public void handleChangeEvent(ChangeEvent<String, String> event) {
        try {
            String key = event.key();
            String value = event.value();

            // 跳过空值事件
            if (value == null) {
                log.debug("跳过空值事件");
                return;
            }

            // 解析事件获取表名和主键
            String tableName = extractTableName(value);
            String primaryKey = extractPrimaryKey(value, key);

            // 提取 LSN
            String lsn = extractLsn(value);
            if (lsn != null) {
                currentLsn.set(lsn);
            }

            // 构造 RocketMQ 消息
            // Tag: 表名
            // Key: 主键
            // Body: Debezium 原始 JSON（不做任何处理）
            String topic = rocketMQConfig.getTopic();
            String tag = tableName != null ? tableName : "UNKNOWN";
            String messageKey = primaryKey != null ? primaryKey : "UNKNOWN";

            // 发送到 RocketMQ
            rocketMQProducerService.sendMessage(topic, tag, messageKey, value);

            // 更新统计
            long count = processedEventCount.incrementAndGet();

            log.info("处理变更事件 #{} - Table: {}, Key: {}, LSN: {}",
                count, tableName, primaryKey, lsn);

        } catch (Exception e) {
            log.error("处理变更事件失败: {}", e.getMessage(), e);
            throw new RuntimeException("变更事件处理失败", e);
        }
    }

    /**
     * 从 Debezium 事件中提取表名
     */
    private String extractTableName(String value) {
        try {
            JsonNode jsonNode = objectMapper.readTree(value);
            JsonNode source = jsonNode.get("source");
            if (source != null) {
                JsonNode table = source.get("table");
                if (table != null) {
                    return table.asText();
                }
            }
        } catch (Exception e) {
            log.warn("提取表名失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 Debezium 事件中提取主键
     */
    private String extractPrimaryKey(String value, String key) {
        try {
            // 优先使用 Debezium 的 key
            if (key != null && !key.isEmpty()) {
                JsonNode keyNode = objectMapper.readTree(key);
                // 如果 key 是对象，提取第一个字段的值
                if (keyNode.isObject()) {
                    var fields = keyNode.fields();
                    if (fields.hasNext()) {
                        return fields.next().getValue().asText();
                    }
                } else {
                    return keyNode.asText();
                }
            }

            // 备选方案：从 after 或 before 中提取 id 字段
            JsonNode jsonNode = objectMapper.readTree(value);
            JsonNode after = jsonNode.get("after");
            if (after != null && after.has("id")) {
                return after.get("id").asText();
            }

            JsonNode before = jsonNode.get("before");
            if (before != null && before.has("id")) {
                return before.get("id").asText();
            }

        } catch (Exception e) {
            log.warn("提取主键失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 Debezium 事件中提取 LSN (Log Sequence Number)
     */
    private String extractLsn(String value) {
        try {
            JsonNode jsonNode = objectMapper.readTree(value);
            JsonNode source = jsonNode.get("source");
            if (source != null) {
                JsonNode lsn = source.get("lsn");
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
     * 获取当前 LSN
     */
    public String getCurrentLsn() {
        return currentLsn.get();
    }

    /**
     * 获取已处理的事件数量
     */
    public long getProcessedEventCount() {
        return processedEventCount.get();
    }
}
