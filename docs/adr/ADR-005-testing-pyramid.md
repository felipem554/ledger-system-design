# ADR-005: Testing Pyramid with Testcontainers and E2E Smoke Tests

## Status
Accepted

## Context
The project initially had an inverted testing distribution — 57% integration
tests (29 tests) vs 43% unit tests (22 tests) and 0% E2E tests. Integration
tests were the primary verification mechanism, which created several problems:

- **Slow feedback loop**: every test run required database and Kafka connectivity
- **No isolation between test layers**: unit-level logic was only validated
  through the full HTTP stack
- **No production readiness gate**: there was no smoke test verifying the
  deployed system worked end-to-end
- **Infrastructure coupling**: tests initially required manual
  `docker compose up` before running, creating a barrier for new contributors

We needed to align with the industry-standard **Testing Pyramid** pattern to
support a professional path to production.

## Decision
Restructure tests into three layers following a 70/20/10 distribution:

### Unit Tests (~70%) — `com.ledger.unit.*`
- Pure logic tests with Mockito mocks, no I/O
- Cover: service validation, exception handling, batch processing, reversal
  direction swapping, event routing, retry/DLQ logic, partition key generation,
  MDC filter lifecycle, HTTP error code mappings, limit coercion, optimistic
  lock conflict handling
- Run with `./gradlew unitTest` — no Docker required

### Integration Tests (~20%) — `com.ledger.integration.*`
- Use **Testcontainers** as the default infrastructure provider
- `TestcontainersInitializer` starts Postgres 16, MongoDB 7, and Kafka
  (cp-kafka 7.6.1) automatically before the Spring context loads
- Containers are shared across all IT classes via lazy companion objects
- No profile flag required — Testcontainers is always-on
- Cover: full HTTP contract, database persistence, balance accumulation,
  cursor pagination, idempotency with real storage, cross-component flows
- Run with `./gradlew integrationTest` — only Docker required

### E2E Smoke Tests (~10%) — `com.ledger.e2e.*`
- Run against a fully deployed stack (docker compose or remote environment)
- Use `RestTemplate` (no Spring context)
- Tagged with `@Tag("e2e")`, excluded from default `test` task
- Verify the critical user journey: health → create accounts → post
  transaction → verify balance → reverse → close account
- Run with `./gradlew e2eTest`

### Gradle task structure
| Task | Scope | Infra |
|------|-------|-------|
| `unitTest` | Unit only | None |
| `integrationTest` | Integration only | Docker (Testcontainers) |
| `test` | Unit + integration | Docker (Testcontainers) |
| `e2eTest` | E2E smoke only | Full running stack |

### CI pipeline alignment
```
PR Quality Gate:   unitTest → integrationTest → security scan
Merge to main:     test → build image → deploy staging → e2eTest (smoke)
Release to prod:   deploy → e2eTest (smoke) → health verify
```

## Consequences

### Positive
- **Fast feedback**: unit tests run in ~5s with no Docker dependency
- **Self-contained**: `./gradlew test` works out of the box with just Docker
- **No manual setup**: removed the need for `docker compose up` before testing
- **Clear ownership**: each test layer has a single responsibility
- **CI-friendly**: Testcontainers works natively on GitHub Actions runners
- **Production gate**: E2E smoke tests verify the deployed system before
  traffic is shifted

### Negative
- **Testcontainers startup**: ~15-30s overhead per test suite (once)
- **Docker dependency**: integration tests require Docker daemon access
- **Image mismatch**: Testcontainers uses `confluentinc/cp-kafka:7.6.1` while
  production uses `apache/kafka:3.8.1` (functionally equivalent, but worth
  noting)
- **Some test overlap**: integration tests cover scenarios also validated by
  unit tests (e.g., unbalanced transaction rejection) — this is intentional,
  as the integration layer validates HTTP status codes and error response shape

## References
- Mike Cohn, *Succeeding with Agile* (Testing Pyramid)
- Google Testing Blog, *Test Sizes* (small/medium/large)
- Testcontainers documentation: https://testcontainers.com
