package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpTokenToolAuthorizationDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Token 授权 tool 中间表 Mapper(2026-07-03 加)。
 */
@Mapper
public interface McpTokenToolAuthorizationMapper extends BaseMapper<McpTokenToolAuthorizationDO> {

    /**
     * 取 token 的所有授权 tool 列表(返回 (backendId, toolName) 二元组)。
     */
    @Select("SELECT backend_id, tool_name FROM mcp_token_tool_authorization WHERE token_id = #{tokenId}")
    List<TokenToolPair> selectPairsByTokenId(@Param("tokenId") Long tokenId);

    /**
     * 删 token 的所有授权 tool。
     */
    @Delete("DELETE FROM mcp_token_tool_authorization WHERE token_id = #{tokenId}")
    int deleteByTokenId(@Param("tokenId") Long tokenId);

    /**
     * 检查 (token, backend, tool) 三元组是否在授权列表里(快查)。
     * 严格模式校验用,索引命中 uk_token_backend_tool。
     */
    @Select("SELECT COUNT(*) FROM mcp_token_tool_authorization " +
            "WHERE token_id = #{tokenId} AND backend_id = #{backendId} AND tool_name = #{toolName}")
    int countByTriple(@Param("tokenId") Long tokenId,
                      @Param("backendId") String backendId,
                      @Param("toolName") String toolName);

    /**
     * 批量查多个 token 的授权 tool 集合(返回 List<TokenToolPair> —— 含 tokenId)。
     * 一次 IN 查询避免 N+1。
     */
    @Select("<script>" +
            "SELECT token_id, backend_id, tool_name FROM mcp_token_tool_authorization " +
            "WHERE token_id IN " +
            "<foreach collection='tokenIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<TokenToolTriple> selectTriplesByTokenIds(@Param("tokenIds") List<Long> tokenIds);

    /** 二元组:backend_id + tool_name(无 tokenId,通常已知道是哪个 token)。 */
    record TokenToolPair(String backendId, String toolName) {}

    /** 三元组:含 tokenId(批量查用)。 */
    record TokenToolTriple(Long tokenId, String backendId, String toolName) {}
}