# Repository Separation Plan

## Current State

The `ledger-system-design` monorepo contains **all** project artifacts in a single repository:
architecture docs, ADRs, API specs, Helm charts, Docker Compose, Grafana/Prometheus configs,
k6 load tests, integration test snippets, and utility scripts. There is no application source
code yet — the project is in the design phase.

## Why Separate?

| Concern | Monorepo Risk | Multi-Repo Benefit |
|---|---|---|
| **CI/CD lifecycle** | A docs change triggers infra pipelines | Each repo has its own pipeline scope |
| **Team ownership** | No clear CODEOWNERS boundary | Platform team owns infra, dev team owns API |
| **Access control** | Single permission model | Fine-grained repo permissions |
| **Release cadence** | Coupled versioning | Independent semver per artifact |
| **Dependency direction** | Implicit coupling | Explicit contracts (OpenAPI, Helm chart versions) |

## Proposed Repository Structure

### 1. `ledger-system-design` (this repo — keep as documentation hub)

**Purpose**: Architecture decisions, design documentation, and system-level specs.

```
ledger-system-design/
├── README.md
├── LICENSE
├── docs/
│   ├── adr/                    # Architecture Decision Records
│   ├── api/openapi.yaml        # API contract (source of truth)
│   ├── architecture/           # Capacity, DB diagrams, index tuning, nginx, CI/CD strategy
│   ├── eventing/               # Kafka schema, configs, eventing model
│   ├── observability/          # Metrics, logging, tracing specs
│   ├── runbooks/               # Operational runbooks
│   ├── security/               # Security hardening docs
│   └── testing/                # Test strategy docs
└── scripts/
    └── capacity_calc.py        # Utility scripts
```

**Rationale**: This is the "system of record" for architectural decisions. It evolves
independently of code and infra. Product managers, architects, and auditors reference this
repo without needing access to source code or infrastructure secrets.

---

### 2. `ledger-api` (new — application source code)

**Purpose**: Kotlin application code for the Transaction API and async workers.

```
ledger-api/
├── README.md
├── build.gradle.kts            # (or pom.xml)
├── Dockerfile
├── .github/
│   └── workflows/
│       ├── pr-checks.yml       # Lint, test, integration-test, security-scan
│       └── build-and-deploy-staging.yml
├── src/
│   ├── main/kotlin/...         # Application code
│   └── test/kotlin/...         # Unit + integration tests
├── db/
│   └── migration/              # PostgreSQL migrations (Flyway/Liquibase)
├── docs/
│   └── openapi.yaml            # Copied or fetched from ledger-system-design
└── docker-compose.yml          # Minimal local dev dependencies (pg, kafka, mongo)
```

**Rationale**: The API is the core deployable artifact. It has its own build, test, container
image, and release lifecycle. Developers work here daily. The CI pipeline builds the Docker
image, runs tests, and deploys to staging.

---

### 3. `ledger-infra` (new — infrastructure-as-code)

**Purpose**: Kubernetes manifests, Helm charts, Docker Compose (full stack), and
observability configurations.

```
ledger-infra/
├── README.md
├── helm/
│   └── ledger/
│       ├── Chart.yaml
│       ├── values.yaml             # Base values
│       ├── values-staging.yaml     # Staging overrides
│       ├── values-prod.yaml        # Production overrides
│       └── templates/
│           ├── _helpers.tpl
│           ├── deployment.yaml
│           ├── hpa.yaml
│           ├── ingress.yaml
│           ├── pdb.yaml
│           └── service.yaml
├── docker/
│   ├── docker-compose.yml          # Full local stack (pg, kafka, mongo, prom, grafana)
│   ├── grafana/
│   │   ├── dashboards/
│   │   └── provisioning/
│   └── prometheus/
│       └── prometheus.yml
├── .github/
│   └── workflows/
│       ├── helm-lint.yml           # Validate Helm chart on PR
│       └── release-prod.yml        # Production deployment (manual approval)
└── docs/
    └── environments.md             # Environment matrix and access
```

**Rationale**: Infrastructure evolves on a different cadence than application code. Platform/SRE
teams manage Helm values, scaling policies, and observability dashboards. Separating infra
prevents accidental infra changes from code PRs and enables strict approval gates for
production deployments.

---

### 4. `ledger-load-tests` (new — performance validation)

**Purpose**: k6 load/performance test suite and integration test harnesses.

```
ledger-load-tests/
├── README.md
├── k6/
│   ├── common.js
│   ├── utils.js
│   ├── baseline.js
│   ├── batch.js
│   ├── hotspot.js
│   ├── idempotency.js
│   ├── read_after_write.js
│   ├── smoke.js
│   └── spike.js
├── integration-tests/
│   └── testcontainers-kotlin-snippet.kt
├── .github/
│   └── workflows/
│       ├── smoke-test.yml          # Triggered after staging deploy (webhook/dispatch)
│       └── nightly-perf.yml        # Scheduled nightly performance run
└── docs/
    └── test-strategy.md
```

**Rationale**: Load tests are environment-agnostic — they run against any target URL.
Keeping them in a separate repo allows:
- Running them against staging, production, or local without coupling to the API build.
- Scheduling nightly performance regression runs independently.
- Sharing test results across teams without exposing application source code.

---

## Dependency and Communication Model

```
┌─────────────────────────┐
│   ledger-system-design  │  ◄── Source of truth for API contract (openapi.yaml)
│   (architecture docs)   │      and architectural decisions
└──────────┬──────────────┘
           │ openapi.yaml (contract)
           ▼
┌─────────────────────────┐      ┌─────────────────────────┐
│      ledger-api         │─────►│      ledger-infra        │
│   (application code)    │image │   (helm, docker, obs)    │
│   Produces: Docker image│tag   │   Consumes: image tag    │
└─────────────────────────┘      └─────────────────────────┘
           │                                │
           │ target URL                     │ deploys to env
           ▼                                ▼
┌─────────────────────────┐      ┌─────────────────────────┐
│   ledger-load-tests     │      │   Kubernetes Cluster     │
│   (k6, integration)     │─────►│   (staging / prod)       │
└─────────────────────────┘ runs └─────────────────────────┘
                           against
```

## Cross-Repo Contracts

| Contract | Owner Repo | Consumer Repo | Mechanism |
|---|---|---|---|
| OpenAPI spec | `ledger-system-design` | `ledger-api` | Git submodule or CI fetch |
| Docker image tag | `ledger-api` | `ledger-infra` | Container registry + Helm values |
| Helm chart version | `ledger-infra` | CI/CD pipelines | Chart.yaml semver |
| Target URL | `ledger-infra` (env config) | `ledger-load-tests` | Environment variable |
| Event schema | `ledger-system-design` | `ledger-api` | Schema registry or CI fetch |

## Migration Order

1. Create `ledger-infra` first (no dependencies on other new repos)
2. Create `ledger-load-tests` second (no dependencies on other new repos)
3. Create `ledger-api` third (scaffold for future implementation)
4. Clean up `ledger-system-design` (remove migrated files, update README)

## Post-Migration Checklist

- [ ] Each repo has its own `README.md` with setup instructions
- [ ] Each repo has a `LICENSE` file
- [ ] Each repo has branch protection on `main`
- [ ] Each repo has a `.github/workflows/` directory with at least a PR validation pipeline
- [ ] `ledger-system-design` README updated to reference the new repos
- [ ] CODEOWNERS file added to each repo
- [ ] Container registry created for `ledger-api` images
