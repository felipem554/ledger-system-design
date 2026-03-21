#!/usr/bin/env bash
# =============================================================================
# push-to-repos.sh
#
# Assumes you have ALREADY created these empty repos on GitHub:
#   - felipem554/ledger-api
#   - felipem554/ledger-infra
#   - felipem554/ledger-load-tests
#
# This script exports files from the implementation branch, organizes them
# into the three repos, commits, and pushes.
#
# Usage:
#   export GITHUB_ORG="felipem554"
#   chmod +x scripts/push-to-repos.sh
#   ./scripts/push-to-repos.sh
# =============================================================================

set -euo pipefail

GITHUB_ORG="${GITHUB_ORG:?Set GITHUB_ORG to your GitHub org or username}"
SOURCE_BRANCH="${SOURCE_BRANCH:-claude/distributed-transaction-system-x4sg3}"
SOURCE_REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORKSPACE="$(mktemp -d)"

echo "=============================================="
echo " Push files to new repos"
echo "=============================================="
echo " Source:        ${SOURCE_REPO_DIR}"
echo " Source Branch: ${SOURCE_BRANCH}"
echo " GitHub Org:    ${GITHUB_ORG}"
echo " Workspace:     ${WORKSPACE}"
echo "=============================================="

# ---------------------------------------------------------------------------
# Export implementation branch to a clean directory
# ---------------------------------------------------------------------------
EXPORT_DIR="${WORKSPACE}/_source"
mkdir -p "${EXPORT_DIR}"
cd "${SOURCE_REPO_DIR}"
git fetch origin "${SOURCE_BRANCH}" 2>/dev/null || true
git archive "origin/${SOURCE_BRANCH}" | tar -x -C "${EXPORT_DIR}"
echo "[OK] Exported branch to ${EXPORT_DIR}"

# ---------------------------------------------------------------------------
# Helper: retry push with exponential backoff
# ---------------------------------------------------------------------------
push_with_retry() {
    local attempt=0 max=4 wait=2
    while [ $attempt -lt $max ]; do
        if git push -u origin main; then
            return 0
        fi
        attempt=$((attempt + 1))
        echo "[RETRY] Push failed. Waiting ${wait}s (attempt ${attempt}/${max})..."
        sleep $wait
        wait=$((wait * 2))
    done
    echo "[ERROR] Push failed after ${max} retries."
    return 1
}

# =====================================================================
# 1. ledger-api
# =====================================================================
echo ""
echo ">>> 1/3  ledger-api"
echo "--------------------"

API_DIR="${WORKSPACE}/ledger-api"
mkdir -p "${API_DIR}"

# Application source (entire ledger-api/ tree)
cp -r "${EXPORT_DIR}/ledger-api/." "${API_DIR}/"

# Developer guides
cp "${EXPORT_DIR}/IMPLEMENTATION_GUIDE.md" "${API_DIR}/"
cp "${EXPORT_DIR}/TESTING.md" "${API_DIR}/"

# Lightweight test compose
[ -f "${EXPORT_DIR}/docker/docker-compose-test.yml" ] && \
    cp "${EXPORT_DIR}/docker/docker-compose-test.yml" "${API_DIR}/docker-compose-test.yml"

# Reference docs
mkdir -p "${API_DIR}/docs"
cp "${EXPORT_DIR}/docs/api/openapi.yaml" "${API_DIR}/docs/openapi.yaml"
cp "${EXPORT_DIR}/docs/architecture/dbdiagram.dbml" "${API_DIR}/docs/dbdiagram.dbml"

# CI workflows (PR checks + staging deploy)
mkdir -p "${API_DIR}/.github/workflows"
cp "${EXPORT_DIR}/.github/workflows/pr-checks.yml" "${API_DIR}/.github/workflows/"
cp "${EXPORT_DIR}/.github/workflows/build-and-deploy-staging.yml" "${API_DIR}/.github/workflows/"

# LICENSE
cp "${EXPORT_DIR}/LICENSE" "${API_DIR}/"

# README
cat > "${API_DIR}/README.md" << 'EOF'
# Ledger API

High-throughput distributed ledger API — **Kotlin / Spring Boot 3.3.5 / JDK 21**.

## Tech Stack

- **PostgreSQL 16** — Source of truth (balances, idempotency, transactional outbox)
- **Kafka** — Event backbone (at-least-once delivery)
- **MongoDB 7** — Read model / projections
- **Flyway** — Database migrations
- **Micrometer + Prometheus** — Observability

## Running Tests

```bash
# Unit tests (~5s, no Docker)
./gradlew unitTest

# Integration tests (~30s, needs Docker for Testcontainers)
./gradlew integrationTest

# All tests
./gradlew test
```

## Local Dev

```bash
docker compose -f docker-compose-test.yml up -d   # lightweight deps
./gradlew bootRun                                   # API on :8080
```

See `TESTING.md` for the full testing guide and `IMPLEMENTATION_GUIDE.md` for component details.
EOF

cd "${API_DIR}"
git init -b main
git add -A
git commit -m "feat: ledger-api — full Kotlin/Spring Boot implementation

Application:
- REST API: accounts, transactions, batch, reversals, idempotency, health
- Domain models + PostgreSQL schema (Flyway V1)
- JDBC repositories (5) + MongoDB projection
- Transactional outbox + Kafka consumer
- Observability: Micrometer, structured JSON logging, MDC filter
- Dockerfile: multi-stage JDK 21, G1GC tuning

Testing (all passing):
- Unit tests (~23): mockito-kotlin
- Integration tests: Testcontainers (Postgres 16, Mongo 7, Kafka)
- E2E smoke tests

CI/CD:
- PR quality gate (lint, test, security scan)
- Staging deploy on merge to main

Migrated from ledger-system-design monorepo."

git remote add origin "https://github.com/${GITHUB_ORG}/ledger-api.git"
push_with_retry
echo "[OK] ledger-api pushed."

# =====================================================================
# 2. ledger-infra
# =====================================================================
echo ""
echo ">>> 2/3  ledger-infra"
echo "----------------------"

INFRA_DIR="${WORKSPACE}/ledger-infra"
mkdir -p "${INFRA_DIR}"

# Helm chart
cp -r "${EXPORT_DIR}/helm" "${INFRA_DIR}/helm"

# Docker Compose + observability (full dev stack)
cp -r "${EXPORT_DIR}/docker" "${INFRA_DIR}/docker"
rm -f "${INFRA_DIR}/docker/docker-compose-test.yml"

# Capacity calculator
mkdir -p "${INFRA_DIR}/scripts"
cp "${EXPORT_DIR}/scripts/capacity_calc.py" "${INFRA_DIR}/scripts/"

# Production release workflow
mkdir -p "${INFRA_DIR}/.github/workflows"
[ -f "${EXPORT_DIR}/.github/workflows/release-prod.yml" ] && \
    cp "${EXPORT_DIR}/.github/workflows/release-prod.yml" "${INFRA_DIR}/.github/workflows/"

# Helm lint CI
cat > "${INFRA_DIR}/.github/workflows/pr-checks.yml" << 'CIEOF'
name: PR Checks
on:
  pull_request:
    branches: [main]
jobs:
  helm-lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-helm@v4
      - run: helm lint helm/ledger
CIEOF

# Environment-specific values
cat > "${INFRA_DIR}/helm/ledger/values-staging.yaml" << 'VEOF'
replicaCount: 2
hpa:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70
env:
  LOG_LEVEL: DEBUG
VEOF

cat > "${INFRA_DIR}/helm/ledger/values-prod.yaml" << 'VEOF'
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
VEOF

cp "${EXPORT_DIR}/LICENSE" "${INFRA_DIR}/"

cat > "${INFRA_DIR}/.gitignore" << 'EOF'
*.secret
*.key
*.pem
.env
values-*.secret.yaml
.DS_Store
EOF

cat > "${INFRA_DIR}/README.md" << 'EOF'
# Ledger Infrastructure

Helm charts, Docker Compose, and observability configs for the Distributed Ledger Platform.

## Local Dev Stack

```bash
cd docker && docker compose up -d
```

| Service    | Port  |
|------------|-------|
| PostgreSQL | 5432  |
| MongoDB    | 27017 |
| Kafka      | 9092  |
| Prometheus | 9090  |
| Grafana    | 3000  |

## Helm Deploy

```bash
# Staging
helm upgrade --install ledger ./helm/ledger \
  --namespace ledger-staging \
  -f helm/ledger/values.yaml -f helm/ledger/values-staging.yaml \
  --set image.tag=<sha>

# Production
helm upgrade --install ledger ./helm/ledger \
  --namespace ledger-prod \
  -f helm/ledger/values.yaml -f helm/ledger/values-prod.yaml \
  --set image.tag=<tag> --atomic --timeout 10m
```
EOF

cd "${INFRA_DIR}"
git init -b main
git add -A
git commit -m "feat: ledger-infra — Helm, Docker Compose, observability

Infrastructure:
- Helm chart (deployment, HPA, PDB, ingress, service)
- Environment-specific values (staging, production)
- Docker Compose: full local stack (Postgres 16, Mongo 7, Kafka, Prometheus, Grafana)
- Grafana dashboards (api-health, ledger-core) + provisioning
- Prometheus scrape config
- Production release workflow (GitHub Actions)
- Capacity calculator script

Migrated from ledger-system-design monorepo."

git remote add origin "https://github.com/${GITHUB_ORG}/ledger-infra.git"
push_with_retry
echo "[OK] ledger-infra pushed."

# =====================================================================
# 3. ledger-load-tests
# =====================================================================
echo ""
echo ">>> 3/3  ledger-load-tests"
echo "---------------------------"

LOAD_DIR="${WORKSPACE}/ledger-load-tests"
mkdir -p "${LOAD_DIR}"

# k6 suite
cp -r "${EXPORT_DIR}/k6" "${LOAD_DIR}/k6"

cp "${EXPORT_DIR}/LICENSE" "${LOAD_DIR}/"

cat > "${LOAD_DIR}/.gitignore" << 'EOF'
*.json
*.csv
summary.html
.DS_Store
.idea/
.vscode/
EOF

mkdir -p "${LOAD_DIR}/baselines"
touch "${LOAD_DIR}/baselines/.gitkeep"

cat > "${LOAD_DIR}/README.md" << 'EOF'
# Ledger Load & Performance Tests

k6 performance validation suite for the Distributed Ledger Platform.

## Scenarios

| Script                  | Purpose                        | Duration |
|-------------------------|--------------------------------|----------|
| smoke.js                | Quick health check             | ~30s     |
| baseline.js             | Standard throughput            | ~5m      |
| batch.js                | Batch transactions             | ~3m      |
| hotspot.js              | Hot account contention         | ~5m      |
| idempotency.js          | Idempotency replay             | ~3m      |
| read_after_write.js     | Consistency after writes       | ~3m      |
| spike.js                | Burst traffic                  | ~5m      |
| heavy_throughput.js     | Max throughput stress          | ~10m     |
| heavy_idempotency.js    | Heavy idempotency stress       | ~10m     |
| endurance.js            | 30-min soak test               | ~30m     |

## Usage

```bash
BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/smoke.js
```
EOF

cd "${LOAD_DIR}"
git init -b main
git add -A
git commit -m "feat: ledger-load-tests — 10 k6 performance scenarios

Scenarios:
- smoke, baseline, batch, hotspot, idempotency, read_after_write, spike
- heavy_throughput, heavy_idempotency, endurance (30-min soak)

Migrated from ledger-system-design monorepo."

git remote add origin "https://github.com/${GITHUB_ORG}/ledger-load-tests.git"
push_with_retry
echo "[OK] ledger-load-tests pushed."

# =====================================================================
# Done
# =====================================================================
echo ""
echo "=============================================="
echo " All done!"
echo "=============================================="
echo ""
echo " Pushed to:"
echo "   https://github.com/${GITHUB_ORG}/ledger-api"
echo "   https://github.com/${GITHUB_ORG}/ledger-infra"
echo "   https://github.com/${GITHUB_ORG}/ledger-load-tests"
echo ""
echo " Temp workspace: ${WORKSPACE}"
echo "=============================================="
