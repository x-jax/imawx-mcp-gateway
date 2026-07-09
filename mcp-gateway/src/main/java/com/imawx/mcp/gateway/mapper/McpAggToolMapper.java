package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpAggToolDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** {@code mcp_agg_tool} Mapper。 */
public interface McpAggToolMapper extends BaseMapper<McpAggToolDO> {

    List<McpAggToolDO> selectByBackendId(@Param("backendId") Long backendId);

    long countByBackendId(@Param("backendId") Long backendId);

    /** 阶段 3 {@code tools/list} 用：按 backendId 顺序取所有聚合工具。 */
    List<McpAggToolDO> selectAllOrdered();
}