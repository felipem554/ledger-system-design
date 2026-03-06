# Testing Guide

## Testing Pyramid

This project follows the **Testing Pyramid** strategy:

```
         /  E2E   \          ~10%  — Smoke tests against full running stack
        /----------\
       / Integration \       ~20%  — Testcontainers (real Postgres/Mongo/Kafka)
      /----------------\
     /    Unit Tests     \   ~70%  — Pure logic, mocks, fast, no I/O
    /____________________\
```

| Layer | Location | Infra required | Gradle task |
|-------|----------|----------------|-------------|
| **Unit** | `com.ledger.unit.*` | None | `./gradlew unitTest` |
| **Integration** | `com.ledger.integration.*` | Docker (Testcontainers) | `./gradlew integrationTest` |
| **E2E** | `com.ledger.e2e.*` | Full running stack | `./gradlew e2eTest` |
| **All (no E2E)** | unit + integration | Docker (Testcontainers) | `./gradlew test` |

---

## Unit tests (70%)

Fast, no infrastructure required. Use Mockito to isolate service logic.

```bash
cd ledger-api
./gradlew unitTest
```

Covers: service validation, exception handling, batch processing, reversal
logic, event routing, retry/DLQ, partition key generation, MDC filter, error
mapping, limit coercion, optimistic lock handling.

---

## Integration tests (20%) — Testcontainers

Integration tests use **Testcontainers by default**. No manual setup needed —
just Docker on the host. Testcontainers automatically starts Postgres 16,
MongoDB 7, and Kafka before the Spring context loads.

```bash
cd ledger-api
./gradlew integrationTest
```

**How it works:** `BaseIntegrationTest` registers `TestcontainersInitializer`,
which starts containers once (shared across all IT classes) and injects dynamic
connection URLs into the Spring context.

**What they test:** Full HTTP contract (status codes, JSON shape), database
persistence (balance accumulation, cursor pagination, idempotency with real
storage), cross-component flows (post → balance → reverse → balance).

**Requirements:**
- Docker daemon running on the host
- ~15-30 s container startup overhead (once per test suite)

**Note:** Kafka uses `confluentinc/cp-kafka:7.6.1` (Testcontainers-compatible).
The dev compose stack uses `apache/kafka:3.8.1` — functionally equivalent.

---

## E2E smoke tests (10%)

Run against a fully deployed ledger stack (API + all infrastructure).

```bash
# Start the full stack
docker compose -f docker/docker-compose.yml up -d --wait

# Run E2E tests
cd ledger-api
./gradlew e2eTest

# Or target a remote environment
E2E_BASE_URL=https://staging.example.com ./gradlew e2eTest
```

E2E tests use `RestTemplate` (no Spring context) and verify the critical path:
health check → create accounts → post transaction → verify balance → reverse →
close account.

---

## Running everything together

```bash
# Unit + integration (default, excludes e2e) — only needs Docker
./gradlew test

# Full pyramid including E2E
docker compose -f docker/docker-compose.yml up -d --wait
./gradlew test && ./gradlew e2eTest
```

---

## Compose files (for E2E and local dev)

| Aspect                | `docker-compose.yml` (dev)         | `docker-compose-test.yml` (lightweight) |
|-----------------------|------------------------------------|------------------------------------------|
| **Purpose**           | Full local environment + E2E       | Minimal infra (alternative to Testcontainers) |
| **Services**          | Postgres, Mongo, Kafka, API, Prometheus, Grafana | Postgres, Mongo, Kafka only |
| **Storage**           | Named volumes (persistent)         | tmpfs (ephemeral)                        |
| **Kafka partitions**  | 128                                | 4                                        |
| **Auto-create topics**| Disabled (uses kafka-init)         | Enabled                                  |
| **Startup time**      | ~45 s (builds API image)           | ~10 s                                    |

---

## Running specific tests

```bash
# Single test class
./gradlew test --tests "com.ledger.integration.AccountIT"

# Single test method
./gradlew test --tests "com.ledger.unit.service.TransactionServiceTest.should reject unbalanced transaction"

# Only unit tests (no Docker needed)
./gradlew unitTest
```

---

## CI pipeline example

```yaml
# Path to production: unit → integration → e2e
jobs:
  unit:
    steps:
      - run: ./gradlew unitTest                          # ~5s, no Docker

  integration:
    needs: unit
    steps:
      - run: ./gradlew integrationTest                   # ~30s, Testcontainers

  e2e:
    needs: integration
    steps:
      - run: docker compose -f docker/docker-compose.yml up -d --wait
      - run: ./gradlew e2eTest                           # ~10s
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Could not find a valid Docker environment` | Docker daemon not running, or socket not accessible. Required for integration tests. |
| Testcontainers startup slow | First run downloads images. Subsequent runs reuse cached images. Consider [Testcontainers Cloud](https://testcontainers.com/cloud/) for CI. |
| Port conflicts | Testcontainers uses random ports, so this shouldn't happen for integration tests. For E2E, check if 8080/5432/27017/19092 are free. |
| Tests pass locally but fail in CI | Ensure CI runners have Docker socket access for Testcontainers. |
| E2E tests fail with connection refused | The full stack must be running (`docker compose -f docker/docker-compose.yml up -d --wait`). Check `E2E_BASE_URL`. |
