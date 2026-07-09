package com.imawx.mcp.gateway.common.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.imawx.mcp.gateway.common.security.SensitiveDataMasker;

/**
 * Logback 消息脱敏 converter。
 */
public class RedactedMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataMasker.redactText(event.getFormattedMessage());
    }
}
