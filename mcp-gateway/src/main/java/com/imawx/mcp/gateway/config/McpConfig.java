package com.imawx.mcp.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用全局配置：开启 @Scheduled（定时任务），提供默认 Executor。
 *
 * <p>2026-07-03 改造：开启 {@code spring.threads.virtual.enabled=true} 之后，
 * {@code mcpGatewayExecutor} 改为 {@link VirtualThreadTaskExecutor}，
 * 任务进来直接起一个虚拟线程，core/max/queue 全部不再有意义。
 *
 * <p>设计动机：MCP gateway 是 I/O 密集型负载（远端 MCP server HTTP 调用 +
 * MyBatis-Plus JDBC 查询），虚拟线程在阻塞 I/O 等待时释放 carrier thread，
 * 高并发场景下吞吐相比固定平台线程池提升一个数量级。
 *
 * <p>约束：业务代码禁止在 hot path 用 {@code synchronized}（会 pin carrier），
 * 改用 {@code java.util.concurrent.locks.ReentrantLock}。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Configuration
@EnableScheduling
public class McpConfig {

    /**
     * 业务通用 Executor（@Primary）。
     *
     * <p>虚拟线程按任务分配：每个 {@code submit} / {@code @Async} 都获得独立的虚拟线程，
     * 阻塞时挂载到 carrier thread pool（Moirator 默认大小 = CPU 核数），不会
     * 占据固定平台线程槽位。
     *
     * <p>线程名前缀 {@code mcp-gw-vt-} —— 跟旧 platform pool 的 {@code mcp-gw-}
     * 区分，便于 thread dump 区分虚拟线程/平台线程。
     */
    @Bean(name = "mcpGatewayExecutor")
    @Primary
    public AsyncTaskExecutor mcpGatewayExecutor() {
        return new VirtualThreadTaskExecutor("mcp-gw-vt-");
    }
}
