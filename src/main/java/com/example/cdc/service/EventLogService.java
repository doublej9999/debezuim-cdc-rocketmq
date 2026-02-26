package com.example.cdc.service;

import com.example.cdc.model.EventLog;
import com.example.cdc.repository.EventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 事件日志服务
 * 管理 CDC 事件的日志记录、查询和清理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventLogService {

    private final EventLogRepository eventLogRepository;

    /**
     * 记录待发送事件
     */
    @Transactional
    public EventLog createEventLog(Long configId, String topic, String tag, String key, String body) {
        EventLog eventLog = EventLog.builder()
            .configId(configId)
            .topic(topic)
            .tag(tag)
            .messageKey(key)
            .messageBody(body)
            .status(EventLog.EventStatus.PENDING)
            .retryCount(0)
            .maxRetry(3)
            .build();

        EventLog saved = eventLogRepository.save(eventLog);
        log.debug("创建事件日志 - ConfigId: {}, Topic: {}, EventId: {}", configId, topic, saved.getId());
        return saved;
    }

    /**
     * 标记事件为已发送
     */
    @Transactional
    public void markAsSent(Long eventId) {
        eventLogRepository.findById(eventId).ifPresent(eventLog -> {
            eventLog.setStatus(EventLog.EventStatus.SENT);
            eventLog.setSentAt(LocalDateTime.now());
            eventLogRepository.save(eventLog);
            log.debug("事件已发送 - EventId: {}", eventId);
        });
    }

    /**
     * 标记事件为失败，并记录错误信息
     */
    @Transactional
    public void markAsFailed(Long eventId, String errorMessage) {
        eventLogRepository.findById(eventId).ifPresent(eventLog -> {
            eventLog.setStatus(EventLog.EventStatus.FAILED);
            eventLog.setErrorMessage(errorMessage);
            eventLogRepository.save(eventLog);
            log.warn("事件发送失败 - EventId: {}, Error: {}", eventId, errorMessage);
        });
    }

    /**
     * 标记事件为待重试
     */
    @Transactional
    public void markForRetry(Long eventId, String errorMessage) {
        eventLogRepository.findById(eventId).ifPresent(eventLog -> {
            if (eventLog.getRetryCount() < eventLog.getMaxRetry()) {
                eventLog.setStatus(EventLog.EventStatus.RETRY);
                eventLog.setRetryCount(eventLog.getRetryCount() + 1);
                eventLog.setErrorMessage(errorMessage);
                eventLogRepository.save(eventLog);
                log.info("事件标记为重试 - EventId: {}, 重试次数: {}/{}",
                    eventId, eventLog.getRetryCount(), eventLog.getMaxRetry());
            } else {
                markAsFailed(eventId, errorMessage + " (超过最大重试次数)");
            }
        });
    }

    /**
     * 查询所有事件日志（分页）
     */
    public Page<EventLog> getAllEvents(int page, int size) {
        return eventLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }

    /**
     * 查询指定配置的事件日志（分页）
     */
    public Page<EventLog> getEventsByConfigId(Long configId, int page, int size) {
        return eventLogRepository.findByConfigIdOrderByCreatedAtDesc(configId, PageRequest.of(page, size));
    }

    /**
     * 查询指定状态的事件日志（分页）
     */
    public Page<EventLog> getEventsByStatus(EventLog.EventStatus status, int page, int size) {
        return eventLogRepository.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(page, size));
    }

    /**
     * 查询待重试的事件
     */
    public List<EventLog> getPendingRetryEvents() {
        return eventLogRepository.findPendingRetryEvents();
    }

    /**
     * 获取事件统计信息
     */
    public Map<String, Long> getEventStatistics() {
        return eventLogRepository.countByStatus().stream()
            .collect(Collectors.toMap(
                arr -> ((EventLog.EventStatus) arr[0]).name(),
                arr -> (Long) arr[1]
            ));
    }

    /**
     * 清理指定时间之前的已发送事件（保留 7 天）
     */
    @Transactional
    public void cleanupOldEvents(int daysToKeep) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(daysToKeep);
        eventLogRepository.deleteByStatusAndCreatedAtBefore(EventLog.EventStatus.SENT, cutoffTime);
        log.info("清理了 {} 之前的已发送事件", cutoffTime);
    }
}
