package com.example.cdc.service;

import com.example.cdc.config.RocketMQConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocketMQ 生产者服务。
 * 支持默认生产者和按配置动态路由的生产者实例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RocketMQProducerService {

    private final RocketMQConfig rocketMQConfig;
    private volatile DefaultMQProducer defaultProducer;
    private final Map<String, ProducerHolder> dynamicProducers = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws MQClientException {
        log.info("初始化 RocketMQ 默认生产者...");
        defaultProducer = createAndStartProducer(
                rocketMQConfig.getNamesrvAddr(),
                rocketMQConfig.getProducerGroup(),
                "default-producer"
        );
        log.info("RocketMQ 默认生产者启动成功 - NameServer: {}, ProducerGroup: {}",
                rocketMQConfig.getNamesrvAddr(), rocketMQConfig.getProducerGroup());
    }

    public void sendMessage(String namesrvAddr, String producerGroup, String topic, String tag, String key, byte[] body) {
        try {
            DefaultMQProducer producer = getProducer(namesrvAddr, producerGroup);
            Message message = new Message(topic, tag, key, body);
            SendResult sendResult = producer.send(message);

            log.debug("消息发送成功 - NameServer: {}, ProducerGroup: {}, Topic: {}, Tag: {}, Key: {}, MsgId: {}, Status: {}",
                    producer.getNamesrvAddr(), producer.getProducerGroup(), topic, tag, key,
                    sendResult.getMsgId(), sendResult.getSendStatus());
        } catch (MQClientException | RemotingException | MQBrokerException | InterruptedException e) {
            log.error("消息发送失败 - Topic: {}, Tag: {}, Key: {}, Error: {}", topic, tag, key, e.getMessage(), e);
            throw new RuntimeException("RocketMQ 消息发送失败", e);
        }
    }

    public void sendMessage(String namesrvAddr, String producerGroup, String topic, String tag, String key, String body) {
        sendMessage(namesrvAddr, producerGroup, topic, tag, key, body.getBytes(StandardCharsets.UTF_8));
    }

    public void sendMessage(String topic, String tag, String key, String body) {
        sendMessage(null, null, topic, tag, key, body);
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭 RocketMQ 生产者...");

        dynamicProducers.values().forEach(holder -> {
            try {
                holder.producer.shutdown();
            } catch (Exception e) {
                log.warn("关闭动态生产者失败 - {}: {}", holder.instanceName, e.getMessage());
            }
        });
        dynamicProducers.clear();

        if (defaultProducer != null) {
            defaultProducer.shutdown();
            defaultProducer = null;
        }

        log.info("RocketMQ 生产者已关闭");
    }

    public boolean isRunning() {
        return defaultProducer != null;
    }

    private DefaultMQProducer getProducer(String namesrvAddr, String producerGroup) throws MQClientException {
        String resolvedNamesrvAddr = normalizeOrDefault(namesrvAddr, rocketMQConfig.getNamesrvAddr());
        String resolvedProducerGroup = normalizeOrDefault(producerGroup, rocketMQConfig.getProducerGroup());

        boolean useDefault = Objects.equals(resolvedNamesrvAddr, rocketMQConfig.getNamesrvAddr())
                && Objects.equals(resolvedProducerGroup, rocketMQConfig.getProducerGroup());
        if (useDefault) {
            return defaultProducer;
        }

        String key = resolvedNamesrvAddr + "|" + resolvedProducerGroup;
        ProducerHolder holder = dynamicProducers.get(key);
        if (holder != null) {
            return holder.producer;
        }

        synchronized (this) {
            ProducerHolder existing = dynamicProducers.get(key);
            if (existing != null) {
                return existing.producer;
            }

            String instanceName = "dynamic-producer-" + Math.abs(key.hashCode());
            DefaultMQProducer dynamicProducer = createAndStartProducer(resolvedNamesrvAddr, resolvedProducerGroup, instanceName);
            dynamicProducers.put(key, new ProducerHolder(dynamicProducer, instanceName));
            log.info("创建动态生产者成功 - NameServer: {}, ProducerGroup: {}, InstanceName: {}",
                    resolvedNamesrvAddr, resolvedProducerGroup, instanceName);
            return dynamicProducer;
        }
    }

    private DefaultMQProducer createAndStartProducer(String namesrvAddr, String producerGroup, String instanceName)
            throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(namesrvAddr);
        producer.setInstanceName(instanceName);
        producer.setSendMsgTimeout(rocketMQConfig.getSendMsgTimeout());
        producer.setRetryTimesWhenSendFailed(rocketMQConfig.getRetryTimesWhenSendFailed());
        producer.setMaxMessageSize(rocketMQConfig.getMaxMessageSize());
        producer.setVipChannelEnabled(false);
        producer.start();
        return producer;
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }

    private record ProducerHolder(DefaultMQProducer producer, String instanceName) {
    }
}
