package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期数据包装：缓存永不过期（不设 TTL），
 * 通过 expireTime 字段在业务层判断是否"逻辑过期"。
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
