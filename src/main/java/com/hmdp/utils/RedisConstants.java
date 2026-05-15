package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    /** 同手机号重发冷却 key 前缀（值无意义，靠存在性判断） */
    public static final String LOGIN_CODE_COOLDOWN_KEY = "login:code:cooldown:";

    /** 同手机号当日发送次数计数 key 前缀，TTL 24h */
    public static final String LOGIN_CODE_COUNT_KEY = "login:code:count:";

    /** 同手机号验证码错误次数计数 key 前缀 */
    public static final String LOGIN_CODE_FAIL_KEY = "login:code:fail:";

    /** 同手机号锁定 key 前缀 */
    public static final String LOGIN_CODE_LOCK_KEY = "login:code:lock:";

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String BLOOM_SHOP_KEY = "bloom:shop";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
