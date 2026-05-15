package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hmdp.login")
public class LoginProperties {

    /** 验证码 TTL（秒） */
    private long codeTtlSeconds = 120;

    /** Token TTL（秒） */
    private long tokenTtlSeconds = 1800;

    /** 续期阈值（秒）：剩余 TTL 小于该值时才续期 */
    private long refreshThresholdSeconds = 600;

    /** 同手机号重发间隔（秒） */
    private long resendIntervalSeconds = 60;

    /** 同手机号每日发送次数上限 */
    private int dailySendLimit = 10;

    /** 同手机号验证码错误次数上限（达到后锁定） */
    private int failLimit = 5;

    /** 锁定时长（秒） */
    private long lockSeconds = 600;

    /** 验证码哈希盐 */
    private String codeSalt = "hmdp-login-default-salt-CHANGE-ME";
}
