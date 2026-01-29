package com.example.cdc.controller;

import com.example.cdc.config.DebeziumConfig;
import com.example.cdc.config.RocketMQConfig;
import com.example.cdc.model.EngineStatus;
import com.example.cdc.service.DebeziumEngineService;
import com.example.cdc.service.RocketMQProducerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 监控接口控制器
 * 提供引擎状态、LSN 和健康检查接口
 */
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MonitorController {

    private final DebeziumEngineService debeziumEngineService;
    private final RocketMQProducerService rocketMQProducerService;
    private final DebeziumConfig debeziumConfig;
    private final RocketMQConfig rocketMQConfig;

    /**
     * 获取引擎完整状态
     * GET /api/monitor/status
     */
    @GetMapping("/status")
    public ResponseEntity<EngineStatus> getStatus() {
        boolean isRunning = debeziumEngineService.isRunning();
        LocalDateTime startTime = debeziumEngineService.getStartTime();

        Long uptimeSeconds = null;
        if (isRunning && startTime != null) {
            uptimeSeconds = Duration.between(startTime, LocalDateTime.now()).getSeconds();
        }

        EngineStatus status = EngineStatus.builder()
            .running(isRunning)
            .currentLsn(debeziumEngineService.getCurrentLsn())
            .processedEvents(debeziumEngineService.getProcessedEventCount())
            .startTime(startTime)
            .uptimeSeconds(uptimeSeconds)
            .rocketmqProducerRunning(rocketMQProducerService.isRunning())
            .databaseHost(debeziumConfig.getDatabase().getHostname())
            .rocketmqNameServer(rocketMQConfig.getNamesrvAddr())
            .rocketmqTopic(rocketMQConfig.getTopic())
            .build();

        return ResponseEntity.ok(status);
    }

    /**
     * 获取当前 LSN
     * GET /api/monitor/lsn
     */
    @GetMapping("/lsn")
    public ResponseEntity<Map<String, String>> getLsn() {
        Map<String, String> response = new HashMap<>();
        response.put("currentLsn", debeziumEngineService.getCurrentLsn());
        response.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * 健康检查接口
     * GET /api/monitor/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean engineRunning = debeziumEngineService.isRunning();
        boolean producerRunning = rocketMQProducerService.isRunning();
        boolean healthy = engineRunning && producerRunning;

        Map<String, Object> response = new HashMap<>();
        response.put("status", healthy ? "UP" : "DOWN");
        response.put("debeziumEngine", engineRunning ? "UP" : "DOWN");
        response.put("rocketmqProducer", producerRunning ? "UP" : "DOWN");
        response.put("timestamp", LocalDateTime.now().toString());

        return healthy ? ResponseEntity.ok(response) : ResponseEntity.status(503).body(response);
    }

    /**
     * 获取处理统计信息
     * GET /api/monitor/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("processedEvents", debeziumEngineService.getProcessedEventCount());
        stats.put("currentLsn", debeziumEngineService.getCurrentLsn());
        stats.put("running", debeziumEngineService.isRunning());

        LocalDateTime startTime = debeziumEngineService.getStartTime();
        if (startTime != null) {
            stats.put("startTime", startTime.toString());
            stats.put("uptimeSeconds", Duration.between(startTime, LocalDateTime.now()).getSeconds());
        }

        return ResponseEntity.ok(stats);
    }
}
