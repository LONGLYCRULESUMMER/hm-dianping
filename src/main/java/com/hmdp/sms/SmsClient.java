package com.hmdp.sms;

/**
 * 短信发送抽象接口。
 * 默认实现为 {@link LogSmsClient}（仅打日志）。
 * 接入真实服务商时新增实现并通过配置切换即可。
 */
public interface SmsClient {

    /**
     * 发送验证码短信。
     *
     * @param phone 接收手机号
     * @param code  6 位数字验证码
     */
    void sendVerificationCode(String phone, String code);
}
