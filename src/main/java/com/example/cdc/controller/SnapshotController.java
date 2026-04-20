package com.example.cdc.controller;

import com.example.cdc.model.SnapshotJob;
import com.example.cdc.repository.SnapshotJobRepository;
import com.example.cdc.service.SnapshotTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * 全量快照任务管理 API
 *
 * <p>提供手动触发、进度查询、历史列表等接口。
 *
 * <pre>
 *   POST /api/snapshot/trigger/{configId}         手动触发指定配置的全量快照
 *   GET  /api/snapshot/jobs                        查询所有快照历史（分页）
 *   GET  /api/snapshot/jobs/{batchId}              查询指定批次详情
 *   GET  /api/snapshot/jobs/config/{configId}      查询指定配置的快照历史
 *   GET  /api/snapshot/jobs/config/{configId}/latest 查询最近一次成功快照的 LSN
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/snapshot")
@RequiredArgsConstructor
public class SnapshotController {

    private final SnapshotTask snapshotTask;
    private final SnapshotJobRepository snapshotJobRepository;

    /**
     * 手动触发指定配置的全量快照
     *
     * <p>快照异步执行，接口立即返回 batchId。
     * 可通过 GET /api/snapshot/jobs/{batchId} 轮询进度。
     */
    @PostMapping("/trigger/{configId}")
    public ResponseEntity<Map<String, Object>> triggerSnapshot(@PathVariable Long configId) {
        try {
            String batchId = snapshotTask.triggerSnapshot(configId);
            log.info("手动触发快照 - ConfigId: {}, BatchId: {}", configId, batchId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "batchId", batchId,
                    "message", "快照任务已提交，请通过 batchId 查询进度"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (IllegalStateException e) {
            // 并发重复触发
            return ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("触发快照失败 - ConfigId: {}, Error: {}", configId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "快照触发失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查询所有快照历史（按创建时间倒序分页）
     */
    @GetMapping("/jobs")
    public ResponseEntity<Page<SnapshotJob>> listAllJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(size, 100);
        return ResponseEntity.ok(
                snapshotJobRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, safeSize))
        );
    }

    /**
     * 查询指定批次快照详情（含进度和 LSN）
     */
    @GetMapping("/jobs/{batchId}")
    public ResponseEntity<SnapshotJob> getJobByBatchId(@PathVariable String batchId) {
        Optional<SnapshotJob> job = snapshotJobRepository.findByBatchId(batchId);
        return job.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查询指定配置的快照历史列表
     */
    @GetMapping("/jobs/config/{configId}")
    public ResponseEntity<Page<SnapshotJob>> listJobsByConfig(
            @PathVariable Long configId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(size, 100);
        return ResponseEntity.ok(
                snapshotJobRepository.findByConfigIdOrderByCreatedAtDesc(configId, PageRequest.of(page, safeSize))
        );
    }

    /**
     * 查询指定配置最近一次成功快照的 LSN 和任务信息
     *
     * <p>下游系统接入时用此接口获取 snapshotLsn，
     * 消费完 _ALL Topic 后从此 LSN 之后开始消费增量 Topic。
     */
    @GetMapping("/jobs/config/{configId}/latest")
    public ResponseEntity<Map<String, Object>> getLatestSuccessfulSnapshot(@PathVariable Long configId) {
        Optional<SnapshotJob> latestJob = snapshotJobRepository
                .findTopByConfigIdAndStatusOrderByFinishedAtDesc(configId, SnapshotJob.JobStatus.DONE);

        if (latestJob.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "message", "该配置尚无成功完成的快照任务"
            ));
        }

        SnapshotJob job = latestJob.get();
        return ResponseEntity.ok(Map.of(
                "found", true,
                "batchId", job.getBatchId(),
                "snapshotLsn", job.getSnapshotLsn() != null ? job.getSnapshotLsn() : "",
                "topic", job.getTopic(),
                "totalRows", job.getTotalRows() != null ? job.getTotalRows() : 0,
                "processedRows", job.getProcessedRows(),
                "finishedAt", job.getFinishedAt() != null ? job.getFinishedAt().toString() : "",
                "consumeGuide", "请先消费 " + job.getTopic() + " 中 batchId=" + job.getBatchId()
                        + " 的全量数据（BEGIN→DATA→END），然后从原 Topic 中 lsn > "
                        + job.getSnapshotLsn() + " 的位置继续消费增量"
        ));
    }
}
