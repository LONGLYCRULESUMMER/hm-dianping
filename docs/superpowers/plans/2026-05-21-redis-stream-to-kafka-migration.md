# Redis Stream → Kafka 消息队列迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将秒杀订单的异步下单流程从 Redis Stream 迁移到 Apache Kafka。

**Architecture:** Lua 脚本仅负责库存扣减和资格校验（去掉 `xadd`），Java 端在 Lua 返回成功后通过 `KafkaTemplate` 发送订单消息到 Kafka topic `seckill-orders`；消费端使用 `@KafkaListener` 异步消费订单并落库。移除所有 Redis Stream 相关代码（consumer group 初始化、手动线程池、pending list 处理）。

**Tech Stack:** Spring Boot 2.7 + spring-kafka + Apache Kafka

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `pom.xml` | 添加 `spring-kafka` 依赖 |
| Modify | `src/main/resources/application.yaml` | 添加 Kafka 配置 |
| Modify | `src/main/resources/seckill.lua` | 移除 Redis Stream 写入 |
| Create | `src/main/java/com/hmdp/config/KafkaConfig.java` | Kafka topic 自动创建 |
| Modify | `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java` | 替换 Redis Stream 为 Kafka 生产/消费 |

---

### Task 1: 添加 spring-kafka Maven 依赖

**Files:**
- Modify: `pom.xml:68-73` (在 redisson 依赖之前插入)

- [ ] **Step 1: 在 pom.xml 中添加 spring-kafka 依赖**

在 `<dependencies>` 中 redisson 依赖之前添加：

```xml
        <!--Kafka-->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
```

版本由 Spring Boot 2.7.18 parent 管理，无需指定 version。

- [ ] **Step 2: Commit**

```bash
git add pom.xml
git commit -m "build: add spring-kafka dependency"
```

---

### Task 2: 添加 Kafka 配置

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: 在 application.yaml 中添加 Kafka 配置**

在 `spring:` 节点下添加 `kafka` 配置块：

```yaml
  kafka:
    bootstrap-servers: 127.0.0.1:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      retries: 3
    consumer:
      group-id: seckill-order-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.hmdp.entity
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "config: add Kafka bootstrap and serde settings"
```

---

### Task 3: 创建 KafkaConfig 配置类

**Files:**
- Create: `src/main/java/com/hmdp/config/KafkaConfig.java`

- [ ] **Step 1: 创建 KafkaConfig.java**

```java
package com.hmdp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaConfig {

    public static final String SECKILL_ORDER_TOPIC = "seckill-orders";

    @Bean
    public NewTopic seckillOrderTopic() {
        // 3 partitions, replication factor 1 (单机开发环境)
        return new NewTopic(SECKILL_ORDER_TOPIC, 3, (short) 1);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/hmdp/config/KafkaConfig.java
git commit -m "feat: add KafkaConfig with seckill-orders topic"
```

---

### Task 4: 修改 Lua 脚本，移除 Redis Stream 写入

**Files:**
- Modify: `src/main/resources/seckill.lua`

- [ ] **Step 1: 更新 seckill.lua**

移除 `KEYS[3]` 参数和 `xadd` 调用。脚本改为只接收 2 个 KEYS 和 2 个 ARGV（不再需要 orderId，由 Java 端生成后发 Kafka）：

```lua
-- 秒杀前置判断脚本
-- KEYS[1] = 库存 key（seckill:stock:{voucherId}）
-- KEYS[2] = 订单 Set key（seckill:order:{voucherId}）
-- ARGV[1] = 用户 ID
--
-- 返回值：
--   0 = 成功（库存够 + 未下过单）
--   1 = 库存不足
--   2 = 用户已下单

-- 1. 判断库存是否充足
local stock = tonumber(redis.call('get', KEYS[1]))
if (stock == nil or stock <= 0) then
    return 1
end

-- 2. 判断用户是否已经下过单
if (redis.call('sismember', KEYS[2], ARGV[1]) == 1) then
    return 2
end

-- 3. 扣减库存 + 记录用户
redis.call('incrby', KEYS[1], -1)
redis.call('sadd', KEYS[2], ARGV[1])

return 0
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/seckill.lua
git commit -m "refactor: remove Redis Stream write from seckill Lua script"
```

---

### Task 5: 重写 VoucherOrderServiceImpl，使用 Kafka 生产和消费

**Files:**
- Modify: `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`

- [ ] **Step 1: 替换整个 VoucherOrderServiceImpl**

```java
package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.KafkaConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Arrays;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, VoucherOrder> kafkaTemplate;

    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderServiceProxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        // 1. 执行 Lua 脚本：判断库存 + 一人一单 + 扣库存 + 记录用户
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        "seckill:stock:" + voucherId,
                        "seckill:order:" + voucherId
                ),
                userId.toString()
        );

        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足！" : "不能重复下单！");
        }

        // 2. Lua 校验通过，发送订单消息到 Kafka
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        kafkaTemplate.send(KafkaConfig.SECKILL_ORDER_TOPIC, String.valueOf(userId), voucherOrder);
        log.debug("秒杀订单消息已发送到 Kafka, orderId={}", orderId);

        return Result.ok(orderId);
    }

    /**
     * Kafka 消费者：异步处理秒杀订单落库
     */
    @KafkaListener(topics = KafkaConfig.SECKILL_ORDER_TOPIC, groupId = "seckill-order-group")
    public void onSeckillOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单，userId={}", userId);
            return;
        }
        try {
            voucherOrderServiceProxy.createVoucherOrder(
                    voucherOrder.getVoucherId(),
                    voucherOrder.getUserId(),
                    voucherOrder.getId()
            );
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId, Long orderId) {
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            log.error("用户已经购买过一次，userId={}, voucherId={}", userId, voucherId);
            return Result.fail("不能重复下单！");
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        save(order);

        return Result.ok(orderId);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java
git commit -m "feat: replace Redis Stream with Kafka for seckill order processing"
```

---

### Task 6: 验证编译通过

- [ ] **Step 1: 执行 Maven 编译**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 最终 commit（如有修复）**

```bash
git add -A
git commit -m "chore: finalize Redis Stream to Kafka migration"
```
