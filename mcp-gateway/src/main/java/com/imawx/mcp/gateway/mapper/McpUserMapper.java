package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpUserDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** {@code mcp_user} 表 Mapper：查询走 XML，增删改走 {@link BaseMapper}。 */
@Mapper
public interface McpUserMapper extends BaseMapper<McpUserDO> {

    McpUserDO selectByUsername(String username);

    /**
     * 2026-07-02 加:邮箱登录用 —— 通过 email 查 user,email 字段有 unique key。
     * 老 selectByUsername 保留兼容(管理后台 / 老用户脚本仍可走 username)。
     */
    McpUserDO selectByEmail(@Param("email") String email);
}