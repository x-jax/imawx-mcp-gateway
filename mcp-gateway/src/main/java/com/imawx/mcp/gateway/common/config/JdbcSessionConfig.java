package com.imawx.mcp.gateway.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@Slf4j
@Configuration
@EnableJdbcHttpSession(
        maxInactiveIntervalInSeconds = 604800,
        tableName = "SPRING_SESSION",
        cleanupCron = "0 * * * * *"
)
public class JdbcSessionConfig {

    @PostConstruct
    public void logJdbcSessionEnabled() {
        log.info("[jdbc-session] enabled table=SPRING_SESSION maxInactive=604800s cleanupCron=\"0 * * * * *\"");
    }
}
