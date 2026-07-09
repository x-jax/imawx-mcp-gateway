package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置项 DO（{@code mcp_system_config}）。
 *
 * <p>主键 {@code configKey} 字符串,value 统一存 string(0/1 表示布尔,数字直接 toString)。
 *
 * <p>已用 key:
 * <ul>
 *   <li>{@code mcp.global.enabled} —— 全局 MCP 开关(0/1)</li>
 *   <li>{@code mcp.session.timeout-hours} —— Session 过期小时数</li>
 *   <li>{@code mcp.auth.totp-enabled} —— TOTP 2FA 总开关(0/1),dev 默认 0,prod 默认 1</li>
 *   <li>{@code mcp.security.stdio.allowed-commands} —— STDIO 命令白名单</li>
 *   <li>{@code mcp.security.redact.keys} —— 审计日志脱敏字段名</li>
 *   <li>{@code mcp.security.redact.patterns} —— 审计日志脱敏正则</li>
 *   <li>{@code mcp.audit.access-log.retention-days} —— 访问日志保留天数，最多 90</li>
 *   <li>{@code mcp.audit.call-log.retention-days} —— 调用日志保留天数，最多 90</li>
 * </ul>
 */
@Data
@TableName("mcp_system_config")
public class McpSystemConfigDO {

    @TableId(type = IdType.INPUT)
    private String configKey;

    private String configValue;

    private String description;

    private String updatedBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
