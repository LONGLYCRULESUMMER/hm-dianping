-- 秒杀前置判断脚本
-- KEYS[1] = 库存 key（seckill:stock:{voucherId}）
-- KEYS[2] = 订单 Set key（seckill:order:{voucherId}）
-- KEYS[3] = 订单消息 Stream key（stream.orders）
-- ARGV[1] = 用户 ID
-- ARGV[2] = 优惠券 ID
-- ARGV[3] = 订单 ID
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

-- 4. 发送订单消息到 Redis Stream
redis.call('xadd', KEYS[3], '*',
        'userId', ARGV[1],
        'voucherId', ARGV[2],
        'orderId', ARGV[3])

return 0
