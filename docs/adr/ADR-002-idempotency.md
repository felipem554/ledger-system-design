# ADR-002: Idempotency for Write Endpoints

## Context
Clients and gateways may retry requests. We must prevent duplicate postings.

## Decision
Require `Idempotency-Key` for all write endpoints. Store `(tenant_id, key)` unique in Postgres with `request_hash`.
- same key + same hash => replay response
- same key + different hash => 409 IDEMPOTENCY_CONFLICT

## Consequences
+ Safe retries
+ Gateway retries can be enabled safely (with Idempotency-Key)
- Slight DB overhead per write (acceptable)
