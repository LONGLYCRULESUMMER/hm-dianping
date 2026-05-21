package com.hmdp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaConfig {

    public static final String SECKILL_ORDER_TOPIC = "seckill-orders";

    @Bean
    public NewTopic seckillOrderTopic() {
        // 3 partitions, replication factor 1 (单机开发环境)
        return new NewTopic(SECKILL_ORDER_TOPIC, 3, (short) 1);
    }
}
