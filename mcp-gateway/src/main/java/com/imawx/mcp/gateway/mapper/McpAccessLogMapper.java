package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpAccessLogDO;
import com.imawx.mcp.gateway.entity.vo.McpAccessLogVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code mcp_access_log} Mapper。
 */
public interface McpAccessLogMapper extends BaseMapper<McpAccessLogDO> {

    List<McpAccessLogVO> selectPageList(@Param("query") McpAccessLogQuery query);

    long countByQuery(@Param("query") McpAccessLogQuery query);

    record McpAccessLogQuery(
            LocalDateTime startTime,
            LocalDateTime endTime,
            String keyword,
            String userEmail,
            String ip,
            String method,
            String result,
            Integer status,
            int offset,
            int size
    ) {
    }
}
