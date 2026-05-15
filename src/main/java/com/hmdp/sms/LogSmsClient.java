package com.hmdp.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogSmsClient implements SmsClient {

    @Override
    public void sendVerificationCode(String phone, String code) {
        log.info("[SMS] -> phone={}, code={} (LogSmsClient: not actually sent)", phone, code);
    }
}
