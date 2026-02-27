package com.optic.sdk.spring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.optic.sdk.Optic;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

final class OpticLogbackBridge implements AutoCloseable {
    private static final String APPENDER_NAME = "OPTIC_OTEL_APPENDER";
    private static final String ROOT_LOGGER = org.slf4j.Logger.ROOT_LOGGER_NAME;

    private final ch.qos.logback.classic.Logger rootLogger;
    private final OpticLogbackAppender appender;

    OpticLogbackBridge(Optic optic) {
        Object factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException("Logback LoggerContext not available");
        }

        this.rootLogger = context.getLogger(ROOT_LOGGER);
        this.appender = new OpticLogbackAppender(optic.logger("optic-logback-bridge"));
        this.appender.setName(APPENDER_NAME);
        this.appender.setContext(context);
        this.appender.start();
        this.rootLogger.addAppender(this.appender);
    }

    @Override
    public void close() {
        if (rootLogger != null && appender != null) {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private static final class OpticLogbackAppender extends AppenderBase<ILoggingEvent> {
        private final Logger otelLogger;

        private OpticLogbackAppender(Logger otelLogger) {
            this.otelLogger = otelLogger;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (event == null) {
                return;
            }

            String loggerName = safe(event.getLoggerName());
            // Avoid feedback loops from SDK/exporter internals.
            if (loggerName.startsWith("io.opentelemetry.") || loggerName.startsWith("com.optic.sdk.")) {
                return;
            }

            String message = safe(event.getFormattedMessage());
            if (message.isEmpty()) {
                return;
            }

            try {
                LogRecordBuilder record = otelLogger.logRecordBuilder()
                        .setTimestamp(event.getTimeStamp(), TimeUnit.MILLISECONDS)
                        .setSeverity(toSeverity(event.getLevel()))
                        .setBody(message)
                        .setAttribute(AttributeKey.stringKey("logger.name"), loggerName)
                        .setAttribute(AttributeKey.stringKey("thread.name"), safe(event.getThreadName()));

                Context ctx = contextFromEvent(event);
                if (ctx != null) {
                    record.setContext(ctx);
                }

                Map<String, String> mdc = event.getMDCPropertyMap();
                if (mdc != null && !mdc.isEmpty()) {
                    for (Map.Entry<String, String> entry : mdc.entrySet()) {
                        String key = safe(entry.getKey());
                        String value = safe(entry.getValue());
                        if (!key.isEmpty() && !value.isEmpty()) {
                            record.setAttribute(AttributeKey.stringKey("log.mdc." + key), value);
                        }
                    }
                }

                IThrowableProxy throwable = event.getThrowableProxy();
                if (throwable != null) {
                    record.setAttribute(AttributeKey.stringKey("exception.type"), safe(throwable.getClassName()));
                    record.setAttribute(AttributeKey.stringKey("exception.message"), safe(throwable.getMessage()));
                }

                record.emit();
            } catch (RuntimeException ignored) {
                // Never break app logging pipeline due to telemetry export errors.
            }
        }

        private static Context contextFromEvent(ILoggingEvent event) {
            Map<String, String> mdc = event.getMDCPropertyMap();
            if (mdc != null) {
                String traceID = firstNonBlank(
                        mdc.get("trace.id"),
                        mdc.get("trace_id"),
                        mdc.get("traceId"),
                        mdc.get("otel.trace_id")
                );
                String spanID = firstNonBlank(
                        mdc.get("span.id"),
                        mdc.get("span_id"),
                        mdc.get("spanId"),
                        mdc.get("otel.span_id")
                );
                if (isValidTraceID(traceID) && isValidSpanID(spanID)) {
                    SpanContext spanContext = SpanContext.createFromRemoteParent(
                            traceID,
                            spanID,
                            TraceFlags.getSampled(),
                            TraceState.getDefault()
                    );
                    return Context.root().with(Span.wrap(spanContext));
                }
            }

            Span current = Span.current();
            if (current != null && current.getSpanContext().isValid()) {
                return Context.current();
            }

            return null;
        }

        private static Severity toSeverity(Level level) {
            if (level == null) {
                return Severity.INFO;
            }
            return switch (level.toInt()) {
                case Level.ERROR_INT -> Severity.ERROR;
                case Level.WARN_INT -> Severity.WARN;
                case Level.INFO_INT -> Severity.INFO;
                case Level.DEBUG_INT -> Severity.DEBUG;
                case Level.TRACE_INT -> Severity.TRACE;
                default -> Severity.INFO;
            };
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                String trimmed = safe(value);
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
            return "";
        }

        private static boolean isValidTraceID(String value) {
            return isHex(value, 32) && !"00000000000000000000000000000000".equals(value);
        }

        private static boolean isValidSpanID(String value) {
            return isHex(value, 16) && !"0000000000000000".equals(value);
        }

        private static boolean isHex(String value, int expectedLen) {
            if (value == null || value.length() != expectedLen) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean ok = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!ok) {
                    return false;
                }
            }
            return true;
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
