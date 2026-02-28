package com.example.cdc.controller;

import com.example.cdc.dto.EventLogDTO;
import com.example.cdc.model.EventLog;
import com.example.cdc.service.EventLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 事件日志查询 API
 * 提供事件日志的查询、统计和管理接口
 */
@RestController
@RequestMapping("/api/event-log")
@RequiredArgsConstructor
public class EventLogController {

    private final EventLogService eventLogService;

    /**
     * 查询所有事件日志（分页）
     */
    @GetMapping
    public ResponseEntity<Page<EventLogDTO>> getAllEvents(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(eventLogService.getAllEventsDTO(page, size));
    }

    /**
     * 搜索事件日志（支持 topic、tag、配置名称搜索）
     */
    @GetMapping("/search")
    public ResponseEntity<Page<EventLogDTO>> searchEvents(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(eventLogService.searchEventsDTO(keyword, status, page, size));
    }

    /**
     * 根据配置 ID 查询事件日志（分页）
     */
    @GetMapping("/config/{configId}")
    public ResponseEntity<Page<EventLogDTO>> getEventsByConfigId(
        @PathVariable Long configId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(eventLogService.getEventsByConfigIdDTO(configId, page, size));
    }

    /**
     * 根据状态查询事件日志（分页）
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<Page<EventLogDTO>> getEventsByStatus(
        @PathVariable EventLog.EventStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(eventLogService.getEventsByStatusDTO(status, page, size));
    }

    /**
     * 获取事件统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Long>> getStatistics() {
        return ResponseEntity.ok(eventLogService.getEventStatistics());
    }

    /**
     * 手动触发重试失败的事件
     */
    @PostMapping("/retry")
    public ResponseEntity<String> retryFailedEvents() {
        try {
            int count = eventLogService.getPendingOrRetryEvents().size();
            return ResponseEntity.ok("已触发重试，待发送/重试事件数: " + count);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("触发重试失败: " + e.getMessage());
        }
    }

    /**
     * 清理历史事件（保留最近 7 天）
     */
    @DeleteMapping("/cleanup")
    public ResponseEntity<String> cleanupOldEvents(
        @RequestParam(defaultValue = "7") int daysToKeep
    ) {
        try {
            eventLogService.cleanupOldEvents(daysToKeep);
            return ResponseEntity.ok("历史事件清理完成");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("清理失败: " + e.getMessage());
        }
    }
}
