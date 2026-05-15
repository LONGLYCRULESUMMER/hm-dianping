# 登录模块企业级改造 + 讲解 PDF 设计文档

> 日期：2026-05-15
> 范围：黑马点评（hm-dianping）登录模块
> 产出：① 改造后的登录模块代码 ② 面向技术答辩的讲解 PDF

---

## 1. 背景与目标

当前项目登录模块是典型的"教学版/toy 版"实现：手机号 + 短信验证码 + Redis Token + 双拦截器。代码可跑通，但与企业生产环境的可用性/安全性差距较大。

**目标**：

1. 在不引入额外中间件、不改变对外 API 的前提下，把登录模块改造到"在中小企业内部基本可用"的水准（中量改造，覆盖问题 1-6）。
2. 基于改造后的代码，制作一份**面向技术答辩/面试讲解**的 PDF（中文，强可视化，11 页），通过"原版本问题 → 改造方案 → 改造效果"的叙事突出工程思考。

**非目标**：

- 不接入真实短信服务商（仅抽象出可插拔接口）
- 不改造数据库 schema（手机号加密存储、登录审计落库等不在本次范围）
- 不引入 JWT、不做多端登录控制、不做登录事件异步发布
- 不写自动化测试（仅要求 `mvn compile` 通过）

---

## 2. Part A：登录模块改造方案

### 2.1 当前实现概览

| 文件 | 当前职责 |
|---|---|
| `UserController` | `/user/code`、`/user/login`、`/user/logout`、`/user/me` 等 |
| `UserServiceImpl#sendCode` | 校验手机号 → 生成 6 位数字 → 明文存 Redis（`login:code:{phone}`，TTL 2min）→ `log.debug` 输出 |
| `UserServiceImpl#login` | 校验手机号 → 比对验证码 → 不存在则建用户 → 生成 UUID token → 用户信息 Hash 存 Redis（`login:token:{token}`，TTL 30min） |
| `RefreshTokenInterceptor` | 全量请求拦截：取 token → 读 Redis → 写 ThreadLocal → 续 TTL 30min |
| `LoginInterceptor` | 受保护路径拦截：ThreadLocal 为空则 401 |
| `MvcConfig` | 注册两个拦截器，排除 `/user/code`、`/user/login` 等 |

### 2.2 改造点（A1 安全层 / A2 会话优化 / A3 工程化）

#### A1 安全层

| 改造点 | 实现方案 | 涉及 Redis Key |
|---|---|---|
| 60 秒重发限制 | `sendCode` 入口先 `SETNX login:code:cooldown:{phone} 1 EX 60`，失败即拒 | `login:code:cooldown:{phone}` |
| 单手机号每日发送上限（默认 10 次） | `INCR login:code:count:{phone}`，首次 `EXPIRE 86400`；超阈值拒绝 | `login:code:count:{phone}` |
| 验证码哈希存储 | 存 `SHA-256(phone + ":" + code + ":" + salt)`，盐取自配置；校验时同算法对比 | `login:code:{phone}` 内容由明文改为 hash |
| 错误次数锁定（5 次锁 10min） | 校验失败时 `INCR login:code:fail:{phone}`，达阈值起 `SET login:code:lock:{phone} 1 EX 600`；任何登录请求先看锁 | `login:code:fail:{phone}` / `login:code:lock:{phone}` |
| 抽象 `SmsClient` | 新增接口 `SmsClient { void send(String phone, String code) }`；提供默认实现 `LogSmsClient`（沿用 log 输出）；`UserServiceImpl` 通过 Spring 注入使用 | — |

#### A2 会话优化

| 改造点 | 实现方案 |
|---|---|
| Hash → JSON 序列化 | 引入 `ObjectMapper`（Spring Boot 自带），登录时 `SET login:token:{token} {json} EX ttl`；`RefreshTokenInterceptor` 读取后 `readValue` 还原 `UserDTO`，保留字段类型 |
| 滑动窗口续期 | `RefreshTokenInterceptor` 拿到 key 后 `TTL` 一次；剩余秒数 < `refreshThreshold`（默认 600s）才 `EXPIRE`，避免每次请求都写 Redis |
| TTL / 阈值 / 上限配置化 | 新增 `LoginProperties`（`@ConfigurationProperties(prefix = "hmdp.login")`），字段：`codeTtlSeconds`、`tokenTtlSeconds`、`refreshThresholdSeconds`、`resendIntervalSeconds`、`dailySendLimit`、`failLimit`、`lockSeconds`、`codeSalt` |

#### A3 工程化小修

- `RefreshTokenInterceptor#afterCompletion` 中 `UserHolder.removeUser()` 清理 ThreadLocal（确认现状后按需补）。
- `LoginSecurityService`：把 A1 中所有"防刷/哈希/锁定"逻辑封装到独立 Service，`UserServiceImpl` 只做编排。
- 拦截器排除路径迁移到 `MvcConfig` 中的常量数组，便于一处维护。

### 2.3 文件改动清单

**新增（4 个）**

- `src/main/java/com/hmdp/config/LoginProperties.java`
- `src/main/java/com/hmdp/sms/SmsClient.java`
- `src/main/java/com/hmdp/sms/LogSmsClient.java`
- `src/main/java/com/hmdp/service/LoginSecurityService.java`

**修改（5 个）**

- `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`
- `src/main/java/com/hmdp/utils/RedisConstants.java`
- `src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java`
- `src/main/java/com/hmdp/config/MvcConfig.java`
- `src/main/resources/application.yaml`（追加 `hmdp.login.*` 配置块）

**不动**

- `LoginInterceptor`（职责单一，无需改）
- `UserController`（对外契约不变）
- 数据库 schema、Mapper、entity

### 2.4 兼容性与回滚

- 对外 API（请求/响应字段）保持不变。
- 由于 token 在 Redis 中的存储格式从 Hash 改为 JSON String，**改造发布时旧 token 会失效**，相当于一次强制下线。可接受。
- 回滚策略：通过 git revert 改造提交即可，无 schema 变更。

### 2.5 验证标准

- `mvn -q -DskipTests compile` 通过。
- 手动 review：所有新增 Bean 能被 Spring 装配，`LoginProperties` 字段在 `application.yaml` 中均有默认值。
- 不要求自动化测试。

---

## 3. Part B：讲解 PDF 设计

### 3.1 定位

- **受众**：技术答辩 / 面试官
- **语言**：中文
- **风格**：Slides 风格（每页一个核心论点 + 强可视化），16:9 横向 A4

### 3.2 章节大纲（11 页）

| # | 标题 | 内容要点 | 主要可视化 |
|---|---|---|---|
| 1 | 封面 | 黑马点评 · 登录模块（企业化改造版） | 项目名 + 技术栈徽章 |
| 2 | 模块全景 | Client / Nginx / Spring Boot / Redis / MySQL 角色与边界 | 架构图 |
| 3 | 原版的 6 个"Toy 味"问题 | 防刷缺失、明文验证码、错误次数无限、Hash 存储类型丢失、续期写放大、SMS 写死 | 红色高亮的问题列表图 |
| 4 | 方案演进：Session → Redis Token | ① 单机 Session ② Tomcat session 复制 ③ Redis 集中存储 | 三张架构对比图 |
| 5 | 主流程时序图（改造后） | 发码 → 校验 → 建用户 → 发 token → 后续请求带 token | Mermaid sequenceDiagram |
| 6 | 安全层改造 ①：防刷 + 哈希 + 锁定 | 60s/日上限/5 次锁 10min 的状态机 | 状态机图 + 关键 git diff 片段 |
| 7 | 安全层改造 ②：SmsClient 抽象 | 接口 + 默认实现 + 扩展位（阿里云/腾讯云） | 类图 |
| 8 | 会话改造 ①：JSON 替代 Hash | 类型保真、读写一次性、可读性 vs Hash 部分字段更新优势 | 对比表 + Redis 数据截图 |
| 9 | 会话改造 ②：滑窗续期 | 阈值思想，写次数对比（每次写 vs 仅在临近过期写） | 时间轴图 + 写入次数对比柱图 |
| 10 | 双拦截器设计 | RefreshToken 拦所有 / Login 拦受保护；为什么拆两个；顺序控制 | 两层流程图 |
| 11 | 总结 + 可继续扩展 | 多端控制、审计事件、JWT、手机号加密列 | 思维导图 |

### 3.3 工具链

- **目录**：`docs/login-module-deck/`
- **页面源**：`slides.html`，单文件多 `<section class="slide">`
- **图表**：Mermaid 通过 CDN 在浏览器端渲染（`mermaid.run()` 完成后再触发打印）
- **样式**：深色 slides 风格（深蓝底 + 强调色），中文字体优先 PingFang SC / Source Han Sans
- **构建**：`build.js`（Node + Playwright），输出 `login-module.pdf`
- **依赖安装一次性**：`npm i playwright && npx playwright install chromium`
- **构建命令**：`node docs/login-module-deck/build.js`

### 3.4 内容素材来源

- 架构 / 时序 / 状态机 / 类图：现写 Mermaid 源
- 代码 diff：从 `git diff baseline..refactor -- <file>` 截取关键段，作为代码块嵌入页面（不是图片，是高亮代码）
- 对比表：手写 Markdown 表 → HTML

### 3.5 验证标准

- `login-module.pdf` 成功生成，11 页齐全
- 中文字体正常（无方块）
- 所有 Mermaid 图正常渲染（无源码裸露）
- 在 macOS Preview / Chrome 中目视检查通过

---

## 4. 执行顺序

1. `git init` + 把现状作为 baseline 提交（commit message: `chore: baseline before login module refactor`）
2. 改造代码（按 A1 → A2 → A3 顺序）
3. `mvn -q -DskipTests compile` 验证
4. 提交改造（commit message: `refactor(login): enterprise-grade hardening`）
5. 写 PDF deck（Part B）
6. 安装 Playwright + 渲染 PDF
7. 提交 deck 产物（包含源 + PDF）

---

## 5. 风险与开放问题

- **Java 25 + 项目 pom**：本机 JDK 是 25，项目 pom 里 Spring Boot / Java 目标版本未在设计阶段核对。若编译失败需先适配 JDK。改造完先跑 `mvn compile`，失败时再处理。
- **Playwright 中文字体**：macOS 自带 PingFang，理论上 Chromium 会用系统字体；若 PDF 出现方块字，回退到内嵌 Web Font（思源黑体）。
- **Mermaid 异步渲染 vs Playwright 打印**：必须在 `page.evaluate(async () => { await mermaid.run(); })` 完成后再 `page.pdf()`，否则会出现源码未替换。构建脚本中需显式等待。
