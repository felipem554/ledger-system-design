# Integration Tests (Testcontainers)

`ledger-api` runs real dependencies for integration tests via Testcontainers
(1.20.x). Containers start once and are shared across all IT classes
(`BaseIntegrationTest` → `TestcontainersConfiguration`); only a Docker daemon is
required.

| Dependency | Image |
|------------|-------|
| PostgreSQL | `postgres:16` |
| MongoDB | `mongo:7` |
| Kafka | `confluentinc/cp-kafka:7.6.1` (dev compose uses `apache/kafka:3.8.1`; functionally equivalent) |

## Goals
- Validate correctness invariants (double-entry, idempotency, reversals)
- Validate persistence (account_state, entries)
- Validate outbox + Kafka publish + Mongo projection (end-to-end)
- Validate the Kafka command-ingestion path (routing, results, DLQ)

## Test pyramid in ledger-api

| Layer | Package | Gradle task |
|-------|---------|-------------|
| Unit (~70%) | `com.ledger.unit.*` | `./gradlew unitTest` |
| Integration (~20%) | `com.ledger.integration.*` | `./gradlew integrationTest` |
| E2E smoke (~10%) | `com.ledger.e2e.*` | `./gradlew e2eTest` (against a running stack) |

## Integration suites (implemented)
- `LedgerPostingIT` — balanced/unbalanced posting, balance accumulation, closed-account and currency-mismatch rejection
- `IdempotencyIT` — replay vs. 409 conflict, no double-count, concurrent keys
- `ReversalIT` — reverse, balance restored, no double reversal
- `BatchIT` — `POST /v1/transactions:batch`, replays, per-item rollback
- `AccountIT` — create/get/list (paginated, filtered)/close, zero balance
- `CommandIngestIT` — command topic → ledger → results topic (CREATED / REPLAYED / FAILED + DLQ), batch commands

Still open from the original plan:
- `OutboxToKafkaToMongoIT` — assert the projection lands in Mongo end to end
  (today covered only by unit tests `OutboxPublisherTest` / `ProjectionConsumerTest`)
- `PaginationIT` — cursor stability for `GET /v1/transactions`

## Key assertions
- No duplicates for same Idempotency-Key
- Atomic balance update
- Projection idempotent upsert by (tenantId, txId)
- Cursor pagination stable

## Tips
- Poll for async outcomes (e.g. the command results topic) with a bounded
  timeout rather than fixed sleeps
- Use fixed tenant/account fixtures for deterministic tests

See `ledger-api/TESTING.md` for the full guide.
