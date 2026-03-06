# Index Tuning Guide

## PostgreSQL (write store)

### Goals
- Keep posting transaction short and lock-scope minimal
- Support fast balance reads
- Support cursor paging for audit/debug reads (not primary history)

### Recommended indexes
- `account_state`: PRIMARY KEY (tenant_id, account_id)
- `idempotency`: UNIQUE (tenant_id, key)
- `transactions_min`: (tenant_id, occurred_at, tx_id) for cursor ordering
- `entries`: (tenant_id, account_id, created_at, id) for account debug queries
- `outbox`: (published_at) to fetch unpublished rows efficiently

### Notes
- Avoid large composite indexes on `entries` that slow writes.
- If `external_ref` is frequently queried, index `(tenant_id, external_ref)` on `transactions_min`.

## MongoDB (projection store)

### Collection: transactions
Document identity: unique (tenantId, txId)

Indexes:
- UNIQUE: `{ tenantId: 1, txId: 1 }`
- For time paging: `{ tenantId: 1, occurredAt: -1, txId: -1 }`
- For account history lookups:
  - `{ tenantId: 1, "entries.accountId": 1, occurredAt: -1 }`
- Optional for metadata search:
  - `{ tenantId: 1, "metadata.reason": 1, occurredAt: -1 }` (only if heavily used)

### Avoid
- too many metadata indexes (index explosion)
- wildcard indexes on large metadata unless carefully constrained

### Idempotent upsert patterns
- Use `updateOne(..., upsert=true)` with `$setOnInsert` for immutable fields.
