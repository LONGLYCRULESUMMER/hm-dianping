# 登录模块企业级改造 + 讲解 PDF 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 hm-dianping 登录模块从 toy 实现改造到中小企业可用水准（防刷、哈希、锁定、SmsClient 抽象、JSON 会话、滑窗续期），并产出一份 11 页的中文讲解 PDF。

**Architecture:** 改造保持对外 API 不变；新增 `LoginProperties` 集中配置、`SmsClient` 接口与 `LogSmsClient` 默认实现、`LoginSecurityService` 封装防刷/哈希/锁定逻辑；`UserServiceImpl` 与 `RefreshTokenInterceptor` 内部改用 JSON 序列化与滑窗续期；PDF 用 HTML + Mermaid + Playwright 链路渲染。

**Tech Stack:** Java 1.8 / Spring Boot 2.7.18 / Spring Data Redis / Jackson / Hutool / Lombok / Node.js + Playwright（仅生成 PDF）

**前置参考（实施时只读，不可修改）：** `docs/superpowers/specs/2026-05-15-login-module-enterprise-refactor-and-deck-design.md`

---

## 关键约定（所有任务通用）

- **工作目录**：`/Users/javagod/VsCodeProjects/hm-dianping`
- **JDK 编译目标**：1.8（pom 已设置）；本机运行时 JDK 25。`mvn -q -DskipTests compile` 是唯一验证手段。
- **不写自动化测试**。
- **包路径**：`com.hmdp.*`
- **Servlet API**：`javax.servlet.*`（Spring Boot 2.x，非 jakarta）
- **Redis 客户端**：`StringRedisTemplate`
- **Git 提交规范**：每个任务结束都 commit；commit message 用 conventional commits 风格
- **Co-author**：每条 commit 末尾加 `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`

---

## 文件结构总览

**新增文件（4 个 Java + 3 个 PDF 资源）：**

| 路径 | 职责 |
|---|---|
| `src/main/java/com/hmdp/config/LoginProperties.java` | `@ConfigurationProperties(prefix = "hmdp.login")`，集中所有登录相关阈值/TTL |
| `src/main/java/com/hmdp/sms/SmsClient.java` | 短信发送抽象接口 |
| `src/main/java/com/hmdp/sms/LogSmsClient.java` | 默认实现，沿用日志输出 |
| `src/main/java/com/hmdp/service/LoginSecurityService.java` | 封装防刷（重发/日上限）、验证码哈希存取、错误次数与锁定逻辑 |
| `docs/login-module-deck/slides.html` | PDF 源页面 |
| `docs/login-module-deck/build.js` | Playwright 渲染脚本 |
| `docs/login-module-deck/package.json` | Node 依赖声明 |

**修改文件：**

| 路径 | 改动概要 |
|---|---|
| `src/main/java/com/hmdp/utils/RedisConstants.java` | 追加新 Key 常量 |
| `src/main/java/com/hmdp/service/impl/UserServiceImpl.java` | `sendCode` / `login` / `logout` 改写 |
| `src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java` | JSON 反序列化 + 滑窗续期 |
| `src/main/java/com/hmdp/config/MvcConfig.java` | 排除路径迁到常量 |
| `src/main/resources/application.yaml` | 追加 `hmdp.login.*` 配置块 |
| `src/main/java/com/hmdp/HmDianPingApplication.java` | 加 `@EnableConfigurationProperties(LoginProperties.class)` |

**保持不变：** `LoginInterceptor`、`UserController`、entity、mapper、所有数据库 schema。

---

## Task 0：初始化 Git 仓库 + Baseline 提交

**Files:**
- Create: `.gitignore`

- [ ] **Step 1: 初始化 git 仓库（项目当前不是 git 仓库）**

```bash
cd /Users/javagod/VsCodeProjects/hm-dianping
git init
git config user.email "javagod@local"
git config user.name "javagod"
```

Expected: `Initialized empty Git repository`

- [ ] **Step 2: 写 `.gitignore`（如已存在则跳过本步）**

如果文件已存在，使用 `cat .gitignore` 查看，缺哪条加哪条；否则新建：

```gitignore
# Java
target/
*.class
*.jar

# IDE
.idea/
*.iml
.vscode/

# Logs
*.log
logs/

# OS
.DS_Store

# Node (for deck build)
docs/login-module-deck/node_modules/
docs/login-module-deck/package-lock.json

# Local env
*.local
```

- [ ] **Step 3: 提交 baseline**

```bash
cd /Users/javagod/VsCodeProjects/hm-dianping
git add -A
git commit -m "chore: baseline before login module enterprise refactor

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Expected: 一次提交记录所有现有文件。

---

## Task 1：新增 `LoginProperties` 配置类 + yaml 默认值

**Files:**
- Create: `src/main/java/com/hmdp/config/LoginProperties.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/java/com/hmdp/HmDianPingApplication.java`

- [ ] **Step 1: 新建 `LoginProperties.java`**

```java
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
```

- [ ] **Step 2: 修改 `HmDianPingApplication.java`，启用配置绑定**

定位文件并加注解：

```bash
cat src/main/java/com/hmdp/HmDianPingApplication.java
```

在 `@SpringBootApplication` 那行下面追加：

```java
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.hmdp.config.LoginProperties.class)
```

或者用 import + 短注解，等价即可。

- [ ] **Step 3: 在 `application.yaml` 末尾追加 `hmdp.login` 配置块**

```yaml
hmdp:
  login:
    code-ttl-seconds: 120
    token-ttl-seconds: 1800
    refresh-threshold-seconds: 600
    resend-interval-seconds: 60
    daily-send-limit: 10
    fail-limit: 5
    lock-seconds: 600
    code-salt: "hmdp-login-default-salt-CHANGE-ME"
```

- [ ] **Step 4: 编译验证**

```bash
cd /Users/javagod/VsCodeProjects/hm-dianping && mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hmdp/config/LoginProperties.java \
        src/main/java/com/hmdp/HmDianPingApplication.java \
        src/main/resources/application.yaml
git commit -m "feat(login): add LoginProperties for centralized config

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 2：新增 `SmsClient` 抽象 + `LogSmsClient` 默认实现

**Files:**
- Create: `src/main/java/com/hmdp/sms/SmsClient.java`
- Create: `src/main/java/com/hmdp/sms/LogSmsClient.java`

- [ ] **Step 1: 新建 `SmsClient.java`**

```java
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
```

- [ ] **Step 2: 新建 `LogSmsClient.java`**

```java
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
```

- [ ] **Step 3: 编译验证**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/hmdp/sms/
git commit -m "feat(login): introduce SmsClient abstraction with log-based default

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 3：扩充 `RedisConstants`

**Files:**
- Modify: `src/main/java/com/hmdp/utils/RedisConstants.java`

- [ ] **Step 1: 在 `LOGIN_USER_TTL` 那行下方追加新常量**

打开 `src/main/java/com/hmdp/utils/RedisConstants.java`，在第 7 行 `LOGIN_USER_TTL` 之后插入：

```java
    /** 同手机号重发冷却 key 前缀（值无意义，靠存在性判断） */
    public static final String LOGIN_CODE_COOLDOWN_KEY = "login:code:cooldown:";

    /** 同手机号当日发送次数计数 key 前缀，TTL 24h */
    public static final String LOGIN_CODE_COUNT_KEY = "login:code:count:";

    /** 同手机号验证码错误次数计数 key 前缀 */
    public static final String LOGIN_CODE_FAIL_KEY = "login:code:fail:";

    /** 同手机号锁定 key 前缀 */
    public static final String LOGIN_CODE_LOCK_KEY = "login:code:lock:";
```

注意：**不要**修改原有 `LOGIN_CODE_KEY` / `LOGIN_USER_KEY` / 它们的 TTL 字段，保持兼容。

- [ ] **Step 2: 编译验证**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hmdp/utils/RedisConstants.java
git commit -m "feat(login): add Redis key constants for anti-abuse and lock

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 4：新增 `LoginSecurityService`

封装防刷、哈希、错误锁定。`UserServiceImpl` 后续只编排、不感知细节。

**Files:**
- Create: `src/main/java/com/hmdp/service/LoginSecurityService.java`

- [ ] **Step 1: 新建文件，完整代码如下**

```java
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

    // ===== 发送阶段 =====

    /** 是否处于重发冷却期内 */
    public boolean inResendCooldown(String phone) {
        return Boolean.TRUE.equals(
                redis.hasKey(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + phone));
    }

    /** 标记冷却 */
    public void markResendCooldown(String phone) {
        redis.opsForValue().set(
                RedisConstants.LOGIN_CODE_COOLDOWN_KEY + phone,
                "1",
                props.getResendIntervalSeconds(), TimeUnit.SECONDS);
    }

    /** 是否已超过当日发送上限 */
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

    /** 计数 +1，首次设置 24h TTL */
    public void incrementDailyCount(String phone) {
        String key = RedisConstants.LOGIN_CODE_COUNT_KEY + phone;
        Long v = redis.opsForValue().increment(key);
        if (v != null && v == 1L) {
            redis.expire(key, 24, TimeUnit.HOURS);
        }
    }

    /** 哈希存储验证码 */
    public void storeCode(String phone, String rawCode) {
        redis.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone,
                hash(phone, rawCode),
                props.getCodeTtlSeconds(), TimeUnit.SECONDS);
    }

    // ===== 校验阶段 =====

    /** 是否处于锁定期 */
    public boolean isLocked(String phone) {
        return Boolean.TRUE.equals(
                redis.hasKey(RedisConstants.LOGIN_CODE_LOCK_KEY + phone));
    }

    /** 校验验证码：成功返回 true，并清理失败计数与验证码本体 */
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
        // 成功：清理验证码与失败计数
        redis.delete(RedisConstants.LOGIN_CODE_KEY + phone);
        redis.delete(RedisConstants.LOGIN_CODE_FAIL_KEY + phone);
        return true;
    }

    private void registerFailure(String phone) {
        String failKey = RedisConstants.LOGIN_CODE_FAIL_KEY + phone;
        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            // 第一次失败给 fail key 一个保底 TTL（与验证码 TTL 对齐 + 缓冲）
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

    // ===== 内部工具 =====

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
```

- [ ] **Step 2: 编译验证**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hmdp/service/LoginSecurityService.java
git commit -m "feat(login): add LoginSecurityService for anti-abuse, hashing, lockout

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 5：改写 `UserServiceImpl#sendCode` 与 `#login`、`#logout`

**Files:**
- Modify: `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`

整体改造：注入 `SmsClient` / `LoginSecurityService` / `LoginProperties` / `ObjectMapper`；用 JSON 序列化 token；`sendCode` 走防刷 + 哈希；`login` 走锁定校验 + 哈希校验；`logout` 不变（已经只删 token）。

- [ ] **Step 1: 用以下完整内容**替换** `UserServiceImpl.java`（旧内容直接覆盖）

```java
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
```

- [ ] **Step 2: 编译验证**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS。
**若失败常见原因**：`UserDTO` 没有 setter — 检查 `src/main/java/com/hmdp/dto/UserDTO.java`，应有 `@Data` 注解；若没有，本任务不修改 DTO，改回用 `BeanUtil.copyProperties(user, UserDTO.class)`：

```java
UserDTO userDTO = cn.hutool.core.bean.BeanUtil.copyProperties(user, UserDTO.class);
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hmdp/service/impl/UserServiceImpl.java
git commit -m "refactor(login): hash code, anti-abuse checks, JSON token storage

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 6：改写 `RefreshTokenInterceptor`（JSON 反序列化 + 滑窗续期）

**Files:**
- Modify: `src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java`

- [ ] **Step 1: 用以下完整内容覆盖**

```java
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
            // 兼容旧格式（极小概率），直接放行让上游决定
            return true;
        }
        UserHolder.saveUser(userDTO);

        // 滑窗续期：剩余 TTL 小于阈值才续
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
```

- [ ] **Step 2: 编译验证（会因 `MvcConfig` 还在调用旧构造器而失败 — 这是预期）**

```bash
mvn -q -DskipTests compile
```

Expected: 出现编译错误 `constructor RefreshTokenInterceptor in class ... cannot be applied to given types` —— 这是预期，下个任务修。

---

## Task 7：修改 `MvcConfig`（适配新构造器 + 排除路径常量化）

**Files:**
- Modify: `src/main/java/com/hmdp/config/MvcConfig.java`

- [ ] **Step 1: 用以下完整内容覆盖**

```java
package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    /** 不需要登录即可访问的路径（LoginInterceptor 排除清单） */
    private static final String[] PUBLIC_PATHS = {
            "/user/code",
            "/user/login",
            "/blog/hot",
            "/shop/**",
            "/shop-type/**",
            "/upload/**",
            "/voucher/**"
    };

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private LoginProperties loginProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 第一道：刷新 token，拦截所有请求，order=0 优先
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate, objectMapper, loginProperties))
                .addPathPatterns("/**")
                .order(0);

        // 第二道：校验登录态，仅拦截受保护路径
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(PUBLIC_PATHS)
                .order(1);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit（把 Task 6 + Task 7 合并提交）**

```bash
git add src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java \
        src/main/java/com/hmdp/config/MvcConfig.java
git commit -m "refactor(login): JSON-based session + sliding TTL refresh

- RefreshTokenInterceptor reads JSON via ObjectMapper
- Renew TTL only when remaining < refreshThresholdSeconds
- MvcConfig wires new dependencies and externalizes public paths

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 8：整体回归编译 + 打 tag

**Files:** 无

- [ ] **Step 1: clean compile**

```bash
cd /Users/javagod/VsCodeProjects/hm-dianping
mvn -q -DskipTests clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 打 tag 标记改造完成**

```bash
git tag -a refactor/login-enterprise -m "Login module enterprise refactor done"
```

- [ ] **Step 3: 生成 baseline → refactor 的 diff 摘要文件**（PDF 制作时引用）

```bash
mkdir -p docs/login-module-deck/diffs
git log --oneline > docs/login-module-deck/diffs/commits.txt
git diff $(git rev-list --max-parents=0 HEAD) HEAD -- \
    src/main/java/com/hmdp/service/impl/UserServiceImpl.java \
    > docs/login-module-deck/diffs/UserServiceImpl.diff
git diff $(git rev-list --max-parents=0 HEAD) HEAD -- \
    src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java \
    > docs/login-module-deck/diffs/RefreshTokenInterceptor.diff
```

Expected: 3 个文件生成在 `docs/login-module-deck/diffs/`

- [ ] **Step 4: Commit diff 产物**

```bash
git add docs/login-module-deck/diffs/
git commit -m "docs(deck): snapshot baseline-vs-refactor diffs for slides

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 9：搭建 PDF deck 工程骨架

**Files:**
- Create: `docs/login-module-deck/package.json`
- Create: `docs/login-module-deck/build.js`
- Create: `docs/login-module-deck/styles.css`

- [ ] **Step 1: 新建 `package.json`**

```json
{
  "name": "hmdp-login-deck",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "build": "node build.js"
  },
  "dependencies": {
    "playwright": "^1.45.0"
  }
}
```

- [ ] **Step 2: 新建 `styles.css`（深色 slides 风格 + 中文字体）**

```css
@page {
  size: A4 landscape;
  margin: 0;
}

* { box-sizing: border-box; }

html, body {
  margin: 0;
  padding: 0;
  font-family: "PingFang SC", "Source Han Sans CN", "Microsoft YaHei", "Hiragino Sans GB", sans-serif;
  color: #e6edf3;
  background: #0d1117;
}

.slide {
  width: 297mm;
  height: 210mm;
  padding: 18mm 22mm;
  page-break-after: always;
  display: flex;
  flex-direction: column;
  background: linear-gradient(135deg, #0d1117 0%, #161b22 100%);
  position: relative;
  overflow: hidden;
}

.slide:last-child { page-break-after: auto; }

.slide-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  border-bottom: 2px solid #30363d;
  padding-bottom: 8mm;
  margin-bottom: 10mm;
}

.slide-title {
  font-size: 28pt;
  font-weight: 700;
  color: #58a6ff;
  margin: 0;
}

.slide-num {
  font-size: 12pt;
  color: #6e7681;
}

.slide-body {
  flex: 1;
  font-size: 14pt;
  line-height: 1.7;
}

.cover {
  align-items: center;
  justify-content: center;
  text-align: center;
}
.cover h1 { font-size: 48pt; color: #58a6ff; margin: 0 0 6mm; }
.cover h2 { font-size: 22pt; color: #c9d1d9; font-weight: 400; margin: 0 0 14mm; }
.cover .badges span {
  display: inline-block;
  background: #21262d;
  color: #79c0ff;
  padding: 4px 12px;
  margin: 4px;
  border-radius: 14px;
  font-size: 11pt;
}

ul, ol { padding-left: 1.2em; }
li { margin-bottom: 6px; }

.bad { color: #f85149; }
.good { color: #3fb950; }
.accent { color: #d2a8ff; }

table {
  border-collapse: collapse;
  width: 100%;
  font-size: 12pt;
}
th, td {
  border: 1px solid #30363d;
  padding: 6px 10px;
  text-align: left;
}
th { background: #161b22; color: #58a6ff; }

pre {
  background: #161b22;
  border: 1px solid #30363d;
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 10pt;
  overflow: hidden;
  color: #e6edf3;
}
code { font-family: "JetBrains Mono", "SF Mono", Menlo, monospace; }

.diff-add { color: #3fb950; }
.diff-del { color: #f85149; }

.mermaid {
  background: #ffffff;
  border-radius: 8px;
  padding: 10px;
  display: flex;
  justify-content: center;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12mm;
}

.callout {
  border-left: 4px solid #d2a8ff;
  padding: 6px 12px;
  background: #161b22;
  margin: 8mm 0;
  font-size: 13pt;
}
```

- [ ] **Step 3: 新建 `build.js`**

```javascript
const { chromium } = require('playwright');
const path = require('path');

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();

  const url = 'file://' + path.resolve(__dirname, 'slides.html');
  await page.goto(url, { waitUntil: 'networkidle' });

  // 等 mermaid 全部渲染完成
  await page.waitForFunction(() => {
    const sources = document.querySelectorAll('.mermaid');
    if (sources.length === 0) return true;
    // mermaid 渲染后会插入 <svg>
    return Array.from(sources).every(el => el.querySelector('svg'));
  }, { timeout: 30000 });

  await page.pdf({
    path: path.resolve(__dirname, 'login-module.pdf'),
    format: 'A4',
    landscape: true,
    printBackground: true,
    margin: { top: 0, right: 0, bottom: 0, left: 0 }
  });

  await browser.close();
  console.log('PDF generated: login-module.pdf');
})();
```

- [ ] **Step 4: Commit**

```bash
git add docs/login-module-deck/package.json \
        docs/login-module-deck/styles.css \
        docs/login-module-deck/build.js
git commit -m "docs(deck): scaffold deck build pipeline (Playwright + Mermaid)

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 10：编写 11 页 slides 内容

**Files:**
- Create: `docs/login-module-deck/slides.html`

- [ ] **Step 1: 新建 `slides.html`（完整 11 页内容如下）**

> 注意：所有 Mermaid 块写在 `<pre class="mermaid">` 内（用 `pre` 而非 `div` 是为了让 mermaid CDN 把里面文本作为源识别后注入 SVG）。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>黑马点评 · 登录模块（企业化改造版）</title>
<link rel="stylesheet" href="styles.css">
<script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
<script>
  mermaid.initialize({
    startOnLoad: true,
    theme: 'default',
    fontFamily: '"PingFang SC","Source Han Sans CN",sans-serif'
  });
</script>
</head>
<body>

<!-- Slide 1: 封面 -->
<section class="slide cover">
  <h1>黑马点评 · 登录模块</h1>
  <h2>从 Toy 实现到企业化改造</h2>
  <div class="badges">
    <span>Spring Boot 2.7</span>
    <span>Redis</span>
    <span>StringRedisTemplate</span>
    <span>Jackson</span>
    <span>双拦截器</span>
    <span>SHA-256</span>
  </div>
</section>

<!-- Slide 2: 模块全景 -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">模块全景</h2><span class="slide-num">02 / 11</span>
  </div>
  <div class="slide-body">
    <pre class="mermaid">
flowchart LR
  C[Client / 前端] -->|HTTP| N[Nginx]
  N --> A[Spring Boot 应用]
  A -->|读写 token / 验证码 / 防刷计数| R[(Redis)]
  A -->|查/建用户| M[(MySQL)]
  A -.->|发送| S[SmsClient<br/>默认 LogSmsClient]

  subgraph A
    direction TB
    F1[RefreshTokenInterceptor<br/>所有请求]
    F2[LoginInterceptor<br/>受保护路径]
    UC[UserController]
    US[UserServiceImpl]
    LS[LoginSecurityService]
    F1 --> F2 --> UC --> US --> LS
  end
    </pre>
  </div>
</section>

<!-- Slide 3: Toy 味问题 -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">原版本的 6 个 "Toy 味" 问题</h2><span class="slide-num">03 / 11</span>
  </div>
  <div class="slide-body">
    <ol>
      <li><span class="bad">①</span> 同手机号可<strong>无限刷验证码</strong>（无 60s 间隔，无日上限）—— 一夜烧光短信费</li>
      <li><span class="bad">②</span> 验证码<strong>明文存 Redis</strong> —— Redis 一旦泄露 = 验证码全裸</li>
      <li><span class="bad">③</span> 验证码<strong>错误次数不限</strong> —— 6 位数字暴力枚举可破</li>
      <li><span class="bad">④</span> 短信发送<strong>写死成 log.debug</strong> —— 永远接不上真实服务商</li>
      <li><span class="bad">⑤</span> Token 续期<strong>每次请求都写 Redis</strong> —— 写放大，热 key 风险</li>
      <li><span class="bad">⑥</span> 用户信息用 <strong>Hash 存</strong>，字段全转 String —— 类型丢失，DTO 还得手动转</li>
    </ol>
    <div class="callout">这 6 个点，企业里任何一个都会被 review 打回。</div>
  </div>
</section>

<!-- Slide 4: 演进 -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">方案演进：Session → Redis Token</h2><span class="slide-num">04 / 11</span>
  </div>
  <div class="slide-body">
    <pre class="mermaid">
flowchart LR
  subgraph V1[① 单机 Session]
    C1[Client] --> T1[Tomcat<br/>HttpSession]
  end
  subgraph V2[② 多实例 Session 复制]
    C2[Client] --> LB2[LB] --> T2A[Tomcat A]
    LB2 --> T2B[Tomcat B]
    T2A <-.复制成本高.-> T2B
  end
  subgraph V3[③ Redis 集中存储]
    C3[Client] --> LB3[LB] --> T3A[Tomcat A]
    LB3 --> T3B[Tomcat B]
    T3A --> R[(Redis<br/>token→user)]
    T3B --> R
  end
    </pre>
    <p style="margin-top:8mm">
      <span class="bad">①</span> 单点不可扩；
      <span class="bad">②</span> 复制带宽 + 一致性都难；
      <span class="good">③</span> 状态外置 Redis，无状态应用，水平扩展无压力。
    </p>
  </div>
</section>

<!-- Slide 5: 主流程时序图 -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">主流程时序图（改造后）</h2><span class="slide-num">05 / 11</span>
  </div>
  <div class="slide-body">
    <pre class="mermaid">
sequenceDiagram
  participant U as 用户
  participant A as Spring Boot
  participant SEC as LoginSecurityService
  participant R as Redis
  participant DB as MySQL
  participant SMS as SmsClient

  U->>A: POST /user/code (phone)
  A->>SEC: 锁定/冷却/日上限 检查
  SEC->>R: hasKey(lock/cooldown/count)
  R-->>SEC: 状态
  SEC-->>A: ok
  A->>SEC: storeCode + 标记冷却 + 计数+1
  SEC->>R: SET hash(code) EX 120; SETEX cooldown; INCR count
  A->>SMS: send(phone, code)
  A-->>U: 200

  U->>A: POST /user/login (phone, code)
  A->>SEC: 锁定检查 + verifyCode
  SEC->>R: GET stored_hash
  SEC->>SEC: hash(input)==stored?
  alt 失败
    SEC->>R: INCR fail; 达阈值则 SET lock
    SEC-->>A: false
    A-->>U: 验证码错误
  else 成功
    SEC->>R: DEL code, fail
    A->>DB: 查/建用户
    A->>R: SET token JSON EX 1800
    A-->>U: token
  end
    </pre>
  </div>
</section>

<!-- Slide 6: 安全层 ① -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">安全层 ①：防刷 + 哈希 + 锁定</h2><span class="slide-num">06 / 11</span>
  </div>
  <div class="slide-body">
    <div class="two-col">
      <div>
        <h3 style="color:#58a6ff">三道闸门</h3>
        <ul>
          <li><strong>冷却</strong>：60s 内重复发同号 → 拒绝</li>
          <li><strong>日上限</strong>：单号每日 10 次 → 拒绝</li>
          <li><strong>锁定</strong>：连错 5 次 → 锁 10 分钟，期间发码/登录都拒</li>
        </ul>
        <h3 style="color:#58a6ff;margin-top:10mm">哈希存储</h3>
        <p>Redis 存 <code>SHA-256(phone + code + salt)</code>，盐通过 <code>hmdp.login.code-salt</code> 配置。</p>
        <p>就算 Redis 被脱库，攻击者也拿不到原始 code。</p>
      </div>
      <div>
        <pre class="mermaid">
stateDiagram-v2
  [*] --> Idle
  Idle --> Cooldown: 发码
  Cooldown --> Idle: 60s 后
  Idle --> Verifying: 用户提交
  Verifying --> Idle: 成功(清失败计数)
  Verifying --> Failed: 失败 fail+1
  Failed --> Idle: fail < 5
  Failed --> Locked: fail ≥ 5
  Locked --> Idle: 10min 后自动解锁
        </pre>
      </div>
    </div>
  </div>
</section>

<!-- Slide 7: 安全层 ② SmsClient -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">安全层 ②：SmsClient 抽象</h2><span class="slide-num">07 / 11</span>
  </div>
  <div class="slide-body">
    <div class="two-col">
      <div>
        <h3 style="color:#58a6ff">为什么要抽象</h3>
        <ul>
          <li>原版直接 <code>log.debug</code>，永远上不了生产</li>
          <li>不同环境需要不同实现：日志 / 阿里云 / 腾讯云 / 自研网关</li>
          <li>测试态可注入 Mock，记录调用</li>
        </ul>
        <h3 style="color:#58a6ff;margin-top:10mm">实现替换方式</h3>
        <p>新增 <code>AliyunSmsClient implements SmsClient</code>，加 <code>@Primary</code> 或 <code>@ConditionalOnProperty</code> 即可，<strong>UserServiceImpl 零改动</strong>。</p>
      </div>
      <div>
        <pre class="mermaid">
classDiagram
  class SmsClient {
    <<interface>>
    +sendVerificationCode(phone, code)
  }
  class LogSmsClient {
    +sendVerificationCode(phone, code)
  }
  class AliyunSmsClient {
    +sendVerificationCode(phone, code)
  }
  class UserServiceImpl {
    -SmsClient smsClient
  }
  SmsClient <|.. LogSmsClient
  SmsClient <|.. AliyunSmsClient
  UserServiceImpl --> SmsClient
        </pre>
      </div>
    </div>
  </div>
</section>

<!-- Slide 8: 会话改造 ① JSON vs Hash -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">会话改造 ①：JSON 替代 Hash</h2><span class="slide-num">08 / 11</span>
  </div>
  <div class="slide-body">
    <table>
      <tr><th></th><th>Hash（原版）</th><th>JSON String（改造后）</th></tr>
      <tr><td>读写命令</td><td>HSET / HGETALL，多 field</td><td>SET / GET，一次完成</td></tr>
      <tr><td>类型保真</td><td class="bad">所有值转 String，Long → "123"</td><td class="good">Jackson 反序列化还原</td></tr>
      <tr><td>反序列化代码量</td><td>BeanUtil.fillBeanWithMap，依赖反射猜类型</td><td>objectMapper.readValue，类型明确</td></tr>
      <tr><td>部分字段更新</td><td class="good">支持 HSET 单字段</td><td class="bad">需要整体 GET → 改 → SET</td></tr>
      <tr><td>调试可读性</td><td>HGETALL 多字段</td><td class="good">GET 一行 JSON 一目了然</td></tr>
    </table>
    <div class="callout">
      <strong>取舍：</strong>登录态字段总量小（id/nickName/icon），<strong>整体读写</strong>是常态，没有"只改一个字段"的场景。JSON 更合适。
    </div>
  </div>
</section>

<!-- Slide 9: 会话改造 ② 滑窗续期 -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">会话改造 ②：滑窗续期</h2><span class="slide-num">09 / 11</span>
  </div>
  <div class="slide-body">
    <div class="two-col">
      <div>
        <h3 style="color:#58a6ff">原版问题</h3>
        <p>每次请求 → <code>EXPIRE</code> 一次。活跃用户每秒 N 个请求 = 每秒 N 次 Redis 写。</p>
        <h3 style="color:#58a6ff;margin-top:8mm">改造</h3>
        <p>读 token 后先 <code>TTL</code>，仅当剩余 &lt; 阈值（默认 600s）才 <code>EXPIRE</code>。</p>
        <pre><code>Long ttl = redis.getExpire(key, SECONDS);
if (ttl &gt; 0 &amp;&amp; ttl &lt; props.getRefreshThresholdSeconds()) {
    redis.expire(key, props.getTokenTtlSeconds(), SECONDS);
}</code></pre>
      </div>
      <div>
        <pre class="mermaid">
gantt
  title Token 续期对比（30 分钟 TTL）
  dateFormat  X
  axisFormat  %s
  section 原版（每请求都续）
  Req1 写: 0, 1
  Req2 写: 5, 1
  Req3 写: 10, 1
  Req4 写: 15, 1
  Req5 写: 20, 1
  Req6 写: 25, 1
  section 改造（仅临近过期续）
  Req1 跳过: 0, 1
  Req2 跳过: 5, 1
  Req3 跳过: 10, 1
  Req4 跳过: 15, 1
  Req5 跳过: 20, 1
  Req6 写: 25, 1
        </pre>
        <p style="text-align:center;margin-top:4mm">
          活跃会话写次数 <span class="bad">6→1</span>（约 -83%）
        </p>
      </div>
    </div>
  </div>
</section>

<!-- Slide 10: 双拦截器 -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">双拦截器设计</h2><span class="slide-num">10 / 11</span>
  </div>
  <div class="slide-body">
    <pre class="mermaid">
flowchart TB
  R[请求进入] --> I1{RefreshTokenInterceptor<br/>order=0, 拦截 /**}
  I1 -->|有 token & 有效| W[ThreadLocal 存 user<br/>必要时滑窗续期]
  I1 -->|无 token / 无效| P1[直接放行]
  W --> I2{LoginInterceptor<br/>order=1, 排除公开路径}
  P1 --> I2
  I2 -->|公开路径| H[Handler]
  I2 -->|受保护路径 & ThreadLocal 为空| X401[401]
  I2 -->|受保护路径 & 已登录| H
  H --> AC[afterCompletion: 清理 ThreadLocal]
    </pre>
    <div class="callout">
      <strong>为什么拆两个？</strong>因为续期需要对<strong>所有请求</strong>生效（包括 /shop 这种不需要登录的查询，登录用户访问也希望续期），但拦登录态只能限定在<strong>受保护路径</strong>。一个拦截器同时承担两件事 → 排除规则会互相打架。
    </div>
  </div>
</section>

<!-- Slide 11: 总结 + 扩展 -->
<section class="slide">
  <div class="slide-header">
    <h2 class="slide-title">总结 & 可继续扩展方向</h2><span class="slide-num">11 / 11</span>
  </div>
  <div class="slide-body">
    <pre class="mermaid">
mindmap
  root((登录模块))
    本次完成
      安全层
        60s 重发
        日 10 次上限
        SHA-256 哈希
        5 错锁 10min
        SmsClient 抽象
      会话层
        JSON 序列化
        滑窗续期
        参数全配置化
    可扩展
      多端登录控制
        手机号→活跃 token Set
        踢下线
      审计
        登录事件异步发布
        Kafka / MQ 落库
      认证形态
        JWT + Refresh Token
      数据保护
        手机号加密列
        脱敏返回
    </pre>
  </div>
</section>

</body>
</html>
```

- [ ] **Step 2: 用浏览器肉眼快速验证 HTML 不报错（可选）**

```bash
open docs/login-module-deck/slides.html
```

如系统弹出浏览器，目视检查 11 页结构、Mermaid 图能渲染即可。

- [ ] **Step 3: Commit**

```bash
git add docs/login-module-deck/slides.html
git commit -m "docs(deck): author 11-slide login module deck content

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 11：安装 Playwright + 渲染 PDF

**Files:** 无新增

- [ ] **Step 1: 安装依赖**

```bash
cd /Users/javagod/VsCodeProjects/hm-dianping/docs/login-module-deck
npm install
npx playwright install chromium
```

Expected: Chromium 下载完成，无错误。

- [ ] **Step 2: 渲染 PDF**

```bash
node build.js
```

Expected: 控制台输出 `PDF generated: login-module.pdf`，并在当前目录产出 `login-module.pdf`。

- [ ] **Step 3: 目视检查 PDF**

```bash
open login-module.pdf
```

检查清单：
- 共 11 页
- 中文无方块（PingFang 正常显示）
- 所有 Mermaid 图为渲染后的图（无源码裸露的 `flowchart LR ...`）
- 颜色与 slides 风格一致（深底 + 蓝标题）

如有 Mermaid 未渲染，把 `build.js` 的 `waitForFunction` 超时改成 60s 重试。
如有中文方块，给 `body` 强制加 `font-family: "PingFang SC", sans-serif !important`。

- [ ] **Step 4: Commit PDF 与 lockfile**

```bash
cd /Users/javagod/VsCodeProjects/hm-dianping
git add docs/login-module-deck/login-module.pdf
# package-lock.json 在 .gitignore 中已忽略；如未忽略也可 git add
git commit -m "docs(deck): build final login-module.pdf

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 12：收尾

- [ ] **Step 1: 全量编译再跑一次**

```bash
cd /Users/javagod/VsCodeProjects/hm-dianping && mvn -q -DskipTests clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 输出最终交付物清单**

```bash
echo "=== 改造文件 ==="
git diff --name-only refactor/login-enterprise~10 refactor/login-enterprise -- src/
echo "=== Deck 产物 ==="
ls -la docs/login-module-deck/
echo "=== Spec / Plan ==="
ls -la docs/superpowers/specs/ docs/superpowers/plans/
```

预期最终交付：
1. 改造后的登录模块代码（已编译通过、已打 tag `refactor/login-enterprise`）
2. `docs/login-module-deck/login-module.pdf`（11 页中文讲解）
3. spec + plan 文档（`docs/superpowers/`）

---

## 自检（plan 完成后由作者执行）

- ✅ 所有 spec 章节有对应 task：A1（Task 3+4+5）/ A2（Task 6+7）/ A3（Task 1+7）/ Part B 11 页（Task 9+10+11）
- ✅ 无 placeholder（无 TBD/TODO/"add appropriate validation"）
- ✅ 类型一致：`SmsClient.sendVerificationCode(phone, code)` 在 Task 2 定义、Task 5 调用，签名一致；`LoginSecurityService` 公开方法 `inResendCooldown` / `markResendCooldown` / `exceededDailyLimit` / `incrementDailyCount` / `storeCode` / `isLocked` / `verifyCode` 在 Task 4 定义、Task 5 全部调用过
- ✅ 文件路径全部绝对/相对仓库根明确
- ✅ 每步都有 Expected 输出或验证手段
