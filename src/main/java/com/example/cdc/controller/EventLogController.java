package com.example.cdc.controller;

import com.example.cdc.dto.EventLogDTO;
import com.example.cdc.model.EventLog;
import com.example.cdc.service.AsyncEventSenderService;
import com.example.cdc.service.EventLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/event-log")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EventLogController {

    private final EventLogService eventLogService;
    private final AsyncEventSenderService asyncEventSenderService;

    @GetMapping
    public ResponseEntity<Page<EventLogDTO>> getAllEvents(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(eventLogService.getAllEventsDTO(page, size));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<EventLogDTO>> searchEvents(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(eventLogService.searchEventsDTO(keyword, status, page, size));
    }

    @GetMapping("/config/{configId}")
    public ResponseEntity<Page<EventLogDTO>> getEventsByConfigId(
        @PathVariable Long configId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(eventLogService.getEventsByConfigIdDTO(configId, page, size));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<Page<EventLogDTO>> getEventsByStatus(
        @PathVariable EventLog.EventStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(eventLogService.getEventsByStatusDTO(status, page, size));
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Long>> getStatistics() {
        return ResponseEntity.ok(eventLogService.getEventStatistics());
    }

    @PostMapping("/retry")
    public ResponseEntity<String> retryFailedEvents() {
        try {
            int processed = asyncEventSenderService.triggerRetryNow();
            return ResponseEntity.ok("已触发重试，本次处理事件数: " + processed);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("触发重试失败: " + e.getMessage());
        }
    }

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
