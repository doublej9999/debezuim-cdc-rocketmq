package com.example.cdc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Debezium 引擎状态模型
 * 用于监控接口返回引擎运行状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngineStatus {

    /**
     * 引擎是否正在运行
     */
    private boolean running;

    /**
     * 当前 LSN (Log Sequence Number)
     */
    private String currentLsn;

    /**
     * 已处理的事件数量
     */
    private long processedEvents;

    /**
     * 引擎启动时间
     */
    private LocalDateTime startTime;

    /**
     * 运行时长（秒）
     */
    private Long uptimeSeconds;

    /**
     * RocketMQ 生产者状态
     */
    private boolean rocketmqProducerRunning;

    /**
     * 配置的数据库主机
     */
    private String databaseHost;

    /**
     * 配置的 RocketMQ NameServer
     */
    private String rocketmqNameServer;

    /**
     * 配置的 RocketMQ Topic
     */
    private String rocketmqTopic;
}
