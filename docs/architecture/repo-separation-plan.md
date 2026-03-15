# Repository Separation Plan

## Current State

The `ledger-system-design` monorepo contains a **fully implemented** distributed ledger platform:

- **`ledger-api/`** — Spring Boot 3.3.5 / Kotlin application (controllers, services, repositories,
  eventing, observability), with Flyway migrations, Dockerfile, unit tests (23+), integration tests
  (Testcontainers), and E2E smoke tests. All tests pass.
- **`docker/`** — Docker Compose for full local dev stack (PostgreSQL 16, MongoDB 7, Kafka 3.8.1,
  Prometheus, Grafana) and a lightweight test stack.
- **`helm/`** — Kubernetes Helm chart with HPA, PDB, ingress, and environment-specific values.
- **`k6/`** — 10 load test scenarios (smoke, baseline, spike, hotspot, batch, idempotency,
  read_after_write, heavy_throughput, heavy_idempotency, endurance).
- **`docs/`** — 5 ADRs, OpenAPI spec, DB schema, capacity planning, eventing model, observability,
  security hardening, runbooks, CI/CD strategy, and testing strategy.
- **`.github/workflows/`** — 3 CI/CD pipelines (PR checks, staging deploy, production release).
- **`IMPLEMENTATION_GUIDE.md`**, **`TESTING.md`** — Developer guides.

## Why Separate?

| Concern | Monorepo Risk | Multi-Repo Benefit |
|---|---|---|
| **CI/CD lifecycle** | A docs change triggers app build + tests | Each repo has its own pipeline scope |
| **Team ownership** | No clear CODEOWNERS boundary | Platform team owns infra, dev team owns API |
| **Access control** | Single permission model | Fine-grained repo permissions |
| **Release cadence** | Coupled versioning | Independent semver per artifact |
| **Blast radius** | Helm tweak runs unit tests; a typo fix rebuilds Docker image | Changes scoped to their concern |
| **Scaling** | Adding a second service (e.g. settlement worker) bloats this repo | One repo per deployable unit |

## Proposed Repository Structure

### 1. `ledger-docs` (rename current repo — architecture & documentation hub)

**Purpose**: Architecture decisions, design documentation, API contract (source of truth),
operational runbooks, and utility scripts.

```
ledger-docs/
├── README.md
├── LICENSE
├── docs/
│   ├── adr/                    # ADR-001 through ADR-005
│   ├── api/openapi.yaml        # API contract (source of truth)
│   ├── architecture/           # Capacity, DB diagrams, index tuning, nginx, CI/CD, repo org
│   ├── eventing/               # Kafka schema, configs, eventing model
│   ├── observability/          # Metrics, logging, tracing specs
│   ├── runbooks/               # Operational runbooks
│   ├── security/               # Security hardening docs
│   └── testing/                # Test strategy docs
└── scripts/
    └── capacity_calc.py
```

**Rationale**: This is the "system of record" for architectural decisions. Product managers,
architects, and auditors reference this repo without needing access to source code or
infrastructure secrets.

---

### 2. `ledger-api` (new — application source code + tests)

**Purpose**: The Kotlin/Spring Boot application — the core deployable unit.

```
ledger-api/
├── README.md
├── IMPLEMENTATION_GUIDE.md
├── TESTING.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── Dockerfile
├── .gitignore
├── docker-compose-test.yml          # Lightweight test infra (pg, mongo, kafka)
├── .github/
│   └── workflows/
│       ├── pr-checks.yml            # Lint, unit-test, integration-test, security-scan
│       └── build-and-deploy-staging.yml
├── src/
│   ├── main/
│   │   ├── kotlin/com/ledger/
│   │   │   ├── LedgerApplication.kt
│   │   │   ├── config/              # KafkaConfig, MongoConfig
│   │   │   ├── domain/model/        # Account, Transaction, Entry, etc.
│   │   │   ├── api/
│   │   │   │   ├── controller/      # 4 REST controllers
│   │   │   │   ├── dto/             # Request/Response DTOs
│   │   │   │   └── exception/       # Exceptions + GlobalExceptionHandler
│   │   │   ├── repository/
│   │   │   │   ├── postgres/        # JDBC repositories (5)
│   │   │   │   └── mongo/           # Projection repository
│   │   │   ├── service/             # AccountService, TransactionService, IdempotencyService
│   │   │   ├── eventing/            # OutboxPublisher, ProjectionConsumer
│   │   │   └── observability/       # LedgerMetrics, TenantFilter
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/V1__init_schema.sql
│   └── test/
│       ├── kotlin/com/ledger/
│       │   ├── unit/                # ~23 unit tests (mockito, no Docker)
│       │   ├── integration/         # Testcontainers (Postgres, Mongo, Kafka)
│       │   └── e2e/                 # Smoke tests (RestTemplate)
│       └── resources/application-test.yml
└── docs/
    ├── openapi.yaml                 # Reference copy from ledger-docs
    └── dbdiagram.dbml               # Reference copy from ledger-docs
```

**Rationale**: The API is the core deployable artifact with its own build, test, container image,
and release lifecycle. Unit and integration tests live with the code they test. The lightweight
`docker-compose-test.yml` provides an alternative to Testcontainers for local development.

---

### 3. `ledger-infra` (new — infrastructure-as-code)

**Purpose**: Helm charts, Docker Compose (full dev stack), and observability configurations.

```
ledger-infra/
├── README.md
├── helm/
│   └── ledger/
│       ├── Chart.yaml
│       ├── values.yaml
│       ├── values-staging.yaml
│       ├── values-prod.yaml
│       └── templates/
│           ├── _helpers.tpl
│           ├── deployment.yaml
│           ├── hpa.yaml
│           ├── ingress.yaml
│           ├── pdb.yaml
│           └── service.yaml
├── docker/
│   ├── docker-compose.yml           # Full local stack (pg, kafka, mongo, api, prom, grafana)
│   ├── grafana/
│   │   ├── dashboards/
│   │   └── provisioning/
│   └── prometheus/
│       └── prometheus.yml
├── .github/
│   └── workflows/
│       ├── helm-lint.yml
│       └── release-prod.yml         # Tag v* → Helm deploy to prod (manual approval)
└── scripts/
    └── capacity_calc.py
```

**Rationale**: Infrastructure evolves on a different cadence. Platform/SRE teams manage Helm
values, scaling policies, and dashboards. Separating prevents accidental infra changes from
code PRs.

---

### 4. `ledger-load-tests` (new — performance validation)

**Purpose**: k6 load/performance test suite.

```
ledger-load-tests/
├── README.md
├── k6/
│   ├── common.js
│   ├── utils.js
│   ├── smoke.js
│   ├── baseline.js
│   ├── batch.js
│   ├── hotspot.js
│   ├── idempotency.js
│   ├── read_after_write.js
│   ├── spike.js
│   ├── heavy_throughput.js
│   ├── heavy_idempotency.js
│   └── endurance.js
├── .github/
│   └── workflows/
│       ├── smoke-test.yml           # On-demand against any environment
│       └── nightly-perf.yml         # Scheduled nightly against staging
└── baselines/                       # Historical performance results
```

**Rationale**: Load tests are environment-agnostic — they run against any target URL.
Scheduling nightly performance regressions independently prevents coupling to the API build.

---

## Dependency and Communication Model

```
┌─────────────────────────┐
│      ledger-docs        │  ◄── Source of truth: OpenAPI spec, ADRs, event schemas
│   (architecture docs)   │
└──────────┬──────────────┘
           │ openapi.yaml (contract)
           ▼
┌─────────────────────────┐      ┌─────────────────────────┐
│      ledger-api         │─────►│      ledger-infra        │
│   (Kotlin application)  │image │   (helm, docker, obs)    │
│   Produces: Docker image│tag   │   Consumes: image tag    │
└─────────────────────────┘      └─────────────────────────┘
           │                                │
           │ target URL                     │ deploys to env
           ▼                                ▼
┌─────────────────────────┐      ┌─────────────────────────┐
│   ledger-load-tests     │      │   Kubernetes Cluster     │
│   (k6 performance)      │─────►│   (staging / prod)       │
└─────────────────────────┘ runs └─────────────────────────┘
                           against
```

## Cross-Repo Contracts

| Contract | Owner Repo | Consumer Repo | Mechanism |
|---|---|---|---|
| OpenAPI spec | `ledger-docs` | `ledger-api` | Git submodule or CI fetch |
| Docker image tag | `ledger-api` | `ledger-infra` | Container registry + Helm values |
| Helm chart version | `ledger-infra` | CI/CD pipelines | Chart.yaml semver |
| Target URL | `ledger-infra` (env config) | `ledger-load-tests` | Environment variable |
| Event schema | `ledger-docs` | `ledger-api` | Schema registry or CI fetch |

## Migration Order

1. Create `ledger-api` first (the implemented application with all source code and tests)
2. Create `ledger-infra` second (Helm, Docker Compose, observability)
3. Create `ledger-load-tests` third (k6 suite)
4. Clean up `ledger-system-design` → becomes `ledger-docs` (remove migrated files, keep docs)

## Post-Migration Checklist

- [ ] Each repo has its own `README.md` with setup instructions
- [ ] Each repo has a `LICENSE` file
- [ ] Each repo has branch protection on `main`
- [ ] Each repo has `.github/workflows/` with at least a PR validation pipeline
- [ ] `ledger-docs` README updated to reference the new repos
- [ ] CODEOWNERS file added to each repo
- [ ] Container registry created for `ledger-api` images
- [ ] All tests pass in `ledger-api` repo independently
- [ ] Helm lint passes in `ledger-infra` repo independently
- [ ] k6 smoke test runs successfully from `ledger-load-tests` repo
