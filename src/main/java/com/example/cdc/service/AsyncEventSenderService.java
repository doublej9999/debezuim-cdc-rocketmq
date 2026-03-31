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

    private BlockingQueue<ChangeEventMessage> eventQueue;
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
        log.info("初始化异步事件发送服务 - 队列大小: {}, 发送线程数: {}, 批处理大小: {}",
            queueSize, senderThreads, batchSize);

        eventQueue = new LinkedBlockingQueue<>(queueSize);

        this.enqueuedCounter = Counter.builder("cdc_events_enqueued_total").register(meterRegistry);
        this.sentCounter = Counter.builder("cdc_events_sent_total").register(meterRegistry);
        this.failedCounter = Counter.builder("cdc_events_failed_total").register(meterRegistry);
        this.sendTimer = Timer.builder("cdc_event_send_latency").register(meterRegistry);
        Gauge.builder("cdc_event_queue_size", eventQueue, BlockingQueue::size).register(meterRegistry);
        senderExecutor = Executors.newFixedThreadPool(senderThreads, r -> {
            Thread t = new Thread(r, "async-event-sender-" + threadCounter.incrementAndGet());
            t.setDaemon(false);
            return t;
        });

        running = true;
        for (int i = 0; i < senderThreads; i++) {
            senderExecutor.submit(this::processBatch);
        }

        // 启动补偿：加载待发送和待重试事件
        reloadPendingEvents();

        log.info("异步事件发送服务已启动");
    }

    public void enqueueEvent(String topic, String tag, String key, String body, Long configId,
                             String namesrvAddr, String producerGroup) {
        if (!running) {
            log.warn("异步发送服务未运行，事件被丢弃 - ConfigId: {}, Topic: {}", configId, topic);
            return;
        }

        try {
            EventLog eventLog = eventLogService.createEventLog(configId, topic, tag, key, body,
                    namesrvAddr, producerGroup);
            ChangeEventMessage message = new ChangeEventMessage(
                topic, tag, key, body, configId, eventLog.getId(), namesrvAddr, producerGroup
            );

            boolean offered = eventQueue.offer(message);
            if (offered) {
                totalEnqueued.incrementAndGet();
                enqueuedCounter.increment();
                log.debug("事件已入队 - ConfigId: {}, Topic: {}, EventId: {}, 队列大小: {}",
                    configId, topic, eventLog.getId(), eventQueue.size());
            } else {
                totalFailed.incrementAndGet();
                failedCounter.increment();
                log.warn("事件队列已满，事件已持久化等待重试 - ConfigId: {}, Topic: {}, EventId: {}",
                    configId, topic, eventLog.getId());
            }
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            failedCounter.increment();
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

                // 发送批次
                if (batchEnabled && !rocketMQProducerService.isOrderlyEnabled()) {
                    sendBatchMessages(batch);
                } else {
                    for (ChangeEventMessage message : batch) {
                        sendSingleMessage(message);
                    }
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
            Timer.Sample sample = Timer.start(meterRegistry);
            rocketMQProducerService.sendMessage(
                message.namesrvAddr, message.producerGroup, message.topic, message.tag, message.key, message.body
            );
            totalSent.incrementAndGet();
            sentCounter.increment();

            sample.stop(sendTimer);

            if (message.eventId != null) {
                eventLogService.markAsSent(message.eventId);
            }

            log.debug("消息发送成功 - ConfigId: {}, Topic: {}, Tag: {}, EventId: {}",
                message.configId, message.topic, message.tag, message.eventId);
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            failedCounter.increment();

            if (message.eventId != null && retryEnabled) {
                eventLogService.markForRetry(message.eventId, e.getMessage());
            } else if (message.eventId != null) {
                eventLogService.markAsFailed(message.eventId, e.getMessage());
            }

            log.error("消息发送失败 - ConfigId: {}, Topic: {}, Tag: {}, EventId: {}, Error: {}",
                message.configId, message.topic, message.tag, message.eventId, e.getMessage());
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
                ChangeEventMessage message = new ChangeEventMessage(
                    event.getTopic(),
                    event.getTag(),
                    event.getMessageKey(),
                    event.getMessageBody(),
                    event.getConfigId(),
                    event.getId(),
                    event.getNamesrvAddr(),
                    event.getProducerGroup()
                );
                boolean offered = eventQueue.offer(message);
                if (!offered) {
                    log.warn("补偿队列已满，事件将保留在数据库待重试 - EventId: {}", event.getId());
                }
            }
        } catch (Exception e) {
            log.error("启动补偿加载失败: {}", e.getMessage(), e);
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

            log.info("发现 {} 条待发送/重试事件，开始重试...", pendingEvents.size());

            int processed = 0;
            for (EventLog event : pendingEvents) {
                ChangeEventMessage message = new ChangeEventMessage(
                    event.getTopic(),
                    event.getTag(),
                    event.getMessageKey(),
                    event.getMessageBody(),
                    event.getConfigId(),
                    event.getId(),
                    event.getNamesrvAddr(),
                    event.getProducerGroup()
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
        private final String namesrvAddr;
        private final String producerGroup;

        public ChangeEventMessage(String topic, String tag, String key, String body,
                                  Long configId, Long eventId, String namesrvAddr, String producerGroup) {
            this.topic = topic;
            this.tag = tag;
            this.key = key;
            this.body = body;
            this.configId = configId;
            this.eventId = eventId;
            this.namesrvAddr = namesrvAddr;
            this.producerGroup = producerGroup;
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
