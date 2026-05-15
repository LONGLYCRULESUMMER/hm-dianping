package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 手写版 Redis 分布式锁（v5）
 * 特性：
 *   - SETNX + 过期时间防死锁
 *   - UUID + 线程ID 标识，防误删
 *   - Lua 脚本保证释放锁的原子性
 * 不支持：可重入、自动续期、主从一致性
 */
public class SimpleRedisLock {

    private final String name;                              // 锁的业务名（如 order:5）
    private final StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";

    // JVM 级别的唯一前缀，保证跨服务器不重复
    // toString(true) 去掉 UUID 中间的横线，缩短长度
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    // 提前把 Lua 脚本加载成常量，避免每次执行都解析
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试获取锁
     * @param timeoutSec 锁的自动过期时间
     * @return true=加锁成功，false=锁被别人占用
     */
    public boolean tryLock(long timeoutSec) {
        // 线程唯一标识 = JVM前缀 + 线程ID
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // SET key value NX EX 超时时间
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁（通过 Lua 脚本原子执行：判断归属 + 删除）
     */
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
