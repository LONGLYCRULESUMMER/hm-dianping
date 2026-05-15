package com.hmdp.service;

import com.hmdp.config.LoginProperties;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * 登录相关的"安全/防刷"逻辑。
 * 把 Redis key 操作和判断收敛在这里，便于讲解与替换实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginSecurityService {

    private final StringRedisTemplate redis;
    private final LoginProperties props;

    public boolean inResendCooldown(String phone) {
        return Boolean.TRUE.equals(
                redis.hasKey(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + phone));
    }

    public void markResendCooldown(String phone) {
        redis.opsForValue().set(
                RedisConstants.LOGIN_CODE_COOLDOWN_KEY + phone,
                "1",
                props.getResendIntervalSeconds(), TimeUnit.SECONDS);
    }

    public boolean exceededDailyLimit(String phone) {
        String key = RedisConstants.LOGIN_CODE_COUNT_KEY + phone;
        String v = redis.opsForValue().get(key);
        if (v == null) {
            return false;
        }
        try {
            return Integer.parseInt(v) >= props.getDailySendLimit();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void incrementDailyCount(String phone) {
        String key = RedisConstants.LOGIN_CODE_COUNT_KEY + phone;
        Long v = redis.opsForValue().increment(key);
        if (v != null && v == 1L) {
            redis.expire(key, 24, TimeUnit.HOURS);
        }
    }

    public void storeCode(String phone, String rawCode) {
        redis.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone,
                hash(phone, rawCode),
                props.getCodeTtlSeconds(), TimeUnit.SECONDS);
    }

    public boolean isLocked(String phone) {
        return Boolean.TRUE.equals(
                redis.hasKey(RedisConstants.LOGIN_CODE_LOCK_KEY + phone));
    }

    public boolean verifyCode(String phone, String rawCode) {
        if (rawCode == null || rawCode.isEmpty()) {
            registerFailure(phone);
            return false;
        }
        String stored = redis.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (stored == null) {
            registerFailure(phone);
            return false;
        }
        if (!stored.equals(hash(phone, rawCode))) {
            registerFailure(phone);
            return false;
        }
        redis.delete(RedisConstants.LOGIN_CODE_KEY + phone);
        redis.delete(RedisConstants.LOGIN_CODE_FAIL_KEY + phone);
        return true;
    }

    private void registerFailure(String phone) {
        String failKey = RedisConstants.LOGIN_CODE_FAIL_KEY + phone;
        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            redis.expire(failKey, props.getCodeTtlSeconds() + 60, TimeUnit.SECONDS);
        }
        if (count != null && count >= props.getFailLimit()) {
            redis.opsForValue().set(
                    RedisConstants.LOGIN_CODE_LOCK_KEY + phone,
                    "1",
                    props.getLockSeconds(), TimeUnit.SECONDS);
            log.warn("phone {} locked due to too many failed attempts", phone);
        }
    }

    private String hash(String phone, String rawCode) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((phone + ":" + rawCode + ":" + props.getCodeSalt())
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
