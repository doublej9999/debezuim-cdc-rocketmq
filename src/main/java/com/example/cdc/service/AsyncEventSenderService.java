package com.example.cdc.service;

import com.example.cdc.model.EventLog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class AsyncEventSenderService {

    private final RocketMQProducerService rocketMQProducerService;
    private final EventLogService eventLogService;
    private final MeterRegistry meterRegistry;

    @Value("${async.event.queue.size:10000}")
    private int queueSize;

    @Value("${async.event.sender.threads:4}")
    private int senderThreads;

    @Value("${async.event.sender.batch.size:100}")
    private int batchSize;

    @Value("${async.event.sender.batch.timeout.ms:5000}")
    private long batchTimeoutMs;

    @Value("${async.event.sender.batch.enabled:false}")
    private boolean batchEnabled;

    @Value("${async.event.retry.enabled:true}")
    private boolean retryEnabled;

    @Value("${async.event.retry.batch.size:200}")
    private int retryBatchSize;

    private List<BlockingQueue<ChangeEventMessage>> eventQueues;
    private ExecutorService senderExecutor;
    private volatile boolean running = false;
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicInteger threadCounter = new AtomicInteger(0);
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    private Counter enqueuedCounter;
    private Counter sentCounter;
    private Counter failedCounter;
    private Timer sendTimer;

    public AsyncEventSenderService(RocketMQProducerService rocketMQProducerService, EventLogService eventLogService, MeterRegistry meterRegistry) {
        this.rocketMQProducerService = rocketMQProducerService;
        this.eventLogService = eventLogService;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        log.info("初始化异步事件发送服务 (严格保序高性能版) - 总队列大小: {}, 发送分片(线程)数: {}, 批处理大小: {}",
            queueSize, senderThreads, batchSize);

        // 初始化分片队列
        eventQueues = new ArrayList<>(senderThreads);
        int perQueueSize = Math.max(1, queueSize / senderThreads);
        for (int i = 0; i < senderThreads; i++) {
            eventQueues.add(new LinkedBlockingQueue<>(perQueueSize));
        }

        this.enqueuedCounter = Counter.builder("cdc_events_enqueued_total").register(meterRegistry);
        this.sentCounter = Counter.builder("cdc_events_sent_total").register(meterRegistry);
        this.failedCounter = Counter.builder("cdc_events_failed_total").register(meterRegistry);
        this.sendTimer = Timer.builder("cdc_event_send_latency").register(meterRegistry);
        
        // 注册各个分片队列的监控
        for (int i = 0; i < senderThreads; i++) {
            final int index = i;
            Gauge.builder("cdc_event_queue_size_" + i, eventQueues.get(i), BlockingQueue::size)
                .description("CDC 事件分片队列 " + i + " 的当前大小")
                .register(meterRegistry);
        }

        senderExecutor = Executors.newFixedThreadPool(senderThreads, r -> {
            Thread t = new Thread(r, "async-event-sender-" + threadCounter.incrementAndGet());
            t.setDaemon(false);
            return t;
        });

        running = true;
        for (int i = 0; i < senderThreads; i++) {
            final int queueIndex = i;
            senderExecutor.submit(() -> processQueue(queueIndex));
        }

        // 启动补偿：加载待发送和待重试事件
        reloadPendingEvents();

        log.info("异步事件发送服务已启动，采用 {} 个独立分片确保严格保序", senderThreads);
    }

    /**
     * 将事件入队，根据 Key 进行 Hash 路由到指定分片队列
     */
    public void enqueueEvent(String topic, String tag, String key, String body, Long configId,
                             String namesrvAddr, String producerGroup, String lsn) {
        if (!running) {
            log.warn("异步发送服务未运行，事件被丢弃 - ConfigId: {}, Topic: {}", configId, topic);
            return;
        }

        try {
            // 1. 持久化日志（带 LSN 幂等检查）
            EventLog eventLog = eventLogService.createEventLog(configId, topic, tag, key, body,
                    namesrvAddr, producerGroup, lsn);
            
            // 如果返回 null，说明触发了幂等检查，此事件已处理过
            if (eventLog == null) {
                return;
            }
            
            ChangeEventMessage message = new ChangeEventMessage(
                topic, tag, key, body, configId, eventLog.getId(), namesrvAddr, producerGroup
            );

            // 2. 路由并入队
            routeAndOffer(message);
            
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            failedCounter.increment();
            log.error("处理事件入队失败 - ConfigId: {}, Topic: {}, Error: {}",
                configId, topic, e.getMessage(), e);
        }
    }

    /**
     * 将已有的事件重新入队（用于补偿或重试），不再创建新的数据库记录
     */
    private void reEnqueueEvent(EventLog eventLog) {
        if (eventLog == null || eventLog.getId() == null) return;
        
        ChangeEventMessage message = new ChangeEventMessage(
            eventLog.getTopic(), eventLog.getTag(), eventLog.getMessageKey(),
            eventLog.getMessageBody(), eventLog.getConfigId(), eventLog.getId(),
            eventLog.getNamesrvAddr(), eventLog.getProducerGroup()
        );
        
        routeAndOffer(message);
    }

    /**
     * 核心路由逻辑：根据 Key 进行 Hash 路由到指定分片队列
     */
    private void routeAndOffer(ChangeEventMessage message) {
        // Hash 路由：确保相同的 Key 路由到同一个 Queue，从而由同一个处理线程顺序发送
        int routeKey = (message.key != null && !message.key.isBlank()) ? message.key.hashCode() : message.configId.hashCode();
        int queueIndex = Math.abs(routeKey) % senderThreads;
        BlockingQueue<ChangeEventMessage> targetQueue = eventQueues.get(queueIndex);

        boolean offered = targetQueue.offer(message);
        if (offered) {
            totalEnqueued.incrementAndGet();
            enqueuedCounter.increment();
            log.debug("事件已入分片队列 {} - ConfigId: {}, Topic: {}, EventId: {}, 队列当前大小: {}",
                queueIndex, message.configId, message.topic, message.eventId, targetQueue.size());
        } else {
            totalFailed.incrementAndGet();
            failedCounter.increment();
            log.warn("分片队列 {} 已满，事件等待重试 - ConfigId: {}, Topic: {}, EventId: {}",
                queueIndex, message.configId, message.topic, message.eventId);
        }
    }

    /**
     * 每个线程负责一个固定的队列，确保队列内的消息严格按序发送
     */
    private void processQueue(int queueIndex) {
        log.info("事件发送线程 {} [分片 {}] 已启动", Thread.currentThread().getName(), queueIndex);
        BlockingQueue<ChangeEventMessage> queue = eventQueues.get(queueIndex);

        while (running) {
            try {
                // 1. 获取首个消息（阻塞等待）
                ChangeEventMessage firstMessage = queue.poll(batchTimeoutMs, TimeUnit.MILLISECONDS);
                if (firstMessage == null) {
                    continue;
                }

                // 2. 尝试批量获取后续消息 (用于提高吞吐量)
                List<ChangeEventMessage> batch = new ArrayList<>();
                batch.add(firstMessage);
                queue.drainTo(batch, batchSize - 1);

                // 3. 执行发送
                // 注意：在严格保序模式下，Batch 发送必须确保在 RocketMQ 侧也是顺序存储的
                // 此处我们按顺序单条发送，或者如果在 RocketMQ 端也能保证批次内顺序则可启用批次
                if (batchEnabled && !rocketMQProducerService.isOrderlyEnabled()) {
                    // 非顺序模式允许乱序批次发送
                    sendBatchMessages(batch);
                } else {
                    // 顺序模式下，循环单条发送，确保前一条成功后再发下一条（或者利用 RocketMQ 的顺序发送 API）
                    for (ChangeEventMessage message : batch) {
                        sendSingleMessage(message);
                    }
                }

            } catch (InterruptedException e) {
                if (running) {
                    log.warn("事件发送线程分片 {} 被中断", queueIndex);
                }
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("处理分片 {} 的事件批次时出错: {}", queueIndex, e.getMessage(), e);
            }
        }

        log.info("事件发送线程分片 {} 已停止", queueIndex);
    }

    private void sendSingleMessage(ChangeEventMessage message) {
        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            
            // RocketMQProducerService.sendMessage 内部在 orderlyEnabled 为 true 时会使用 key 进行 Queue 选择
            // 由于同一个分片线程负责固定的 Key，且单线程循环发送，因此保证了全局严格顺序
            rocketMQProducerService.sendMessage(
                message.namesrvAddr, message.producerGroup, message.topic, message.tag, message.key, message.body
            );
            
            totalSent.incrementAndGet();
            sentCounter.increment();
            sample.stop(sendTimer);

            if (message.eventId != null) {
                eventLogService.markAsSent(message.eventId);
            }

            log.debug("分片发送成功 - ConfigId: {}, Topic: {}, EventId: {}",
                message.configId, message.topic, message.eventId);
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            failedCounter.increment();

            if (message.eventId != null && retryEnabled) {
                eventLogService.markForRetry(message.eventId, e.getMessage());
            } else if (message.eventId != null) {
                eventLogService.markAsFailed(message.eventId, e.getMessage());
            }

            log.error("分片发送失败 - ConfigId: {}, Topic: {}, EventId: {}, Error: {}",
                message.configId, message.topic, message.eventId, e.getMessage());
        }
    }

    /**
     * 批量发送消息（按 topic 分组）
     */
    private void sendBatchMessages(List<ChangeEventMessage> batch) {
        Map<String, List<ChangeEventMessage>> grouped = batch.stream()
            .collect(java.util.stream.Collectors.groupingBy(m -> m.topic));

        for (Map.Entry<String, List<ChangeEventMessage>> entry : grouped.entrySet()) {
            List<ChangeEventMessage> messages = entry.getValue();
            if (messages.isEmpty()) {
                continue;
            }

            try {
                List<org.apache.rocketmq.common.message.Message> rocketMessages = new java.util.ArrayList<>(messages.size());
                for (ChangeEventMessage message : messages) {
                    rocketMessages.add(new org.apache.rocketmq.common.message.Message(
                        message.topic, message.tag, message.key, message.body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    ));
                }

                rocketMQProducerService.sendBatchMessages(rocketMessages);

                for (ChangeEventMessage message : messages) {
                    totalSent.incrementAndGet();
                    sentCounter.increment();
                    if (message.eventId != null) {
                        eventLogService.markAsSent(message.eventId);
                    }
                }
            } catch (Exception e) {
                for (ChangeEventMessage message : messages) {
                    totalFailed.incrementAndGet();
                    failedCounter.increment();
                    if (message.eventId != null && retryEnabled) {
                        eventLogService.markForRetry(message.eventId, e.getMessage());
                    } else if (message.eventId != null) {
                        eventLogService.markAsFailed(message.eventId, e.getMessage());
                    }
                }
                log.error("批量消息发送失败 - Topic: {}, Count: {}, Error: {}",
                    entry.getKey(), messages.size(), e.getMessage());
            }
        }
    }

    /**
     * 启动补偿：加载待发送和待重试事件
     */
    private void reloadPendingEvents() {
        try {
            List<EventLog> pending = eventLogService.getPendingRetryEvents();
            if (pending.isEmpty()) {
                return;
            }

            log.info("启动补偿加载 {} 条待发送/重试事件", pending.size());
            for (EventLog event : pending) {
                reEnqueueEvent(event);
            }
        } catch (Exception e) {
            log.error("启动补偿加载失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("正在关闭异步事件发送服务...");
        running = false;

        // 尝试刷空所有分片队列
        for (int i = 0; i < senderThreads; i++) {
            BlockingQueue<ChangeEventMessage> queue = eventQueues.get(i);
            if (!queue.isEmpty()) {
                log.info("分片 {} 仍有 {} 条事件待处理，尝试优雅闭环...", i, queue.size());
                ChangeEventMessage message;
                while ((message = queue.poll()) != null) {
                    sendSingleMessage(message);
                }
            }
        }

        if (senderExecutor != null) {
            senderExecutor.shutdown();
            try {
                if (!senderExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("发送线程池未能在 30 秒内正常退出，强制关闭");
                    senderExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("等待发送线程池关闭时被中断");
                senderExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("异步事件发送服务已关闭 - 总入队: {}, 总发送: {}, 总失败: {}",
            totalEnqueued.get(), totalSent.get(), totalFailed.get());
    }

    @Scheduled(fixedDelayString = "${async.event.retry.interval.seconds:60}000")
    public void retryFailedEvents() {
        retryFailedEventsInternal(false);
    }

    public int triggerRetryNow() {
        return retryFailedEventsInternal(true);
    }

    private int retryFailedEventsInternal(boolean forceRun) {
        if ((!running || !retryEnabled) && !forceRun) {
            return 0;
        }

        try {
            List<EventLog> pendingEvents = eventLogService.getPendingRetryEvents(retryBatchSize);
            if (pendingEvents.isEmpty()) {
                return 0;
            }

            log.info("发现 {} 条待发送/重试事件，开始重试...", pendingEvents.size());

            int processed = 0;
            for (EventLog event : pendingEvents) {
                // 重试也需要入路由队列以保证顺序
                reEnqueueEvent(event);
                processed++;
            }

            log.info("重试任务入队完成");
            return processed;
        } catch (Exception e) {
            log.error("重试失败事件时出错: {}", e.getMessage(), e);
            return 0;
        }
    }

    public Statistics getStatistics() {
        int totalQueued = eventQueues.stream().mapToInt(BlockingQueue::size).sum();
        List<ShardStatistics> shardStats = new ArrayList<>();
        for (int i = 0; i < eventQueues.size(); i++) {
            shardStats.add(new ShardStatistics(i, eventQueues.get(i).size()));
        }
        
        return new Statistics(
            totalEnqueued.get(),
            totalSent.get(),
            totalFailed.get(),
            totalQueued,
            running,
            shardStats
        );
    }

    @Data
    private static class ChangeEventMessage {
        private final String topic;
        private final String tag;
        private final String key;
        private final String body;
        private final Long configId;
        private final Long eventId;
        private final String namesrvAddr;
        private final String producerGroup;
    }

    @Data
    public static class Statistics {
        private final long totalEnqueued;
        private final long totalSent;
        private final long totalFailed;
        private final int queuedSize;
        private final boolean running;
        private final List<ShardStatistics> shards;
    }

    @Data
    public static class ShardStatistics {
        private final int id;
        private final int queueSize;
    }
}
