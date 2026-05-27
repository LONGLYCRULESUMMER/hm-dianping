package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisBloomFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.BLOOM_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisBloomFilter redisBloomFilter;

    @Resource
    private Cache<Long, Shop> shopLocalCache;

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
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        shopLocalCache.invalidate(shop.getId());
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
