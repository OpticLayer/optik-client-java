# ObserveX Java SDK (Spring Boot)

ObserveX Java SDK exports OpenTelemetry traces, metrics, and logs to ObserveX OTLP HTTP ingestion and includes Spring Boot auto-configuration.

## Quick Start

Add dependency:

```xml
<dependency>
  <groupId>com.observex</groupId>
  <artifactId>observex-sdk-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

For Spring Boot, set properties:

```yaml
observex:
  api-key: your-team-api-key
  service-name: orders-spring
  endpoint: http://localhost:8080
  environment: local
  service-version: 1.0.0
  enable-traces: true
  enable-metrics: true
  enable-logs: true
  export-interval: 10s
```

Once enabled, telemetry is exported with:

- `Authorization: Bearer <api_key>`
- traces: `POST /otlp/v1/traces`
- metrics: `POST /otlp/v1/metrics`
- logs: `POST /otlp/v1/logs`
- resource attributes: `service.name`, `deployment.environment`, `service.version`

## Env Vars

Same semantics as the Python SDK:

| Property | Env Var | Default | Description |
|---|---|---|---|
| `observex.api-key` | `OBSERVEX_API_KEY` / `OTEL_API_KEY` | — | Team API key (required) |
| `observex.service-name` | `OBSERVEX_SERVICE_NAME` / `OTEL_SERVICE_NAME` | — | Service name (required) |
| `observex.endpoint` | `OBSERVEX_ENDPOINT` / `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:8080` | Backend base URL |
| `observex.environment` | `OBSERVEX_ENVIRONMENT` | `local` | Deployment environment |
| `observex.service-version` | `OBSERVEX_SERVICE_VERSION` | — | Service version |
| `observex.enable-traces` | `OBSERVEX_ENABLE_TRACES` | `true` | Trace export toggle |
| `observex.export-interval` | `OBSERVEX_EXPORT_INTERVAL_MS` / `OTEL_METRIC_EXPORT_INTERVAL` | `10s` | Metric export interval |
| `observex.enable-metrics` | `OBSERVEX_ENABLE_METRICS` | `true` | Master metrics toggle |
| `observex.enable-logs` | `OBSERVEX_ENABLE_LOGS` | `true` | Log export toggle |

## Non-Spring Usage

```java
ObserveXConfig config = ObserveXConfig.fromEnv()
    .setApiKey("your-team-api-key")
    .setServiceName("batch-worker")
    .setEndpoint("http://localhost:8080");

ObserveX sdk = ObserveX.init(config);
Meter meter = sdk.meter("batch-worker");
Tracer tracer = sdk.tracer("batch-worker");
Logger logger = sdk.logger("batch-worker");
// create counters/histograms using meter
// create spans using tracer, log records using logger
sdk.shutdown();
```

## Spring Boot Notes

- Add `spring-boot-starter-actuator` in your application to emit standard HTTP/JVM metrics.
- This SDK registers an `OpenTelemetryMeterRegistry` bridge so Micrometer meters are exported through OpenTelemetry.
- Trace and log exporters are initialized automatically, but application logs are only exported if your app writes OTel logs (or installs a logging bridge/appender).
