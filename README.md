# Distributed Ledger Platform

A **high-throughput distributed ledger platform** with a fully implemented Kotlin/Spring Boot API,
comprehensive test suite, CI/CD pipelines, and production-ready infrastructure configs.

## System Architecture
- **NGINX Ingress** (edge routing)
- **Kotlin / Spring Boot 3.3.5** (transaction API + async workers)
- **PostgreSQL 16** (source of truth: balances, idempotency, outbox)
- **Kafka** (event backbone — at-least-once delivery)
- **MongoDB 7** (projection/read model)
- **Prometheus + Grafana** (observability)
- **k6** (performance/load validation)
- **Helm** (Kubernetes deployment model)
- **Docker Compose** (local environment)

## Implementation Status

The core application is **fully implemented and tested**:

| Component | Status |
|---|---|
| REST API (accounts, transactions, batch, idempotency, health) | Done |
| Domain models + PostgreSQL schema (Flyway) | Done |
| JDBC repositories + MongoDB projection | Done |
| Transactional outbox pattern + Kafka consumer | Done |
| Observability (Micrometer metrics, structured logging, MDC) | Done |
| Unit tests (~23, mockito-kotlin) | Passing |
| Integration tests (Testcontainers: Postgres, Mongo, Kafka) | Passing |
| E2E smoke tests | Passing |
| Dockerfile (multi-stage JDK 21) | Done |
| CI/CD workflows (GitHub Actions) | Done |
| k6 load tests (10 scenarios) | Done |
| Helm chart (HPA, PDB, ingress) | Done |

See `IMPLEMENTATION_GUIDE.md` for full details and `TESTING.md` for the testing guide.

## Documentation Index

### Architecture
- Capacity planning: `docs/architecture/capacity-planning.md`
- Index tuning: `docs/architecture/index-tuning.md`
- NGINX ingress guidance: `docs/architecture/nginx-ingress.md`
- CI/CD strategy: `docs/architecture/ci-cd-strategy.md`
- Repository separation plan: `docs/architecture/repo-separation-plan.md`

### ADRs
- `docs/adr/ADR-001-hybrid-write-model.md`
- `docs/adr/ADR-002-idempotency.md`
- `docs/adr/ADR-003-kafka-partitioning.md`
- `docs/adr/ADR-004-at-least-once.md`
- `docs/adr/ADR-005-testing-pyramid.md`

### Operations and Reliability
- Eventing model: `docs/eventing/eventing.md`
- Observability: `docs/observability/observability.md`
- Security hardening: `docs/security/security-hardening.md`
- Runbook (hot account contention): `docs/runbooks/hot-account-contention.md`
- Runbook (outbox lag): `docs/runbooks/outbox-lag.md`

### Testing
- Testing guide: `TESTING.md`
- Testcontainers approach: `docs/testing/testcontainers.md`
- k6 suite notes: `k6/README.md`

## Quick Start (Local)
1. `cd docker && docker compose up -d`
2. `cd ledger-api && ./gradlew bootRun`
3. API available at `http://localhost:8080`
4. Grafana at `http://localhost:3000` (admin/admin)

## Running Tests
```bash
cd ledger-api

# Unit tests only (no Docker required)
./gradlew unitTest

# Integration tests (requires Docker for Testcontainers)
./gradlew integrationTest

# All tests (unit + integration)
./gradlew test
```

## Load Test Example
```bash
BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/baseline.js
```

## CI/CD Strategy
- PR quality gates (lint, unit tests, integration tests, security scan).
- Auto-deploy validated `main` to **staging** via Helm.
- Promote immutable releases to **production** with manual approval on tag `v*`.

See `docs/architecture/ci-cd-strategy.md` for full details.

## Documentation Improvement Roadmap
1. Add a `docs/glossary.md` (domain terms, invariants, throughput terminology).
2. Add non-functional requirements with measurable targets (SLOs/SLIs, RPO/RTO).
3. Add a release management section (versioning policy, release checklist, rollback policy).
4. Add sequence diagrams for critical flows (write path, idempotency replay, outbox to projection).
5. Add an environment matrix (dev/staging/prod differences in scale, data retention, access controls).
6. Add risk register with mitigation owners for throughput bottlenecks and data consistency risks.

## Next Steps
- Split monorepo into dedicated repositories (see `docs/architecture/repo-separation-plan.md`).
- Set up container registry and Kubernetes staging environment.
- Configure secrets management (replace hardcoded credentials).
- Add OpenTelemetry distributed tracing.
- Add per-tenant rate limiting.
- Add production monitoring alerts.
