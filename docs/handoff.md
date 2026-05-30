# Handoff: hm-dianping 面试学习辅导

## 项目背景

用户正在准备 **Java 后端日常实习面试**，学习项目是 `hm-dianping`（类大众点评的生活服务应用）。
- **GitHub 仓库**: https://github.com/LONGLYCRULESUMMER/hm-dianping
- **本地路径**: `/Users/javagod/VsCodeProjects/hm-dianping`
- **技术栈**: Spring Boot + MyBatis-Plus + Redis + Kafka + Caffeine + Redisson

## 你的角色

你是**交互式面试辅导老师**，全程使用**中文**教学。教学流程如下：

1. **逐知识点（KP）教学**: 每次只讲一个知识点，讲完确认用户理解后再进入下一个
2. **模拟追问**: 一个模块的所有 KP 讲完后，扮演面试官对用户进行连续追问（每次一题，等用户回答后评价并追问）
3. **查漏补缺**: 追问后识别薄弱点，补充知识或让用户重述
4. **STAR 话术**: 每个模块最后一个 KP 是帮用户准备面试开场话术

## 已完成的工作

### 1. 登录模块 ✅（14 个 KP + 追问 完成）
- KP1-14 全部完成：Session 问题 → Redis Token → 双拦截器 → Token 续期 → ThreadLocal → 验证码安全 → JWT 对比 → Redis 数据结构选型 → UserDTO → 单设备登录 → Token 安全 → 并发安全 → 拦截器 vs 过滤器 → STAR 话术
- 模拟追问已完成
- 产出文档: `docs/login-module-storage-design.md`

### 2. 缓存模块 ✅（8 个 KP + 追问 完成）
- KP1-8 全部完成：Cache Aside → 缓存穿透（布隆过滤器+空值缓存）→ 布隆过滤器原理 → 缓存击穿（互斥锁）→ 缓存雪崩（TTL 随机化）→ 双写一致性 → queryById 代码解读 → STAR 话术
- 模拟追问已完成（评分 A-）
- **代码补充**（已提交到 GitHub）:
  - `src/main/java/com/hmdp/config/CaffeineConfig.java`: Caffeine 本地缓存配置
  - `src/main/java/com/hmdp/service/impl/ShopServiceImpl.java`: 重构为 Caffeine→Redis→Bloom→MySQL 四层查询链路 + 互斥锁 + TTL 随机化
  - `pom.xml`: 添加 Caffeine 依赖

### 3. 秒杀模块 ✅（9 个 KP 完成，追问未做）
- KP1-9 全部完成：秒杀全链路架构 → 超卖（乐观锁 stock>0）→ 一人一单（三层防护）→ 分布式锁演进（v1→v5→Redisson）→ Lua 脚本 → Kafka 异步下单 → 全局唯一 ID（RedisIdWorker）→ 秒杀存储设计 → STAR 话术

### 4. 全模块存储设计文档 ✅
- `docs/all-modules-storage-design.md`: 覆盖所有 7 个模块的 MySQL 表设计 + Redis Key 设计

## 下一步工作（按优先级）

### ⏭️ 立即要做
1. **秒杀模块模拟追问**: KP 全部讲完但还没做追问环节。模式和之前一样——你扮面试官，每次问一题，用户回答后打分 + 追问，最后给整体评分
   - 重点追问方向: Lua 脚本原子性、Kafka 消息丢失处理、分布式锁 vs Redisson 区别、乐观锁为什么用 stock>0 而不是 CAS、全局 ID 和雪花算法对比

### 🔲 后续可做（用户尚未决定）
2. **其余模块的面试知识**: 用户决定不写代码实现点赞/关注/签到/附近商铺/Feed流等功能，但需要知道它们的 Redis 数据结构用法以应对面试（已在 `docs/all-modules-storage-design.md` 中文档化）
3. **全模块综合模拟面试**: 跨模块随机抽题追问
4. **简历润色/项目描述优化**: 基于已学内容帮用户调整简历上的项目描述

## 用户特点和偏好

- **语言**: 全程中文
- **水平**: 对 Java 和 Spring Boot 有基础，Redis 基础命令不太熟（已补充讲解过），分布式概念需要从原理讲起
- **学习风格**: 喜欢先听概念再看代码，代码需要逐行解释
- **已知薄弱点**:
  - ThreadLocal 弱引用/强引用内部原理
  - Session 共享方案和 JWT 内部结构（已讲过但偏薄弱）
  - W-TinyLFU 曾误说成 W-TinyLRU（已纠正：是基于频率而非最近使用）
  - SETNX 等 Redis 具体命令（已补充）
  - Redisson watchdog 细节（已讲但建议追问时再巩固）
- **简历要点**: 用户坚持在简历上保留 Caffeine（虽然对实习面试非必要），因此相关追问需要准备充分
- **面试场景**: 定位日常实习，不需要特别深入的源码级分析

## 关键技术决策（面试中需自圆其说）

| 决策 | 原因 | 面试话术要点 |
|------|------|------------|
| Redis Token 而非 JWT | 支持服务端主动失效、滑动续期 | "JWT 无法服务端撤销" |
| Cache Aside 而非 Write Through | 更适合读多写少场景 | "先写 DB 再删缓存，不是更新缓存" |
| Bloom→空值缓存 双层防穿透 | 布隆过滤器可能误判，空值缓存兜底 | "布隆挡大部分，空值兜底剩余" |
| stock>0 而非 CAS | CAS 高并发下成功率低 | "stock=旧值 多线程读到相同值只有一个能成功" |
| Kafka 而非 BlockingQueue | 持久化 + 分布式 + 重试 | "JVM 挂了消息不丢" |
| Caffeine L1 + Redis L2 | 减少 Redis 网络开销 | "热点数据本地缓存，2 分钟过期容忍短暂不一致" |

## 重要文件索引

| 文件 | 说明 |
|------|------|
| `src/main/java/com/hmdp/service/impl/ShopServiceImpl.java` | 缓存模块核心（四层查询 + 互斥锁 + TTL 随机化） |
| `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java` | 秒杀模块核心（Lua 预判 + Kafka 生产/消费 + 落库） |
| `src/main/java/com/hmdp/utils/SimpleRedisLock.java` | 手写分布式锁 v5（SETNX+EX+UUID+Lua释放） |
| `src/main/java/com/hmdp/utils/RedisIdWorker.java` | 全局唯一 ID 生成器（时间戳+Redis INCR） |
| `src/main/java/com/hmdp/utils/RedisBloomFilter.java` | 手写布隆过滤器（Redis BitMap） |
| `src/main/java/com/hmdp/config/CaffeineConfig.java` | Caffeine 本地缓存配置 |
| `src/main/resources/seckill.lua` | 秒杀 Lua 脚本（库存+一人一单+预扣） |
| `src/main/resources/unlock.lua` | 分布式锁释放 Lua 脚本 |
| `docs/all-modules-storage-design.md` | 全模块 MySQL + Redis 存储设计 |
| `docs/login-module-storage-design.md` | 登录模块存储设计 |

## 模拟追问的格式规范

追问环节请遵循以下格式：
1. 每次只问一题，等用户回答
2. 对回答给出评分（A/B/C）+ 简短点评 + 补充正确答案
3. 如果回答有误，纠正后追问相关延伸题
4. 全部问完后给整体评分和薄弱点总结
5. 追问难度控制在日常实习面试水平，不需要问源码级细节
