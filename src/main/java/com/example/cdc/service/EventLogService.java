package com.example.cdc.service;

import com.example.cdc.dto.EventLogDTO;
import com.example.cdc.model.EventLog;
import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.repository.EventLogRepository;
import com.example.cdc.repository.DataSourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
    private final DataSourceConfigRepository dataSourceConfigRepository;

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
     * 搜索事件日志（支持 topic、tag、配置名称搜索）
     */
    public Page<EventLog> searchEvents(String keyword, String statusStr, int page, int size) {
        EventLog.EventStatus status = null;
        if (statusStr != null && !statusStr.isEmpty()) {
            try {
                status = EventLog.EventStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                log.warn("无效的状态值: {}", statusStr);
            }
        }

        // 如果有关键词，尝试通过配置名称查找配置ID
        if (keyword != null && !keyword.isEmpty()) {
            List<Long> configIds = dataSourceConfigRepository.findAll().stream()
                .filter(config -> config.getName().contains(keyword))
                .map(config -> config.getId())
                .collect(Collectors.toList());

            // 如果找到匹配的配置，搜索这些配置的事件
            if (!configIds.isEmpty()) {
                return eventLogRepository.searchEventsByConfigIds(configIds, keyword, status, PageRequest.of(page, size));
            }
        }

        // 否则只按 topic/tag 搜索
        return eventLogRepository.searchEvents(keyword, status, PageRequest.of(page, size));
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

    // ========== DTO 转换方法 ==========

    /**
     * 将 EventLog 转换为 EventLogDTO
     */
    private EventLogDTO convertToDTO(EventLog eventLog) {
        String configName = dataSourceConfigRepository.findById(eventLog.getConfigId())
            .map(DataSourceConfig::getName)
            .orElse("未知配置");

        return EventLogDTO.builder()
            .id(eventLog.getId())
            .configId(eventLog.getConfigId())
            .configName(configName)
            .topic(eventLog.getTopic())
            .tag(eventLog.getTag())
            .messageKey(eventLog.getMessageKey())
            .status(eventLog.getStatus())
            .retryCount(eventLog.getRetryCount())
            .maxRetry(eventLog.getMaxRetry())
            .errorMessage(eventLog.getErrorMessage())
            .createdAt(eventLog.getCreatedAt())
            .sentAt(eventLog.getSentAt())
            .build();
    }

    /**
     * 查询所有事件日志（分页，返回 DTO）
     */
    public Page<EventLogDTO> getAllEventsDTO(int page, int size) {
        return getAllEvents(page, size).map(this::convertToDTO);
    }

    /**
     * 查询指定配置的事件日志（分页，返回 DTO）
     */
    public Page<EventLogDTO> getEventsByConfigIdDTO(Long configId, int page, int size) {
        return getEventsByConfigId(configId, page, size).map(this::convertToDTO);
    }

    /**
     * 查询指定状态的事件日志（分页，返回 DTO）
     */
    public Page<EventLogDTO> getEventsByStatusDTO(EventLog.EventStatus status, int page, int size) {
        return getEventsByStatus(status, page, size).map(this::convertToDTO);
    }

    /**
     * 搜索事件日志（支持 topic、tag、配置名称搜索，返回 DTO）
     */
    public Page<EventLogDTO> searchEventsDTO(String keyword, String statusStr, int page, int size) {
        return searchEvents(keyword, statusStr, page, size).map(this::convertToDTO);
    }
}
