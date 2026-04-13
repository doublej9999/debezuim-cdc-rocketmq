package com.example.cdc.repository;

import com.example.cdc.model.EventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EventLogRepository extends JpaRepository<EventLog, Long> {

    Page<EventLog> findByConfigIdOrderByCreatedAtDesc(Long configId, Pageable pageable);

    boolean existsByConfigIdAndLsn(Long configId, String lsn);

    Page<EventLog> findByStatusOrderByCreatedAtDesc(EventLog.EventStatus status, Pageable pageable);

    Page<EventLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
        SELECT e FROM EventLog e
        WHERE (e.status = 'PENDING' OR e.status = 'RETRY')
          AND e.retryCount < e.maxRetry
          AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= CURRENT_TIMESTAMP)
        ORDER BY e.createdAt ASC
        """)
    List<EventLog> findPendingRetryEvents(Pageable pageable);

    @Query("""
        SELECT e FROM EventLog e
        WHERE e.status = 'FAILED' OR e.status = 'RETRY'
        ORDER BY e.createdAt ASC
        """)
    List<EventLog> findManualRetryEvents(Pageable pageable);

    @Query("SELECT e.status, COUNT(e) FROM EventLog e GROUP BY e.status")
    List<Object[]> countByStatus();

    @Query("""
        SELECT e FROM EventLog e
        WHERE (:keyword IS NULL OR e.topic LIKE %:keyword% OR e.tag LIKE %:keyword%)
          AND (:status IS NULL OR e.status = :status)
        ORDER BY e.createdAt DESC
        """)
    Page<EventLog> searchEvents(@Param("keyword") String keyword,
                                @Param("status") EventLog.EventStatus status,
                                Pageable pageable);

    @Query("""
        SELECT e FROM EventLog e
        WHERE (e.configId IN :configIds OR :keyword IS NULL OR e.topic LIKE %:keyword% OR e.tag LIKE %:keyword%)
          AND (:status IS NULL OR e.status = :status)
        ORDER BY e.createdAt DESC
        """)
    Page<EventLog> searchEventsByConfigIds(@Param("configIds") List<Long> configIds,
                                           @Param("keyword") String keyword,
                                           @Param("status") EventLog.EventStatus status,
                                           Pageable pageable);

    void deleteByStatusAndCreatedAtBefore(EventLog.EventStatus status, LocalDateTime before);

    void deleteByCreatedAtBefore(LocalDateTime before);

    @Modifying
    @Query("update EventLog e set e.status = 'SENT', e.sentAt = :sentAt, e.nextRetryAt = null where e.id = :eventId")
    int markAsSent(@Param("eventId") Long eventId, @Param("sentAt") LocalDateTime sentAt);

    @Modifying
    @Query("update EventLog e set e.status = 'FAILED', e.errorMessage = :errorMessage, e.nextRetryAt = null where e.id = :eventId")
    int markAsFailed(@Param("eventId") Long eventId, @Param("errorMessage") String errorMessage);

    /**
     * 核心逻辑：标记事件为重试状态并更新配置。
     * 
     * 当事件发送失败时调用此方法。包含以下核心机制：
     * 1. 状态跃迁：如果重试次数未达到最大值，状态更新为 RETRY；若已达最大值，则更新为 FAILED，不再自动重试。
     * 2. 路由更新：更新该事件对应的 MQ 路由信息（topic/tag/namesrvAddr/producerGroup），
     *    确保如果在重试期间用户修改了配置，能使用最新的配置进行重试。
     * 3. 并发安全：通过校验 status <> 'SENT' 避免修改已成功发送的记录。
     */
    @Modifying
    @Query("""
        update EventLog e
           set e.status =
                   CASE WHEN e.retryCount + 1 >= e.maxRetry THEN 'FAILED' ELSE 'RETRY' END,
               e.retryCount = e.retryCount + 1,
               e.errorMessage = :errorMessage,
               e.nextRetryAt =
                   CASE WHEN e.retryCount + 1 >= e.maxRetry THEN null ELSE :nextRetryAt END,
               e.topic = :topic,
               e.tag = :tag,
               e.namesrvAddr = :namesrvAddr,
               e.producerGroup = :producerGroup
         where e.id = :eventId
           and e.status <> 'SENT'
        """)
    int markForRetry(@Param("eventId") Long eventId,
                     @Param("errorMessage") String errorMessage,
                     @Param("nextRetryAt") LocalDateTime nextRetryAt,
                     @Param("topic") String topic,
                     @Param("tag") String tag,
                     @Param("namesrvAddr") String namesrvAddr,
                     @Param("producerGroup") String producerGroup);

    /**
     * 手动重试核心逻辑：重置事件状态。
     * 将 FAILED 或处于 RETRY 状态的事件强制还原为 PENDING，并清空重试计数器，使得发送服务能够重新处理。
     */
    @Modifying
    @Query("""
        update EventLog e
           set e.status = 'PENDING',
               e.retryCount = 0,
               e.nextRetryAt = null
         where e.id = :eventId
           and (e.status = 'FAILED' or e.status = 'RETRY')
        """)
    int resetForManualRetry(@Param("eventId") Long eventId);
}

