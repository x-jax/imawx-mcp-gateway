package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.mapper.McpBackendMapper.McpBackendQuery;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code mcp_backend} 表 Mapper：增删改走 {@link BaseMapper}，查询走 XML。
 *
 * <p>2026-07-03 改:本表不再按 userId 隔离 —— 所有登录用户共享同一份 backend 池。
 * 因此所有方法不再带 userId 参数。
 */
public interface McpBackendMapper extends BaseMapper<McpBackendDO> {

    McpBackendDO selectById(@Param("id") Long id);

    /**
     * 全局按 backendId 查(2026-07-03 改) —— 任何用户创建的 backend 都能查出来,不再强绑 userId。
     * 业务层用此校验"backendId 全平台唯一",create 路径有冲突就返 CONFLICT。
     */
    McpBackendDO selectByBackendId(@Param("backendId") String backendId);

    /**
     * 2026-07-02 加,2026-07-03 改:聚合路由(/api/mcp)用 —— 拿所有启用的 backend,顺序按 backend_id 稳定。
     * 不再按 userId 过滤 —— 所有登录用户共享同一份 backend 池。
     */
    List<McpBackendDO> selectEnabled();

    /** 2026-07-03 改:不再 userId 过滤,管理员/普通用户都能看到全平台 backend 列表。 */
    long countByQuery(@Param("query") McpBackendQuery query);

    List<McpBackendDO> selectPageList(@Param("query") McpBackendQuery query);

    class McpBackendQuery {
        public String keyword;
        public String transportType;
        public Integer enabled;
        public int offset;
        public int size;
    }
}