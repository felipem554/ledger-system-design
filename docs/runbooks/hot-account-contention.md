# Runbook: Hot Account Contention

## Symptom
- Increased posting p99 latency
- Elevated conflict rate (409 CONCURRENCY_CONFLICT)
- Postgres lock waits rise

## Causes
- Many postings hitting same small set of accounts

## Mitigations
- Enable/adjust per-tenant rate limiting
- Introduce per-account concurrency guard (hash bucket locks)
- Route specific traffic to async ingestion (enqueue/batch)
- If business allows: use clearing accounts and periodic consolidation
