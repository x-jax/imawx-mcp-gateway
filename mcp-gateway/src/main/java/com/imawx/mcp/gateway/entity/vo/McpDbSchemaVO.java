package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpDbSchemaVO {
    private Long dbConnId;
    private String connName;
    private String dbType;
    private LocalDateTime refreshedAt;
    private List<Table> tables;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Table {
        private String name;
        private String type;
        private String remarks;
        private List<Column> columns;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Column {
        private String name;
        private String type;
        private String remarks;
        private boolean primaryKey;
        private boolean nullable;
    }
}
