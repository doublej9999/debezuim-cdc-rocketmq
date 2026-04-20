package com.example.cdc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 全量快照任务记录
 *
 * 每次定时或手动触发的全量快照都会对应一条记录，用于：
 * 1. 追踪快照执行进度（totalRows / processedRows）
 * 2. 存储快照启动前读取的 snapshotLsn，供下游消费端做 _ALL + 增量对齐
 * 3. 提供快照历史查询，方便运维排查
 */
@Entity
@Table(name = "snapshot_job", indexes = {
    @Index(name = "idx_snapshot_config_id", columnList = "config_id"),
    @Index(name = "idx_snapshot_status", columnList = "status"),
    @Index(name = "idx_snapshot_batch_id", columnList = "batch_id", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的数据源配置 ID
     */
    @Column(name = "config_id", nullable = false)
    private Long configId;

    /**
     * 本次快照的唯一批次 ID，格式：{configId}-{timestamp}
     * 同时也写入 RocketMQ 消息的 Header（X-Snapshot-Batch-Id），供消费端识别同一批次
     */
    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    /**
     * 快照启动前，从源库读取的当前 WAL LSN（PostgreSQL pg_current_wal_lsn()）
     * 消费端消费完 _ALL 后，应从增量 Topic 中跳过 lsn <= snapshotLsn 的事件
     */
    @Column(name = "snapshot_lsn", length = 128)
    private String snapshotLsn;

    /**
     * 快照目标 Topic（{rocketmqTopic}_ALL）
     */
    @Column(name = "topic", length = 255)
    private String topic;

    /**
     * 快照状态
     */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    /**
     * 表的总行数（快照开始前 COUNT(*) 估算）
     */
    @Column(name = "total_rows")
    private Long totalRows;

    /**
     * 已推送至 RocketMQ 的行数
     */
    @Column(name = "processed_rows")
    @Builder.Default
    private Long processedRows = 0L;

    /**
     * 错误信息（FAILED 状态时填写）
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * 快照任务开始时间
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /**
     * 快照任务完成时间
     */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /**
     * 快照任务状态枚举
     */
    public enum JobStatus {
        PENDING,   // 待执行（已创建，尚未开始）
        RUNNING,   // 执行中
        DONE,      // 成功完成
        FAILED     // 执行失败
    }
}
