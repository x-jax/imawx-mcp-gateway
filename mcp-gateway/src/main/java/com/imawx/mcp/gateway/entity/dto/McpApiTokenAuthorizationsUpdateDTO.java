package com.imawx.mcp.gateway.entity.dto;

import com.imawx.mcp.gateway.entity.dto.McpApiTokenCreateDTO.AuthorizedToolRef;
import lombok.Data;

import java.util.List;

/**
 * 单独更新 token 授权范围 DTO。
 */
@Data
public class McpApiTokenAuthorizationsUpdateDTO {

    /**
     * 授权模式(0=全开放 / 1=严格)。
     * null = 不修改(保留旧值)—— 避免前端只传 authorizedBackends 时把 restrictMode 误清掉。
     */
    private Integer restrictMode;

    /**
     * IP 白名单 —— 完整替换。
     * 传 null = 不修改;传空列表 = 清空白名单,即不限制 IP。
     */
    private List<String> ipWhitelist;

    /**
     * 授权可访问的 backend 列表 —— 完整替换(不是追加)。
     * 传 null = 不修改;传空列表 = 清空 backend 级授权。
     */
    private List<String> authorizedBackends;

    /**
     * 授权可调用的具体 tool 列表 —— 完整替换。
     * 传 null = 不修改;传空列表 = 清空 tool 级授权。
     */
    private List<AuthorizedToolRef> authorizedTools;
}
