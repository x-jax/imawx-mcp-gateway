package com.imawx.mcp.gateway.config;

import com.baomidou.mybatisplus.autoconfigure.DdlApplicationRunner;
import com.baomidou.mybatisplus.extension.ddl.DdlScriptErrorHandler;
import com.baomidou.mybatisplus.extension.ddl.IDdl;
import com.baomidou.mybatisplus.extension.ddl.history.IDdlGenerator;
import com.baomidou.mybatisplus.extension.ddl.history.MysqlDdlGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * MyBatis-Plus 全局配置。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    private static final String DDL_SQL_PATTERN = "classpath*:db/**/*.sql";
    private static final String DDL_ROOT = "/db/";

    @Bean
    public IDdl mcpGatewayDdl(DataSource dataSource, ResourcePatternResolver resourcePatternResolver) {
        List<String> sqlFiles = scanSqlFiles(resourcePatternResolver);
        log.info("[ddl] discovered sql files: {}", sqlFiles);
        return new IDdl() {
            @Override
            public void runScript(Consumer<DataSource> consumer) {
                consumer.accept(dataSource);
            }

            @Override
            public IDdlGenerator getDdlGenerator() {
                return MysqlDdlGenerator.newInstance();
            }

            @Override
            public List<String> getSqlFiles() {
                return sqlFiles;
            }
        };
    }

    @Bean
    public DdlApplicationRunner ddlApplicationRunner(List<IDdl> ddlList) {
        DdlApplicationRunner runner = new DdlApplicationRunner(ddlList);
        runner.setThrowException(true);
        runner.setDdlScriptErrorHandler(DdlScriptErrorHandler.ThrowsErrorHandler.INSTANCE);
        return runner;
    }

    private static List<String> scanSqlFiles(ResourcePatternResolver resourcePatternResolver) {
        try {
            List<String> sqlFiles = Stream.of(resourcePatternResolver.getResources(DDL_SQL_PATTERN))
                    .map(MybatisPlusConfig::toClasspathSqlPath)
                    .distinct()
                    .sorted()
                    .toList();
            if (sqlFiles.isEmpty()) {
                throw new IllegalStateException("No DDL sql files found by pattern: " + DDL_SQL_PATTERN);
            }
            return sqlFiles;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan DDL sql files by pattern: " + DDL_SQL_PATTERN, e);
        }
    }

    private static String toClasspathSqlPath(Resource resource) {
        try {
            String url = resource.getURL().toExternalForm();
            int dbIndex = url.indexOf(DDL_ROOT);
            if (dbIndex < 0) {
                throw new IllegalStateException("DDL sql file is outside db directory: " + url);
            }
            return "db/" + url.substring(dbIndex + DDL_ROOT.length());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve DDL sql file path: " + resource, e);
        }
    }
}
