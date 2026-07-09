package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpApiTokenDO;

/**
 * API Token Mapper。
 */
public interface McpApiTokenMapper extends BaseMapper<McpApiTokenDO> {

    /**
     * API token 使用服务端生成的高熵随机值，库里保存确定性哈希，鉴权时直接走索引命中。
     */
    McpApiTokenDO selectActiveByHash(String tokenHash);
}
