package com.example.cdc.service;

import com.example.cdc.dto.EventLogDTO;
import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.model.EventLog;
import com.example.cdc.repository.DataSourceConfigRepository;
import com.example.cdc.repository.EventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventLogService {

    private final EventLogRepository eventLogRepository;
    private final DataSourceConfigRepository dataSourceConfigRepository;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    @Value("${event.cleanup.days:30}")
    private int cleanupDays;

    @Transactional
    public EventLog createEventLog(Long configId, String topic, String tag, String key, String body,
                                   String namesrvAddr, String producerGroup, String lsn) {
        // 幂等检查：如果该 LSN 已存在，则不再创建且不抛出错误，静默跳过
        if (lsn != null && !lsn.isEmpty()) {
            boolean exists = eventLogRepository.existsByConfigIdAndLsn(configId, lsn);
            if (exists) {
                log.debug("事件已存在，命中幂等策略 - ConfigId: {}, LSN: {}", configId, lsn);
                return null;
            }
        }

        EventLog eventLog = EventLog.builder()
            .configId(configId)
            .topic(topic)
            .tag(tag)
            .messageKey(key)
            .messageBody(body)
            .namesrvAddr(namesrvAddr)
            .producerGroup(producerGroup)
            .lsn(lsn)
            .status(EventLog.EventStatus.PENDING)
            .retryCount(0)
            .maxRetry(3)
            .build();

        EventLog saved = eventLogRepository.save(eventLog);
        log.debug("创建事件日志 - ConfigId: {}, Topic: {}, EventId: {}", configId, topic, saved.getId());
        return saved;
    }

    @Transactional
    public void markAsSent(Long eventId) {
        int updated = eventLogRepository.markAsSent(eventId, LocalDateTime.now());
        if (updated > 0) {
            log.debug("事件已发送 - EventId: {}", eventId);
        }
    }

    @Transactional
    public void markAsFailed(Long eventId, String errorMessage) {
        int updated = eventLogRepository.markAsFailed(eventId, errorMessage);
        if (updated > 0) {
            log.warn("事件发送失败 - EventId: {}, Error: {}", eventId, errorMessage);
        }
    }

    @Transactional
    public void markForRetry(Long eventId, String errorMessage) {
        // 先获取当前重试次数来计算退避时间
        eventLogRepository.findById(eventId).ifPresent(eventLog -> {
            int nextRetryCount = eventLog.getRetryCount() + 1;
            // 指数退避：2^retryCount 分钟，最高 30 分钟
            long delayMinutes = Math.min(30, 1L << Math.min(nextRetryCount, 5));
            LocalDateTime nextRetryAt = LocalDateTime.now().plusMinutes(delayMinutes);

            int retryUpdated = eventLogRepository.markForRetry(eventId, errorMessage, nextRetryAt);
            if (retryUpdated > 0) {
                log.info("事件标记为重试 - EventId: {}, 重试次数: {}/{}, 下次重试: {}",
                    eventId, nextRetryCount, eventLog.getMaxRetry(), nextRetryAt);
                return;
            }
            markAsFailed(eventId, errorMessage + " (超过最大重试次数)");
        });
    }

    public Page<EventLog> getAllEvents(int page, int size) {
        return eventLogRepository.findAllByOrderByCreatedAtDesc(buildPageRequest(page, size));
    }

    public Page<EventLog> getEventsByConfigId(Long configId, int page, int size) {
        return eventLogRepository.findByConfigIdOrderByCreatedAtDesc(configId, buildPageRequest(page, size));
    }

    public Page<EventLog> getEventsByStatus(EventLog.EventStatus status, int page, int size) {
        return eventLogRepository.findByStatusOrderByCreatedAtDesc(status, buildPageRequest(page, size));
    }

    public Page<EventLog> searchEvents(String keyword, String statusStr, int page, int size) {
        EventLog.EventStatus status = null;
        if (statusStr != null && !statusStr.isEmpty()) {
            try {
                status = EventLog.EventStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                log.warn("无效的状态值: {}", statusStr);
            }
        }

        if (keyword != null && !keyword.isEmpty()) {
            List<Long> configIds = dataSourceConfigRepository.findIdsByNameContaining(keyword);

            if (!configIds.isEmpty()) {
                return eventLogRepository.searchEventsByConfigIds(configIds, keyword, status, buildPageRequest(page, size));
            }
        }

        return eventLogRepository.searchEvents(keyword, status, buildPageRequest(page, size));
    }

    public List<EventLog> getPendingRetryEvents() {
        return getPendingRetryEvents(200);
    }

    public List<EventLog> getPendingRetryEvents(int limit) {
        int safeLimit = Math.max(1, limit);
        return eventLogRepository.findPendingRetryEvents(PageRequest.of(0, safeLimit));
    }

    public Map<String, Long> getEventStatistics() {
        return eventLogRepository.countByStatus().stream()
            .collect(Collectors.toMap(
                arr -> ((EventLog.EventStatus) arr[0]).name(),
                arr -> (Long) arr[1]
            ));
    }

    @Transactional
    public void cleanupOldEvents(int daysToKeep) {
        if (daysToKeep < 1 || daysToKeep > 3650) {
            throw new IllegalArgumentException("daysToKeep must be between 1 and 3650");
        }
        LocalDateTime sentCutoff = LocalDateTime.now().minusDays(daysToKeep);
        eventLogRepository.deleteByStatusAndCreatedAtBefore(EventLog.EventStatus.SENT, sentCutoff);
        log.info("清理 {} 之前的已发送事件", sentCutoff);

        // 所有状态的事件在 2 倍保留天数后强制清理（防止 FAILED/RETRY 无限堆积）
        LocalDateTime allCutoff = LocalDateTime.now().minusDays(daysToKeep * 2L);
        eventLogRepository.deleteByCreatedAtBefore(allCutoff);
        log.info("清理 {} 之前的所有过期事件（包括 FAILED/RETRY）", allCutoff);
    }

    /**
     * 每天凌晨 2 点自动清理旧事件日志
     * - 已发送事件：保留 N 天（默认 3 天）
     * - 所有事件：保留 2N 天后强制清理
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void scheduledCleanup() {
        log.info("开始自动清理旧事件日志（已发送: {}天, 所有状态: {}天）...", cleanupDays, cleanupDays * 2);
        try {
            cleanupOldEvents(cleanupDays);
            log.info("自动清理完成");
        } catch (Exception e) {
            log.error("自动清理失败: {}", e.getMessage(), e);
        }
    }

    private EventLogDTO convertToDTO(EventLog eventLog, Map<Long, String> configNameMap) {
        String configName = configNameMap.getOrDefault(eventLog.getConfigId(), "未知配置");

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
            .lsn(eventLog.getLsn())
            .createdAt(eventLog.getCreatedAt())
            .sentAt(eventLog.getSentAt())
            .build();
    }

    public Page<EventLogDTO> getAllEventsDTO(int page, int size) {
        return mapToDTOPage(getAllEvents(page, size));
    }

    public Page<EventLogDTO> getEventsByConfigIdDTO(Long configId, int page, int size) {
        return mapToDTOPage(getEventsByConfigId(configId, page, size));
    }

    public Page<EventLogDTO> getEventsByStatusDTO(EventLog.EventStatus status, int page, int size) {
        return mapToDTOPage(getEventsByStatus(status, page, size));
    }

    public Page<EventLogDTO> searchEventsDTO(String keyword, String statusStr, int page, int size) {
        return mapToDTOPage(searchEvents(keyword, statusStr, page, size));
    }

    private PageRequest buildPageRequest(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize);
    }

    private Page<EventLogDTO> mapToDTOPage(Page<EventLog> events) {
        Set<Long> configIds = events.getContent().stream()
            .map(EventLog::getConfigId)
            .collect(Collectors.toSet());

        Map<Long, String> configNameMap = dataSourceConfigRepository.findByIdIn(configIds).stream()
            .collect(Collectors.toMap(DataSourceConfig::getId, DataSourceConfig::getName));

        List<EventLogDTO> content = events.getContent().stream()
            .map(event -> convertToDTO(event, configNameMap))
            .toList();

        return new PageImpl<>(content, events.getPageable(), events.getTotalElements());
    }
}
