# Testing Guide

Integration tests require PostgreSQL 16, MongoDB 7, and Apache Kafka. You can
provide these dependencies in two ways: **Testcontainers** (zero setup, requires
Docker) or **Docker Compose** (faster iteration, services stay running).

---

## Option 1 — Testcontainers (self-contained)

Testcontainers spins up disposable containers automatically when the tests run.
No manual setup needed — just Docker on the host.

```bash
cd ledger-api
./gradlew test -Dspring.profiles.active=test,testcontainers
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

---

## Option 2 — Docker Compose (fast iteration)

Start the infrastructure once and run tests against it repeatedly.

### Start the test services

```bash
docker compose -f docker/docker-compose-test.yml up -d --wait
```

This starts lightweight versions of Postgres, Mongo, and Kafka using `tmpfs`
volumes (data is discarded on container stop).

### Run the tests

```bash
cd ledger-api
./gradlew test
```

Without the `testcontainers` profile, tests connect to `localhost` using the
defaults in `application-test.yml` (ports 5432, 27017, 19092).

### Tear down

```bash
docker compose -f docker/docker-compose-test.yml down
```

**When to use:**
- Rapid local development — run tests in < 5 s with no startup penalty
- CI environments where Docker-in-Docker is unavailable but compose services
  can be started as a separate step
- When you want to inspect database/broker state between test runs

**Trade-offs:**
- Requires a manual `docker compose up` step (or a CI job step)
- State persists across test runs (mitigate with `@Transactional` or cleanup)
- Port conflicts if other services use 5432/27017/19092

---

## Option 3 — Full development stack

You can also run the full dev compose file, which includes the API, Prometheus,
and Grafana alongside the infrastructure:

```bash
docker compose -f docker/docker-compose.yml up -d --wait
```

Then run tests the same way as Option 2. The ports and credentials are
identical.

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

## Unit tests

Unit tests use Mockito and have no infrastructure dependencies:

```bash
cd ledger-api
./gradlew test --tests "com.ledger.unit.*"
```

---

## Running specific integration tests

```bash
# Single test class
./gradlew test --tests "com.ledger.integration.AccountIT"

# Single test method
./gradlew test --tests "com.ledger.integration.AccountIT.should create account"

# All integration tests with Testcontainers
./gradlew test --tests "com.ledger.integration.*" -Dspring.profiles.active=test,testcontainers
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Connection refused` on port 5432/27017/19092 | Compose services not running. Start them with `docker compose -f docker/docker-compose-test.yml up -d --wait` |
| `Could not find a valid Docker environment` | Docker daemon not running, or socket not accessible. Required for Testcontainers mode. |
| Port already in use | Stop conflicting services, or use Testcontainers mode (uses random ports). |
| Tests pass locally but fail in CI | Check if CI has Docker socket access. If not, start compose services in a prior CI step and skip the `testcontainers` profile. |
