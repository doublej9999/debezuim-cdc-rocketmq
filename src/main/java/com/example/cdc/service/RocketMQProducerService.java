package com.example.cdc.service;

import com.example.cdc.config.RocketMQConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RocketMQ 生产者服务
 * 负责初始化 DefaultMQProducer 并提供消息发送功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RocketMQProducerService {

    private final RocketMQConfig rocketMQConfig;
    private DefaultMQProducer producer;
    private volatile boolean started = false;

    /**
     * 初始化并启动 RocketMQ 生产者
     */
    @PostConstruct
    public void init() throws MQClientException {
        log.info("初始化 RocketMQ 生产者...");

        producer = new DefaultMQProducer(rocketMQConfig.getProducerGroup());
        producer.setNamesrvAddr(rocketMQConfig.getNamesrvAddr());
        producer.setSendMsgTimeout(rocketMQConfig.getSendMsgTimeout());
        producer.setRetryTimesWhenSendFailed(rocketMQConfig.getRetryTimesWhenSendFailed());
        producer.setMaxMessageSize(rocketMQConfig.getMaxMessageSize());
        producer.setVipChannelEnabled(false);
        producer.start();
        started = true;

        log.info("RocketMQ 生产者启动成功 - NameServer: {}, ProducerGroup: {}",
            rocketMQConfig.getNamesrvAddr(),
            rocketMQConfig.getProducerGroup());
    }

    /**
     * 发送消息到 RocketMQ
     *
     * @param topic 主题
     * @param tag 标签（表名）
     * @param key 消息键（主键）
     * @param body 消息体（Debezium 原始 JSON）
     */
    public void sendMessage(String topic, String tag, String key, byte[] body) {
        try {
            Message message = new Message(topic, tag, key, body);

            SendResult sendResult;
            if (key != null && !key.isBlank()) {
                // 使用 key 做 sharding，确保同 key 路由到固定队列实现顺序消费
                MessageQueueSelector selector = (mqs, msg, arg) -> {
                    int index = Math.floorMod(arg.hashCode(), mqs.size());
                    return mqs.get(index);
                };
                sendResult = producer.send(message, selector, key);
            } else {
                sendResult = producer.send(message);
            }

            log.debug("消息发送成功 - Topic: {}, Tag: {}, Key: {}, MsgId: {}, Status: {}",
                topic, tag, key, sendResult.getMsgId(), sendResult.getSendStatus());

        } catch (MQClientException | RemotingException | MQBrokerException | InterruptedException e) {
            log.error("消息发送失败 - Topic: {}, Tag: {}, Key: {}, Error: {}",
                topic, tag, key, e.getMessage(), e);
            throw new RuntimeException("RocketMQ 消息发送失败", e);
        }
    }

    /**
     * 有序发送：相同 key 进入同一队列
     */
    public void sendMessageOrdered(String topic, String tag, String key, byte[] body) {
        try {
            Message message = new Message(topic, tag, key, body);
            if (key == null || key.isBlank()) {
                sendMessage(topic, tag, key, body);
                return;
            }

            MessageQueueSelector selector = (mqs, msg, arg) -> {
                int index = Math.abs(arg.hashCode()) % mqs.size();
                return mqs.get(index);
            };

            SendResult sendResult = producer.send(message, selector, key);

            log.debug("消息有序发送成功 - Topic: {}, Tag: {}, Key: {}, MsgId: {}, Status: {}",
                topic, tag, key, sendResult.getMsgId(), sendResult.getSendStatus());

        } catch (MQClientException | RemotingException | MQBrokerException | InterruptedException e) {
            log.error("消息有序发送失败 - Topic: {}, Tag: {}, Key: {}, Error: {}",
                topic, tag, key, e.getMessage(), e);
            throw new RuntimeException("RocketMQ 消息发送失败", e);
        }
    }

    /**
     * 批量发送消息（要求同一 Topic）
     */
    public void sendBatchMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            SendResult sendResult = producer.send(messages);
            log.debug("批量消息发送成功 - Topic: {}, MsgCount: {}, MsgId: {}, Status: {}",
                messages.get(0).getTopic(), messages.size(), sendResult.getMsgId(), sendResult.getSendStatus());
        } catch (MQClientException | RemotingException | MQBrokerException | InterruptedException e) {
            log.error("批量消息发送失败 - Topic: {}, MsgCount: {}, Error: {}",
                messages.get(0).getTopic(), messages.size(), e.getMessage(), e);
            throw new RuntimeException("RocketMQ 批量消息发送失败", e);
        }
    }

    /**
     * 发送消息（字符串版本）
     */
    public void sendMessage(String topic, String tag, String key, String body) {
        sendMessage(topic, tag, key, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 优雅关闭生产者
     */
    @PreDestroy
    public void shutdown() {
        if (producer != null) {
            log.info("关闭 RocketMQ 生产者...");
            producer.shutdown();
            started = false;
            log.info("RocketMQ 生产者已关闭");
        }
    }

    /**
     * 检查生产者是否正在运行
     */
    public boolean isRunning() {
        return producer != null && started;
    }

    public boolean isOrderlyEnabled() {
        return Boolean.TRUE.equals(rocketMQConfig.getOrderlyEnabled());
    }
}
