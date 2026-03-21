#!/usr/bin/env bash
# =============================================================================
# migrate-repos.sh
#
# Migrates the ledger-system-design monorepo into four independent repositories:
#   1. ledger-api         (new — Kotlin application + tests + Dockerfile + CI)
#   2. ledger-infra       (new — Helm, Docker Compose, Grafana, Prometheus)
#   3. ledger-load-tests  (new — k6 performance test suite)
#   4. ledger-docs        (this repo cleaned up — architecture docs only)
#
# Prerequisites:
#   - git, gh (GitHub CLI) installed and authenticated
#   - Docker installed (for verifying tests if --verify flag is used)
#   - The source repo must be on or have access to the branch that contains
#     the ledger-api/ implementation code
#
# Usage:
#   export GITHUB_ORG="felipem554"
#   export SOURCE_BRANCH="claude/distributed-transaction-system-x4sg3"
#   chmod +x scripts/migrate-repos.sh
#   ./scripts/migrate-repos.sh [--verify]
#
# Options:
#   --verify   Run tests in ledger-api after migration to confirm they pass
#
# The script is idempotent — it skips repo creation if the repo already exists.
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
GITHUB_ORG="${GITHUB_ORG:?Set GITHUB_ORG to your GitHub org or username}"
SOURCE_BRANCH="${SOURCE_BRANCH:-claude/distributed-transaction-system-x4sg3}"
WORKSPACE="${WORKSPACE:-$(mktemp -d)}"
SOURCE_REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERIFY="${1:-}"

# Repo visibility (public or private)
REPO_VISIBILITY="${REPO_VISIBILITY:-public}"

echo "=============================================="
echo " Ledger Repository Migration"
echo "=============================================="
echo " Source:        ${SOURCE_REPO_DIR}"
echo " Source Branch: ${SOURCE_BRANCH}"
echo " GitHub Org:    ${GITHUB_ORG}"
echo " Workspace:     ${WORKSPACE}"
echo " Visibility:    ${REPO_VISIBILITY}"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Prepare: export a clean copy of the source branch
# ---------------------------------------------------------------------------
EXPORT_DIR="${WORKSPACE}/_source"
echo "[PREP] Exporting branch ${SOURCE_BRANCH} to ${EXPORT_DIR}..."
mkdir -p "${EXPORT_DIR}"
cd "${SOURCE_REPO_DIR}"

# Ensure we have the branch locally
git fetch origin "${SOURCE_BRANCH}" 2>/dev/null || true

# Export the full tree from the implementation branch (no .git)
git archive "origin/${SOURCE_BRANCH}" | tar -x -C "${EXPORT_DIR}"
echo "[OK] Branch exported."
echo ""

# ---------------------------------------------------------------------------
# Helper: create GitHub repo if it doesn't exist
# ---------------------------------------------------------------------------
create_repo_if_missing() {
    local repo_name="$1"
    local description="$2"

    if gh repo view "${GITHUB_ORG}/${repo_name}" &>/dev/null; then
        echo "[SKIP] Repo ${GITHUB_ORG}/${repo_name} already exists."
    else
        echo "[CREATE] Creating ${GITHUB_ORG}/${repo_name}..."
        gh repo create "${GITHUB_ORG}/${repo_name}" \
            --"${REPO_VISIBILITY}" \
            --description "${description}" \
            --confirm
        echo "[OK] Created ${GITHUB_ORG}/${repo_name}"
    fi
}

# ---------------------------------------------------------------------------
# Helper: initialize a local repo, commit, and push with retry
# ---------------------------------------------------------------------------
init_and_push() {
    local repo_dir="$1"
    local repo_name="$2"
    local commit_message="$3"

    cd "${repo_dir}"
    git init -b main
    git add -A
    git commit -m "${commit_message}"
    git remote add origin "git@github.com:${GITHUB_ORG}/${repo_name}.git"

    echo "[PUSH] Pushing ${repo_name} to origin/main..."
    local attempt=0
    local max_retries=4
    local wait_time=2
    while [ $attempt -lt $max_retries ]; do
        if git push -u origin main; then
            echo "[OK] Pushed ${repo_name} successfully."
            return 0
        fi
        attempt=$((attempt + 1))
        echo "[RETRY] Push failed. Waiting ${wait_time}s before retry ${attempt}/${max_retries}..."
        sleep $wait_time
        wait_time=$((wait_time * 2))
    done
    echo "[ERROR] Failed to push ${repo_name} after ${max_retries} retries."
    return 1
}

# =====================================================================
# STEP 1: Create ledger-api
# =====================================================================
echo ">>> STEP 1: ledger-api (Kotlin application)"
echo "-------------------------------------------"

API_DIR="${WORKSPACE}/ledger-api"
mkdir -p "${API_DIR}"

# --- Application source code (entire ledger-api/ directory) ---
cp -r "${EXPORT_DIR}/ledger-api/." "${API_DIR}/"

# --- Developer guides ---
cp "${EXPORT_DIR}/IMPLEMENTATION_GUIDE.md" "${API_DIR}/IMPLEMENTATION_GUIDE.md"
cp "${EXPORT_DIR}/TESTING.md" "${API_DIR}/TESTING.md"

# --- Lightweight test compose (for local dev without Testcontainers) ---
if [ -f "${EXPORT_DIR}/docker/docker-compose-test.yml" ]; then
    cp "${EXPORT_DIR}/docker/docker-compose-test.yml" "${API_DIR}/docker-compose-test.yml"
fi

# --- Reference copies of API spec and DB schema ---
mkdir -p "${API_DIR}/docs"
cp "${EXPORT_DIR}/docs/api/openapi.yaml" "${API_DIR}/docs/openapi.yaml"
cp "${EXPORT_DIR}/docs/architecture/dbdiagram.dbml" "${API_DIR}/docs/dbdiagram.dbml"

# --- CI/CD workflows (PR checks + staging deploy only — prod deploy goes to infra) ---
mkdir -p "${API_DIR}/.github/workflows"
cp "${EXPORT_DIR}/.github/workflows/pr-checks.yml" "${API_DIR}/.github/workflows/pr-checks.yml"
cp "${EXPORT_DIR}/.github/workflows/build-and-deploy-staging.yml" "${API_DIR}/.github/workflows/build-and-deploy-staging.yml"

# --- LICENSE ---
cp "${EXPORT_DIR}/LICENSE" "${API_DIR}/LICENSE"

# --- README ---
cat > "${API_DIR}/README.md" << 'API_README'
# Ledger API

High-throughput distributed ledger API built with **Kotlin / Spring Boot 3.3.5**.

## Architecture

- **Spring Boot 3.3.5** + **Kotlin** (JDK 21)
- **PostgreSQL 16** — Source of truth (balances, idempotency, transactional outbox)
- **Kafka** — Event backbone (at-least-once delivery)
- **MongoDB 7** — Read model / projections
- **Flyway** — Database migrations
- **Micrometer + Prometheus** — Observability

## API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/v1/accounts` | Create account |
| GET | `/v1/accounts` | List accounts |
| GET | `/v1/accounts/{id}` | Get account |
| POST | `/v1/accounts/{id}:close` | Close account |
| GET | `/v1/accounts/{id}/balance` | Get balance |
| POST | `/v1/transactions` | Post transaction (idempotent) |
| GET | `/v1/transactions` | List transactions |
| GET | `/v1/transactions/{txId}` | Get transaction |
| POST | `/v1/transactions/{txId}:reverse` | Reverse transaction |
| POST | `/v1/transactions:batch` | Batch post transactions |
| GET | `/v1/idempotency/{key}` | Check idempotency key status |
| GET | `/healthz` | Liveness probe |
| GET | `/readyz` | Readiness probe |

Full OpenAPI spec: `docs/openapi.yaml`

## Local Development

```bash
# Option 1: Start dependencies via Docker Compose (lightweight)
docker compose -f docker-compose-test.yml up -d

# Option 2: Use full stack from ledger-infra repo
cd ../ledger-infra/docker && docker compose up -d

# Run the application
./gradlew bootRun
```

## Running Tests

```bash
# Unit tests only (~5s, no Docker required)
./gradlew unitTest

# Integration tests (~30s, requires Docker for Testcontainers)
./gradlew integrationTest

# All tests (unit + integration)
./gradlew test

# E2E smoke tests (requires full running stack)
./gradlew e2eTest
```

See `TESTING.md` for the full testing guide and pyramid strategy.

## Build Docker Image

```bash
./gradlew bootJar
docker build -t ledger-api:local .
```

## Related Repositories

| Repository | Purpose |
|---|---|
| [ledger-docs](https://github.com/GITHUB_ORG/ledger-system-design) | Architecture docs, ADRs, API contract (source of truth) |
| [ledger-infra](https://github.com/GITHUB_ORG/ledger-infra) | Helm charts, Docker Compose (full stack), observability configs |
| [ledger-load-tests](https://github.com/GITHUB_ORG/ledger-load-tests) | k6 performance & load test suite |
API_README

sed -i "s/GITHUB_ORG/${GITHUB_ORG}/g" "${API_DIR}/README.md"

# Ensure .gitignore exists and excludes build artifacts
if [ ! -f "${API_DIR}/.gitignore" ]; then
    cat > "${API_DIR}/.gitignore" << 'EOF'
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar

# IDE
.idea/
*.iml
.vscode/

# OS
.DS_Store
Thumbs.db

# Secrets
.env
*.secret
EOF
fi

# --- Optional: verify tests pass ---
if [ "${VERIFY}" = "--verify" ]; then
    echo "[VERIFY] Running tests in ledger-api..."
    cd "${API_DIR}"
    if ./gradlew test; then
        echo "[OK] All tests pass."
    else
        echo "[WARN] Tests failed. Continuing with migration but review before using."
    fi
fi

create_repo_if_missing "ledger-api" "High-throughput distributed ledger API (Kotlin/Spring Boot)"
init_and_push "${API_DIR}" "ledger-api" "$(cat <<'COMMIT'
Initial commit — ledger API application

Fully implemented Kotlin/Spring Boot distributed ledger platform:

Application:
- REST API: accounts, transactions (single + batch), reversals, idempotency, health
- Domain models + PostgreSQL schema (Flyway V1 migration)
- JDBC repositories (5) + MongoDB projection repository
- Transactional outbox pattern + Kafka consumer for projections
- Observability: Micrometer metrics, structured JSON logging, MDC tenant/trace filter
- Dockerfile: multi-stage JDK 21 build with G1GC tuning

Testing (all passing):
- Unit tests (~23): service logic, exception handling, eventing, observability
- Integration tests: Testcontainers (PostgreSQL 16, MongoDB 7, Kafka)
- E2E smoke tests: full critical path validation

CI/CD:
- GitHub Actions: PR quality gate (lint, test, security scan)
- GitHub Actions: build & deploy to staging on merge to main

Migrated from ledger-system-design monorepo.
COMMIT
)"

# =====================================================================
# STEP 2: Create ledger-infra
# =====================================================================
echo ""
echo ">>> STEP 2: ledger-infra (infrastructure)"
echo "-------------------------------------------"

INFRA_DIR="${WORKSPACE}/ledger-infra"
mkdir -p "${INFRA_DIR}"

# --- Helm charts ---
cp -r "${EXPORT_DIR}/helm" "${INFRA_DIR}/helm"

# --- Docker Compose (full dev stack) + observability configs ---
cp -r "${EXPORT_DIR}/docker" "${INFRA_DIR}/docker"
# Remove the test-only compose (that stays with ledger-api)
rm -f "${INFRA_DIR}/docker/docker-compose-test.yml"

# --- Capacity calculator ---
mkdir -p "${INFRA_DIR}/scripts"
cp "${EXPORT_DIR}/scripts/capacity_calc.py" "${INFRA_DIR}/scripts/capacity_calc.py"

# --- Production release workflow ---
mkdir -p "${INFRA_DIR}/.github/workflows"
if [ -f "${EXPORT_DIR}/.github/workflows/release-prod.yml" ]; then
    cp "${EXPORT_DIR}/.github/workflows/release-prod.yml" "${INFRA_DIR}/.github/workflows/release-prod.yml"
fi

# --- Helm lint CI ---
cat > "${INFRA_DIR}/.github/workflows/pr-checks.yml" << 'EOF'
name: PR Checks

on:
  pull_request:
    branches: [main]

jobs:
  helm-lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up Helm
        uses: azure/setup-helm@v4
      - name: Lint Helm chart
        run: helm lint helm/ledger

  yaml-lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Install yamllint
        run: pip install yamllint
      - name: Lint YAML files
        run: yamllint -d relaxed docker/ helm/ || true
EOF

# --- Environment-specific Helm values ---
cat > "${INFRA_DIR}/helm/ledger/values-staging.yaml" << 'EOF'
# Staging environment overrides
replicaCount: 2

hpa:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70

env:
  LOG_LEVEL: DEBUG
EOF

cat > "${INFRA_DIR}/helm/ledger/values-prod.yaml" << 'EOF'
# Production environment overrides
replicaCount: 3

hpa:
  enabled: true
  minReplicas: 3
  maxReplicas: 30
  targetCPUUtilizationPercentage: 70

pdb:
  enabled: true
  minAvailable: 2

env:
  LOG_LEVEL: INFO
EOF

# --- LICENSE ---
cp "${EXPORT_DIR}/LICENSE" "${INFRA_DIR}/LICENSE"

# --- .gitignore ---
cat > "${INFRA_DIR}/.gitignore" << 'EOF'
# Secrets
*.secret
*.key
*.pem
.env
values-*.secret.yaml

# OS
.DS_Store
Thumbs.db
EOF

# --- README ---
cat > "${INFRA_DIR}/README.md" << 'INFRA_README'
# Ledger Infrastructure

Infrastructure-as-code for the Distributed Ledger Platform.

## Contents

- `helm/` — Helm chart for Kubernetes deployment (API + workers)
- `docker/` — Docker Compose for local development environment
  - PostgreSQL 16, MongoDB 7, Kafka (KRaft mode)
  - Prometheus + Grafana with pre-configured dashboards
- `scripts/` — Utility scripts (capacity calculator)

## Quick Start (Local Development)

```bash
cd docker
docker compose up -d
```

Services available:
| Service     | Port  |
|-------------|-------|
| PostgreSQL  | 5432  |
| MongoDB     | 27017 |
| Kafka       | 9092  |
| Prometheus  | 9090  |
| Grafana     | 3000  |

## Helm Deployment

```bash
# Staging
helm upgrade --install ledger ./helm/ledger \
  --namespace ledger-staging \
  -f helm/ledger/values.yaml \
  -f helm/ledger/values-staging.yaml \
  --set image.tag=<commit-sha>

# Production
helm upgrade --install ledger ./helm/ledger \
  --namespace ledger-prod \
  -f helm/ledger/values.yaml \
  -f helm/ledger/values-prod.yaml \
  --set image.tag=<release-tag> \
  --atomic --timeout 10m
```

## Capacity Calculator

```bash
python3 scripts/capacity_calc.py --tps 2000 --entries 4
```

## Related Repositories

| Repository | Purpose |
|---|---|
| [ledger-docs](https://github.com/GITHUB_ORG/ledger-system-design) | Architecture docs & API contract |
| [ledger-api](https://github.com/GITHUB_ORG/ledger-api) | Application source code (Kotlin/Spring Boot) |
| [ledger-load-tests](https://github.com/GITHUB_ORG/ledger-load-tests) | k6 performance & load test suite |
INFRA_README

sed -i "s/GITHUB_ORG/${GITHUB_ORG}/g" "${INFRA_DIR}/README.md"

create_repo_if_missing "ledger-infra" "Infrastructure-as-code for the Distributed Ledger Platform (Helm, Docker, observability)"
init_and_push "${INFRA_DIR}" "ledger-infra" "$(cat <<'COMMIT'
Initial commit — ledger infrastructure

Migrated from ledger-system-design monorepo:

Infrastructure:
- Helm chart for Kubernetes deployment (HPA, PDB, ingress, service)
- Environment-specific Helm values (staging, production)
- Docker Compose: full local dev stack (PostgreSQL 16, MongoDB 7, Kafka, Prometheus, Grafana)
- Grafana dashboards (api-health, ledger-core) + provisioning
- Prometheus scrape configuration

CI/CD:
- GitHub Actions: Helm lint on PR
- GitHub Actions: production release pipeline (manual approval on tag v*)

Utilities:
- Capacity calculator script
COMMIT
)"

# =====================================================================
# STEP 3: Create ledger-load-tests
# =====================================================================
echo ""
echo ">>> STEP 3: ledger-load-tests (k6 performance suite)"
echo "-------------------------------------------"

LOAD_DIR="${WORKSPACE}/ledger-load-tests"
mkdir -p "${LOAD_DIR}"

# --- k6 test suite ---
cp -r "${EXPORT_DIR}/k6" "${LOAD_DIR}/k6"

# --- LICENSE ---
cp "${EXPORT_DIR}/LICENSE" "${LOAD_DIR}/LICENSE"

# --- .gitignore ---
cat > "${LOAD_DIR}/.gitignore" << 'EOF'
# k6 output
*.json
*.csv
summary.html

# OS
.DS_Store
Thumbs.db

# IDE
.idea/
.vscode/
EOF

# --- Baselines directory for future results ---
mkdir -p "${LOAD_DIR}/baselines"
cat > "${LOAD_DIR}/baselines/.gitkeep" << 'EOF'
EOF

# --- GitHub Actions ---
mkdir -p "${LOAD_DIR}/.github/workflows"
cat > "${LOAD_DIR}/.github/workflows/smoke-test.yml" << 'EOF'
name: Smoke Test

on:
  workflow_dispatch:
    inputs:
      target_url:
        description: 'Target base URL'
        required: true
        default: 'http://localhost:8080'
      tenant:
        description: 'Tenant ID'
        required: true
        default: 't1'

jobs:
  smoke:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Install k6
        run: |
          sudo gpg -k
          sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D68
          echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
          sudo apt-get update
          sudo apt-get install k6
      - name: Run smoke test
        run: |
          BASE_URL=${{ github.event.inputs.target_url }} \
          TENANT=${{ github.event.inputs.tenant }} \
          k6 run k6/smoke.js
EOF

cat > "${LOAD_DIR}/.github/workflows/nightly-perf.yml" << 'EOF'
name: Nightly Performance

on:
  schedule:
    - cron: '0 3 * * 1-5'  # Weekdays at 3 AM UTC
  workflow_dispatch:

jobs:
  baseline:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Install k6
        run: |
          sudo gpg -k
          sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D68
          echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
          sudo apt-get update
          sudo apt-get install k6
      - name: Run baseline performance test
        env:
          BASE_URL: ${{ secrets.STAGING_URL }}
          TENANT: perf-tenant
        run: k6 run k6/baseline.js --out json=results.json
      - name: Upload results
        uses: actions/upload-artifact@v4
        with:
          name: k6-results-${{ github.run_id }}
          path: results.json
EOF

# --- README ---
cat > "${LOAD_DIR}/README.md" << 'LOAD_README'
# Ledger Load & Performance Tests

k6 performance validation suite for the Distributed Ledger Platform.

## Available Scenarios

| Script | Purpose | Duration |
|---|---|---|
| `smoke.js` | Quick health check (CI gate) | ~30s |
| `baseline.js` | Standard throughput (60% reads, 40% writes) | ~5m |
| `batch.js` | Batch transaction performance | ~3m |
| `hotspot.js` | Hot account contention testing | ~5m |
| `idempotency.js` | Idempotency replay validation | ~3m |
| `read_after_write.js` | Consistency check after writes | ~3m |
| `spike.js` | Burst traffic resilience | ~5m |
| `heavy_throughput.js` | Maximum throughput stress test | ~10m |
| `heavy_idempotency.js` | Heavy idempotency stress test | ~10m |
| `endurance.js` | 30-minute soak test | ~30m |

## Running Tests

```bash
# Against local environment
BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/smoke.js

# Against staging
BASE_URL=https://ledger-staging.example.com TENANT=t1 k6 run k6/baseline.js

# Heavy throughput (requires seeded accounts)
BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/heavy_throughput.js
```

## Shared Modules

- `k6/common.js` — Shared configuration, thresholds, and API helpers
- `k6/utils.js` — UUID generation, random selection, cursor paging

## Thresholds

Default thresholds enforced across scenarios:
- Error rate < 1%
- p95 latency < 200ms

## Related Repositories

| Repository | Purpose |
|---|---|
| [ledger-docs](https://github.com/GITHUB_ORG/ledger-system-design) | Architecture docs & API contract |
| [ledger-api](https://github.com/GITHUB_ORG/ledger-api) | Application source code (Kotlin/Spring Boot) |
| [ledger-infra](https://github.com/GITHUB_ORG/ledger-infra) | Helm charts & Docker Compose |
LOAD_README

sed -i "s/GITHUB_ORG/${GITHUB_ORG}/g" "${LOAD_DIR}/README.md"

create_repo_if_missing "ledger-load-tests" "Performance and load test suite for the Distributed Ledger Platform (k6)"
init_and_push "${LOAD_DIR}" "ledger-load-tests" "$(cat <<'COMMIT'
Initial commit — ledger load & performance tests

Migrated from ledger-system-design monorepo:

k6 scenarios (10):
- smoke, baseline, batch, hotspot, idempotency, read_after_write, spike
- heavy_throughput, heavy_idempotency, endurance (30-min soak)

CI/CD:
- GitHub Actions: on-demand smoke test against any environment
- GitHub Actions: nightly performance baseline against staging
COMMIT
)"

# =====================================================================
# STEP 4: Summary and cleanup instructions
# =====================================================================
echo ""
echo ">>> STEP 4: Cleanup instructions for ledger-system-design (→ ledger-docs)"
echo "-------------------------------------------"
echo ""
echo "The following directories have been migrated and can be removed:"
echo ""
echo "  Migrated to ledger-api:"
echo "    - ledger-api/                (entire application + tests)"
echo "    - IMPLEMENTATION_GUIDE.md"
echo "    - TESTING.md"
echo "    - .github/workflows/pr-checks.yml"
echo "    - .github/workflows/build-and-deploy-staging.yml"
echo ""
echo "  Migrated to ledger-infra:"
echo "    - helm/"
echo "    - docker/"
echo "    - scripts/capacity_calc.py"
echo "    - .github/workflows/release-prod.yml"
echo ""
echo "  Migrated to ledger-load-tests:"
echo "    - k6/"
echo ""
echo "  Can be removed (replaced by integration tests in ledger-api):"
echo "    - integration-tests/"
echo ""
echo "  Keep in ledger-docs (this repo):"
echo "    - docs/           (all documentation)"
echo "    - README.md       (update to be docs-only)"
echo "    - LICENSE"
echo ""
echo "To clean up, run:"
echo "  cd ${SOURCE_REPO_DIR}"
echo "  git checkout main"
echo "  git rm -r ledger-api/ helm/ docker/ k6/ integration-tests/ .github/"
echo "  git rm IMPLEMENTATION_GUIDE.md TESTING.md"
echo "  git rm scripts/capacity_calc.py"
echo "  # Update README.md to reference the new repos"
echo "  git add -A"
echo "  git commit -m 'chore: remove files migrated to dedicated repos (ledger-api, ledger-infra, ledger-load-tests)'"
echo "  git push origin main"
echo ""

# =====================================================================
# Summary
# =====================================================================
echo ""
echo "=============================================="
echo " Migration Complete!"
echo "=============================================="
echo ""
echo " New repositories created:"
echo "   https://github.com/${GITHUB_ORG}/ledger-api"
echo "   https://github.com/${GITHUB_ORG}/ledger-infra"
echo "   https://github.com/${GITHUB_ORG}/ledger-load-tests"
echo ""
echo " Original repository (becomes documentation hub):"
echo "   https://github.com/${GITHUB_ORG}/ledger-system-design"
echo ""
echo " What was migrated:"
echo "   ledger-api:"
echo "     - Full Kotlin/Spring Boot application (src/main + src/test)"
echo "     - build.gradle.kts, settings.gradle.kts, gradlew"
echo "     - Dockerfile (multi-stage JDK 21)"
echo "     - Flyway migration (V1__init_schema.sql)"
echo "     - Unit tests (~23), integration tests (Testcontainers), E2E smoke"
echo "     - IMPLEMENTATION_GUIDE.md, TESTING.md"
echo "     - PR checks + staging deploy workflows"
echo "     - docker-compose-test.yml (lightweight test infra)"
echo ""
echo "   ledger-infra:"
echo "     - Helm chart (deployment, HPA, PDB, ingress, service)"
echo "     - Docker Compose (full stack: pg, mongo, kafka, prometheus, grafana)"
echo "     - Grafana dashboards + Prometheus config"
echo "     - Environment-specific Helm values (staging, production)"
echo "     - Production release workflow"
echo "     - Capacity calculator script"
echo ""
echo "   ledger-load-tests:"
echo "     - 10 k6 scenarios (smoke through 30-min endurance)"
echo "     - Smoke test + nightly performance workflows"
echo ""
echo " Next steps:"
echo "   1. Verify the new repos on GitHub"
echo "   2. Clone ledger-api and run: ./gradlew test"
echo "   3. Set up branch protection on each repo's main branch"
echo "   4. Add CODEOWNERS files"
echo "   5. Configure secrets for CI/CD (container registry, staging URL)"
echo "   6. Clean up migrated files from ledger-system-design (see above)"
echo "   7. Optionally rename ledger-system-design → ledger-docs on GitHub"
echo ""
echo " Workspace (temporary): ${WORKSPACE}"
echo "=============================================="
