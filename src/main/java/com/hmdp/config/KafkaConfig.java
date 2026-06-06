package com.hmdp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaConfig {

    public static final String SECKILL_ORDER_TOPIC = "seckill-orders";
    public static final String CACHE_INVALIDATION_TOPIC = "cache-invalidation";

    @Bean
    public NewTopic seckillOrderTopic() {
        // 3 partitions, replication factor 1 (单机开发环境)
        return new NewTopic(SECKILL_ORDER_TOPIC, 3, (short) 1);
    }

    @Bean
    public NewTopic cacheInvalidationTopic() {
        // 所有节点都需要消费，1 partition 够用
        return new NewTopic(CACHE_INVALIDATION_TOPIC, 1, (short) 1);
    }
}
