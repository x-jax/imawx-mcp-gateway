package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpBackendExtensionDO;
import org.apache.ibatis.annotations.Param;

public interface McpBackendExtensionMapper extends BaseMapper<McpBackendExtensionDO> {
    McpBackendExtensionDO selectByBackendId(@Param("backendId") Long backendId);
    int deleteByBackendId(@Param("backendId") Long backendId);
}
