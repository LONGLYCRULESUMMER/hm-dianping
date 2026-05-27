# 登录模块 · 数据存储设计

## 一、MySQL 表设计

### tb_user（用户表）

```sql
CREATE TABLE `tb_user` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone`       varchar(11)  NOT NULL                      COMMENT '手机号码',
  `password`    varchar(128) DEFAULT ''                     COMMENT '密码，加密存储',
  `nick_name`   varchar(32)  DEFAULT ''                     COMMENT '昵称，默认是随机字符',
  `icon`        varchar(255) DEFAULT ''                     COMMENT '用户头像',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  PRIMARY KEY (`id`),
  UNIQUE INDEX `uniqe_key_phone` (`phone`)   -- 手机号唯一索引，防并发重复创建
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 关键设计点

| 字段 | 说明 |
|------|------|
| `id` | 自增主键，MyBatis-Plus `@TableId(type = IdType.AUTO)` |
| `phone` | **唯一索引**，防止并发登录创建重复用户（数据库兜底） |
| `password` | 当前项目用验证码登录，此字段为空字符串，预留给密码登录扩展 |
| `nick_name` | 新用户自动生成 `user_` + 10 位随机字符串 |
| `icon` | 默认空字符串，用户自行上传 |

#### 对应 Java 实体

```java
@TableName("tb_user")
public class User {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String phone;
    private String password;
    private String nickName;
    private String icon;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

#### 传输给前端的 DTO（不暴露敏感字段）

```java
public class UserDTO {
    private Long id;        // 用户标识
    private String nickName; // 昵称
    private String icon;     // 头像
    // 不包含 phone、password → 安全 + 节省空间
}
```

---

## 二、Redis Key 设计

### 全景图

```
模块          Key                              Value              TTL        用途
───────────  ──────────────────────────────   ─────────────────  ─────────  ──────────────────

验证码存储    login:code:{phone}               SHA-256哈希值       120s       验证码（哈希后存储）
                                                                (2分钟)    验证成功后立即 DELETE

重发冷却      login:code:cooldown:{phone}       "1"                60s        60s 内不能重发
                                                                (1分钟)    只看 key 是否存在

每日计数      login:code:count:{phone}          数字字符串          24h        每日最多发 10 次
                                              ("0","1"...)       (24小时)   SETNX+INCR

失败计数      login:code:fail:{phone}           数字字符串          180s       验证码输错次数
                                              ("0","1"...)       (3分钟)    累计 5 次触发锁定

锁定标记      login:code:lock:{phone}           "1"                600s       失败太多次后锁定
                                                                (10分钟)   锁定期间发码和登录都拒绝

登录会话      login:token:{uuid}                UserDTO JSON       1800s      Token → 用户信息
                                              {id,nickName,icon} (30分钟)   滑动续期（TTL<10min 时刷新）
                                                                           登出时 DELETE
```

### 命名规范

```
规则：模块:功能:标识
示例：login:code:138xxxx
      └────┘└──┘└──────┘
      模块   功能  手机号

好处：
  ① 可读性强，一看就知道干什么的
  ② Redis 可视化工具（RedisInsight）按冒号自动分层展示
  ③ 方便批量查看/清理：KEYS login:code:* → 所有验证码相关 key
```

### TTL 设计理由

```
验证码 120s    → 太短用户来不及输入，太长被截获后利用窗口大
冷却期 60s     → 太短防不住轰炸，太长影响正常用户重发体验
每日上限 24h   → 自然日重置
失败计数 180s  → 比验证码 TTL (120s) 多 60s，保证验证码过期后还能记住失败次数
锁定 600s      → 10 分钟足够劝退攻击者，又不会太久影响正常用户
Token 1800s    → 30 分钟活跃窗口 + 滑动续期，平衡安全和用户体验
```

### 数据结构选型

```
全部使用 String 类型，原因：

① UserDTO 只有 3 个字段，每次整体读写 → Hash 的部分读写优势用不上
② SET key val EX ttl → 一条命令原子设置值和 TTL
   HSET + EXPIRE → 两条命令，非原子，可能 TTL 丢失
③ Jackson 反序列化直接得到强类型 UserDTO 对象，代码更简洁
```

---

## 三、数据流转图

```
POST /user/code
  → login:code:cooldown:{phone}   检查是否存在（冷却中？）
  → login:code:count:{phone}      检查是否 ≥ 10（到上限？）
  → login:code:lock:{phone}       检查是否存在（被锁定？）
  → login:code:{phone}            SET 哈希后的验证码, TTL=120s
  → login:code:cooldown:{phone}   SET "1", TTL=60s
  → login:code:count:{phone}      SETNX "0" TTL=24h + INCR

POST /user/login
  → login:code:lock:{phone}       检查锁定
  → login:code:{phone}            GET 哈希值 → 比对 → DELETE（一码一用）
  → login:code:fail:{phone}       失败时 INCR → ≥5 时创建 lock key
  → tb_user                       SELECT → 不存在则 INSERT
  → login:token:{uuid}            SET UserDTO JSON, TTL=1800s

每次请求（RefreshTokenInterceptor）
  → login:token:{uuid}            GET → 反序列化 → 存 ThreadLocal
  → login:token:{uuid}            TTL < 600s 时 EXPIRE 刷到 1800s

POST /user/logout
  → login:token:{uuid}            DELETE
```
