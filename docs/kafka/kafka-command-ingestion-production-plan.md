# Kafka Command Ingestion - Production Implementation Plan

> Mirrored from `ledger-api/docs/kafka-command-ingestion-production-plan.md`; the copy in ledger-api is canonical.

Reviewed production-ready plan for adding Kafka-driven transaction commands to
`ledger-api`. This document improves the existing command-ingestion proposal by
locking the remaining design decisions and breaking the implementation into
manual Jira-style tickets.

## Summary

The existing Kafka command-ingestion plan is directionally correct: it separates
inbound commands from outbound ledger events, reuses the existing write services,
keeps HTTP as a supported write path, and calls out DLQ, observability, and load
testing. Before implementation, v1 should be tightened in the following ways:

- Include `ledger.commands.results.v1` in v1 for async success/failure
  correlation.
- Use a dedicated command consumer group and listener factory; do not reuse
  projection consumer wiring.
- Make command idempotency deterministic by serializing the exact service request
  body before calling existing services.
- Define batch semantics explicitly: `PostBatch` completes with per-item
  results, not all-or-nothing.
- Use 32 partitions for production command topics, with local/test overrides.
- Treat reverse ordering carefully: commands with ordering dependencies must
  share an `orderingKey`; otherwise the system provides at-least-once processing
  plus idempotency, not global ordering.

## Key Interfaces

### Topics

Add one inbound command topic:

- Name: `ledger.commands.transactions.v1`
- Production/staging partitions: 32
- Message key: `{tenantId}:{bucket}`
- Bucket source: derive from `orderingKey` when present, otherwise
  `idempotencyKey`

Add one command DLQ topic:

- Name: `ledger.commands.dlq.v1`
- Payload includes original topic, partition, offset, key, value, headers, error
  class, error message, and `failedAt`

Add one command result topic:

- Name: `ledger.commands.results.v1`
- Message key: `commandId`
- Payload includes `schemaVersion`, `commandId`, `commandType`, `tenantId`,
  `status`, optional `txId`, optional `batchResults`, optional `error`, and
  `processedAt`

### Command Envelope

All commands are JSON values on `ledger.commands.transactions.v1`.

```json
{
  "schemaVersion": 1,
  "commandId": "uuid",
  "commandType": "PostTransaction | ReverseTransaction | PostBatch",
  "tenantId": "tenant-a",
  "idempotencyKey": "optional-for-PostBatch",
  "orderingKey": "optional-business-ordering-key",
  "issuedAt": "2026-06-30T10:00:00Z",
  "payload": {}
}
```

Rules:

- `PostTransaction` requires `idempotencyKey`; payload is `TransactionRequest`.
- `ReverseTransaction` requires `idempotencyKey`; payload is
  `{ "txId": "uuid" }`.
- `PostBatch` does not use envelope-level `idempotencyKey`; each item uses its
  own existing `BatchTransactionItem.idempotencyKey`.
- Unknown `schemaVersion` or `commandType` goes directly to DLQ and emits a
  failed result.
- `IdempotencyReplayException` is a successful terminal outcome and emits
  `REPLAYED`.

## Jira-Style Implementation Tickets

### LEDGER-KAFKA-001: Finalize Command Contract And Configuration

Implement config keys under `ledger.kafka.topic` and
`ledger.kafka.command-ingest`.

Acceptance criteria:

- `application.yml` defines command topic, command DLQ, result topic, group id,
  enabled flag, concurrency, retry settings, and partition bucket count.
- `application-test.yml` uses `.test.v1` topic names and lower concurrency.
- Documentation states the envelope, keying rule, result payload, and DLQ
  payload.
- Default listener flag is off: `COMMAND_INGEST_ENABLED=false`.

### LEDGER-KAFKA-002: Add Dedicated Command Kafka Wiring

Create a separate `CommandKafkaConfig`.

Acceptance criteria:

- New consumer factory uses group `ledger-command-ingest-v1`.
- Manual ack is enabled with `AckMode.MANUAL`.
- Auto commit is disabled.
- Fetch/poll settings are explicitly set in the factory, not only YAML.
- Listener concurrency defaults to 32-compatible production sizing but remains
  configurable.
- Projection consumer config remains unchanged.

### LEDGER-KAFKA-003: Add Command DTOs And Validation

Create command envelope DTOs and payload mapping.

Acceptance criteria:

- Jackson can deserialize all three command types.
- Jakarta validation covers tenant, commandId, issuedAt, idempotency
  requirements, and payload shape.
- Invalid JSON, invalid payloads, unknown type, and unknown schema version are
  classified as permanent failures.
- Serialization for service idempotency uses the same canonical ObjectMapper
  output style as existing HTTP controllers.

### LEDGER-KAFKA-004: Implement Command Handler

Add a handler that delegates only to existing services.

Acceptance criteria:

- `PostTransaction` calls `TransactionService.postTransaction`.
- `ReverseTransaction` calls `TransactionService.reverseTransaction`.
- `PostBatch` calls `BatchTransactionService.postBatch`.
- No ledger balance, account, transaction, or idempotency rules are duplicated in
  the handler.
- `IdempotencyReplayException` maps to `REPLAYED`.
- Business validation exceptions map to permanent failure.
- Infrastructure/concurrency exceptions map to retryable failure.

### LEDGER-KAFKA-005: Implement Consumer, DLQ, And Result Producer

Add the listener and terminal outcome publishing.

Acceptance criteria:

- Consumer is gated by `ledger.kafka.command-ingest.enabled`.
- MDC includes `tenantId`, `commandId`, and `txId` when available.
- Offsets are acknowledged only after successful handling, replay handling, or
  DLQ handoff.
- Permanent failures go directly to DLQ.
- Retryable failures use exponential backoff and then DLQ.
- Every terminal outcome emits a result event to `ledger.commands.results.v1`.

### LEDGER-KAFKA-006: Add Observability

Extend `LedgerMetrics`.

Acceptance criteria:

- Counters exist for received, succeeded, replayed, failed, retried, and DLQ'd
  commands.
- Metrics are tagged by `commandType` and result, with low-cardinality tags
  only.
- Timer records ingest latency from `issuedAt` to terminal processing.
- Logs include command lifecycle events without logging full financial payloads.
- Prometheus can scrape the new metrics through the existing actuator endpoint.

### LEDGER-KAFKA-007: Add Integration And Unit Tests

Cover the command path with the existing test style.

Acceptance criteria:

- Unit tests cover DTO parsing, validation, handler routing, and exception
  classification.
- Integration test produces `PostTransaction` and verifies Postgres write, outbox
  row, Kafka outbound event, and Mongo projection.
- Duplicate command test proves idempotent replay is acked and emits `REPLAYED`.
- Unbalanced transaction test proves DLQ and result failure.
- `PostBatch` test proves mixed item outcomes are returned in the result topic.
- Existing HTTP, outbox, and projection tests remain green.

### LEDGER-KAFKA-008: Add Local Topic Setup And Operational Docs

Make the path runnable locally and ready for staging.

Acceptance criteria:

- Compose/dev instructions describe creating command, DLQ, and result topics
  explicitly for non-auto-create environments.
- Production topic recommendation is 32 partitions, replication factor 3, and
  `min.insync.replicas=2`.
- Runbook documents DLQ inspection, replay policy, feature flag rollout, and
  rollback by disabling the listener.
- Docs clearly state HTTP remains supported and is not deprecated.

### LEDGER-KAFKA-009: Migrate Load Tests To Kafka Writes

Use `xk6-kafka` for Kafka write scenarios while keeping HTTP reads and account
seeding.

Acceptance criteria:

- Add an xk6-kafka build path under `ledger-load-tests`.
- Add `common-kafka.js` with command production helpers.
- Port throughput, spike, batch, and endurance write paths to Kafka.
- Keep account setup and read-after-write verification over HTTP.
- Load-test success requires produce success, result-topic success rate, DLQ
  count zero, and consumer lag draining within SLA.

## Test Plan

Run:

- `./gradlew unitTest`
- `./gradlew integrationTest`
- targeted Kafka command integration tests
- one local compose smoke with `COMMAND_INGEST_ENABLED=true`
- one k6 Kafka smoke after `LEDGER-KAFKA-009`

Acceptance scenarios:

- Valid post command creates exactly one ledger transaction.
- Duplicate post command does not create a second transaction.
- Invalid command does not block the partition.
- Batch command returns per-item status.
- Result topic lets a producer correlate every terminal command by `commandId`.

## Assumptions

- HTTP write APIs remain supported indefinitely.
- Result topic is included in v1.
- Production command topic starts with 32 partitions.
- Strict ordering is only guaranteed for commands sharing the same Kafka key;
  producers with ordering dependencies must set the same `orderingKey`.
- DLQ replay tooling is documented but not automated in this phase.
