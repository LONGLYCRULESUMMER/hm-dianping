package com.hmdp.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.LoginProperties;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final LoginProperties props;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate,
                                   ObjectMapper objectMapper,
                                   LoginProperties props) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("authorization");
        if (token == null || token.trim().isEmpty()) {
            return true;
        }
        String key = LOGIN_USER_KEY + token;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return true;
        }
        UserDTO userDTO;
        try {
            userDTO = objectMapper.readValue(json, UserDTO.class);
        } catch (Exception e) {
            return true;
        }
        UserHolder.saveUser(userDTO);

        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0 && ttl < props.getRefreshThresholdSeconds()) {
            stringRedisTemplate.expire(key, props.getTokenTtlSeconds(), TimeUnit.SECONDS);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}
