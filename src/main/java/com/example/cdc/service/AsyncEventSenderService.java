package com.example.cdc.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步事件发送服务
 * 将 Debezium 事件缓冲到队列中，异步发送到 RocketMQ
 * 解耦 Debezium 引擎和 RocketMQ 生产者，防止 RocketMQ 连接问题导致引擎停止
 */
@Slf4j
@Service
public class AsyncEventSenderService {

    private final RocketMQProducerService rocketMQProducerService;

    @Value("${async.event.queue.size:10000}")
    private int queueSize;

    @Value("${async.event.sender.threads:4}")
    private int senderThreads;

    @Value("${async.event.sender.batch.size:100}")
    private int batchSize;

    @Value("${async.event.sender.batch.timeout.ms:5000}")
    private long batchTimeoutMs;

    private BlockingQueue<ChangeEventMessage> eventQueue;
    private ExecutorService senderExecutor;
    private volatile boolean running = false;
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    public AsyncEventSenderService(RocketMQProducerService rocketMQProducerService) {
        this.rocketMQProducerService = rocketMQProducerService;
    }

    /**
     * 初始化异步发送服务
     */
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

        // 启动发送线程
        for (int i = 0; i < senderThreads; i++) {
            senderExecutor.submit(this::processBatch);
        }

        log.info("异步事件发送服务已启动");
    }

    /**
     * 将事件加入队列（非阻塞）
     * 如果队列满，记录警告但不阻塞 Debezium 引擎
     */
    public void enqueueEvent(String topic, String tag, String key, String body, Long configId) {
        if (!running) {
            log.warn("异步发送服务未运行，事件被丢弃 - ConfigId: {}, Topic: {}", configId, topic);
            return;
        }

        ChangeEventMessage message = new ChangeEventMessage(topic, tag, key, body, configId);

        boolean offered = eventQueue.offer(message);
        if (offered) {
            totalEnqueued.incrementAndGet();
            log.debug("事件已入队 - ConfigId: {}, Topic: {}, 队列大小: {}", configId, topic, eventQueue.size());
        } else {
            totalFailed.incrementAndGet();
            log.warn("事件队列已满，事件被丢弃 - ConfigId: {}, Topic: {}, 队列大小: {}",
                configId, topic, eventQueue.size());
        }
    }

    /**
     * 批量处理事件
     */
    private void processBatch() {
        log.info("事件发送线程已启动");

        while (running) {
            try {
                ChangeEventMessage firstMessage = eventQueue.poll(batchTimeoutMs, TimeUnit.MILLISECONDS);
                if (firstMessage == null) {
                    continue;
                }

                // 收集批次
                java.util.List<ChangeEventMessage> batch = new java.util.ArrayList<>();
                batch.add(firstMessage);
                eventQueue.drainTo(batch, batchSize - 1);

                // 发送批次
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

    /**
     * 发送单条消息
     */
    private void sendSingleMessage(ChangeEventMessage message) {
        try {
            rocketMQProducerService.sendMessage(message.topic, message.tag, message.key, message.body);
            totalSent.incrementAndGet();
            log.debug("消息发送成功 - ConfigId: {}, Topic: {}, Tag: {}",
                message.configId, message.topic, message.tag);
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            log.error("消息发送失败 - ConfigId: {}, Topic: {}, Tag: {}, Error: {}",
                message.configId, message.topic, message.tag, e.getMessage());
            // 不抛出异常，继续处理下一条消息
        }
    }

    /**
     * 优雅关闭
     */
    @PreDestroy
    public void shutdown() {
        log.info("关闭异步事件发送服务...");
        running = false;

        // 处理剩余的事件
        if (!eventQueue.isEmpty()) {
            log.info("处理剩余的 {} 条事件...", eventQueue.size());
            ChangeEventMessage message;
            while ((message = eventQueue.poll()) != null) {
                sendSingleMessage(message);
            }
        }

        // 关闭线程池
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

    /**
     * 获取统计信息
     */
    public Statistics getStatistics() {
        return new Statistics(
            totalEnqueued.get(),
            totalSent.get(),
            totalFailed.get(),
            eventQueue.size(),
            running
        );
    }

    /**
     * 变更事件消息
     */
    @Data
    private static class ChangeEventMessage {
        private final String topic;
        private final String tag;
        private final String key;
        private final String body;
        private final Long configId;

        public ChangeEventMessage(String topic, String tag, String key, String body, Long configId) {
            this.topic = topic;
            this.tag = tag;
            this.key = key;
            this.body = body;
            this.configId = configId;
        }
    }

    /**
     * 统计信息
     */
    @Data
    public static class Statistics {
        private final long totalEnqueued;
        private final long totalSent;
        private final long totalFailed;
        private final int queuedSize;
        private final boolean running;
    }
}
