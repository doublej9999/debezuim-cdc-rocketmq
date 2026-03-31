package com.example.cdc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 配置类
 * 从 application.yml 读取 RocketMQ 相关配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rocketmq")
public class RocketMQConfig {

    /**
     * NameServer 地址
     */
    private String namesrvAddr;

    /**
     * 生产者组名
     */
    private String producerGroup;

    /**
     * 目标 Topic
     */
    private String topic;

    /**
     * 发送消息超时时间（毫秒）
     */
    private Integer sendMsgTimeout = 3000;

    /**
     * 发送失败时的重试次数
     */
    private Integer retryTimesWhenSendFailed = 2;

    /**
     * 最大消息大小（字节）
     */
    private Integer maxMessageSize = 4194304;

    /**
     * 是否启用有序发送（相同 key 进入同一队列）
     */
    private Boolean orderlyEnabled = true;
}
