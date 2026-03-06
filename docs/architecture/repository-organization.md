# Repository Organization Strategy

## Current State — Monorepo

Everything lives in a single repository:

```
ledger-system-design/
├── ledger-api/          # Application code (Kotlin/Spring Boot)
├── docker/              # Docker Compose (dev + test)
├── helm/                # Kubernetes Helm chart
├── k6/                  # Load tests
├── docs/                # Architecture docs, ADRs, runbooks
├── scripts/             # Utility scripts
├── integration-tests/   # Testcontainers snippet (unused)
└── .github/workflows/   # CI/CD pipelines
```

### Problems with current structure

| Problem | Impact |
|---------|--------|
| **Mixed concerns** | Docs, infra, app code, and load tests share a single version history |
| **Blast radius** | A docs change triggers CI for the app; a Helm tweak runs unit tests |
| **Access control** | Everyone with repo access can modify infra, secrets config, and production Helm values |
| **Scaling** | Adding a second service (e.g., a settlement worker) would bloat this repo further |
| **Lifecycle mismatch** | Docs evolve on a different cadence than app code; Helm values change per environment |

---

## Proposed Structure — Multi-Repo

Split into **4 repositories**, each with a clear owner and lifecycle:

### 1. `ledger-api` — Application Code

The service itself. One repo per deployable unit.

```
ledger-api/
├── src/
│   ├── main/kotlin/com/ledger/
│   └── test/kotlin/com/ledger/
│       ├── unit/           # 70% — Mockito, no infra
│       ├── integration/    # 20% — Testcontainers
│       └── e2e/            # 10% — Smoke tests (RestTemplate)
├── build.gradle.kts
├── Dockerfile
├── docker-compose-test.yml  # Lightweight infra for local dev
├── TESTING.md
├── CHANGELOG.md
└── .github/workflows/
    └── ci.yml              # Build → unit → integration → image push
```

**Owns:** application code, unit tests, integration tests, Dockerfile, CI.
**Does NOT own:** Helm charts, k6 load tests, architecture documentation.

**Why separate?** The service has its own release lifecycle. Its CI should only
trigger on code changes. When you add a second service (`ledger-worker`,
`ledger-gateway`), each gets its own repo following the same pattern.

### 2. `ledger-platform` — Infrastructure & Deployment

Helm charts, Terraform/IaC, environment configuration.

```
ledger-platform/
├── helm/
│   ├── ledger-api/
│   │   ├── Chart.yaml
│   │   ├── values.yaml           # Defaults
│   │   ├── values-staging.yaml   # Staging overrides
│   │   └── values-prod.yaml      # Production overrides
│   ├── kafka/                    # If self-managing Kafka
│   └── monitoring/               # Prometheus + Grafana stack
├── terraform/                    # Cloud resources (RDS, DocumentDB, MSK, etc.)
│   ├── modules/
│   ├── environments/
│   │   ├── staging/
│   │   └── prod/
│   └── backend.tf
├── docker/
│   └── docker-compose.yml        # Full local dev stack
├── scripts/
│   └── capacity_calc.py
└── .github/workflows/
    ├── deploy-staging.yml        # Helm deploy on merge to main
    └── deploy-prod.yml           # Helm deploy on tag push
```

**Owns:** Helm charts, Terraform, environment config, deploy pipelines.
**Why separate?** Infrastructure changes have different review requirements
(SRE/platform team), different blast radius, and should not require rebuilding
the application. Environment-specific secrets live here (via sealed-secrets or
external-secrets-operator).

### 3. `ledger-docs` — Architecture Documentation

Design docs, ADRs, API specs, runbooks — everything non-executable.

```
ledger-docs/
├── README.md               # System overview and doc index
├── adr/
│   ├── ADR-001-hybrid-write-model.md
│   ├── ADR-002-idempotency.md
│   ├── ADR-003-kafka-partitioning.md
│   ├── ADR-004-at-least-once.md
│   └── ADR-005-testing-pyramid.md
├── api/
│   └── openapi.yaml
├── architecture/
│   ├── system-context.md     # C4 Level 1
│   ├── container-diagram.md  # C4 Level 2
│   ├── capacity-planning.md
│   ├── dbdiagram.dbml
│   ├── index-tuning.md
│   └── nginx-ingress.md
├── eventing/
│   ├── eventing.md
│   └── event-schema.v1.json
├── observability/
│   └── observability.md
├── security/
│   └── security-hardening.md
├── runbooks/
│   ├── hot-account-contention.md
│   └── outbox-lag.md
├── testing/
│   └── testing-strategy.md
└── glossary.md
```

**Owns:** all design documentation, ADRs, API specs, runbooks.
**Why separate?** Documentation evolves at a different pace than code. Product
managers, architects, and auditors need read access without seeing
implementation details. Version history stays clean — a typo fix in a runbook
doesn't pollute the app's git log.

### 4. `ledger-loadtest` — Performance Validation

k6 scripts, performance baselines, benchmark results.

```
ledger-loadtest/
├── README.md
├── scripts/
│   ├── common.js
│   ├── utils.js
│   ├── smoke.js
│   ├── baseline.js
│   ├── batch.js
│   ├── idempotency.js
│   ├── heavy_throughput.js
│   ├── heavy_idempotency.js
│   ├── endurance.js
│   ├── spike.js
│   ├── hotspot.js
│   └── read_after_write.js
├── baselines/
│   ├── 2024-Q1-baseline.json
│   └── 2024-Q2-baseline.json
├── dashboards/
│   └── k6-grafana.json
└── .github/workflows/
    └── nightly-perf.yml    # Scheduled load test against staging
```

**Owns:** load test scripts, baseline comparisons, perf dashboards.
**Why separate?** Load tests run on a different schedule (nightly, pre-release)
and target a deployed environment. They need their own CI triggers and don't
need to rebuild the application.

---

## Migration Path

This is not a big-bang migration. Do it incrementally:

| Phase | Action | Effort |
|-------|--------|--------|
| **1** | Create `ledger-docs` repo, move `docs/` there. Add cross-links. | 1 day |
| **2** | Create `ledger-platform` repo, move `helm/`, `docker/docker-compose.yml`, deploy workflows. | 1 day |
| **3** | Create `ledger-loadtest` repo, move `k6/`. | 0.5 day |
| **4** | Rename `ledger-system-design` → `ledger-api`. Clean up root. Move `docker-compose-test.yml` into app repo. | 0.5 day |
| **5** | Update CI cross-references. Staging deploy triggers from `ledger-api` image push. | 1 day |

**Total: ~4 days of focused work.**

---

## What Stays Together vs. What Separates

### Keep together (same repo)
- Application code + its unit/integration tests + Dockerfile + test compose
- This is the **deployable unit boundary**

### Separate
- **Docs from code** — different audience, different cadence
- **Infra from app** — different blast radius, different reviewers
- **Load tests from app** — different trigger, different target environment

### Rule of thumb
> If two things change for different reasons, at different times, by different
> people — they belong in different repositories.

---

## Additional Improvements

### What's missing today

| Area | Gap | Recommendation |
|------|-----|----------------|
| **OpenAPI contract testing** | API spec in docs but not validated against code | Add `springdoc-openapi` to generate spec from controllers, diff against `openapi.yaml` in CI |
| **Database migration testing** | Flyway runs but migrations aren't tested in isolation | Add a `migrationTest` Gradle task that runs Flyway on a fresh Testcontainers Postgres |
| **Contract versioning** | No consumer-driven contracts | Consider Pact for downstream consumers of Kafka events |
| **Secrets management** | Helm values contain plaintext passwords | Use `external-secrets-operator` or `sealed-secrets` |
| **Dependency updates** | No automated dependency management | Add Renovate or Dependabot config |
| **Code coverage** | No coverage gates | Add JaCoCo with minimum 80% line coverage on unit tests |
| **Structured logging** | Exists but no log shipping config | Add Fluent Bit / Loki config to `ledger-platform` |
| **API versioning** | All endpoints are `/v1/` but no strategy for `/v2/` | Document versioning policy in ADR (URL path vs. header) |
| **Feature flags** | None | Not needed yet, but plan for it before multi-tenant rollout |
| **Changelog** | No CHANGELOG.md | Adopt conventional commits + auto-generated changelog |

### Maturity milestones

```
Level 1 (Now):  Monorepo, manual deploy, basic CI
                ✓ Testing pyramid
                ✓ Testcontainers
                ✓ ADRs
                ✓ Helm chart

Level 2 (Next): Multi-repo, automated staging, contract tests
                □ Split repos (docs, platform, loadtest, api)
                □ OpenAPI contract validation in CI
                □ JaCoCo coverage gates
                □ Dependabot / Renovate
                □ Sealed secrets

Level 3 (Later): Production-grade, multi-service
                □ Consumer-driven contracts (Pact)
                □ Canary deployments
                □ Feature flags
                □ SLO dashboards with burn-rate alerts
                □ Chaos engineering (kill pod, network partition)
```

---

## Decision Matrix: Mono vs. Multi-Repo

| Factor | Monorepo | Multi-Repo (proposed) |
|--------|----------|----------------------|
| **Simplicity** | Easier to start | Slightly more setup |
| **CI speed** | Rebuilds everything | Only rebuilds what changed |
| **Access control** | All-or-nothing | Fine-grained per repo |
| **Dependency management** | Implicit (same repo) | Explicit (versioned artifacts) |
| **Team scaling** | Bottleneck at ~5 devs | Scales to multiple teams |
| **Audit trail** | Mixed history | Clean per-concern history |

**Recommendation:** Move to multi-repo when you have more than one deployable
service or more than one team. For a single team with a single service, the
monorepo is fine — but structure it *as if* it will split, so the migration is
trivial.
