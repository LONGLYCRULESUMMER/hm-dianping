package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.config.KafkaConfig;
import com.hmdp.dto.RedisData;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisBloomFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.BLOOM_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LOGICAL_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LOGICAL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisBloomFilter redisBloomFilter;

    @Resource
    private Cache<Long, Shop> shopLocalCache;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @PostConstruct
    private void initShopBloomFilter() {
        List<Shop> shops = list();
        for (Shop shop : shops) {
            redisBloomFilter.add(BLOOM_SHOP_KEY, shop.getId().toString());
        }
    }

    @Override
    public Result queryById(Long id) {
        // 一级缓存：Caffeine 本地缓存
        Shop localShop = shopLocalCache.getIfPresent(id);
        if (localShop != null) {
            return Result.ok(localShop);
        }

        // 二级缓存：Redis
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            shopLocalCache.put(id, shop);
            return Result.ok(shop);
        }
        if (shopJson != null) {
            return Result.fail("Not Exist!");
        }

        // 布隆过滤器拦截
        if (!redisBloomFilter.mightContain(BLOOM_SHOP_KEY, id.toString())) {
            return Result.fail("Not Exist!");
        }

        // 互斥锁防击穿：只让一个线程回源数据库
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean locked = tryLock(lockKey);
            if (!locked) {
                Thread.sleep(50);
                return queryById(id);
            }
            // 双重检查：拿到锁后再查一次缓存，可能其他线程已经写入了
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                shopLocalCache.put(id, shop);
                return Result.ok(shop);
            }

            shop = getById(id);
            if (shop != null) {
                // TTL 随机化防雪崩：30 + 0~5 分钟
                long ttl = CACHE_SHOP_TTL + RandomUtil.randomInt(0, 5);
                stringRedisTemplate.opsForValue().set(
                        CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), ttl, TimeUnit.MINUTES);
                shopLocalCache.put(id, shop);
                return Result.ok(shop);
            }
            stringRedisTemplate.opsForValue().set(
                    CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return Result.fail("Not Exist!");
    }

    /**
     * 逻辑过期方案查询商铺（缓存击穿备选方案）。
     * 缓存永不真正过期，通过 expireTime 字段判断是否逻辑过期。
     * 过期时开新线程异步重建缓存，当前请求返回旧数据，保证高可用。
     */
    public Result queryByIdWithLogicalExpiry(Long id) {
        // 一级缓存：Caffeine
        Shop localShop = shopLocalCache.getIfPresent(id);
        if (localShop != null) {
            return Result.ok(localShop);
        }

        // 二级缓存：Redis（逻辑过期 key）
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_LOGICAL_KEY + id);
        if (StrUtil.isBlank(json)) {
            // 逻辑过期方案要求热点数据预热，未命中说明不是热点数据
            return Result.fail("Not Exist!");
        }

        // 反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 未逻辑过期 → 直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            shopLocalCache.put(id, shop);
            return Result.ok(shop);
        }

        // 已逻辑过期 → 尝试获取互斥锁，异步重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        boolean locked = tryLock(lockKey);
        if (locked) {
            // 拿到锁，开新线程异步重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShopToRedisWithLogicalExpiry(id, CACHE_SHOP_LOGICAL_TTL);
                } catch (Exception e) {
                    log.error("缓存重建失败, shopId={}", id, e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 无论是否拿到锁，都返回旧数据（高可用）
        shopLocalCache.put(id, shop);
        return Result.ok(shop);
    }

    /**
     * 将商铺数据以逻辑过期方式写入 Redis（用于预热和重建）。
     */
    public void saveShopToRedisWithLogicalExpiry(Long id, Long expireMinutes) {
        Shop shop = getById(id);
        if (shop == null) {
            return;
        }
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
        // 不设 TTL → 缓存永不真正过期
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_LOGICAL_KEY + id, JSONUtil.toJsonStr(redisData));
        shopLocalCache.put(id, shop);
    }

    @Override
    public Result saveShop(Shop shop) {
        save(shop);
        redisBloomFilter.add(BLOOM_SHOP_KEY, shop.getId().toString());
        return Result.ok(shop.getId());
    }

    @Override
    public Result updateShop(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺 id 不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除 Redis 缓存（Cache Aside）
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        // 3. 失效本机 Caffeine
        shopLocalCache.invalidate(shop.getId());
        // 4. 广播 Kafka → 其他节点的 Caffeine 也失效
        kafkaTemplate.send(KafkaConfig.CACHE_INVALIDATION_TOPIC, shop.getId().toString());
        // 5. 布隆过滤器更新
        redisBloomFilter.add(BLOOM_SHOP_KEY, shop.getId().toString());
        return Result.ok();
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
