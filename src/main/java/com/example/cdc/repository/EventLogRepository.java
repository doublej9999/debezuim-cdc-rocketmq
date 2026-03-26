package com.example.cdc.repository;

import com.example.cdc.model.EventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件日志 Repository
 */
@Repository
public interface EventLogRepository extends JpaRepository<EventLog, Long> {

    /**
     * 根据配置 ID 查询事件日志（分页）
     */
    Page<EventLog> findByConfigIdOrderByCreatedAtDesc(Long configId, Pageable pageable);

    /**
     * 根据状态查询事件日志（分页）
     */
    Page<EventLog> findByStatusOrderByCreatedAtDesc(EventLog.EventStatus status, Pageable pageable);

    /**
     * 查询所有事件日志（分页）
     */
    Page<EventLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 查询待重试的事件（状态为 PENDING 或 RETRY，且重试次数未达上限）
     */
    @Query("SELECT e FROM EventLog e WHERE (e.status = 'PENDING' OR e.status = 'RETRY') AND e.retryCount < e.maxRetry AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= CURRENT_TIMESTAMP) ORDER BY e.createdAt ASC")
    List<EventLog> findPendingRetryEvents();

    /**
     * 统计各状态的事件数量
     */
    @Query("SELECT e.status, COUNT(e) FROM EventLog e GROUP BY e.status")
    List<Object[]> countByStatus();

    /**
     * 搜索事件日志（支持 topic、tag 搜索）
     */
    @Query("SELECT e FROM EventLog e WHERE " +
           "(:keyword IS NULL OR e.topic LIKE %:keyword% OR e.tag LIKE %:keyword%) AND " +
           "(:status IS NULL OR e.status = :status) " +
           "ORDER BY e.createdAt DESC")
    Page<EventLog> searchEvents(String keyword, EventLog.EventStatus status, Pageable pageable);

    /**
     * 搜索事件日志（支持配置ID、topic、tag 搜索）
     */
    @Query("SELECT e FROM EventLog e WHERE " +
           "(e.configId IN :configIds OR :keyword IS NULL OR e.topic LIKE %:keyword% OR e.tag LIKE %:keyword%) AND " +
           "(:status IS NULL OR e.status = :status) " +
           "ORDER BY e.createdAt DESC")
    Page<EventLog> searchEventsByConfigIds(List<Long> configIds, String keyword, EventLog.EventStatus status, Pageable pageable);

    /**
     * 删除指定时间之前的已发送事件（清理历史数据）
     */
    void deleteByStatusAndCreatedAtBefore(EventLog.EventStatus status, LocalDateTime before);
}
