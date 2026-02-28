package com.optic.sdk.spring;

import com.optic.sdk.Optic;
import com.optic.sdk.OpticConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnClass(Optic.class)
@EnableConfigurationProperties(OpticProperties.class)
@ConditionalOnProperty(prefix = "optic", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpticAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public Optic opticSdk(OpticProperties properties, Environment environment) {
        OpticConfig config = buildConfig(properties, environment);
        return Optic.init(config);
    }

    @Bean
    @ConditionalOnMissingBean(OpenTelemetry.class)
    public OpenTelemetry opticOpenTelemetry(Optic optic) {
        return optic.getOpenTelemetry();
    }

    @Bean
    @ConditionalOnClass({MeterRegistry.class, OpenTelemetryMeterRegistry.class})
    @ConditionalOnProperty(prefix = "optic", name = "enable-metrics", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "opticOpenTelemetryMeterRegistry")
    public MeterRegistry opticOpenTelemetryMeterRegistry(Optic optic) {
        return OpenTelemetryMeterRegistry.builder(optic.getOpenTelemetry()).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "optic", name = "enable-logs", havingValue = "true", matchIfMissing = true)
    @ConditionalOnClass(name = {"ch.qos.logback.classic.LoggerContext", "org.slf4j.LoggerFactory"})
    @ConditionalOnMissingBean(name = "opticLogbackBridge")
    public AutoCloseable opticLogbackBridge(Optic optic) {
        return new OpticLogbackBridge(optic);
    }

    private static OpticConfig buildConfig(OpticProperties properties, Environment environment) {
        OpticConfig config = OpticConfig.fromEnv();

        if (hasText(properties.getApiKey())) {
            config.setApiKey(properties.getApiKey());
        }
        if (hasText(properties.getServiceName())) {
            config.setServiceName(properties.getServiceName());
        }
        if (hasText(properties.getEndpoint())) {
            config.setEndpoint(properties.getEndpoint());
        }
        if (hasText(properties.getEnvironment())) {
            config.setEnvironment(properties.getEnvironment());
        }
        if (hasText(properties.getServiceVersion())) {
            config.setServiceVersion(properties.getServiceVersion());
        }
        config.setEnableTraces(properties.isEnableTraces());
        config.setEnableMetrics(properties.isEnableMetrics());
        config.setEnableLogs(properties.isEnableLogs());
        config.setExportInterval(properties.getExportInterval());

        if (!hasText(config.getServiceName())) {
            config.setServiceName(environment.getProperty("spring.application.name", ""));
        }

        return config;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
