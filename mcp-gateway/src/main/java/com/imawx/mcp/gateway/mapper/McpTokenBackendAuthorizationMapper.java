package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpTokenBackendAuthorizationDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Token 授权 backend 中间表 Mapper(2026-07-03 加)。
 */
@Mapper
public interface McpTokenBackendAuthorizationMapper extends BaseMapper<McpTokenBackendAuthorizationDO> {

    /**
     * 取 token 的所有授权 backend 列表。
     */
    @Select("SELECT backend_id FROM mcp_token_backend_authorization WHERE token_id = #{tokenId}")
    List<String> selectBackendIdsByTokenId(@Param("tokenId") Long tokenId);

    /**
     * 删 token 的所有授权(更新授权时先全删再批量插 —— token 数量小,简单可靠)。
     */
    @Delete("DELETE FROM mcp_token_backend_authorization WHERE token_id = #{tokenId}")
    int deleteByTokenId(@Param("tokenId") Long tokenId);

    /**
     * 批量查多个 token 的授权 backend 集合(返回 Map<tokenId, List<backendId>>)。
     * 一次 IN 查询避免 N+1。
     */
    @Select("<script>" +
            "SELECT token_id, backend_id FROM mcp_token_backend_authorization " +
            "WHERE token_id IN " +
            "<foreach collection='tokenIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<TokenBackendPair> selectPairsByTokenIds(@Param("tokenIds") List<Long> tokenIds);

    /** 简单 DTO:token_id + backend_id 一对。 */
    record TokenBackendPair(Long tokenId, String backendId) {}
}