# Testing Guide

## Testing Pyramid

This project follows the **Testing Pyramid** strategy:

```
         /  E2E   \          ~10%  — Smoke tests against running stack
        /----------\
       / Integration \       ~20%  — Testcontainers or Compose (real DB/Kafka)
      /----------------\
     /    Unit Tests     \   ~70%  — Pure logic, mocks, fast, no I/O
    /____________________\
```

| Layer | Location | Infra required | Gradle task |
|-------|----------|----------------|-------------|
| **Unit** | `com.ledger.unit.*` | None | `./gradlew unitTest` |
| **Integration** | `com.ledger.integration.*` | Postgres, Mongo, Kafka | `./gradlew integrationTest` |
| **E2E** | `com.ledger.e2e.*` | Full running stack | `./gradlew e2eTest` |
| **All (no E2E)** | unit + integration | Postgres, Mongo, Kafka | `./gradlew test` |

---

## Unit tests (70%)

Fast, no infrastructure required. Use Mockito to isolate service logic.

```bash
cd ledger-api
./gradlew unitTest
```

Covers: service validation, exception handling, event routing, retry logic,
partition key generation, MDC filter, error mapping.

---

## Integration tests (20%)

Require real Postgres, MongoDB, and Kafka. Two ways to provide them:

### Option A — Testcontainers (self-contained)

Spins up disposable containers automatically. Just needs Docker.

```bash
./gradlew integrationTest -Dspring.profiles.active=test,testcontainers
```

**How it works:** The `testcontainers` profile activates
`TestcontainersInitializer`, which starts Postgres, Mongo, and Kafka containers
before the Spring context loads and injects their dynamic connection URLs.

**When to use:**
- CI pipelines with Docker socket access
- First-time contributors — no prior setup needed
- When you want full isolation between test runs

**Trade-offs:**
- ~15-30 s container startup overhead per test suite
- Requires Docker socket (may not work in all CI environments)
- Kafka image is `confluentinc/cp-kafka:7.6.1` (compose uses `apache/kafka:3.8.1`)

### Option B — Docker Compose (fast iteration)

Start infrastructure once, run tests repeatedly.

```bash
# Start test services
docker compose -f docker/docker-compose-test.yml up -d --wait

# Run integration tests
./gradlew integrationTest

# Tear down
docker compose -f docker/docker-compose-test.yml down
```

**When to use:**
- Rapid local development — no startup penalty after first `docker compose up`
- CI environments where Docker-in-Docker is unavailable
- When you want to inspect database/broker state between runs

**Trade-offs:**
- Requires manual `docker compose up` step
- State persists across test runs (each IT uses unique tenant IDs to mitigate)
- Port conflicts if other services use 5432/27017/19092

---

## E2E smoke tests (10%)

Run against a fully running ledger stack (API + all infrastructure).

```bash
# Start the full stack
docker compose -f docker/docker-compose.yml up -d --wait

# Run E2E tests
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
# Unit + integration (default ./gradlew test, excludes e2e)
docker compose -f docker/docker-compose-test.yml up -d --wait
./gradlew test

# Unit + integration with Testcontainers (no compose needed)
./gradlew test -Dspring.profiles.active=test,testcontainers

# Full pyramid including E2E
docker compose -f docker/docker-compose.yml up -d --wait
./gradlew test && ./gradlew e2eTest
```

---

## Differences between compose files

| Aspect                | `docker-compose.yml` (dev)         | `docker-compose-test.yml` (test) |
|-----------------------|------------------------------------|----------------------------------|
| **Purpose**           | Full local environment             | Minimal infra for tests          |
| **Services**          | Postgres, Mongo, Kafka, API, Prometheus, Grafana | Postgres, Mongo, Kafka only |
| **Storage**           | Named volumes (persistent)         | tmpfs (ephemeral)                |
| **Kafka partitions**  | 128                                | 4                                |
| **Auto-create topics**| Disabled (uses kafka-init)         | Enabled                          |
| **Startup time**      | ~45 s (builds API image)           | ~10 s                            |

---

## Running specific tests

```bash
# Single test class
./gradlew test --tests "com.ledger.integration.AccountIT"

# Single test method
./gradlew test --tests "com.ledger.unit.service.TransactionServiceTest.should reject unbalanced transaction"

# All integration tests with Testcontainers
./gradlew integrationTest -Dspring.profiles.active=test,testcontainers
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
    services: [postgres, mongo, kafka]                    # or use testcontainers
    steps:
      - run: ./gradlew integrationTest                   # ~15s

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
| `Connection refused` on port 5432/27017/19092 | Compose services not running. Start them with `docker compose -f docker/docker-compose-test.yml up -d --wait` |
| `Could not find a valid Docker environment` | Docker daemon not running, or socket not accessible. Required for Testcontainers mode. |
| Port already in use | Stop conflicting services, or use Testcontainers mode (uses random ports). |
| Tests pass locally but fail in CI | Check if CI has Docker socket access. If not, start compose services in a prior CI step and skip the `testcontainers` profile. |
| E2E tests fail with connection refused | The full stack must be running (`docker compose -f docker/docker-compose.yml up -d --wait`). Check `E2E_BASE_URL`. |
