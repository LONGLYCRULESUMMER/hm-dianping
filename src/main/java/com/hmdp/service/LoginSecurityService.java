package com.hmdp.service;

import com.hmdp.config.LoginProperties;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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

    private static final String DEFAULT_INSECURE_SALT = "hmdp-login-default-salt-CHANGE-ME";

    private final StringRedisTemplate redis;
    private final LoginProperties props;

    @PostConstruct
    void warnIfUsingDefaultSalt() {
        if (DEFAULT_INSECURE_SALT.equals(props.getCodeSalt())) {
            log.warn("[SECURITY] hmdp.login.code-salt is using the built-in default value. " +
                    "Set a unique value via configuration before deploying to production.");
        }
    }

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
        // 先用 SETNX + EX 原子性地"如果 key 不存在则创建并带 24h TTL"，
        // 再 INCR；这样无论是否存在崩溃窗口，TTL 都已经先于计数被设置好。
        redis.opsForValue().setIfAbsent(key, "0", 24, TimeUnit.HOURS);
        redis.opsForValue().increment(key);
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
        // 同样的原子化 TTL 模式：先 SETNX 占位带 TTL，再 INCR
        redis.opsForValue().setIfAbsent(failKey, "0",
                props.getCodeTtlSeconds() + 60, TimeUnit.SECONDS);
        Long count = redis.opsForValue().increment(failKey);
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
