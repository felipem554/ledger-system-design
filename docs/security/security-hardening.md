# Security Hardening Guide (v1)

## Network boundaries
- Public exposure ONLY via NGINX Ingress
- Ledger API is ClusterIP (private)
- Datastores (Postgres, Kafka, Mongo) are private services
- Use Kubernetes NetworkPolicies to restrict pod-to-pod access

## Transport security
- TLS termination at Ingress
- Prefer mTLS between Ingress and services (optional)
- Use TLS for Kafka and Mongo in production

## Authentication & Authorization
- JWT bearer tokens (OAuth2/OIDC)
- Tenant derived from JWT claim; header override disabled in prod
- RBAC:
  - `ledger:write` (post, reverse, batch)
  - `ledger:read` (balance, list, get)
  - `ledger:admin` (close account)

## Request protection
- Enforce Idempotency-Key for ALL write endpoints
- Rate limit by tenant (ingress + app-level)
- Max body size and max entries per transaction
- Input validation for currency, amounts, and account state

## Secrets management
- Use Kubernetes Secrets (or external secret manager)
- Rotate DB credentials and JWT keys
- Never log secrets; scrub headers and payload fields

## Data protection
- Store money as BIGINT minor units
- Avoid storing PII in ledger objects; store references
- Encrypt disks / volumes at rest (cloud managed or OS-level)
- Backups:
  - Postgres PITR
  - Mongo snapshots
  - Kafka retention policies and backups as needed

## Observability security
- Restrict /metrics and /readyz endpoints to internal networks
- Redact sensitive fields in logs
- Sampling for high-volume INFO logs

## Supply chain
- Pin base images
- Run as non-root
- SBOM and vuln scans in CI
- Enable read-only root filesystem where possible
