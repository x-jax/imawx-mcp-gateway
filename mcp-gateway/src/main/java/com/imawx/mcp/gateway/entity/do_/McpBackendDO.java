package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部 MCP server 配置 DO。
 *
 * <p>字段命名以新规范（snake_case + 业务语义清晰）为准。状态字段 {@code status}
 * 用整数 code：0=DISCONNECTED / 1=CONNECTED / 2=FAILED，配套枚举见
 * {@code ConnectionStatusEnum}（前端契约要求 string，对齐在 VO 层做）。
 *
 * <p>企业内部部署下，所有登录用户共享同一份 {@code mcp_backend} 池。新增时记录
 * {@code createdBy}，后续编辑记录 {@code updatedBy}，用于审计。
 */
@Data
@TableName("mcp_backend")
public class McpBackendDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务主键（用户起的别名转 key）。 */
    private String backendId;

    /** 创建人 userId。 */
    private Long createdBy;

    /** 最近修改人 userId；自动同步和探活任务不绑定人。 */
    private Long updatedBy;

    /** 前端展示名。 */
    private String serverName;

    /** 传输类型：STDIO / SSE / HTTP。 */
    private String transportType;

    /** URL（HTTPSSE）或命令（STDIO）。 */
    private String endpoint;

    /**
     * SSE/HTTP 鉴权 Token 密文 envelope。读取用于下游鉴权时需解密，VO 不暴露此字段。
     */
    private String authToken;

    /** STDIO 的 args、env 等（JSON 字符串）。 */
    private String extraConfig;

    /**
     * 用户标签 JSON 数组字符串，如 ["prod","团队-A"]。
     */
    private String tags;

    /** 备注。 */
    private String remark;

    /** 连接状态：0=DISCONNECTED 1=CONNECTED 2=FAILED。 */
    private Integer status;

    /** 1启用 / 0禁用。 */
    private Integer enabled;

    /** 健康探测周期（秒）。 */
    private Integer healthInterval;

    /** 熔断阈值。 */
    private Integer failThreshold;

    /** 连续失败计数。 */
    private Integer failCount;

    private LocalDateTime lastCheckAt;
    private String lastError;
    private LocalDateTime lastSyncAt;
    private String lastSyncError;

    /** 工具快照 JSON。 */
    private String toolsSnapshot;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
