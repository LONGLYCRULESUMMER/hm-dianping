-- 释放锁的 Lua 脚本
-- KEYS[1] 是锁的 key
-- ARGV[1] 是当前线程的唯一标识

-- 比较锁中的 value 是否等于当前线程标识
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 是自己的锁，删除它
    return redis.call('del', KEYS[1])
end
-- 不是自己的锁，不做任何操作
return 0
