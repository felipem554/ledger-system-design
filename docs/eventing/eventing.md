# Eventing (Kafka)

## Topics
- `ledger.transactions.v1` — ledger business events (posted/reversed)
- `ledger.outbox.dlq.v1` — dead-letter queue for poison messages

## Delivery semantics
- At-least-once delivery
- Consumers MUST be idempotent

## Partitioning strategy (v1)
**Partition key = `tenantId + ":" + bucket`**

- `bucket = murmur3(txId) % 128`
- Producer uses the same formula on retries to guarantee stable partition routing.
- Ordering is guaranteed only within a partition. This is acceptable because PostgreSQL is the source of truth.

## Consumer groups
- `ledger-projection-mongo-v1` — writes/upserts Mongo projections
- (future) `ledger-audit-v1` — compliance/audit sink
- (future) `ledger-analytics-v1` — analytics/reporting

## Offset commit rule
Commit offsets ONLY after the consumer has:
1) processed the event successfully AND
2) performed the idempotent write (Mongo upsert) AND
3) recorded any local processing markers (if used).

## Idempotent upsert rule
Mongo collection `transactions` has a **unique** compound index on `(tenantId, txId)`.
Consumer performs `updateOne(filter, update, upsert=true)` so replay is safe.

## Poison messages
After N retries, produce the original message + error context to `ledger.outbox.dlq.v1`.
Alert on DLQ growth.
