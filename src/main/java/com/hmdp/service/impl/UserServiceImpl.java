package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.LoginProperties;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.service.LoginSecurityService;
import com.hmdp.sms.SmsClient;
import com.hmdp.utils.RegexUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SmsClient smsClient;
    private final LoginSecurityService security;
    private final LoginProperties props;
    private final ObjectMapper objectMapper;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        if (security.isLocked(phone)) {
            return Result.fail("操作过于频繁，请稍后再试");
        }
        if (security.inResendCooldown(phone)) {
            return Result.fail("发送过于频繁，请稍后再试");
        }
        if (security.exceededDailyLimit(phone)) {
            return Result.fail("今日发送次数已达上限");
        }

        String code = RandomUtil.randomNumbers(6);
        security.storeCode(phone, code);
        security.markResendCooldown(phone);
        security.incrementDailyCount(phone);

        smsClient.sendVerificationCode(phone, code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        if (security.isLocked(phone)) {
            return Result.fail("账号已锁定，请稍后再试");
        }
        if (!security.verifyCode(phone, loginForm.getCode())) {
            return Result.fail("验证码错误");
        }

        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
        }

        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setNickName(user.getNickName());
        userDTO.setIcon(user.getIcon());

        String json;
        try {
            json = objectMapper.writeValueAsString(userDTO);
        } catch (JsonProcessingException e) {
            log.error("serialize UserDTO failed", e);
            return Result.fail("登录失败，请重试");
        }

        stringRedisTemplate.opsForValue().set(
                LOGIN_USER_KEY + token,
                json,
                props.getTokenTtlSeconds(), TimeUnit.SECONDS);

        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        if (token == null || token.trim().isEmpty()) {
            return Result.ok();
        }
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        return Result.ok();
    }
}
