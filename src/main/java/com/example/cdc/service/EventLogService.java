package com.example.cdc.service;

import com.example.cdc.dto.EventLogDTO;
import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.model.EventLog;
import com.example.cdc.repository.DataSourceConfigRepository;
import com.example.cdc.repository.EventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

    @Transactional
    public void markAsSent(Long eventId) {
        eventLogRepository.findById(eventId).ifPresent(eventLog -> {
            eventLog.setStatus(EventLog.EventStatus.SENT);
            eventLog.setSentAt(LocalDateTime.now());
            eventLogRepository.save(eventLog);
            log.debug("事件已发送 - EventId: {}", eventId);
        });
    }

    @Transactional
    public void markAsFailed(Long eventId, String errorMessage) {
        eventLogRepository.findById(eventId).ifPresent(eventLog -> {
            eventLog.setStatus(EventLog.EventStatus.FAILED);
            eventLog.setErrorMessage(errorMessage);
            eventLogRepository.save(eventLog);
            log.warn("事件发送失败 - EventId: {}, Error: {}", eventId, errorMessage);
        });
    }

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

    public Page<EventLog> getAllEvents(int page, int size) {
        return eventLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }

    public Page<EventLog> getEventsByConfigId(Long configId, int page, int size) {
        return eventLogRepository.findByConfigIdOrderByCreatedAtDesc(configId, PageRequest.of(page, size));
    }

    public Page<EventLog> getEventsByStatus(EventLog.EventStatus status, int page, int size) {
        return eventLogRepository.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(page, size));
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
            List<Long> configIds = dataSourceConfigRepository.findAll().stream()
                .filter(config -> config.getName().contains(keyword))
                .map(DataSourceConfig::getId)
                .toList();

            if (!configIds.isEmpty()) {
                return eventLogRepository.searchEventsByConfigIds(configIds, keyword, status, PageRequest.of(page, size));
            }
        }

        return eventLogRepository.searchEvents(keyword, status, PageRequest.of(page, size));
    }

    public List<EventLog> getPendingRetryEvents() {
        return eventLogRepository.findPendingRetryEvents();
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
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(daysToKeep);
        eventLogRepository.deleteByStatusAndCreatedAtBefore(EventLog.EventStatus.SENT, cutoffTime);
        log.info("清理 {} 之前的已发送事件", cutoffTime);
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
