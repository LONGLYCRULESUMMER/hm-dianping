package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

@Component
public class RedisBloomFilter {
    private static final long DEFAULT_SIZE = 1L << 26;
    private static final int DEFAULT_HASH_COUNT = 6;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void add(String key, String value) {
        if (value == null) {
            return;
        }
        long[] offsets = getOffsets(value);
        for (long offset : offsets) {
            stringRedisTemplate.opsForValue().setBit(key, offset, true);
        }
    }

    public boolean mightContain(String key, String value) {
        if (value == null) {
            return false;
        }
        long[] offsets = getOffsets(value);
        for (long offset : offsets) {
            Boolean bit = stringRedisTemplate.opsForValue().getBit(key, offset);
            if (!Boolean.TRUE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    private long[] getOffsets(String value) {
        long[] offsets = new long[DEFAULT_HASH_COUNT];
        long hash1 = fnv1a64(value, 0xcbf29ce484222325L);
        long hash2 = fnv1a64(value, 0x84222325cbf29ce4L);
        for (int i = 0; i < DEFAULT_HASH_COUNT; i++) {
            long combinedHash = hash1 + i * hash2;
            offsets[i] = (combinedHash & Long.MAX_VALUE) % DEFAULT_SIZE;
        }
        return offsets;
    }

    private long fnv1a64(String value, long seed) {
        long hash = seed;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
