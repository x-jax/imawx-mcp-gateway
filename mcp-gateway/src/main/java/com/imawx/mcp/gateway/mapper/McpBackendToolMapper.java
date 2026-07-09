package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpBackendToolDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** {@code mcp_backend_tool} Mapper。 */
public interface McpBackendToolMapper extends BaseMapper<McpBackendToolDO> {

    List<McpBackendToolDO> selectByBackendId(@Param("backendId") Long backendId);

    long countByBackendId(@Param("backendId") Long backendId);

    /** 2026-07-03 加:批量按 backend_id(Long,主键)拉 tool 列表(token 授权编辑 UI 用,避免 N+1)。 */
    List<McpBackendToolDO> selectListByBackendIds(@Param("backendIds") List<Long> backendIds);

    /**
     * 2026-07-03 加:判断某 backend 下是否有指定 tool(精确匹配)——
     * 聚合 tools/call 的"tool 是否存在"快查,索引命中 uk_backend_tool。
     * 跟 token 授权判断联用,区分"tool 不存在"(-32601 NotFound)
     * vs "tool 存在但 token 未授权"(403 FORBIDDEN)。
     */
    long countByBackendIdAndToolName(@Param("backendId") Long backendId,
                                     @Param("toolName") String toolName);
}