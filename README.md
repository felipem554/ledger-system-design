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

## Documentation Index

### Architecture
- Capacity planning: `docs/architecture/capacity-planning.md`
- Index tuning: `docs/architecture/index-tuning.md`
- NGINX ingress guidance: `docs/architecture/nginx-ingress.md`
- CI/CD strategy for client preview delivery: `docs/architecture/ci-cd-strategy.md`

### ADRs
- `docs/adr/ADR-001-hybrid-write-model.md`
- `docs/adr/ADR-002-idempotency.md`
- `docs/adr/ADR-003-kafka-partitioning.md`
- `docs/adr/ADR-004-at-least-once.md`
- `docs/adr/ADR-005-testing-pyramid.md`
- `docs/adr/ADR-006-api-worker-split.md`

### Operations and Reliability
- Eventing model: `docs/eventing/eventing.md`
- Observability: `docs/observability/observability.md`
- Security hardening: `docs/security/security-hardening.md`
- Runbook (hot account contention): `docs/runbooks/hot-account-contention.md`
- Runbook (outbox lag): `docs/runbooks/outbox-lag.md`

### Project Organization
- Repository organization strategy: `docs/architecture/repository-organization.md`

### Testing
- Testing strategy (pyramid): `docs/testing/testing-strategy.md`
- Testing guide: `TESTING.md`
- Testcontainers approach: `docs/testing/testcontainers.md`
- k6 suite notes: `k6/README.md`

## Quick Start (Local)
1. `cd docker`
2. `docker compose up -d`
3. NGINX load balances traffic on `:8080` across 2 API instances
4. 2 worker instances process the outbox and Kafka projections
5. Import Grafana dashboards from `docker/grafana/dashboards`

### Process Modes
The same Docker image runs as either API or worker, controlled by `LEDGER_MODE`:
| Mode | Description |
|------|-------------|
| `api` | HTTP endpoints only (no Kafka consumer/publisher) |
| `worker` | Outbox publisher + projection consumer (no HTTP routing) |
| `all` | Everything (default, for standalone development) |

## Load Test Example
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

## Expected Next Phase
After design approval, start implementation with:
- A minimal vertical slice (POST transaction + projection read).
- End-to-end CI pipeline and staging deployment.
- Performance baseline and capacity validation against target throughput.
