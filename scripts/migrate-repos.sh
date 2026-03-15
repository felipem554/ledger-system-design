#!/usr/bin/env bash
# =============================================================================
# migrate-repos.sh
#
# Migrates the ledger-system-design monorepo into four independent repositories:
#   1. ledger-system-design  (stays — architecture & design docs)
#   2. ledger-infra           (new — Helm, Docker, observability configs)
#   3. ledger-load-tests      (new — k6 + integration test suite)
#   4. ledger-api             (new — scaffold for Kotlin application)
#
# Prerequisites:
#   - git, gh (GitHub CLI) installed and authenticated
#   - You are a member of the target GitHub org (or using a personal account)
#
# Usage:
#   export GITHUB_ORG="felipem554"   # your GitHub org or username
#   chmod +x scripts/migrate-repos.sh
#   ./scripts/migrate-repos.sh
#
# The script is idempotent — it skips repo creation if the repo already exists.
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
GITHUB_ORG="${GITHUB_ORG:?Set GITHUB_ORG to your GitHub org or username}"
WORKSPACE="${WORKSPACE:-$(mktemp -d)}"
SOURCE_REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# Repo visibility (public or private)
REPO_VISIBILITY="${REPO_VISIBILITY:-public}"

echo "=============================================="
echo " Ledger Repository Migration"
echo "=============================================="
echo " Source:     ${SOURCE_REPO_DIR}"
echo " GitHub Org: ${GITHUB_ORG}"
echo " Workspace:  ${WORKSPACE}"
echo " Visibility: ${REPO_VISIBILITY}"
echo "=============================================="
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
# Helper: initialize a local repo, commit, and push
# ---------------------------------------------------------------------------
init_and_push() {
    local repo_dir="$1"
    local repo_name="$2"
    local commit_message="$3"

    cd "${repo_dir}"
    git init -b main
    git add -A
    git commit -m "${commit_message}"
    git remote add origin "https://github.com/${GITHUB_ORG}/${repo_name}.git"

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
# STEP 1: Create ledger-infra
# =====================================================================
echo ""
echo ">>> STEP 1: ledger-infra"
echo "-------------------------------------------"

INFRA_DIR="${WORKSPACE}/ledger-infra"
mkdir -p "${INFRA_DIR}"

# Copy Helm charts
cp -r "${SOURCE_REPO_DIR}/helm" "${INFRA_DIR}/helm"

# Copy Docker Compose and related configs
cp -r "${SOURCE_REPO_DIR}/docker" "${INFRA_DIR}/docker"

# Create README
cat > "${INFRA_DIR}/README.md" << 'INFRA_README'
# Ledger Infrastructure

Infrastructure-as-code for the Distributed Ledger Platform.

## Contents

- `helm/` — Helm chart for Kubernetes deployment (API + workers)
- `docker/` — Docker Compose for local development environment
  - PostgreSQL 16, MongoDB 7, Kafka 3.7 (KRaft mode)
  - Prometheus + Grafana with pre-configured dashboards

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
helm upgrade --install ledger ./helm/ledger \
  --namespace ledger \
  --values helm/ledger/values.yaml \
  -f helm/ledger/values-staging.yaml
```

## Related Repositories

| Repository | Purpose |
|---|---|
| [ledger-system-design](https://github.com/GITHUB_ORG/ledger-system-design) | Architecture docs & API contract |
| [ledger-api](https://github.com/GITHUB_ORG/ledger-api) | Application source code (Kotlin) |
| [ledger-load-tests](https://github.com/GITHUB_ORG/ledger-load-tests) | k6 performance & integration tests |
INFRA_README

# Replace placeholder with actual org
sed -i "s/GITHUB_ORG/${GITHUB_ORG}/g" "${INFRA_DIR}/README.md"

# Copy LICENSE
cp "${SOURCE_REPO_DIR}/LICENSE" "${INFRA_DIR}/LICENSE"

# Create .gitignore
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

# Create GitHub Actions: Helm lint on PR
mkdir -p "${INFRA_DIR}/.github/workflows"
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
        run: yamllint -c .yamllint.yml . || true
EOF

# Create placeholder environment values
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

create_repo_if_missing "ledger-infra" "Infrastructure-as-code for the Distributed Ledger Platform (Helm, Docker, observability)"
init_and_push "${INFRA_DIR}" "ledger-infra" "$(cat <<'COMMIT'
Initial commit — ledger infrastructure

Migrated from ledger-system-design monorepo:
- Helm chart for Kubernetes deployment
- Docker Compose for local development (PostgreSQL, Kafka, MongoDB, Prometheus, Grafana)
- Grafana dashboards and Prometheus configuration
- GitHub Actions PR validation (Helm lint)
- Environment-specific Helm values (staging, production)
COMMIT
)"

# =====================================================================
# STEP 2: Create ledger-load-tests
# =====================================================================
echo ""
echo ">>> STEP 2: ledger-load-tests"
echo "-------------------------------------------"

LOAD_DIR="${WORKSPACE}/ledger-load-tests"
mkdir -p "${LOAD_DIR}"

# Copy k6 tests
cp -r "${SOURCE_REPO_DIR}/k6" "${LOAD_DIR}/k6"

# Copy integration tests
cp -r "${SOURCE_REPO_DIR}/integration-tests" "${LOAD_DIR}/integration-tests"

# Copy testing docs
mkdir -p "${LOAD_DIR}/docs"
cp "${SOURCE_REPO_DIR}/docs/testing/testcontainers.md" "${LOAD_DIR}/docs/testcontainers.md"

# Create README
cat > "${LOAD_DIR}/README.md" << 'LOAD_README'
# Ledger Load & Integration Tests

Performance validation and integration test suite for the Distributed Ledger Platform.

## k6 Load Tests

### Available Scenarios

| Script | Purpose |
|---|---|
| `baseline.js` | Standard throughput validation |
| `batch.js` | Batch transaction performance |
| `hotspot.js` | Hot account contention testing |
| `idempotency.js` | Idempotency replay validation |
| `read_after_write.js` | Consistency check after writes |
| `smoke.js` | Quick health check (CI gate) |
| `spike.js` | Burst traffic resilience |

### Running Tests

```bash
# Against local environment
BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/smoke.js

# Against staging
BASE_URL=https://ledger-staging.example.com TENANT=t1 k6 run k6/baseline.js
```

### Shared Modules

- `k6/common.js` — Shared configuration and thresholds
- `k6/utils.js` — Helper functions

## Integration Tests

- `integration-tests/` — Testcontainers-based integration test snippets (Kotlin)

## Related Repositories

| Repository | Purpose |
|---|---|
| [ledger-system-design](https://github.com/GITHUB_ORG/ledger-system-design) | Architecture docs & API contract |
| [ledger-api](https://github.com/GITHUB_ORG/ledger-api) | Application source code (Kotlin) |
| [ledger-infra](https://github.com/GITHUB_ORG/ledger-infra) | Helm charts & Docker Compose |
LOAD_README

sed -i "s/GITHUB_ORG/${GITHUB_ORG}/g" "${LOAD_DIR}/README.md"

# Copy LICENSE
cp "${SOURCE_REPO_DIR}/LICENSE" "${LOAD_DIR}/LICENSE"

# Create .gitignore
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

# Create GitHub Actions
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

create_repo_if_missing "ledger-load-tests" "Performance and integration test suite for the Distributed Ledger Platform (k6, Testcontainers)"
init_and_push "${LOAD_DIR}" "ledger-load-tests" "$(cat <<'COMMIT'
Initial commit — ledger load & integration tests

Migrated from ledger-system-design monorepo:
- k6 load test suite (baseline, batch, hotspot, idempotency, smoke, spike)
- Testcontainers integration test snippets (Kotlin)
- GitHub Actions workflows (smoke test on dispatch, nightly performance)
COMMIT
)"

# =====================================================================
# STEP 3: Create ledger-api (scaffold)
# =====================================================================
echo ""
echo ">>> STEP 3: ledger-api"
echo "-------------------------------------------"

API_DIR="${WORKSPACE}/ledger-api"
mkdir -p "${API_DIR}"

# Copy OpenAPI spec as reference
mkdir -p "${API_DIR}/docs"
cp "${SOURCE_REPO_DIR}/docs/api/openapi.yaml" "${API_DIR}/docs/openapi.yaml"

# Copy DB schema reference
cp "${SOURCE_REPO_DIR}/docs/architecture/dbdiagram.dbml" "${API_DIR}/docs/dbdiagram.dbml"

# Create README
cat > "${API_DIR}/README.md" << 'API_README'
# Ledger API

High-throughput distributed ledger API built with Kotlin.

## Architecture

- **Kotlin** (Spring Boot / Ktor) — Transaction API + async workers
- **PostgreSQL** — Source of truth (balances, idempotency, outbox)
- **Kafka** — Event backbone (at-least-once delivery)
- **MongoDB** — Read model / projections

## API Contract

The OpenAPI specification is in `docs/openapi.yaml`.
The canonical version lives in [ledger-system-design](https://github.com/GITHUB_ORG/ledger-system-design).

## Database Schema

See `docs/dbdiagram.dbml` for the PostgreSQL schema design.

## Local Development

```bash
# Start dependencies (requires ledger-infra repo)
cd ../ledger-infra/docker && docker compose up -d

# Run the application
./gradlew bootRun
```

## Build & Test

```bash
# Unit tests
./gradlew test

# Integration tests (Testcontainers)
./gradlew integrationTest

# Build Docker image
docker build -t ledger-api:local .
```

## Related Repositories

| Repository | Purpose |
|---|---|
| [ledger-system-design](https://github.com/GITHUB_ORG/ledger-system-design) | Architecture docs & API contract |
| [ledger-infra](https://github.com/GITHUB_ORG/ledger-infra) | Helm charts & Docker Compose |
| [ledger-load-tests](https://github.com/GITHUB_ORG/ledger-load-tests) | k6 performance & integration tests |
API_README

sed -i "s/GITHUB_ORG/${GITHUB_ORG}/g" "${API_DIR}/README.md"

# Copy LICENSE
cp "${SOURCE_REPO_DIR}/LICENSE" "${API_DIR}/LICENSE"

# Create .gitignore
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

# Create Dockerfile scaffold
cat > "${API_DIR}/Dockerfile" << 'EOF'
# Multi-stage build for Kotlin/JVM application
FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
EOF

# Create GitHub Actions
mkdir -p "${API_DIR}/.github/workflows"
cat > "${API_DIR}/.github/workflows/pr-checks.yml" << 'EOF'
name: PR Checks

on:
  pull_request:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Build and test
        run: ./gradlew build
      - name: Integration tests
        run: ./gradlew integrationTest

  security-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run Trivy vulnerability scanner
        uses: aquasecurity/trivy-action@master
        with:
          scan-type: fs
          scan-ref: .
EOF

cat > "${API_DIR}/.github/workflows/build-and-deploy-staging.yml" << 'EOF'
name: Build & Deploy to Staging

on:
  push:
    branches: [main]

jobs:
  build-image:
    runs-on: ubuntu-latest
    outputs:
      image_tag: ${{ steps.meta.outputs.tags }}
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Build and test
        run: ./gradlew build
      - name: Docker meta
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ secrets.REGISTRY }}/ledger-api
          tags: type=sha
      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: ${{ steps.meta.outputs.tags }}
EOF

create_repo_if_missing "ledger-api" "High-throughput distributed ledger API (Kotlin)"
init_and_push "${API_DIR}" "ledger-api" "$(cat <<'COMMIT'
Initial commit — ledger API scaffold

Project scaffold for the Kotlin ledger application:
- OpenAPI spec and DB schema reference (from ledger-system-design)
- Dockerfile (multi-stage JDK 21 build)
- GitHub Actions (PR checks, build & deploy to staging)
- Project structure ready for Kotlin/Spring Boot implementation
COMMIT
)"

# =====================================================================
# STEP 4: Clean up ledger-system-design
# =====================================================================
echo ""
echo ">>> STEP 4: Clean up ledger-system-design"
echo "-------------------------------------------"
echo ""
echo "The following files have been migrated to other repos and can be"
echo "removed from ledger-system-design after you verify the new repos:"
echo ""
echo "  Migrated to ledger-infra:"
echo "    - helm/"
echo "    - docker/"
echo ""
echo "  Migrated to ledger-load-tests:"
echo "    - k6/"
echo "    - integration-tests/"
echo ""
echo "  Migrated to ledger-api:"
echo "    - docs/api/openapi.yaml      (copy — keep original as source of truth)"
echo "    - docs/architecture/dbdiagram.dbml (copy — keep original as source of truth)"
echo ""
echo "To clean up, run:"
echo "  cd ${SOURCE_REPO_DIR}"
echo "  git rm -r helm/ docker/ k6/ integration-tests/"
echo "  git commit -m 'chore: remove files migrated to dedicated repos'"
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
echo "   https://github.com/${GITHUB_ORG}/ledger-infra"
echo "   https://github.com/${GITHUB_ORG}/ledger-load-tests"
echo "   https://github.com/${GITHUB_ORG}/ledger-api"
echo ""
echo " Original repository (kept as design hub):"
echo "   https://github.com/${GITHUB_ORG}/ledger-system-design"
echo ""
echo " Next steps:"
echo "   1. Verify the new repos on GitHub"
echo "   2. Set up branch protection on each repo's main branch"
echo "   3. Add CODEOWNERS files"
echo "   4. Configure secrets for CI/CD (container registry, staging URL)"
echo "   5. Clean up migrated files from ledger-system-design (see above)"
echo "   6. Update ledger-system-design README with cross-repo links"
echo ""
echo " Workspace (temporary): ${WORKSPACE}"
echo "=============================================="
