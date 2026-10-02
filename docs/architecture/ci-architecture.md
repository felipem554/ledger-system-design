# CI / Deployment Architecture (multi-repo)

> Mirrored from `ledger-api/docs/ci-architecture.md`; the copy in ledger-api is canonical.

How the three repositories fit together, and why the app repo does **not** deploy.

## The three repos

| Repo | Owns | Holds secrets |
|------|------|---------------|
| **ledger-api** | The application: build, test, and **publish a container image** | GHCR publish only (built-in `GITHUB_TOKEN`) |
| **ledger-infra** | Deployment: Helm chart, Kubernetes manifests, environments | **Cluster / kube credentials** |
| **ledger-load-tests** | Verification: k6 scenarios (and Kafka load generator) | Target URLs only |

Three repos is the right separation. The problem to avoid is *wiring* them so that
one repo reaches into another's files or credentials.

## Principle: artifacts & events, not cross-repo checkouts

Repos communicate through a **published image tag** and **trigger events**, never by
checking out each other's source. Concretely:

- `ledger-api` builds and pushes `ghcr.io/<owner>/ledger-api:<sha>`. Done.
- `ledger-infra` deploys that tag (it owns the chart *and* the kube credentials).
- `ledger-load-tests` runs against a deployed URL, triggered on demand.

Why this matters:

- **No credential sprawl.** The app repo — which many people push to — holds no
  kube credentials and no broad cross-repo token. Deploy credentials live only in
  `ledger-infra`. This shrinks the blast radius of a leaked secret, a compromised
  action, or a malicious dependency.
- **Loose coupling.** The contract is a stable image tag, not file paths. Any repo
  can restructure internally without breaking another's pipeline.
- **Clear ownership.** Build failures are the app's; deploy failures are infra's;
  smoke failures are load-tests'.

### Anti-pattern (what we deliberately removed)

The app pipeline previously checked out `ledger-infra` (for the Helm chart) and
`ledger-load-tests` (for k6) with a broad `CI_REPOS_TOKEN` PAT, and ran `helm`
with `KUBE_CONFIG_STAGING`. That coupled the app to the other repos' layouts and
put cluster credentials in the app repo. Removed in favour of the flow below.

## Target flow

```
 ledger-api                     ledger-infra                 ledger-load-tests
 ──────────                     ────────────                 ─────────────────
 push to main
   │ test
   │ build image
   ▼
 GHCR  ledger-api:<sha> ──(image tag)──►  deploy (owns KUBE creds)
                                              │  helm upgrade
                                              ▼
                                           staging ──(target URL)──►  k6 / Kafka
                                                                       load run
```

The `(image tag)` and `(target URL)` handoffs are one-way and carry no source —
each hop needs at most a narrow "trigger a workflow" token, never "clone
everything".

## What is implemented now

- **ledger-api**:
  - `.github/workflows/pr-checks.yml` — PR quality gate: lint, unit tests,
    integration tests (Testcontainers), Trivy. Self-contained.
  - `.github/workflows/build-and-publish.yml` — on push to `main`: test, then build
    and push the image to GHCR. **No deploy, no kube creds, no cross-repo token.**

## To wire later (owned by the other repos)

1. **Deploy — in `ledger-infra`.**
   - Move the Helm deploy + `KUBE_CONFIG_*` into `ledger-infra`'s own workflow.
   - Trigger it from `ledger-api` via `repository_dispatch` with the new image tag,
     using a **fine-grained, trigger-only** token (or let image automation bump the
     tag). The app repo never gains cluster access.
   - **North star:** GitOps (Argo CD / Flux) — a controller in the cluster watches
     `ledger-infra` + the registry and reconciles. Then **no** CI holds kube
     credentials at all (pull-based).
2. **Verify — in `ledger-load-tests`.**
   - A `workflow_dispatch`/scheduled workflow that takes a target URL and runs k6
     (smoke) and the load suites. `ledger-infra` triggers it after a successful
     deploy. Nothing checks this repo out.

## Note: where the Kafka load generator lives

The Kafka command-stream load generator currently lives in **ledger-api**
(`src/loadtest`) because it reuses the app's command contract DTOs
(`CommandEnvelope`, `TransactionRequest`) via a Gradle source set — keeping it here
guarantees the load payloads match the real schema. The k6 HTTP scenarios live in
**ledger-load-tests**. If we later want all load tooling in one repo, the app's
command contract should first be published as a small shared artifact so the
generator can depend on it without living next to the app.
