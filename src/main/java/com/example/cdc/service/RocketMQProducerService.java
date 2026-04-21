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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RocketMQProducerService {

    private final RocketMQConfig rocketMQConfig;
    private volatile DefaultMQProducer defaultProducer;
    private final Map<String, ProducerHolder> dynamicProducers = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws MQClientException {
        defaultProducer = createAndStartProducer(
                rocketMQConfig.getNamesrvAddr(),
                rocketMQConfig.getProducerGroup(),
                "default-producer"
        );
        log.info("RocketMQ default producer started: namesrv={}, producerGroup={}",
                rocketMQConfig.getNamesrvAddr(), rocketMQConfig.getProducerGroup());
    }

    public void sendMessage(String namesrvAddr, String producerGroup, String topic, String tag, String key, byte[] body) {
        sendMessage(namesrvAddr, producerGroup, new Message(topic, tag, key, body));
    }

    public void sendMessage(String namesrvAddr, String producerGroup, String topic, String tag, String key, String body) {
        sendMessage(namesrvAddr, producerGroup, topic, tag, key, body.getBytes(StandardCharsets.UTF_8));
    }

    public void sendMessage(String topic, String tag, String key, String body) {
        sendMessage(null, null, topic, tag, key, body);
    }

    public void sendMessage(String namesrvAddr, String producerGroup, Message message) {
        String topic = message.getTopic();
        String tag = message.getTags();
        String key = message.getKeys();
        try {
            DefaultMQProducer producer = getProducer(namesrvAddr, producerGroup);

            SendResult sendResult;
            if (isOrderlyEnabled() && key != null && !key.isBlank()) {
                MessageQueueSelector selector = (mqs, msg, arg) -> {
                    int index = Math.floorMod(arg.hashCode(), mqs.size());
                    return mqs.get(index);
                };
                sendResult = producer.send(message, selector, key);
            } else {
                sendResult = producer.send(message);
            }

            log.debug("Message sent: namesrv={}, producerGroup={}, topic={}, tag={}, key={}, msgId={}, status={}",
                    producer.getNamesrvAddr(), producer.getProducerGroup(), topic, tag, key,
                    sendResult.getMsgId(), sendResult.getSendStatus());
        } catch (MQClientException | RemotingException | MQBrokerException | InterruptedException e) {
            log.error("Message send failed: topic={}, tag={}, key={}, error={}", topic, tag, key, e.getMessage(), e);
            throw new RuntimeException("RocketMQ message send failed", e);
        }
    }

    public void sendBatchMessages(List<Message> messages) {
        sendBatchMessages(null, null, messages);
    }

    public void sendBatchMessages(String namesrvAddr, String producerGroup, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            DefaultMQProducer producer = getProducer(namesrvAddr, producerGroup);
            SendResult sendResult = producer.send(messages);
            log.debug("Batch messages sent: namesrv={}, producerGroup={}, topic={}, count={}, msgId={}, status={}",
                    producer.getNamesrvAddr(), producer.getProducerGroup(), messages.get(0).getTopic(),
                    messages.size(), sendResult.getMsgId(), sendResult.getSendStatus());
        } catch (MQClientException | RemotingException | MQBrokerException | InterruptedException e) {
            log.error("Batch message send failed: topic={}, count={}, error={}",
                    messages.get(0).getTopic(), messages.size(), e.getMessage(), e);
            throw new RuntimeException("RocketMQ batch message send failed", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        dynamicProducers.values().forEach(holder -> {
            try {
                holder.producer.shutdown();
            } catch (Exception e) {
                log.warn("Failed to shutdown dynamic producer {}: {}", holder.instanceName, e.getMessage());
            }
        });
        dynamicProducers.clear();

        if (defaultProducer != null) {
            defaultProducer.shutdown();
            defaultProducer = null;
        }
    }

    public boolean isRunning() {
        return defaultProducer != null;
    }

    public boolean isOrderlyEnabled() {
        return Boolean.TRUE.equals(rocketMQConfig.getOrderlyEnabled());
    }

    private DefaultMQProducer getProducer(String namesrvAddr, String producerGroup) throws MQClientException {
        String resolvedNamesrvAddr = normalizeOrDefault(namesrvAddr, rocketMQConfig.getNamesrvAddr());
        String resolvedProducerGroup = normalizeOrDefault(producerGroup, rocketMQConfig.getProducerGroup());

        boolean useDefault = Objects.equals(resolvedNamesrvAddr, rocketMQConfig.getNamesrvAddr())
                && Objects.equals(resolvedProducerGroup, rocketMQConfig.getProducerGroup());
        if (useDefault) {
            return defaultProducer;
        }

        String producerKey = resolvedNamesrvAddr + "|" + resolvedProducerGroup;
        ProducerHolder holder = dynamicProducers.get(producerKey);
        if (holder != null) {
            return holder.producer;
        }

        synchronized (this) {
            ProducerHolder existing = dynamicProducers.get(producerKey);
            if (existing != null) {
                return existing.producer;
            }

            String instanceName = "dynamic-producer-" + Math.abs(producerKey.hashCode());
            DefaultMQProducer dynamicProducer = createAndStartProducer(
                    resolvedNamesrvAddr, resolvedProducerGroup, instanceName);
            dynamicProducers.put(producerKey, new ProducerHolder(dynamicProducer, instanceName));
            log.info("Dynamic producer created: namesrv={}, producerGroup={}, instanceName={}",
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
