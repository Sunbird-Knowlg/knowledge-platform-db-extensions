package org.sunbird.janusgraph.cdc;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class KafkaEventSink implements EventSink {

    private static final Logger logger = LoggerFactory.getLogger(KafkaEventSink.class);
    private KafkaProducer<String, String> producer;
    private String topic;

    public KafkaEventSink(String bootstrapServers, String topic) {
        this.topic = topic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        this.producer = new KafkaProducer<>(props);
        logger.info("KafkaEventSink initialized with topic: {}", topic);
    }

    @Override
    public void send(String key, String message) {
        try {
            producer.send(new ProducerRecord<>(topic, key, message));
        } catch (Exception e) {
            logger.error("Failed to send event to Kafka topic {}: {}", topic, e.getMessage());
        }
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }
}
