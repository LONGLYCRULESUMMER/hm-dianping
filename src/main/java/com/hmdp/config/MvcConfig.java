package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    /** 不需要登录即可访问的路径（LoginInterceptor 排除清单） */
    private static final String[] PUBLIC_PATHS = {
            "/user/code",
            "/user/login",
            "/blog/hot",
            "/shop/**",
            "/shop-type/**",
            "/upload/**",
            "/voucher/**"
    };

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private LoginProperties loginProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate, objectMapper, loginProperties))
                .addPathPatterns("/**")
                .order(0);

        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(PUBLIC_PATHS)
                .order(1);
    }
}
