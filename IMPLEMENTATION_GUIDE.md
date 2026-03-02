# Distributed Ledger System - Implementation Guide

## What Has Been Implemented

### Core Application (`ledger-api/`)

| Component | Status | Description |
|-----------|--------|-------------|
| **Gradle Build** | Done | Kotlin DSL, Spring Boot 3.3.5, Java 21, Gradle wrapper |
| **Domain Models** | Done | Account, Transaction, Entry, AccountState, IdempotencyRecord, OutboxEvent |
| **PostgreSQL Schema** | Done | Flyway V1 migration: accounts, account_state, transactions_min, entries, idempotency, outbox |
| **PostgreSQL Repositories** | Done | JDBC-based: AccountRepository, AccountStateRepository, TransactionRepository, IdempotencyRepository, OutboxRepository |
| **MongoDB Projection** | Done | TransactionDocument, TransactionProjectionRepository (upsert/query), MongoIndexInitializer |
| **REST API** | Done | AccountController, TransactionController, IdempotencyController, HealthController (matches OpenAPI spec) |
| **Core Services** | Done | TransactionService (post, reverse, batch, query), AccountService, IdempotencyService |
| **Idempotency** | Done | SHA-256 request hashing, unique constraint on (tenant_id, key), replay detection, conflict detection |
| **Outbox Pattern** | Done | OutboxPublisher (scheduled poll + Kafka publish), mark-as-published |
| **Kafka Consumer** | Done | ProjectionConsumer (TransactionPosted → MongoDB upsert, TransactionReversed → status update, DLQ on failure) |
| **Observability** | Done | Micrometer metrics (posting counter, latency histogram, idempotency hits, outbox lag), structured JSON logging, MDC tenant/trace/txId filter |
| **Dockerfile** | Done | Multi-stage build (JDK 21 builder → JRE 21 runtime), non-root user, G1GC tuning |
| **Docker Compose** | Done | Updated with ledger-api service, health-check dependencies on Postgres/Mongo/Kafka |
| **Unit Tests** | Done | 23 tests: TransactionServiceTest, AccountServiceTest, IdempotencyServiceTest (mockito-kotlin) |
| **Integration Tests** | Done | Testcontainers-based: AccountIT, LedgerPostingIT, IdempotencyIT, ReversalIT, BatchIT |
| **CI/CD Workflows** | Done | GitHub Actions: pr-checks.yml, build-and-deploy-staging.yml, release-prod.yml |
| **k6 Load Tests** | Done | Enhanced: heavy_idempotency.js, heavy_throughput.js, endurance.js |

### Architecture & Design (existing docs)

| Document | Path |
|----------|------|
| ADR-001 Hybrid Write Model | `docs/adr/ADR-001-hybrid-write-model.md` |
| ADR-002 Idempotency | `docs/adr/ADR-002-idempotency.md` |
| ADR-003 Kafka Partitioning | `docs/adr/ADR-003-kafka-partitioning.md` |
| ADR-004 At-Least-Once | `docs/adr/ADR-004-at-least-once.md` |
| OpenAPI Spec | `docs/api/openapi.yaml` |
| DB Diagram | `docs/architecture/dbdiagram.dbml` |
| Index Tuning | `docs/architecture/index-tuning.md` |
| CI/CD Strategy | `docs/architecture/ci-cd-strategy.md` |
| Capacity Planning | `docs/architecture/capacity-planning.md` |
| Nginx Ingress | `docs/architecture/nginx-ingress.md` |
| Eventing Schema | `docs/eventing/event-schema.v1.json` |
| Observability | `docs/observability/observability.md` |
| Security Hardening | `docs/security/security-hardening.md` |
| Testcontainers Guide | `docs/testing/testcontainers.md` |
| Runbooks | `docs/runbooks/` |

---

## How to Run

### Prerequisites
- Java 21
- Docker & Docker Compose
- k6 (for load tests)

### Run Unit Tests (no Docker required)
```bash
cd ledger-api
./gradlew test --tests "com.ledger.unit.*"
```

### Run Integration Tests (requires Docker for Testcontainers)
```bash
cd ledger-api
./gradlew test --tests "com.ledger.integration.*"
```

### Run All Tests
```bash
cd ledger-api
./gradlew test
```

### Start Full Stack Locally
```bash
cd docker
docker compose up -d

# Wait for all services to be healthy, then:
# API available at http://localhost:8080
# Grafana at http://localhost:3000 (admin/admin)
# Prometheus at http://localhost:9090
```

### Seed Test Data and Run k6
```bash
export BASE_URL=http://localhost:8080
export TENANT=t1

# Seed accounts
for i in $(seq 1 2000); do
  curl -s -X POST $BASE_URL/v1/accounts \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: $TENANT" \
    -d "{\"name\":\"THR_A$i\",\"type\":\"ASSET\",\"currency\":\"EUR\"}" > /dev/null
done

# Run heavy idempotency test
k6 run k6/heavy_idempotency.js

# Run heavy throughput test
k6 run k6/heavy_throughput.js

# Run endurance (soak) test
k6 run k6/endurance.js
```

---

## What Requires Manual Setup / Further Implementation

### 1. Kubernetes Deployment (Helm)

The Helm chart is defined in `helm/ledger/` but deploying it requires:

1. **Container Registry**: Push the Docker image to a registry (e.g., GHCR, ECR, GCR)
   ```bash
   cd ledger-api
   ./gradlew bootJar
   docker build -t ghcr.io/<org>/ledger-api:latest .
   docker push ghcr.io/<org>/ledger-api:latest
   ```
2. **Kubernetes Cluster**: Apply Helm chart with proper values
   ```bash
   helm upgrade --install ledger-api helm/ledger \
     --namespace ledger \
     --set image.repository=ghcr.io/<org>/ledger-api \
     --set image.tag=<sha> \
     --set env.POSTGRES_URL=jdbc:postgresql://<pg-host>:5432/ledger \
     --set env.MONGO_URI=mongodb://<mongo-host>:27017/ledger \
     --set env.KAFKA_BOOTSTRAP=<kafka-host>:9092
   ```
3. **Managed Databases**: PostgreSQL, MongoDB, and Kafka should be managed services in production (e.g., AWS RDS, Atlas, MSK)

### 2. Secrets Management

- Replace hardcoded credentials in `values.yaml` and `docker-compose.yml` with:
  - Kubernetes Secrets or External Secrets Operator
  - HashiCorp Vault or cloud-native secret managers
  - GitHub Actions secrets for CI/CD

### 3. OpenTelemetry Distributed Tracing

The `OTEL_EXPORTER_OTLP_ENDPOINT` is configured but the OpenTelemetry Java agent needs to be added:

```dockerfile
# In Dockerfile, add before ENTRYPOINT:
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.8.0/opentelemetry-javaagent.jar /app/otel-agent.jar

# Then in ENTRYPOINT, add:
"-javaagent:/app/otel-agent.jar",
"-Dotel.service.name=ledger-api",
```

Set up a trace collector (Jaeger, Tempo, or OTLP-compatible backend) and configure the exporter endpoint.

### 4. TLS / mTLS

- Configure TLS termination at the ingress level (see `docs/architecture/nginx-ingress.md`)
- For service-to-service mTLS, use Istio or Linkerd service mesh
- Database connections should use TLS in production

### 5. Rate Limiting

Add per-tenant rate limiting as described in the hot-account-contention runbook:

```kotlin
// Implement a RateLimitingFilter or use Spring Cloud Gateway
// Configure limits per tenant in application.yml or a config service
```

### 6. Production Monitoring Alerts

Configure alerting rules in Prometheus/Grafana:
- `ledger_postings_total{result="concurrency_conflict"}` rate spike
- `ledger_outbox_lag_seconds` > threshold
- `ledger_posting_latency_seconds` p99 > SLO
- JVM heap usage, GC pause times, connection pool exhaustion

### 7. Database Partitioning (for massive scale)

As described in `docs/architecture/capacity-planning.md`:
- Partition `transactions_min` and `entries` tables by `tenant_id` using PostgreSQL declarative partitioning
- Implement periodic archival of old outbox events
- Add read replicas for query-heavy workloads

### 8. Consumer Group Scaling

The Kafka consumer currently runs with concurrency 3. For production:
- Match consumer concurrency to the number of partitions (128 partitions → can scale up to 128 consumers)
- Deploy separate consumer-group pods if needed
- Monitor consumer lag via Kafka metrics

### 9. Grafana Dashboards

The provisioned dashboards in `docker/grafana/dashboards/` should be enhanced with:
- k6 test result overlays
- Kafka consumer lag panels
- PostgreSQL connection pool metrics
- MongoDB query performance metrics

### 10. Contract Testing

Add API contract tests using Spring Cloud Contract or Pact:
```kotlin
// Verify OpenAPI spec compliance
// Add consumer-driven contract tests for Kafka events
```

---

## Project Structure

```
ledger-system-design/
├── ledger-api/                          # Main application
│   ├── build.gradle.kts                 # Gradle build with all dependencies
│   ├── settings.gradle.kts
│   ├── Dockerfile                       # Multi-stage Docker build
│   ├── gradlew / gradlew.bat           # Gradle wrapper
│   └── src/
│       ├── main/
│       │   ├── kotlin/com/ledger/
│       │   │   ├── LedgerApplication.kt
│       │   │   ├── config/              # KafkaConfig, MongoConfig
│       │   │   ├── domain/model/        # Account, Transaction, Entry, etc.
│       │   │   ├── api/
│       │   │   │   ├── controller/      # REST controllers
│       │   │   │   ├── dto/             # Request/Response DTOs
│       │   │   │   └── exception/       # Exception classes + GlobalExceptionHandler
│       │   │   ├── repository/
│       │   │   │   ├── postgres/        # JDBC repositories
│       │   │   │   └── mongo/           # MongoDB projection repository
│       │   │   ├── service/             # Business logic
│       │   │   ├── eventing/            # OutboxPublisher, ProjectionConsumer
│       │   │   └── observability/       # LedgerMetrics, TenantFilter
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/V1__init_schema.sql
│       └── test/
│           ├── kotlin/com/ledger/
│           │   ├── unit/service/        # Unit tests (23 tests)
│           │   └── integration/         # Integration tests with Testcontainers
│           └── resources/application-test.yml
├── docker/
│   ├── docker-compose.yml               # Full local stack
│   ├── prometheus/prometheus.yml
│   └── grafana/                         # Dashboards + provisioning
├── k6/                                  # Load tests
│   ├── heavy_idempotency.js             # Heavy idempotency stress test
│   ├── heavy_throughput.js              # Max throughput test
│   ├── endurance.js                     # 30-min soak test
│   ├── baseline.js, spike.js, etc.      # Standard scenarios
│   └── README.md
├── .github/workflows/                   # CI/CD pipelines
│   ├── pr-checks.yml                    # PR quality gate
│   ├── build-and-deploy-staging.yml     # Main → staging
│   └── release-prod.yml                 # Tag → production
├── helm/ledger/                         # Kubernetes Helm chart
├── docs/                                # Architecture docs, ADRs, runbooks
└── IMPLEMENTATION_GUIDE.md              # This file
```
