package com.example.cdc.service;

import com.example.cdc.model.EventLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class AsyncEventSenderService {

    private final RocketMQProducerService rocketMQProducerService;
    private final EventLogService eventLogService;

    @Value("${async.event.queue.size:10000}")
    private int queueSize;

    @Value("${async.event.sender.threads:4}")
    private int senderThreads;

    @Value("${async.event.sender.batch.size:100}")
    private int batchSize;

    @Value("${async.event.sender.batch.timeout.ms:5000}")
    private long batchTimeoutMs;

    @Value("${async.event.retry.enabled:true}")
    private boolean retryEnabled;

    @Value("${async.event.retry.batch.size:200}")
    private int retryBatchSize;

    private BlockingQueue<ChangeEventMessage> eventQueue;
    private ExecutorService senderExecutor;
    private volatile boolean running = false;
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    public AsyncEventSenderService(RocketMQProducerService rocketMQProducerService, EventLogService eventLogService) {
        this.rocketMQProducerService = rocketMQProducerService;
        this.eventLogService = eventLogService;
    }

    @PostConstruct
    public void init() {
        log.info("初始化异步事件发送服务 - 队列大小: {}, 发送线程数: {}, 批处理大小: {}",
            queueSize, senderThreads, batchSize);

        eventQueue = new LinkedBlockingQueue<>(queueSize);
        senderExecutor = Executors.newFixedThreadPool(senderThreads, r -> {
            Thread t = new Thread(r, "AsyncEventSender-" + System.nanoTime());
            t.setDaemon(false);
            return t;
        });

        running = true;
        for (int i = 0; i < senderThreads; i++) {
            senderExecutor.submit(this::processBatch);
        }

        log.info("异步事件发送服务已启动");
    }

    public void enqueueEvent(String topic, String tag, String key, String body, Long configId) {
        if (!running) {
            log.warn("异步发送服务未运行，事件被丢弃 - ConfigId: {}, Topic: {}", configId, topic);
            return;
        }

        try {
            EventLog eventLog = eventLogService.createEventLog(configId, topic, tag, key, body);
            ChangeEventMessage message = new ChangeEventMessage(topic, tag, key, body, configId, eventLog.getId());

            boolean offered = eventQueue.offer(message);
            if (offered) {
                totalEnqueued.incrementAndGet();
                log.debug("事件已入队 - ConfigId: {}, Topic: {}, EventId: {}, 队列大小: {}",
                    configId, topic, eventLog.getId(), eventQueue.size());
            } else {
                totalFailed.incrementAndGet();
                log.warn("事件队列已满，事件已持久化等待重试 - ConfigId: {}, Topic: {}, EventId: {}",
                    configId, topic, eventLog.getId());
            }
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            log.error("保存事件到数据库失败 - ConfigId: {}, Topic: {}, Error: {}",
                configId, topic, e.getMessage(), e);
        }
    }

    private void processBatch() {
        log.info("事件发送线程已启动");

        while (running) {
            try {
                ChangeEventMessage firstMessage = eventQueue.poll(batchTimeoutMs, TimeUnit.MILLISECONDS);
                if (firstMessage == null) {
                    continue;
                }

                List<ChangeEventMessage> batch = new ArrayList<>();
                batch.add(firstMessage);
                eventQueue.drainTo(batch, batchSize - 1);

                for (ChangeEventMessage message : batch) {
                    sendSingleMessage(message);
                }

            } catch (InterruptedException e) {
                if (running) {
                    log.warn("事件发送线程被中断: {}", e.getMessage());
                }
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("处理事件批次时出错: {}", e.getMessage(), e);
            }
        }

        log.info("事件发送线程已停止");
    }

    private void sendSingleMessage(ChangeEventMessage message) {
        try {
            rocketMQProducerService.sendMessage(message.topic, message.tag, message.key, message.body);
            totalSent.incrementAndGet();

            if (message.eventId != null) {
                eventLogService.markAsSent(message.eventId);
            }

            log.debug("消息发送成功 - ConfigId: {}, Topic: {}, Tag: {}, EventId: {}",
                message.configId, message.topic, message.tag, message.eventId);
        } catch (Exception e) {
            totalFailed.incrementAndGet();

            if (message.eventId != null && retryEnabled) {
                eventLogService.markForRetry(message.eventId, e.getMessage());
            } else if (message.eventId != null) {
                eventLogService.markAsFailed(message.eventId, e.getMessage());
            }

            log.error("消息发送失败 - ConfigId: {}, Topic: {}, Tag: {}, EventId: {}, Error: {}",
                message.configId, message.topic, message.tag, message.eventId, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭异步事件发送服务...");
        running = false;

        if (eventQueue != null && !eventQueue.isEmpty()) {
            log.info("处理剩余的 {} 条事件...", eventQueue.size());
            ChangeEventMessage message;
            while ((message = eventQueue.poll()) != null) {
                sendSingleMessage(message);
            }
        }

        if (senderExecutor != null) {
            senderExecutor.shutdown();
            try {
                if (!senderExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("发送线程池未能在 30 秒内关闭，强制关闭");
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

            log.info("发现 {} 条待重试事件，开始重试...", pendingEvents.size());

            int processed = 0;
            for (EventLog event : pendingEvents) {
                ChangeEventMessage message = new ChangeEventMessage(
                    event.getTopic(),
                    event.getTag(),
                    event.getMessageKey(),
                    event.getMessageBody(),
                    event.getConfigId(),
                    event.getId()
                );
                sendSingleMessage(message);
                processed++;
            }

            log.info("重试任务完成");
            return processed;
        } catch (Exception e) {
            log.error("重试失败事件时出错: {}", e.getMessage(), e);
            return 0;
        }
    }

    public Statistics getStatistics() {
        return new Statistics(
            totalEnqueued.get(),
            totalSent.get(),
            totalFailed.get(),
            eventQueue.size(),
            running
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

        public ChangeEventMessage(String topic, String tag, String key, String body, Long configId, Long eventId) {
            this.topic = topic;
            this.tag = tag;
            this.key = key;
            this.body = body;
            this.configId = configId;
            this.eventId = eventId;
        }
    }

    @Data
    public static class Statistics {
        private final long totalEnqueued;
        private final long totalSent;
        private final long totalFailed;
        private final int queuedSize;
        private final boolean running;
    }
}
