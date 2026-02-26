package com.example.cdc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 事件日志实体
 * 记录所有 CDC 事件的处理状态和详情
 */
@Entity
@Table(name = "event_log", indexes = {
    @Index(name = "idx_config_id", columnList = "config_id"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的数据源配置 ID
     */
    @Column(name = "config_id", nullable = false)
    private Long configId;

    /**
     * RocketMQ Topic
     */
    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    /**
     * RocketMQ Tag
     */
    @Column(name = "tag", length = 255)
    private String tag;

    /**
     * 消息 Key（通常是主键）
     */
    @Column(name = "message_key", length = 255)
    private String messageKey;

    /**
     * 消息体（JSON 格式）
     */
    @Column(name = "message_body", columnDefinition = "TEXT")
    private String messageBody;

    /**
     * 事件状态：PENDING（待发送）、SENT（已发送）、FAILED（失败）、RETRY（重试中）
     */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private EventStatus status;

    /**
     * 重试次数
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 最大重试次数
     */
    @Column(name = "max_retry", nullable = false)
    @Builder.Default
    private Integer maxRetry = 3;

    /**
     * 错误信息
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 发送时间
     */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 事件状态枚举
     */
    public enum EventStatus {
        PENDING,   // 待发送
        SENT,      // 已发送
        FAILED,    // 失败
        RETRY      // 重试中
    }
}
