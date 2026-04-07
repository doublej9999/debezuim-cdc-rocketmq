package com.example.cdc.service;

import com.example.cdc.model.DataSourceConfig;
import com.example.cdc.model.EventLog;
import com.example.cdc.repository.DataSourceConfigRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AsyncEventSenderService {

    private final RocketMQProducerService rocketMQProducerService;
    private final EventLogService eventLogService;
    private final DataSourceConfigRepository dataSourceConfigRepository;
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

    public AsyncEventSenderService(RocketMQProducerService rocketMQProducerService,
                                   EventLogService eventLogService,
                                   DataSourceConfigRepository dataSourceConfigRepository,
                                   MeterRegistry meterRegistry) {
        this.rocketMQProducerService = rocketMQProducerService;
        this.eventLogService = eventLogService;
        this.dataSourceConfigRepository = dataSourceConfigRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        log.info("初始化异步事件发送服务 - queueSize: {}, senderThreads: {}, batchSize: {}",
                queueSize, senderThreads, batchSize);

        eventQueues = new ArrayList<>(senderThreads);
        int perQueueSize = Math.max(1, queueSize / senderThreads);
        for (int i = 0; i < senderThreads; i++) {
            eventQueues.add(new LinkedBlockingQueue<>(perQueueSize));
        }

        enqueuedCounter = Counter.builder("cdc_events_enqueued_total").register(meterRegistry);
        sentCounter = Counter.builder("cdc_events_sent_total").register(meterRegistry);
        failedCounter = Counter.builder("cdc_events_failed_total").register(meterRegistry);
        sendTimer = Timer.builder("cdc_event_send_latency").register(meterRegistry);

        for (int i = 0; i < senderThreads; i++) {
            Gauge.builder("cdc_event_queue_size_" + i, eventQueues.get(i), BlockingQueue::size)
                    .description("CDC 事件分片队列 " + i + " 当前大小")
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

        reloadPendingEvents();
        log.info("异步事件发送服务已启动");
    }

    public void enqueueEvent(String topic, String tag, String key, String body, Long configId,
                             String namesrvAddr, String producerGroup, String lsn) {
        if (!running) {
            log.warn("异步发送服务未运行，忽略事件 - ConfigId: {}, Topic: {}", configId, topic);
            return;
        }

        try {
            EventLog eventLog = eventLogService.createEventLog(configId, topic, tag, key, body,
                    namesrvAddr, producerGroup, lsn);
            if (eventLog == null) {
                return;
            }
            ChangeEventMessage message = new ChangeEventMessage(
                    topic, tag, key, body, configId, eventLog.getId(), namesrvAddr, producerGroup
            );
            routeAndOffer(message);
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            failedCounter.increment();
            log.error("事件入队失败 - ConfigId: {}, Topic: {}, Error: {}", configId, topic, e.getMessage(), e);
        }
    }

    private void reEnqueueEvent(EventLog eventLog) {
        if (eventLog == null || eventLog.getId() == null) {
            return;
        }
        ChangeEventMessage message = new ChangeEventMessage(
                eventLog.getTopic(),
                eventLog.getTag(),
                eventLog.getMessageKey(),
                eventLog.getMessageBody(),
                eventLog.getConfigId(),
                eventLog.getId(),
                eventLog.getNamesrvAddr(),
                eventLog.getProducerGroup()
        );
        routeAndOffer(message);
    }

    private void routeAndOffer(ChangeEventMessage message) {
        int routeKey = (message.key != null && !message.key.isBlank())
                ? message.key.hashCode()
                : message.configId.hashCode();
        int queueIndex = Math.abs(routeKey) % senderThreads;
        BlockingQueue<ChangeEventMessage> targetQueue = eventQueues.get(queueIndex);

        boolean offered = targetQueue.offer(message);
        if (offered) {
            totalEnqueued.incrementAndGet();
            enqueuedCounter.increment();
            log.debug("事件入队成功 - Queue: {}, ConfigId: {}, Topic: {}, EventId: {}",
                    queueIndex, message.configId, message.topic, message.eventId);
        } else {
            totalFailed.incrementAndGet();
            failedCounter.increment();
            log.warn("队列已满，事件入队失败 - Queue: {}, ConfigId: {}, Topic: {}, EventId: {}",
                    queueIndex, message.configId, message.topic, message.eventId);
        }
    }

    private void processQueue(int queueIndex) {
        log.info("事件发送线程启动 - Name: {}, Queue: {}", Thread.currentThread().getName(), queueIndex);
        BlockingQueue<ChangeEventMessage> queue = eventQueues.get(queueIndex);

        while (running) {
            try {
                ChangeEventMessage firstMessage = queue.poll(batchTimeoutMs, TimeUnit.MILLISECONDS);
                if (firstMessage == null) {
                    continue;
                }

                List<ChangeEventMessage> batch = new ArrayList<>();
                batch.add(firstMessage);
                queue.drainTo(batch, batchSize - 1);

                if (batchEnabled && !rocketMQProducerService.isOrderlyEnabled()) {
                    sendBatchMessages(batch);
                } else {
                    for (ChangeEventMessage message : batch) {
                        sendSingleMessage(message);
                    }
                }
            } catch (InterruptedException e) {
                if (running) {
                    log.warn("事件发送线程被中断 - Queue: {}", queueIndex);
                }
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("处理分片队列失败 - Queue: {}, Error: {}", queueIndex, e.getMessage(), e);
            }
        }

        log.info("事件发送线程停止 - Queue: {}", queueIndex);
    }

    private void sendSingleMessage(ChangeEventMessage message) {
        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            String resolvedNamesrvAddr = resolveNamesrvAddr(message);
            String resolvedProducerGroup = resolveProducerGroup(message);

            rocketMQProducerService.sendMessage(
                    resolvedNamesrvAddr, resolvedProducerGroup, message.topic, message.tag, message.key, message.body
            );

            totalSent.incrementAndGet();
            sentCounter.increment();
            sample.stop(sendTimer);

            if (message.eventId != null) {
                eventLogService.markAsSent(message.eventId);
            }
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            failedCounter.increment();

            if (message.eventId != null && retryEnabled) {
                eventLogService.markForRetry(message.eventId, e.getMessage());
            } else if (message.eventId != null) {
                eventLogService.markAsFailed(message.eventId, e.getMessage());
            }

            log.error("单条消息发送失败 - ConfigId: {}, Topic: {}, EventId: {}, Error: {}",
                    message.configId, message.topic, message.eventId, e.getMessage());
        }
    }

    private void sendBatchMessages(List<ChangeEventMessage> batch) {
        Map<BatchRoute, List<ChangeEventMessage>> grouped = batch.stream()
                .collect(Collectors.groupingBy(m -> new BatchRoute(
                        resolveNamesrvAddr(m),
                        resolveProducerGroup(m),
                        m.topic
                )));

        for (Map.Entry<BatchRoute, List<ChangeEventMessage>> entry : grouped.entrySet()) {
            List<ChangeEventMessage> messages = entry.getValue();
            if (messages.isEmpty()) {
                continue;
            }

            try {
                List<Message> rocketMessages = new ArrayList<>(messages.size());
                for (ChangeEventMessage message : messages) {
                    rocketMessages.add(new Message(
                            message.topic,
                            message.tag,
                            message.key,
                            message.body.getBytes(StandardCharsets.UTF_8)
                    ));
                }

                BatchRoute route = entry.getKey();
                rocketMQProducerService.sendBatchMessages(route.namesrvAddr(), route.producerGroup(), rocketMessages);

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
                        entry.getKey().topic(), messages.size(), e.getMessage());
            }
        }
    }

    private void reloadPendingEvents() {
        try {
            List<EventLog> pending = eventLogService.getPendingRetryEvents();
            if (pending.isEmpty()) {
                return;
            }

            log.info("启动补偿加载 {} 条待重试事件", pending.size());
            for (EventLog event : pending) {
                reEnqueueEvent(event);
            }
        } catch (Exception e) {
            log.error("启动补偿加载失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭异步事件发送服务...");
        running = false;

        if (eventQueues != null) {
            for (int i = 0; i < eventQueues.size(); i++) {
                BlockingQueue<ChangeEventMessage> queue = eventQueues.get(i);
                if (!queue.isEmpty()) {
                    log.info("分片 {} 仍有 {} 条消息，尝试发送剩余消息", i, queue.size());
                    ChangeEventMessage message;
                    while ((message = queue.poll()) != null) {
                        sendSingleMessage(message);
                    }
                }
            }
        }

        if (senderExecutor != null) {
            senderExecutor.shutdown();
            try {
                if (!senderExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    senderExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                senderExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("异步事件发送服务已关闭 - totalEnqueued: {}, totalSent: {}, totalFailed: {}",
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
            List<EventLog> pendingEvents = forceRun
                    ? eventLogService.getManualRetryEvents(retryBatchSize)
                    : eventLogService.getPendingRetryEvents(retryBatchSize);

            if (pendingEvents.isEmpty()) {
                return 0;
            }

            int processed = 0;
            for (EventLog event : pendingEvents) {
                if (forceRun && !eventLogService.prepareEventForManualRetry(event.getId())) {
                    continue;
                }
                reEnqueueEvent(event);
                processed++;
            }
            return processed;
        } catch (Exception e) {
            log.error("重试失败事件出错: {}", e.getMessage(), e);
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

    private String resolveNamesrvAddr(ChangeEventMessage message) {
        if (message.namesrvAddr != null && !message.namesrvAddr.isBlank()) {
            return message.namesrvAddr;
        }
        if (message.configId == null) {
            return null;
        }
        return dataSourceConfigRepository.findById(message.configId)
                .map(DataSourceConfig::getRocketmqNamesrvAddr)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .orElse(null);
    }

    private String resolveProducerGroup(ChangeEventMessage message) {
        if (message.producerGroup != null && !message.producerGroup.isBlank()) {
            return message.producerGroup;
        }
        if (message.configId == null) {
            return null;
        }
        return dataSourceConfigRepository.findById(message.configId)
                .map(DataSourceConfig::getRocketmqProducerGroup)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .orElse(null);
    }

    private record BatchRoute(String namesrvAddr, String producerGroup, String topic) {
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

