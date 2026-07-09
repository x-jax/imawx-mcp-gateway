package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpSystemConfigDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 系统配置表 mapper(2026-07-02 加)。
 *
 * <p>BaseMapper 自带 selectById / insert / updateById / deleteById;这里只
 * 加 listAll 用于 admin 配置页全量查询。
 */
@Mapper
public interface McpSystemConfigMapper extends BaseMapper<McpSystemConfigDO> {

    /** 列出所有配置项(按 config_key 升序,UI 列表展示用) */
    List<McpSystemConfigDO> listAll();
}
