package com.imawx.mcp.gateway;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MCP 聚合网关启动类。
 *
 * <p>阶段 1 单机版入口。后续阶段增量新增 starter / 包扫描，<b>不在此入口加额外逻辑</b>。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@EnableAsync
@EnableScheduling
@MapperScan("com.imawx.mcp.gateway.mapper")
@SpringBootApplication
public class McpGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpGatewayApplication.class, args);
    }

}
