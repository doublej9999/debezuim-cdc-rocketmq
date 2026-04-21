package com.example.cdc.repository;

import com.example.cdc.model.SnapshotJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SnapshotJobRepository extends JpaRepository<SnapshotJob, Long> {

    Optional<SnapshotJob> findByBatchId(String batchId);

    Page<SnapshotJob> findByConfigIdOrderByCreatedAtDesc(Long configId, Pageable pageable);

    Page<SnapshotJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 查询指定配置最近一次成功完成的快照任务（用于获取最新 snapshotLsn）
     */
    Optional<SnapshotJob> findTopByConfigIdAndStatusOrderByFinishedAtDesc(
            Long configId, SnapshotJob.JobStatus status);

    /**
     * 检查是否有正在运行的快照任务（防止并发触发）
     */
    boolean existsByConfigIdAndStatus(Long configId, SnapshotJob.JobStatus status);

    /**
     * 更新已推送行数（用于进度上报）
     */
    @Modifying
    @Transactional
    @Query("update SnapshotJob j set j.processedRows = :processedRows where j.id = :id")
    int updateProcessedRows(@Param("id") Long id, @Param("processedRows") Long processedRows);

    /**
     * 标记任务为完成状态
     */
    @Modifying
    @Transactional
    @Query("""
        update SnapshotJob j
           set j.status = 'DONE',
               j.finishedAt = :finishedAt,
               j.processedRows = :processedRows
         where j.id = :id
        """)
    int markAsDone(@Param("id") Long id,
                   @Param("processedRows") Long processedRows,
                   @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 标记任务为失败状态
     */
    @Modifying
    @Transactional
    @Query("""
        update SnapshotJob j
           set j.status = 'FAILED',
               j.finishedAt = :finishedAt,
               j.errorMessage = :errorMessage
         where j.id = :id
        """)
    int markAsFailed(@Param("id") Long id,
                     @Param("errorMessage") String errorMessage,
                     @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 清理指定日期前的历史快照记录（仅删除 DONE/FAILED 状态）
     */
    @Query("delete from SnapshotJob j where j.createdAt < :before and j.status in ('DONE', 'FAILED')")
    @Modifying
    @Transactional
    int deleteOldJobs(@Param("before") LocalDateTime before);

    /**
     * 查询所有 RUNNING 状态快照（启动时用于清理僵死任务）
     */
    List<SnapshotJob> findByStatus(SnapshotJob.JobStatus status);
}
