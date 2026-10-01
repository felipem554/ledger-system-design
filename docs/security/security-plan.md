# Security Plan

> Mirrored from `ledger-api/docs/security-plan.md`; the copy in ledger-api is canonical. For the target-state hardening baseline see [security-hardening.md](security-hardening.md).

Forward-looking plan to secure `ledger-api`. **Planning only — no code in this
document.** The service is currently unauthenticated; this plan takes it to a
production-viable security posture in prioritized phases.

## 1. Current state (honest baseline)

Verified against the code as of this writing:

| Area | Today | Risk |
|------|-------|------|
| Authentication | **None** — no Spring Security, no tokens, every endpoint is open | Anyone who can reach the port can call any API |
| Tenant isolation | `X-Tenant-Id` is a **client-supplied, trusted header** | Any caller can read/write **any** tenant's ledger by changing the header |
| Authorization | **None** — no roles, scopes, or per-tenant checks | No separation of read/write/admin, no ownership checks |
| Transport | HTTP, Kafka (`PLAINTEXT`), Postgres/Mongo all unencrypted | Traffic sniffable/tamperable on the wire |
| Secrets | Hardcoded defaults (`ledger`/`ledger`, Grafana `admin`/`admin`) | Credentials in source/compose |
| Kafka | No SASL/SSL, no ACLs; command ingestion trusts the envelope `tenantId` | A misconfigured/hostile producer can post cross-tenant |
| Actuator | `/actuator/prometheus`, `/actuator/health` (details always on) exposed unauthenticated | Operational/topology leakage |
| Audit | Logs carry `tenantId`/`txId` but no authenticated principal | Can't answer "who did this" for a financial system |

**The single most important gap:** tenant isolation is *assumed*, not *enforced*.
For a ledger holding balances, that is a P0.

## 2. Threat model

- **External attacker** reaching the API/Kafka: wants to read balances, post
  fraudulent transactions, or impersonate tenants.
- **Malicious/compromised tenant**: authenticated but tries to act on another
  tenant's data (horizontal privilege escalation) — the core multi-tenant threat.
- **Malicious/compromised producer** on the command stream: posts commands with a
  `tenantId` it isn't entitled to.
- **Insider / operator**: over-broad access; no audit trail.
- **Supply chain**: vulnerable dependency or base image.

Out of scope for this plan: DDoS at the edge (CDN/WAF concern), physical security.

## 3. Principles

- **Never trust client-supplied identity.** Tenant and roles come from a verified
  token, not a header.
- **Defense in depth.** AuthN + AuthZ + transport + network + audit, not one layer.
- **Least privilege** for callers, services, DB users, and Kafka principals.
- **Secure by default.** Ship locked down; open up explicitly per environment.
- **Auditable.** Every mutation is attributable to an authenticated principal.

---

## 4. Phased plan

### Phase 0 — Identity & isolation (P0, do first)

The minimum to stop cross-tenant access.

1. **Authentication — OAuth2/OIDC resource server.**
   - Add `spring-boot-starter-oauth2-resource-server`; validate JWT bearer tokens
     against an IdP (Keycloak/Auth0/Cognito). All `/v1/**` endpoints require a
     valid token; `/healthz`/`/readyz` stay open for probes.
   - Service-to-service callers use the OAuth2 **client-credentials** grant.
2. **Tenant from the token, not the header.**
   - Derive the tenant from a verified claim (e.g. `tenant_id` / `org`). The
     `X-Tenant-Id` header is either removed or **must match** the token claim —
     mismatch → `403`. `TenantFilter` (already present) becomes the enforcement
     point, populating a request-scoped tenant from the principal.
   - Every repository/service call is scoped by that trusted tenant.
3. **Secrets externalization.**
   - Remove hardcoded DB/Grafana credentials from `application.yml` and
     `docker/docker-compose.yml`; source from env/secret store (Vault, cloud
     secrets manager, or k8s secrets). Rotate the dev defaults.
4. **Lock down actuator.**
   - Keep only `/actuator/health` (liveness) public; require auth (or bind to an
     internal management port / network) for `prometheus` and `info`. Turn off
     `health show-details: always` for anonymous callers.

**Done when:** an unauthenticated request gets `401`; an authenticated request for
another tenant gets `403`; no secret is committed to the repo.

### Phase 1 — Authorization, Kafka security, audit (P1)

1. **Authorization model.**
   - Scopes/roles: `ledger:read`, `ledger:write`, `ledger:admin` (e.g. account
     close, reversal). Enforce with method security (`@PreAuthorize`) or a
     `SecurityFilterChain` authorization matcher.
   - Optional per-account entitlements if tenants need sub-tenant boundaries.
2. **Kafka security (both consumers + producers).**
   - `SASL_SSL` with per-service principals; TLS on the broker listeners.
   - **Topic ACLs**: only the ledger app may consume `ledger.commands.*` and
     produce `ledger.transactions.*`.
   - **Command authorization**: the command consumer must verify the envelope
     `tenantId` against the producer's authorized tenant (carried in a signed
     token/header on the message, or enforced by per-tenant topics/ACLs). A
     command for a tenant the producer can't act on → DLQ, not processed. This
     closes the "trusted envelope" hole that mirrors the HTTP header problem.
3. **Transport encryption end to end.**
   - TLS for the HTTP API (or terminate at the gateway/ingress), Postgres
     (`sslmode=require`), and MongoDB (`tls=true`).
4. **Audit logging.**
   - Record `{principal/clientId, tenant, action, resource, result, timestamp}`
     for every mutation (post, reverse, batch, account close). Ship to an
     append-only/immutable sink. Extend the existing MDC pattern with the
     authenticated subject.
5. **Rate limiting** (already on the roadmap) — per tenant/principal, to bound
   abuse and protect the DB. Complements Kafka backpressure.

**Done when:** roles are enforced, Kafka is authenticated + ACL'd with command
tenant checks, all hops are TLS, and every mutation is audited.

### Phase 2 — Data protection & hardening (P2)

1. **Data at rest**: enable DB/disk encryption (managed-DB feature or volume
   encryption). Consider field-level encryption for sensitive `metadata`.
2. **Log redaction**: never log full financial payloads (the command consumer
   already avoids this — extend the rule and add a review check).
3. **Supply chain**: dependency scanning (OWASP dependency-check / Dependabot),
   container image scanning (Trivy/Grype) in CI, and an SBOM. Pin base images.
4. **HTTP hardening**: security headers (HSTS, `X-Content-Type-Options`, etc.),
   strict CORS allow-list, request size limits.
5. **Network segmentation**: Postgres/Mongo/Kafka never publicly exposed (the
   compose port mappings are dev-only); private subnets + security groups in prod.
6. **DLQ handling**: treat DLQ contents as sensitive (they contain raw payloads);
   restrict access and redact when surfacing.
7. **Verification**: threat-model review and a penetration test before GA.

---

## 5. Priority summary

| Priority | Work | Why |
|----------|------|-----|
| **P0** | AuthN (OIDC), tenant-from-token, secrets, actuator lockdown | Stops cross-tenant read/write — the core risk |
| **P1** | AuthZ (roles), Kafka SASL/SSL + ACLs + command tenant check, TLS, audit, rate limiting | Enforces least privilege and closes the stream-side isolation gap |
| **P2** | Encryption at rest, log redaction, dep/image scanning, HTTP hardening, network segmentation, pen test | Hardening and compliance readiness |

## 6. Assumptions & non-goals

- Assumes an available IdP for OIDC (Keycloak/Auth0/Cognito) — introducing one is
  part of Phase 0.
- A gateway/ingress may terminate TLS and do coarse auth, but the service must
  **still** enforce tenant isolation itself (defense in depth) — it cannot rely on
  the gateway alone.
- This plan does not assume card/PCI data flows through the ledger; if that
  changes, PCI-DSS scope must be added.
