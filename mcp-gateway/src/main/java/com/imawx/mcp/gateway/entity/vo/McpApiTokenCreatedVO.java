package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建 Token 响应 VO(对应前端 {@code ImawxApiTokenCreated},2026-07-01 加)。
 *
 * <p><b>这是唯一一次能看到明文 token 的地方</b>。前端需要做醒目的"复制"按钮 +
 * "关闭后不再显示"提示,防止用户错过。
 *
 * <p>明文格式:{@code imwx_<32位 base62>},总长 37 字符。
 */
@Data
@Builder
public class McpApiTokenCreatedVO {

    /** 数据库主键 ID(string 输出避免 JS 精度丢失)。 */
    private String id;
    /** 用户备注名。 */
    private String name;
    /** token 前 8 位(列表展示用)。 */
    private String tokenPrefix;
    /**
     * <b>明文 token</b>(唯一一次)。前端拿到后立刻展示给用户 + 让用户复制,
     * 关掉弹窗后不再展示。
     */
    private String plaintext;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}