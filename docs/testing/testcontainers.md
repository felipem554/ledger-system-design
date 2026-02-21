# Integration Tests (Testcontainers)

This project uses Testcontainers to run real dependencies for integration tests:
- PostgreSQL
- MongoDB
- Kafka (optional; can be mocked for pure write-path tests)

## Goals
- Validate correctness invariants (double-entry, idempotency, reversals)
- Validate persistence (account_state, entries)
- Validate outbox + Kafka publish + Mongo projection (end-to-end)

## Suggested structure (JUnit5 + Kotlin)
- `LedgerPostingIT`
- `IdempotencyIT`
- `ReversalIT`
- `PaginationIT`
- `OutboxToKafkaToMongoIT`

## Key assertions
- No duplicates for same Idempotency-Key
- Atomic balance update
- Projection idempotent upsert by (tenantId, txId)
- Cursor pagination stable

## Tips
- Use Awaitility to wait for async projection completion
- Use fixed tenant/account fixtures for deterministic tests
