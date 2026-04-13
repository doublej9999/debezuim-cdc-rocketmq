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

