# hm-dianping 全模块存储设计

## 总览

```
模块              MySQL 表                 Redis Key 前缀              Redis 数据结构
─────────────    ──────────────           ──────────────────          ────────────
登录与会话        tb_user                  login:code:*                String
                                          login:token:*               String
商铺缓存          tb_shop / tb_shop_type   cache:shop:*                String
                                          bloom:shop                  BitMap（布隆过滤器）
                                          lock:shop:*                 String（互斥锁）
秒杀下单          tb_voucher               seckill:stock:*             String
                 tb_seckill_voucher
                 tb_voucher_order
博客/点赞         tb_blog                  blog:liked:*                SortedSet
                 tb_blog_comments
关注/Feed        tb_follow                feed:*                      SortedSet
附近商铺          tb_shop                  shop:geo:*                  GEO（底层 ZSet）
用户签到          tb_sign                  sign:*                      BitMap
```

---

## 一、登录与会话模块

### MySQL：tb_user

```sql
CREATE TABLE tb_user (
  id          BIGINT UNSIGNED AUTO_INCREMENT  COMMENT '主键',
  phone       VARCHAR(11) NOT NULL            COMMENT '手机号码',
  password    VARCHAR(128) DEFAULT ''         COMMENT '密码，加密存储',
  nick_name   VARCHAR(32) DEFAULT ''          COMMENT '昵称',
  icon        VARCHAR(255) DEFAULT ''         COMMENT '头像',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  UNIQUE INDEX uniqe_key_phone (phone)   -- ★ 手机号唯一索引，防并发重复创建
);
```

**设计要点：**
- `phone` 唯一索引：并发登录时数据库兜底防重复创建用户
- `password` 预留字段：当前用验证码登录，为密码登录扩展预留
- 返回前端的 UserDTO 只包含 id/nickName/icon，不暴露 phone/password

### Redis 设计

| Key | Value | 类型 | TTL | 用途 |
|-----|-------|------|-----|------|
| `login:code:{phone}` | SHA-256 哈希值 | String | 120s | 验证码（哈希存储防泄漏） |
| `login:code:cooldown:{phone}` | "1" | String | 60s | 重发冷却标记 |
| `login:code:count:{phone}` | 数字 | String | 24h | 每日发送计数 |
| `login:code:fail:{phone}` | 数字 | String | 180s | 验证码失败计数 |
| `login:code:lock:{phone}` | "1" | String | 600s | 锁定标记（失败5次触发） |
| `login:token:{uuid}` | UserDTO JSON | String | 1800s | 登录会话（滑动续期） |

**为什么全用 String？**
- UserDTO 只有 3 个字段，每次整体读写
- SET key val EX ttl 原子设置值和过期时间
- Hash 需要 HSET + EXPIRE 两步，非原子

---

## 二、商铺缓存模块

### MySQL：tb_shop

```sql
CREATE TABLE tb_shop (
  id          BIGINT UNSIGNED AUTO_INCREMENT  COMMENT '主键',
  name        VARCHAR(128) NOT NULL           COMMENT '商铺名称',
  type_id     BIGINT UNSIGNED NOT NULL        COMMENT '商铺类型',
  images      VARCHAR(1024) NOT NULL          COMMENT '商铺图片',
  area        VARCHAR(128) DEFAULT NULL       COMMENT '商圈',
  address     VARCHAR(255) NOT NULL           COMMENT '地址',
  x           DOUBLE UNSIGNED NOT NULL        COMMENT '经度',
  y           DOUBLE UNSIGNED NOT NULL        COMMENT '纬度',
  avg_price   BIGINT UNSIGNED DEFAULT NULL    COMMENT '均价',
  sold        INT UNSIGNED DEFAULT 0          COMMENT '销量',
  comments    INT UNSIGNED DEFAULT 0          COMMENT '评论数',
  score       INT UNSIGNED DEFAULT 0          COMMENT '评分',
  open_hours  VARCHAR(32) DEFAULT NULL        COMMENT '营业时间',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  INDEX foreign_key_type (type_id)  -- ★ 按类型查询的索引
);
```

### MySQL：tb_shop_type

```sql
CREATE TABLE tb_shop_type (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  name        VARCHAR(32) DEFAULT NULL   COMMENT '类型名称（如美食、KTV）',
  icon        VARCHAR(255) DEFAULT ''    COMMENT '图标',
  sort        INT UNSIGNED DEFAULT NULL  COMMENT '排序权重',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id)
);
```

**设计要点：**
- `type_id` 索引：按商铺类型查询（首页分类展示）
- 经纬度 x/y：为 GEO 附近商铺查询预留

### Redis 设计

| Key | Value | 类型 | TTL | 用途 |
|-----|-------|------|-----|------|
| `cache:shop:{id}` | Shop JSON / ""（空值） | String | 30~35min（随机化） | 商铺缓存 + 空值防穿透 |
| `bloom:shop` | 位数组（2^26 位 ≈ 8MB） | BitMap | 无 | 布隆过滤器防穿透 |
| `lock:shop:{id}` | "1" | String | 10s | 互斥锁防击穿 |

**Caffeine 本地缓存（一级）：**

| 参数 | 值 | 原因 |
|------|-----|------|
| maximumSize | 1000 | 覆盖热点商铺 + 冗余 |
| expireAfterWrite | 2min | 短 TTL 兜底多节点一致性 |
| 淘汰策略 | W-TinyLFU（自动） | 按频率淘汰，比 LRU 命中率高 |

**查询链路：Caffeine → Redis → 布隆过滤器 → MySQL → 逐级回填**

---

## 三、秒杀下单模块

### MySQL：tb_voucher（普通券）

```sql
CREATE TABLE tb_voucher (
  id           BIGINT UNSIGNED AUTO_INCREMENT,
  shop_id      BIGINT UNSIGNED DEFAULT NULL  COMMENT '商铺id',
  title        VARCHAR(255) NOT NULL         COMMENT '标题',
  sub_title    VARCHAR(255) DEFAULT NULL     COMMENT '副标题',
  rules        VARCHAR(1024) DEFAULT NULL    COMMENT '使用规则',
  pay_value    BIGINT UNSIGNED NOT NULL      COMMENT '支付金额（分）',
  actual_value BIGINT NOT NULL               COMMENT '抵扣金额（分）',
  type         TINYINT UNSIGNED NOT NULL     COMMENT '0=普通券 1=秒杀券',
  status       TINYINT UNSIGNED NOT NULL     COMMENT '1=上架 2=下架 3=过期',
  create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id)
);
```

### MySQL：tb_seckill_voucher（秒杀券附加信息）

```sql
CREATE TABLE tb_seckill_voucher (
  voucher_id  BIGINT UNSIGNED NOT NULL  COMMENT '关联的优惠券id',
  stock       INT NOT NULL              COMMENT '库存',
  begin_time  TIMESTAMP NOT NULL        COMMENT '秒杀开始时间',
  end_time    TIMESTAMP NOT NULL        COMMENT '秒杀结束时间',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (voucher_id)  -- ★ 主键就是 voucher_id，和 tb_voucher 一对一
);
```

### MySQL：tb_voucher_order（订单）

```sql
CREATE TABLE tb_voucher_order (
  id          BIGINT UNSIGNED NOT NULL  COMMENT '主键（全局唯一ID生成器）',
  user_id     BIGINT UNSIGNED NOT NULL  COMMENT '下单用户id',
  voucher_id  BIGINT UNSIGNED NOT NULL  COMMENT '优惠券id',
  pay_type    TINYINT UNSIGNED NOT NULL COMMENT '支付方式',
  status      TINYINT UNSIGNED NOT NULL COMMENT '1=未支付 2=已支付 3=已核销 4=已取消 5=退款',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  pay_time    TIMESTAMP DEFAULT NULL    COMMENT '支付时间',
  use_time    TIMESTAMP DEFAULT NULL    COMMENT '核销时间',
  refund_time TIMESTAMP DEFAULT NULL    COMMENT '退款时间',
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id)  -- ★ 不是自增！是全局唯一ID生成器（RedisIdWorker）
);
```

**设计要点：**
- tb_voucher 和 tb_seckill_voucher 是**一对一**关系（通过 voucher_id 关联）
- 秒杀券单独建表是因为秒杀字段（库存/时间）只有秒杀券才需要
- tb_voucher_order 的 id 是**全局唯一 ID**（时间戳 + Redis 自增序列），不是自增

### Redis 设计

| Key | Value | 类型 | TTL | 用途 |
|-----|-------|------|-----|------|
| `seckill:stock:{voucherId}` | 库存数量 | String | 无 | 秒杀库存预热（Lua 脚本原子扣减） |

**秒杀链路（Lua 脚本原子化）：**
```
① 判断库存是否充足：GET seckill:stock:{id} > 0 ?
② 判断一人一单：SISMEMBER seckill:order:{id} userId ?（如果实现了的话）
③ 扣减库存：DECR seckill:stock:{id}
以上三步在一个 Lua 脚本里原子执行
```

---

## 四、博客 / 点赞模块

### MySQL：tb_blog

```sql
CREATE TABLE tb_blog (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  shop_id     BIGINT NOT NULL               COMMENT '关联商户id',
  user_id     BIGINT UNSIGNED NOT NULL       COMMENT '作者id',
  title       VARCHAR(255) NOT NULL          COMMENT '标题',
  images      VARCHAR(2048) NOT NULL         COMMENT '图片（逗号分隔）',
  content     VARCHAR(2048) NOT NULL         COMMENT '正文',
  liked       INT UNSIGNED DEFAULT 0         COMMENT '点赞数',
  comments    INT UNSIGNED DEFAULT 0         COMMENT '评论数',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id)
);
```

### Redis 设计

| Key | Value | 类型 | TTL | 用途 |
|-----|-------|------|-----|------|
| `blog:liked:{blogId}` | userId 集合（score=时间戳） | **SortedSet** | 无 | 点赞记录 + 点赞排行榜 |

**为什么用 SortedSet？**
```
Set 也能去重（防重复点赞），但 SortedSet 额外提供：
  ① score 存时间戳 → 可以按点赞时间排序 → 点赞榜
  ② ZRANK 查排名 → O(logN)
  ③ ZRANGE 取 Top N → 点赞列表

操作：
  点赞：ZADD blog:liked:{id} {timestamp} {userId}
  取消：ZREM blog:liked:{id} {userId}
  是否点赞：ZSCORE blog:liked:{id} {userId} → 非空=已点赞
  Top5 点赞：ZRANGE blog:liked:{id} 0 4
```

---

## 五、关注 / Feed 流模块

### MySQL：tb_follow

```sql
CREATE TABLE tb_follow (
  id             BIGINT AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL  COMMENT '粉丝id',
  follow_user_id BIGINT UNSIGNED NOT NULL  COMMENT '被关注的人id',
  create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (id)
);
```

**设计要点：**
- 一条记录 = "user_id 关注了 follow_user_id"
- 共同关注：用 Redis Set 取交集（SINTER）

### Redis 设计

| Key | Value | 类型 | TTL | 用途 |
|-----|-------|------|-----|------|
| `feed:{userId}` | blogId 集合（score=时间戳） | **SortedSet** | 无 | Feed 收件箱（推模式） |

**Feed 流推模式：**
```
用户A 发了一篇博客（blogId=100）
  → 查出A的所有粉丝：userId = [1, 2, 3]
  → 推送到每个粉丝的收件箱：
    ZADD feed:1 {timestamp} 100
    ZADD feed:2 {timestamp} 100
    ZADD feed:3 {timestamp} 100

粉丝查看 Feed：
  ZREVRANGEBYSCORE feed:{userId} {maxScore} 0 LIMIT offset count
  → 按时间倒序 + 滚动分页（不能用传统 page/size，因为新数据插入会导致翻页错乱）
```

**为什么用 SortedSet 而不是 List？**
```
List：只能按位置分页（LRANGE 0 9），新数据插入后位置会变 → 翻页重复/遗漏
SortedSet：按 score（时间戳）分页，新数据不影响已读数据的位置 → 滚动分页稳定
```

---

## 六、附近商铺模块

### Redis 设计

| Key | Value | 类型 | TTL | 用途 |
|-----|-------|------|-----|------|
| `shop:geo:{typeId}` | 商铺经纬度集合 | **GEO** | 无 | 按类型查附近商铺 |

**GEO 底层是 SortedSet：**
```
GEOADD shop:geo:1 116.397128 39.916527 "shopId:1"
GEOADD shop:geo:1 116.405285 39.904989 "shopId:2"

查附近 5km 的美食商铺：
  GEOSEARCH shop:geo:1 FROMLONLAT 116.40 39.91 BYRADIUS 5 km ASC
  → 返回按距离排序的商铺列表
```

**为什么按 typeId 分 key？**
```
用户搜"附近美食" → 只查 shop:geo:1（美食类型）
用户搜"附近KTV" → 只查 shop:geo:2（KTV类型）
→ 不用全量商铺里筛选，查询更快
```

---

## 七、用户签到模块

### MySQL：tb_sign（原始设计，实际用 Redis 替代）

```sql
CREATE TABLE tb_sign (
  id          BIGINT UNSIGNED AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  year        YEAR NOT NULL,
  month       TINYINT NOT NULL,
  date        DATE NOT NULL,
  is_backup   TINYINT UNSIGNED DEFAULT NULL  COMMENT '是否补签',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (id)
);
```

**问题：每个用户每天一条记录 → 一年 365 条 → 100 万用户 = 3.65 亿条 → 太多了！**

### Redis 设计（替代 MySQL）

| Key | Value | 类型 | TTL | 用途 |
|-----|-------|------|-----|------|
| `sign:{userId}:{yyyyMM}` | 32 位位图（每位=一天） | **BitMap** | 无 | 月度签到记录 |

**为什么用 BitMap？**
```
MySQL：每天签到 = 插入一条记录（几十字节）
BitMap：每天签到 = 设一个位（1 bit）

一个月：MySQL = 30 条记录   vs   BitMap = 4 字节（32 位）
一年：  MySQL = 365 条记录  vs   BitMap = 48 字节（12个月 × 4字节）
100 万用户一年：MySQL = 3.65 亿条  vs  BitMap = 48MB

操作：
  签到：SETBIT sign:1:202605 17 1     → 第18天签到（0-based）
  查签到：GETBIT sign:1:202605 17     → 1=已签 0=未签
  连续签到天数：BITFIELD sign:1:202605 GET u18 0 → 取出前18位 → 位运算算连续1
```

---

## 八、Redis 数据结构选型总结

```
数据结构      项目中的用途                    为什么选它
──────────   ─────────────────────         ─────────────────
String       Token、验证码、缓存、库存、锁     简单 k-v，原子 SET+EX
BitMap       布隆过滤器、签到                 极省空间（1 bit/元素）
SortedSet    点赞榜、Feed 收件箱              去重 + 按 score 排序 + 范围查询
GEO          附近商铺                        经纬度存储 + 距离计算（底层 ZSet）
```

**面试常问：你用过 Redis 哪些数据结构？各自在什么场景？**
> "String 用在 Token 存储和商铺缓存；BitMap 用在布隆过滤器和用户签到，极省空间；SortedSet 用在点赞排行榜和 Feed 流的滚动分页；GEO 用在附近商铺查询，底层是 SortedSet + GeoHash 编码。"
