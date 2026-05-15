package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类
 * 提供 RedissonClient Bean，供其他组件注入使用
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        // 单机模式配置
        // 生产环境可改为：
        //   useSentinelServers  → 哨兵模式
        //   useClusterServers   → 集群模式
        //   useMasterSlaveServers → 主从模式
        config.useSingleServer()
              .setAddress("redis://127.0.0.1:6379")
              .setPassword(null);   // 如有密码，在此设置

        return Redisson.create(config);
    }
}
