# Kafka: Eventing & Projections

How the ledger uses Kafka to propagate committed writes from the Postgres
source-of-truth to the MongoDB read model, and how it is configured.

## Role in the architecture

Postgres is the system of record for balances and transactions. Kafka is the
**asynchronous event backbone** that fans committed transactions out to the read
side. MongoDB holds the query-optimized **projection** that serves transaction
reads (`GET /v1/transactions`, `GET /v1/transactions/{txId}`).

```
                 (single DB transaction)
POST /v1/transactions ─► Postgres: transactions_min + entries + account_state
                                   + outbox row              │
                                                             ▼
                              OutboxPublisher (@Scheduled poll)
                                                             │  produce
                                                             ▼
                                   Kafka topic  ledger.transactions.v1
                                                             │  consume (group)
                                                             ▼
                              ProjectionConsumer (@KafkaListener)
                                                             │  upsert
                                                             ▼
                                   MongoDB  transactions projection
```

The write path never calls Kafka directly. It relies on the **transactional
outbox** pattern so that "the transaction is committed" and "an event will be
published" cannot diverge.

## Transactional outbox (producer side)

`TransactionService.postTransaction` / `reverseTransaction` run inside a single
`@Transactional` unit that writes the ledger rows **and** an `outbox` row
(`OutboxRepository.insert`) atomically. The event is therefore guaranteed to
exist iff the transaction committed.

`OutboxPublisher` (`src/main/kotlin/com/ledger/eventing/OutboxPublisher.kt`) then
drains the outbox on a fixed schedule (enabled by `@EnableScheduling` on
`LedgerApplication`):

1. `@Scheduled(fixedDelay = ledger.outbox.poll-interval-ms)` — default **200 ms**.
2. `OutboxRepository.fetchUnpublished(batchSize)` selects up to
   `ledger.outbox.batch-size` (default **100**) rows:
   ```sql
   SELECT * FROM outbox
   WHERE published_at IS NULL
   ORDER BY created_at ASC
   LIMIT ?
   FOR UPDATE SKIP LOCKED
   ```
   `FOR UPDATE SKIP LOCKED` lets multiple app instances poll concurrently
   without grabbing the same rows.
3. For each event, in order: derive the **partition key**, then
   `kafkaTemplate.send(topic, key, payload).get()` — the `.get()` makes the
   send **synchronous**, so the row is only marked published after the broker
   acknowledges.
4. Successfully-sent `event_id`s are collected; on the **first** send failure the
   loop `break`s (it does not skip ahead, preserving order).
5. `OutboxRepository.markPublished(ids)` sets `published_at = now()` for the
   acked events. Unsent events stay `published_at IS NULL` and are retried on the
   next poll.
6. `metrics.recordOutboxLag(events.size - publishedIds.size)` records how many
   fetched events were not published this cycle.

### Partition key

```
key = "{tenantId}:{bucket}"
bucket = abs(murmur3_32(txId)) % ledger.kafka.partition-buckets   // default 128
```

Keying by `tenantId` + a hash bucket of `txId` keeps all events for a given
transaction on the same partition (ordering for a `txId`), and spreads load
across up to `partition-buckets` keys per tenant. Note the bucket is derived from
`txId`, **not** `accountId` — events touching the same account but different
transactions may land on different partitions.

## Topics

| Purpose | Property | Default (prod profile) | Test profile |
|---------|----------|------------------------|--------------|
| Main transaction events | `ledger.kafka.topic.transactions` | `ledger.transactions.v1` | `ledger.transactions.test.v1` |
| Dead-letter queue | `ledger.kafka.topic.dlq` | `ledger.outbox.dlq.v1` | `ledger.outbox.dlq.test.v1` |
| Partition-key buckets | `ledger.kafka.partition-buckets` | `128` | `16` |

The dev compose stack creates the transactions topic with 128 partitions; the
lightweight test stack uses 4 and auto-creates topics.

## Event schema

Built by `TransactionService.buildEventPayload`. Values are JSON strings
(`StringSerializer`); the body is the `payload` column stored as `jsonb`.

```json
{
  "schemaVersion": 1,
  "eventId": "uuid",
  "eventType": "TransactionPosted | TransactionReversed",
  "tenantId": "t1",
  "txId": "uuid",
  "occurredAt": "2026-06-28T14:32:13.764840Z",
  "producer": { "service": "ledger-api", "version": "1.0.0" },
  "payload": {
    "currency": "EUR",
    "externalRef": "order_123",
    "status": "POSTED | REVERSED",
    "entries": [
      { "accountId": "uuid", "direction": "DEBIT | CREDIT", "amountMinor": 100 }
    ],
    "metadata": { }
  }
}
```

## Projection consumer

`ProjectionConsumer` (`src/main/kotlin/com/ledger/eventing/ProjectionConsumer.kt`)
is a `@KafkaListener` on the transactions topic, consumer group
`ledger-projection-mongo-v1`, using the custom `kafkaListenerContainerFactory`.

- **Manual acks.** The container factory uses `AckMode.MANUAL`; the listener calls
  `ack.acknowledge()` only after the Mongo write succeeds (or after the message
  is routed to the DLQ). Offsets are never auto-committed.
- **In-memory retry.** Each record is processed in a loop up to `maxRetries = 3`
  (immediate retries, no backoff). After 3 failures the record is sent to the DLQ
  and acked so the partition is not blocked.
- **Routing by event type** (`processEvent`):
  - `TransactionPosted` → `TransactionProjectionRepository.upsert(tx)` — a Mongo
    upsert keyed by `(tenantId, txId)`. Immutable fields use `setOnInsert`; only
    `status` uses `set`. This makes the projection **idempotent**: re-delivering
    the same event does not duplicate or mutate the record.
  - `TransactionReversed` → `updateStatus(tenantId, txId, REVERSED)`.
  - anything else → logged as an unknown event type.
- **DLQ payload** (`sendToDlq`) wraps the original topic/partition/offset/key and
  value plus the error and a timestamp, produced to `ledger.kafka.topic.dlq`
  under the original message key.

## Delivery & ordering guarantees

- **DB ↔ event atomicity:** guaranteed by the outbox row being written in the
  same transaction as the ledger rows.
- **Outbox → Kafka:** **at-least-once.** If the process dies after the broker ack
  but before `markPublished`, the event is re-sent next poll. The idempotent
  producer (`enable.idempotence=true`, `acks=all`) prevents broker-side
  duplicates within a producer session; cross-restart re-sends are still possible.
- **Kafka → Mongo:** **at-least-once**, made effectively idempotent by the
  `setOnInsert` upsert. Duplicate deliveries converge to the same projection
  state.
- **Ordering:** within a single publisher poll, sends are sequential and stop on
  first error, so per-key order is preserved. Per partition, Kafka preserves
  order and the consumer (`concurrency = 3`) processes one partition per thread.

## Configuration reference

### Producer (`application.yml` → `spring.kafka.producer`)

| Setting | Value | Why |
|---------|-------|-----|
| `acks` | `all` | Durability — wait for in-sync replicas |
| `enable.idempotence` | `true` | No duplicates within a producer session |
| `linger.ms` | `5` | Small batching window |
| `batch.size` | `65536` | Larger producer batches |
| `compression.type` | `snappy` | Reduce network/storage |
| `delivery.timeout.ms` | `120000` | Upper bound on a send (incl. retries) |
| `request.timeout.ms` | `30000` | Per-request timeout |
| key/value serializer | `StringSerializer` | Payloads are JSON strings |

### Consumer (`KafkaConfig.kt` + `application.yml` → `spring.kafka.consumer`)

The listener uses the **custom** `consumerFactory` bean in
`src/main/kotlin/com/ledger/config/KafkaConfig.kt`, which sets:

| Setting | Value |
|---------|-------|
| `group.id` | `ledger-projection-mongo-v1` |
| `enable.auto.commit` | `false` (manual acks) |
| `auto.offset.reset` | `earliest` |
| `isolation.level` | `read_committed` |
| `max.poll.records` | `500` |
| `session.timeout.ms` | `15000` |
| `heartbeat.interval.ms` | `3000` |
| `max.poll.interval.ms` | `300000` |
| key/value deserializer | `StringDeserializer` |
| container `ackMode` | `MANUAL` |
| container `concurrency` | `3` |

## Operational notes & caveats

These are worth knowing before tuning or extending the eventing path:

- **Synchronous publishing throughput.** The publisher awaits each send with
  `.get()`, so events are produced one-at-a-time per poll. With `acks=all` this
  bounds outbox drain throughput; the producer `batch.size`/`linger.ms` settings
  have limited effect because sends are not pipelined. Raising
  `ledger.outbox.batch-size` and/or running more app instances increases drain
  parallelism (pollers grab disjoint rows via `SKIP LOCKED`).
- **Cross-instance ordering.** A single poller preserves per-key order. With
  multiple instances polling concurrently, two pollers can grab different
  outbox rows sharing the same partition key and publish them out of `created_at`
  order. If strict per-key ordering across instances is required, this needs a
  single active publisher or per-key locking.
- **Custom consumer factory overrides YAML.** Because `KafkaConfig` defines an
  explicit `ConsumerFactory`, the `spring.kafka.consumer.properties.*` fetch
  tuning in `application.yml` (`fetch.min.bytes`, `fetch.max.wait.ms`,
  `max.partition.fetch.bytes`) is **not** applied to this listener — only the
  keys present in the `KafkaConfig` props map take effect. Add them to the bean
  if that tuning is intended.
- **No backoff on consumer retries.** The 3 retries are immediate; a transient
  Mongo outage lasting longer than three attempts routes the message to the DLQ.
  Consider a backoff/`DefaultErrorHandler` if transient downstream failures are
  expected.
- **DLQ has no consumer.** Messages are produced to the DLQ but nothing in the
  app reprocesses them; draining/replaying the DLQ is currently a manual/ops task.

## Local & test setup

- Dependencies: `docker compose -f docker-compose-test.yml up -d` (Postgres,
  Mongo, Kafka). The compose Kafka exposes its external listener on
  `localhost:19092`.
- Run the app pointing at that listener:
  `KAFKA_BOOTSTRAP=localhost:19092 ./gradlew bootRun`
  (the app default is `localhost:9092`).
- Integration tests start Kafka via Testcontainers
  (`confluentinc/cp-kafka:7.6.1`) and use the `test` topic names above.
