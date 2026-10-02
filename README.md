# Ledger System Design Documentation

This repository contains architecture, operations, and delivery documentation for a **high-throughput distributed ledger platform**.

## System Scope
The target platform is designed around:
- **NGINX Ingress** (edge routing)
- **Kotlin services** (transaction API + async workers)
- **PostgreSQL** (source of truth: balances, idempotency, outbox)
- **Kafka** (event backbone)
- **MongoDB** (projection/read model)
- **Prometheus + Grafana** (observability)
- **k6** (performance/load validation)
- **Helm** (Kubernetes deployment model)
- **Docker Compose** (local environment)

## Related Repositories
This repo holds the design. The system is implemented across three repos that
hand off via a published image tag (see `docs/architecture/ci-architecture.md`):

| Repo | Owns |
|------|------|
| `ledger-service` (cloned locally as `ledger-api`) | Kotlin / Spring Boot 3.3 / JDK 21 service: HTTP API, Kafka command ingestion, outbox, Mongo projection; publishes `ghcr.io/felipem554/ledger-service` |
| `ledger-infra` | Helm chart, staging/prod values, deploy workflows, Docker Compose stack, Grafana dashboards |
| `ledger-load-tests` | k6 HTTP suite (the `k6/` folder here mirrors it) |

`docs/api/openapi.yaml`, `docs/architecture/dbdiagram.dbml`, `docs/kafka/*`,
`docs/security/security-plan.md` and `docs/architecture/ci-architecture.md`
mirror the canonical copies in `ledger-service`. `docker/` and `helm/` mirror
`ledger-infra`. Change the source repo first, then re-sync here.

## Documentation Index

### Architecture
- Capacity planning: `docs/architecture/capacity-planning.md`
- Index tuning: `docs/architecture/index-tuning.md`
- NGINX ingress guidance: `docs/architecture/nginx-ingress.md`
- CI/CD strategy for client preview delivery: `docs/architecture/ci-cd-strategy.md`
- Multi-repo CI/deploy architecture: `docs/architecture/ci-architecture.md`
- Data model: `docs/architecture/dbdiagram.dbml`
- API contract: `docs/api/openapi.yaml`

### ADRs
- `docs/adr/ADR-001-hybrid-write-model.md`
- `docs/adr/ADR-002-idempotency.md`
- `docs/adr/ADR-003-kafka-partitioning.md`
- `docs/adr/ADR-004-at-least-once.md`

### Operations and Reliability
- Eventing model: `docs/eventing/eventing.md`
- Outbound eventing & projections: `docs/kafka/kafka.md`
- Inbound Kafka command ingestion: `docs/kafka/kafka-command-ingestion.md`
- Command ingestion production plan: `docs/kafka/kafka-command-ingestion-production-plan.md`
- Observability: `docs/observability/observability.md`
- Security hardening (target state): `docs/security/security-hardening.md`
- Security plan (current gaps + phased roadmap): `docs/security/security-plan.md`
- Runbook (hot account contention): `docs/runbooks/hot-account-contention.md`
- Runbook (outbox lag): `docs/runbooks/outbox-lag.md`

### Testing
- Testcontainers approach: `docs/testing/testcontainers.md`
- k6 suite notes: `k6/README.md`

## Quick Start (Local)
Runs the published `ledger-service` image with Postgres, Mongo, Kafka (command
topics created, ingestion enabled), Prometheus and Grafana:
```bash
cp docker/.env.example docker/.env   # optional; every variable has a dev default
docker compose -f docker/docker-compose.yml up -d
```
API on `:8080` (`/healthz`, `/actuator/prometheus`), Prometheus on `:9090`,
Grafana on `:3000` with the *Ledger — Overview* and *Ledger — JVM & HTTP*
dashboards pre-provisioned. To test a local build, set `LEDGER_SERVICE_IMAGE` in
`docker/.env`, or run `docker compose -f docker/docker-compose.yml up -d --build`
inside `ledger-service`.

## Kubernetes (Helm)
`helm/ledger` deploys the service with HPA, PDB, NetworkPolicy and a Secret for
`POSTGRES_PASSWORD`. It includes `values-staging.yaml` and `values-prod.yaml`.
Supply the password at deploy time and never commit it:
```bash
helm upgrade --install ledger helm/ledger -f helm/ledger/values-staging.yaml \
  --set image.tag=<sha> --set secretEnv.POSTGRES_PASSWORD=<value>
```

## Application Footprint
Measured on 2026-10-01 against the local Docker stack (`ledger-service` image built
locally, command ingestion enabled; 12-core / 15 GiB Linux host, no container limits).
CPU 100% = one core.

**Artifact**

| Item | Size |
|------|------|
| Spring Boot fat jar | 61 MiB |
| Container image (`eclipse-temurin:21-jre-jammy` base) | 649 MB on disk, 216 MB compressed |
| Startup | ~4.3 s to application ready; container healthy ~5 s after start (with dependencies already up) |

**Runtime: `ledger-service` container**

| State | CPU | Memory (RSS) | Heap used | Non-heap | Threads |
|-------|-----|--------------|-----------|----------|---------|
| Idle (after startup) | ~9% | ~306 MiB | ~44 MiB | ~103 MiB | 62 |
| k6 `baseline`, ~343 req/s for 2 min | avg 131%, peak 180% | avg 430 MiB, peak 453 MiB | ~72 MiB | ~148 MiB | peak 80 |

During that run: p95 latency 8.2 ms, 0% errors, 197 young-GC pauses (max 10 ms), no
full GCs. JVM flags: G1, `MaxGCPauseMillis=100`, `MaxRAMPercentage=75`. So under the
Helm `2Gi` memory limit the heap caps at about 1.5 GiB, and the measured working
set stays well inside the `512Mi` request.

**Supporting stack under the same load** (local single-node dev configs, not
production sizing)

| Container | CPU avg / peak | Memory avg / peak |
|-----------|----------------|-------------------|
| Kafka (`apache/kafka:3.8.1`) | 67% / 268% | 619 / 759 MiB |
| MongoDB 7 | 31% / 75% | 286 / 440 MiB |
| PostgreSQL 16 | 24% / 41% | 154 / 208 MiB |
| Grafana | <1% | 184 MiB |
| Prometheus | <1% | 110 MiB |

The full local stack uses about **1.8 GiB RAM on average and 2.1 GiB at peak** under this load. Re-measure with
`docker stats` while running a `k6/` scenario. Use the heavy scenarios for
capacity work.

## Load Testing
Two complementary suites:

- **Kafka command stream** (primary write-path test) — a JVM generator in
  `ledger-service` (`src/loadtest`) that produces `PostTransaction` commands, tallies
  outcomes from the results topic, and fails the run on SLA miss. Requires
  `COMMAND_INGEST_ENABLED=true` (already on in the full Docker stack):
  ```bash
  # in ledger-service
  RATE=200 DURATION_SECONDS=120 ACCOUNTS=500 ./gradlew kafkaLoadTest
  ```
- **k6 HTTP suite** — reads, idempotency, batch, and HTTP writes. Scenarios
  self-seed their accounts and run ≤ 2 minutes; includes heavy idempotency,
  heavy throughput, and endurance scenarios. See `k6/README.md`.
  ```bash
  BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/baseline.js
  ```

## Capacity Calculator Example
```bash
scripts/capacity_calc.py --tps 2000 --entries 4
```

## CI/CD Recommendation (Client-Ready Delivery)
To let clients continuously validate progress while development continues:
- Use PR quality gates (lint/test/security checks).
- Auto-deploy validated `main` to a stable **staging environment**.
- Promote immutable releases to production with manual approval.

See full implementation guidance in:
- `docs/architecture/ci-cd-strategy.md`

## Documentation Improvement Roadmap
To make this documentation more professional and client-ready before implementation starts:
1. Add a `docs/glossary.md` (domain terms, invariants, throughput terminology).
2. Add non-functional requirements with measurable targets (SLOs/SLIs, RPO/RTO, compliance constraints).
3. Add a release management section (versioning policy, release checklist, rollback policy).
4. Add sequence diagrams for critical flows (write path, idempotency replay, outbox to projection).
5. Add an environment matrix (dev/staging/prod differences in scale, data retention, and access controls).
6. Add risk register with mitigation owners for throughput bottlenecks and data consistency risks.

## Implementation Status
**Implemented in `ledger-service`:** double-entry posting, idempotency (ADR-002),
transactional outbox → Kafka → MongoDB projection (ADR-001/003/004), batch (≤ 500
items, each atomic), reversal, flag-gated Kafka command ingestion with results
and DLQ topics, Prometheus metrics and Grafana dashboards. CI publishes an image;
`ledger-infra` deploys it.

**Open gaps:**
- No authentication/authorization; tenant isolation relies on a trusted
  `X-Tenant-Id` header — top pre-production gap (`docs/security/security-plan.md`).
- Rate limiting not implemented.
- DLQ replay is a manual ops task.
- Tracing (OpenTelemetry) not wired; `ledger_outbox_lag_seconds` reports backlog
  size rather than age (`docs/observability/observability.md`).
- Cross-repo deploy/verify triggers not yet wired (`docs/architecture/ci-architecture.md`).
- Capacity validation against target throughput still to be run with the
  heavy k6 scenarios and the Kafka generator.
