# CI/CD Strategy for Client Preview Environments

## Objective
Set up a delivery workflow so the client can continuously access a stable application preview while the team keeps building features for a high-throughput ledger system.

## Guiding Principles
- **Fast feedback for developers**: every pull request gets automated validation.
- **Safe releases for client demos**: only promoted builds reach shared environments.
- **Traceability**: every deployment maps to a commit SHA and image tag.
- **Production-like confidence**: use the same container image and Helm chart across environments.

## Environment Model
Use four environments with clear promotion gates:

1. **Local**
   - Docker Compose + local service runtime.
   - Used for rapid development and debugging.
2. **CI Ephemeral/Test**
   - Runs automatically in pipelines (unit/integration checks, static checks, contract checks).
   - No human access required.
3. **Client Staging (always available)**
   - Shared URL for client validation and weekly demos.
   - Updated from `main` after passing quality gates.
4. **Production**
   - Manual approval gate.
   - Uses immutable image tags and Helm release history.

## Branch and Release Flow
- `feature/*` → pull request to `main`.
- `main` is always deployable.
- Optional `release/*` branch for coordinated release hardening.
- Tag releases as `vX.Y.Z` for production promotion.

## Pipeline Design (Recommended)

### 1) Pull Request Pipeline (Quality Gate)
Run on each PR:
- Lint/format checks.
- Unit tests.
- Integration tests (including Testcontainers where relevant).
- API contract checks (if OpenAPI/consumer contracts are available).
- Security scans:
  - Dependency vulnerability scan.
  - Container image scan (if image build happens in PR).

**Outcome**: PR merge blocked unless all required checks pass.

### 2) Main Branch Pipeline (Build + Staging Deploy)
Run on each merge to `main`:
- Build application artifact and Docker image.
- Tag image with commit SHA (and optionally semantic pre-release).
- Push image to container registry.
- Deploy to **Client Staging** via Helm.
- Run smoke tests against staging URL.

**Outcome**: Client sees latest validated version with minimal delay.

### 3) Release Pipeline (Production Promotion)
Run on Git tag `v*`:
- Reuse previously built immutable image.
- Deploy to production using Helm (manual approval required).
- Run post-deploy smoke + synthetic transaction checks.
- Publish release notes.

## Required Quality Gates for a Throughput-Critical Ledger
Before allowing staging/production deployments:
- **Correctness**
  - Idempotency tests.
  - Concurrency/race condition tests for hot accounts.
  - Outbox/event publishing consistency tests.
- **Performance**
  - Baseline k6 scenario budget checks (p95/p99 latency and error rate).
  - Optional nightly stress tests and trend dashboard.
- **Reliability/Security**
  - SAST and dependency scanning.
  - Secrets scanning in repo and pipeline logs.
  - Migration compatibility checks (for PostgreSQL/Mongo schema changes).

## Suggested GitHub Actions Workflow Layout
Create the following workflows in `.github/workflows/`:

- `pr-checks.yml`
  - Trigger: `pull_request`
  - Jobs: lint, test, integration-test, security-scan
- `build-and-deploy-staging.yml`
  - Trigger: `push` on `main`
  - Jobs: build-image, publish-image, helm-deploy-staging, smoke-test
- `release-prod.yml`
  - Trigger: tag `v*`
  - Jobs: approval, helm-deploy-prod, post-deploy-verify

> If using another CI platform (GitLab CI, Jenkins, CircleCI), keep the same stage logic and promotion gates.

## Deployment and Rollback Standards
- Use Helm with environment values files (`values-staging.yaml`, `values-prod.yaml`).
- Enable `--atomic` where feasible for safer upgrades.
- Keep rollout strategy conservative for stateful dependencies (PostgreSQL/Kafka/Mongo managed separately).
- Define rollback playbook:
  - rollback Helm release
  - verify API health and lag metrics
  - communicate incident status and ETA

## Secrets and Credentials
- Never store plain secrets in Git.
- Use external secret manager integration (e.g., cloud secret manager + Kubernetes External Secrets).
- Restrict CI service account permissions:
  - PR pipelines: read-only where possible.
  - Deploy pipelines: scoped to target namespace/environment.

## Minimum Client-Visible SLOs for Staging
Publish these in project docs and validate continuously:
- API availability target (e.g., 99.5% on staging).
- p95 latency target for core transaction endpoint.
- Error rate threshold.
- Event propagation lag target (Kafka → projection).

## Implementation Checklist
1. Create container registry and IAM/service account permissions.
2. Add CI workflows for PR, main, and release.
3. Configure branch protection on `main` (required checks + reviews).
4. Add staging/prod Helm values and deployment secrets integration.
5. Add smoke tests and k6 baseline gate.
6. Add observability dashboard links and alert routing.
7. Run first end-to-end dry run with a sample feature PR.

## Documentation Artifacts to Add Next
- Runbook: "Staging deployment failed".
- Runbook: "Rollback procedure".
- Release checklist template.
- Definition of Done (includes performance and observability criteria).
