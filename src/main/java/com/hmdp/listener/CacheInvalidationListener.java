package com.hmdp.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.config.KafkaConfig;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * Kafka 缓存失效消费者：
 * 收到商铺更新消息后，invalidate 本节点的 Caffeine 缓存。
 * 每个节点用独立 groupId（广播模式），确保所有节点都能消费到。
 */
@Slf4j
@Component
public class CacheInvalidationListener {

    @Resource
    private Cache<Long, Shop> shopLocalCache;

    @KafkaListener(
            topics = KafkaConfig.CACHE_INVALIDATION_TOPIC,
            groupId = "cache-invalidation-#{T(java.util.UUID).randomUUID().toString()}"
    )
    public void onCacheInvalidation(String shopIdStr) {
        try {
            Long shopId = Long.parseLong(shopIdStr);
            shopLocalCache.invalidate(shopId);
            log.debug("Caffeine 缓存已失效, shopId={}", shopId);
        } catch (NumberFormatException e) {
            log.warn("无效的缓存失效消息: {}", shopIdStr);
        }
    }
}
