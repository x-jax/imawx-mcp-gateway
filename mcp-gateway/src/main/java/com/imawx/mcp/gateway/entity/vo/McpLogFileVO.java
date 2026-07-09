package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 日志文件元数据 VO(对应前端 {@code ImawxLogFile},2026-07-01 加)。
 *
 * <p>前端不展示绝对路径(避免泄露服务器目录结构),只展示 name。
 */
@Data
@Builder
public class McpLogFileVO {

    /** 文件名(相对 {@code imawx.log.dir} 根目录,例如 {@code mcp-gateway.log} 或 {@code mcp-gateway.log.2026-07-01.0.gz})。 */
    private String name;

    /** 文件绝对路径。仅后端内部使用,不返回给前端。 */
    private String absolutePath;

    /** 文件大小(字节)。 */
    private long size;

    /** 最后修改时间(epoch millis)。 */
    private long lastModified;

    /** 是否 gzip 压缩(归档文件)。 */
    private boolean gzipped;

    /** 文件分类:{@code active} 活跃日志 / {@code archive} 历史归档。 */
    private String category;

    /**
     * 文件名短名(去掉 {@code archive/} 前缀),给前端 sidebar 展示用。
     * 由 Service 层计算后塞进 name 字段(name = shortName + 后缀信息)。
     */
    public String getShortName() {
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}