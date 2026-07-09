package com.imawx.mcp.gateway.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt 密码编码器独立配置(2026-07-01 加)。
 *
 * <p>单独放一个 {@code @Configuration} 而不是塞在 {@link SecurityConfig} 里,
 * 避免循环依赖 —— {@code SecurityConfig} 现在通过字段注入 TokenAuthInterceptor,
 * TokenAuthInterceptor 间接依赖 PasswordEncoder,如果 PasswordEncoder 还是 SecurityConfig 里的
 * {@code @Bean} 方法,Spring 启动时会进入 SecurityConfig ↔ McpApiTokenService 循环解析。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}