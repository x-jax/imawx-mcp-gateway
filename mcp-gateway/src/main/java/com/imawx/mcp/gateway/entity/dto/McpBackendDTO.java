package com.imawx.mcp.gateway.entity.dto;

import com.imawx.mcp.gateway.common.enums.TransportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 下游 MCP 服务配置入参 DTO（创建 / 更新共用）。
 *
 * <p>stage 1 不引入 UpdateGroup / CreateGroup 区分，验证全在控制器层。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Data
public class McpBackendDTO {

    /** 主键（更新时必填，创建时为空）。 */
    private Long id;

    /** 下游唯一标识。 */
    @NotBlank(message = "backendId 不能为空")
    @Size(max = 64, message = "backendId 长度不能超过 64")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "backendId 仅允许字母数字下划线短横线")
    private String backendId;

    @NotBlank(message = "name 不能为空")
    @Size(max = 128, message = "name 长度不能超过 128")
    private String name;

    @NotNull(message = "transportType 不能为空")
    private TransportType transportType;

    /** STDIO 用。 */
    @Size(max = 512, message = "stdioCommand 长度不能超过 512")
    private String stdioCommand;

    /** STDIO 用，JSON 数组字符串。 */
    private String stdioArgs;

    /** SSE / Streamable 用。 */
    @Size(max = 512, message = "remoteUrl 长度不能超过 512")
    private String remoteUrl;

    /** STDIO 用，JSON 对象字符串。 */
    private String envJson;

    private Integer healthInterval;

    private Integer failThreshold;

    /** 1 启用 / 0 停用。 */
    private Integer enabled;

    @Size(max = 512, message = "remark 长度不能超过 512")
    private String remark;
}
