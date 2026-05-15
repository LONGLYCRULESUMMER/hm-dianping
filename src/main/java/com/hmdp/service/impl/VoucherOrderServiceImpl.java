package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String ORDER_STREAM_KEY = "stream.orders";
    private static final String ORDER_CONSUMER_GROUP = "g1";
    private static final String ORDER_CONSUMER_NAME = "c1";

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderServiceProxy;

    // Lua 脚本：前置判断（库存 + 一人一单）+ 扣库存 + 记录用户
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        initOrderStreamGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private void initOrderStreamGroup() {
        try {
            RedisSerializer<String> serializer = stringRedisTemplate.getStringSerializer();
            byte[] key = serializer.serialize(ORDER_STREAM_KEY);
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.streamCommands().xGroupCreate(key, ORDER_CONSUMER_GROUP, ReadOffset.from("0"), true);
                return null;
            });
        } catch (RedisSystemException e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_CONSUMER_GROUP, ORDER_CONSUMER_NAME),
                            org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                                    .count(1)
                                    .block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    for (MapRecord<String, Object, Object> record : records) {
                        handleVoucherOrder(record);
                    }
                } catch (Exception e) {
                    log.error("处理订单任务异常", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handleVoucherOrder(MapRecord<String, Object, Object> record) {
        VoucherOrder voucherOrder = buildVoucherOrder(record.getValue());
        handleVoucherOrder(voucherOrder);
        acknowledge(record.getId());
    }

    private void handlePendingList() {
        while (true) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(ORDER_CONSUMER_GROUP, ORDER_CONSUMER_NAME),
                        org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(1),
                        StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.from("0"))
                );
                if (records == null || records.isEmpty()) {
                    break;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    handleVoucherOrder(record);
                }
            } catch (Exception e) {
                log.error("处理 pending-list 订单异常", e);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单，userId={}", userId);
            return;
        }
        try {
            voucherOrderServiceProxy.createVoucherOrder(voucherOrder.getVoucherId(), voucherOrder.getUserId(), voucherOrder.getId());
        } finally {
            lock.unlock();
        }
    }

    private VoucherOrder buildVoucherOrder(Map<Object, Object> value) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(Long.valueOf(value.get("orderId").toString()));
        voucherOrder.setUserId(Long.valueOf(value.get("userId").toString()));
        voucherOrder.setVoucherId(Long.valueOf(value.get("voucherId").toString()));
        return voucherOrder;
    }

    private void acknowledge(RecordId recordId) {
        StreamOperations<String, Object, Object> streamOperations = stringRedisTemplate.opsForStream();
        streamOperations.acknowledge(ORDER_STREAM_KEY, ORDER_CONSUMER_GROUP, recordId);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        // 1. 执行 Lua 脚本，原子完成：
        //    - 判断库存是否充足
        //    - 判断用户是否已下过单
        //    - 扣减 Redis 库存
        //    - 记录下单用户
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        "seckill:stock:" + voucherId,
                        "seckill:order:" + voucherId,
                        ORDER_STREAM_KEY
                ),
                userId.toString(),
                voucherId.toString(),
                String.valueOf(orderId)
        );

        // 2. 根据返回值判断
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足！" : "不能重复下单！");
        }

        // 3. Lua 已经把订单消息写入 Redis Stream，这里直接返回订单号
        return Result.ok(orderId);
    }

    /**
     * 同步落库：扣减 DB 库存 + 保存订单
     * 注意：Redis 已做过所有校验，这里的 DB 操作理论上不会失败
     * 但为了健壮性仍保留乐观锁 (stock > 0) 兜底
     */
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

        // 扣减 DB 库存（乐观锁兜底）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }

        // 保存订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
