# HM-DianPing 项目拆解

> 扫描时间：2026-05-03  
> 结论基于当前仓库代码现状，不依赖提交历史。当前目录不是独立 Git 仓库，且本机环境未安装 `mvn`，因此这里只能按代码落地情况判断进度。

## 1. 项目目标拆解

这个项目是一个基于 Spring Boot + MyBatis-Plus + Redis 的大众点评后端，核心实现点可以拆成下面几块：

1. 项目基础设施
2. 用户登录与会话管理
3. 商铺查询、缓存与检索
4. 优惠券与秒杀下单
5. 博客/探店笔记
6. 关注关系与 Feed 流
7. 评论互动
8. 文件上传
9. 测试、配置、安全性与工程化

---

## 2. 当前进度总览

### 已完成

- Spring Boot 项目骨架与基础依赖
- MyBatis-Plus、Redis、Redisson 基础接入
- 短信验证码登录
- Token 存 Redis，并通过拦截器做登录校验和续期
- 用户登出、查询当前用户、查询用户详情
- 商铺基础查询、增改、按类型/名称分页查询
- 商铺详情的基础 Redis 缓存
- 店铺分类查询
- 普通券新增
- 秒杀券新增
- 秒杀库存预热到 Redis
- Lua 脚本完成秒杀前置校验
- 秒杀订单 ID 生成与同步落库
- 图片上传/删除
- 博客基础发帖、热门列表、我的博客列表

### 部分完成

- 博客能力只做了 Controller 层基础接口，业务层基本还没真正承接
- 商铺缓存只做了最基础版，没有做完整缓存治理
- 秒杀链路可以跑通，但还没做成高并发下更稳的异步化方案

### 未完成

- 关注/取关
- 共同关注
- 博客点赞状态、点赞榜
- 博客详情增强
- Feed 推送与滚动分页
- 评论相关接口与业务
- 用户签到
- GEO 附近商铺查询
- 完整测试
- 生产级配置治理

---

## 3. 模块拆解

## 3.1 项目基础设施

### 已完成

- Spring Boot 启动类、Mapper 扫描、AOP 代理暴露
  - `src/main/java/com/hmdp/HmDianPingApplication.java`
- MyBatis-Plus 分页配置
  - `src/main/java/com/hmdp/config/MybatisConfig.java`
- 全局异常处理
  - `src/main/java/com/hmdp/config/WebExceptionAdvice.java`
- Redis、Redisson 基础接入
  - `src/main/java/com/hmdp/config/RedissonConfig.java`
  - `src/main/resources/application.yaml`

### 风险/问题

- `application.yaml` 中 Redis 配置了密码 `123456`，但 `RedissonConfig` 里写的是 `setPassword(null)`，配置不一致。
- `application.yaml` 里数据库驱动还是 `com.mysql.jdbc.Driver`，在 Spring Boot 2.7 + MySQL 8 下通常建议改为 `com.mysql.cj.jdbc.Driver`。
- 配置文件里直接写了数据库和 Redis 凭据，缺少环境隔离。

---

## 3.2 用户登录与会话管理

### 已完成

- 手机号格式校验
- 验证码生成并写入 Redis
- 基于验证码登录
- 用户不存在时自动注册
- 登录态写入 Redis Hash
- Token 续期拦截器
- 登录校验拦截器
- 登出删除 Redis Token
- 获取当前登录用户
- 查询用户详情

### 代码位置

- `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`
- `src/main/java/com/hmdp/controller/UserController.java`
- `src/main/java/com/hmdp/config/MvcConfig.java`
- `src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java`
- `src/main/java/com/hmdp/utils/LoginInterceptor.java`
- `src/main/java/com/hmdp/utils/UserHolder.java`

### 未完成/可增强

- 验证码发送目前只有日志输出，没有真实短信服务接入
- 缺少登录频控、防刷与验证码错误次数限制
- 没有密码登录、多端管理、强制下线等能力
- 没有签到相关实现，虽然 `RedisConstants` 里已经预留了 `USER_SIGN_KEY`

---

## 3.3 商铺查询、缓存与检索

### 已完成

- 商铺详情查询
- 商铺新增
- 商铺更新
- 按类型分页查询商铺
- 按名称分页搜索商铺
- 店铺分类列表查询
- 商铺详情基础缓存：先查 Redis，未命中回源数据库，再写回 Redis

### 代码位置

- `src/main/java/com/hmdp/controller/ShopController.java`
- `src/main/java/com/hmdp/service/impl/ShopServiceImpl.java`
- `src/main/java/com/hmdp/controller/ShopTypeController.java`

### 部分完成

- `ShopServiceImpl#queryById` 已有最基础缓存逻辑，但没有完整缓存治理能力

### 未完成

- 缓存穿透保护：空值缓存
- 缓存击穿保护：互斥锁或逻辑过期
- 缓存雪崩治理：TTL 抖动/预热策略
- 更新商铺后的缓存一致性处理
- 商铺 GEO 数据写入 Redis
- 基于 GEO 的附近商铺查询

### 现有问题

- `queryById()` 末尾存在冗余分支，逻辑可收敛
- 当前查不到店铺时直接返回 `"Not Exist!"`，没有做空值缓存，容易被穿透

---

## 3.4 优惠券与秒杀下单

### 已完成

- 查询店铺优惠券列表
- 新增普通券
- 新增秒杀券
- 秒杀库存预热到 Redis
- 基于 Lua 的秒杀原子校验
  - 库存是否充足
  - 是否重复下单
  - 扣减 Redis 库存
  - 记录下单用户
- 基于 Redis 的全局订单 ID 生成
- 通过事务落库创建订单
- 使用数据库乐观扣减兜底

### 代码位置

- `src/main/java/com/hmdp/controller/VoucherController.java`
- `src/main/java/com/hmdp/controller/VoucherOrderController.java`
- `src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `src/main/java/com/hmdp/utils/RedisIdWorker.java`
- `src/main/resources/seckill.lua`

### 部分完成

- 秒杀主链路已经能跑通，但还是同步落库，不是更稳的异步削峰实现

### 未完成/可增强

- 秒杀时间窗口校验
- 数据库层一人一单唯一约束校验
- 基于 Redis Stream / 阻塞队列的异步下单
- 失败补偿与异常订单处理
- 下单后的幂等恢复策略
- `RedissonClient` 已注入但当前实现中没有实际使用

---

## 3.5 博客/探店笔记

### 已完成

- 发布博客
- 查询当前用户自己的博客
- 查询热门博客
- 热门博客列表中补齐作者昵称和头像
- 点赞接口占位可用，但只是直接改数据库点赞数

### 代码位置

- `src/main/java/com/hmdp/controller/BlogController.java`
- `src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`

### 部分完成

- Controller 层已经暴露了部分接口
- Service 层还是空实现，业务没有真正下沉

### 未完成

- 查询博客详情
- 点赞/取消点赞切换
- 判断当前用户是否点赞
- 点赞排行榜
- 查询指定用户博客
- 发布博客后推送到粉丝收件箱
- Feed 流拉取
- 滚动分页

### 现有问题

- `BlogController#likeBlog` 目前只会 `liked + 1`，没有取消点赞，也没有 Redis 维度去重
- 热门博客的用户信息是循环查库，后续容易形成 N+1 查询

---

## 3.6 关注关系与 Feed 流

### 已完成

- 仅有实体、Mapper、Service、Controller 骨架
- `RedisConstants` 里已经预留了 `FEED_KEY`

### 未完成

- 关注
- 取关
- 是否关注
- 共同关注
- 发博文后推送到粉丝收件箱
- 收件箱分页查询
- 基于时间戳的滚动分页

### 代码位置

- `src/main/java/com/hmdp/controller/FollowController.java`
- `src/main/java/com/hmdp/service/impl/FollowServiceImpl.java`

---

## 3.7 评论互动

### 已完成

- 仅有评论实体、Mapper、Service、Controller 骨架

### 未完成

- 发表评论
- 查询评论列表
- 评论楼层/回复关系
- 评论点赞/删除
- 评论权限校验

### 代码位置

- `src/main/java/com/hmdp/controller/BlogCommentsController.java`
- `src/main/java/com/hmdp/service/impl/BlogCommentsServiceImpl.java`

---

## 3.8 文件上传

### 已完成

- 博客图片上传
- 图片删除
- 基于哈希分片目录生成文件路径

### 代码位置

- `src/main/java/com/hmdp/controller/UploadController.java`

### 未完成/可增强

- 文件类型白名单校验
- 文件大小限制
- 恶意文件防护
- 对象存储替换本地磁盘

---

## 3.9 测试、配置、安全性与工程化

### 已完成

- 有测试启动类骨架

### 未完成

- 单元测试
- 集成测试
- 秒杀并发测试
- 缓存行为测试
- 登录链路测试
- README 或开发文档
- 环境变量化配置
- 数据初始化说明
- 启动步骤文档

### 现状

- `src/test/java/com/hmdp/HmDianPingApplicationTests.java` 为空
- 仓库内没有项目说明文档
- 当前环境未安装 `mvn`，无法在这里直接做构建校验

---

## 4. 推荐的后续实现顺序

如果按“先补业务主干，再补高并发细节”的方式推进，建议顺序如下：

1. 补全博客服务层  
   先把博客详情、点赞/取消点赞、是否点赞、点赞榜做完整。

2. 补全关注关系  
   做关注、取关、是否关注、共同关注，为 Feed 流做基础。

3. 实现 Feed 流  
   发博文推送到粉丝收件箱，完成滚动分页读取。

4. 补商铺缓存治理  
   加空值缓存、互斥锁/逻辑过期、更新删缓存。

5. 补 GEO 附近商铺  
   这是店铺检索能力的完整形态。

6. 升级秒杀下单  
   引入异步队列、异常补偿、唯一约束校验。

7. 补评论与签到

8. 最后补测试、README、配置治理

---

## 5. 适合继续拆成任务卡的待办清单

### P0：主链路补全

- [ ] 博客详情
- [ ] 点赞/取消点赞
- [ ] 点赞排行榜
- [ ] 关注/取关/是否关注
- [ ] 共同关注
- [ ] Feed 推送与滚动分页

### P1：稳定性补强

- [ ] 商铺缓存穿透/击穿治理
- [ ] 商铺更新后的缓存一致性
- [ ] 秒杀异步下单
- [ ] 秒杀异常补偿
- [ ] 唯一索引和幂等治理

### P2：能力补全

- [ ] 评论系统
- [ ] 用户签到
- [ ] GEO 附近商铺
- [ ] 文件上传安全校验

### P3：工程化

- [ ] 测试补齐
- [ ] README
- [ ] 环境配置拆分
- [ ] 凭据治理

