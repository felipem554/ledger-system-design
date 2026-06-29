# Kafka Command Ingestion — Implementation Plan

Plan to make the ledger **consume transaction commands from a Kafka stream** and
execute them as ledger operations, plus migrate load testing from HTTP to Kafka.
This is an *inbound* (write-driving) path and is distinct from the existing
*outbound* eventing path documented in [`kafka.md`](./kafka.md).

> Status: planning only — no code in this document.

---

## 1. Goal & scope

Today the only way to drive a write into the ledger is an HTTP request
(`POST /v1/transactions`, `:batch`, `:reverse`). The k6 load suite
(`ledger-load-tests/`) hammers those endpoints over HTTP.

We want the application to also **consume commands from a Kafka topic** and
perform the same operations, so that:

1. Producers can enqueue transactions asynchronously (decoupled, buffered,
   replayable) instead of making synchronous HTTP calls.
2. Load testing exercises the Kafka ingestion path at scale rather than (or in
   addition to) HTTP.

**In scope:** an inbound command consumer, its config/topics, message contract,
idempotency/ordering/error semantics, observability, tests, and the load-test
migration.

**Out of scope (this phase):** removing the HTTP API (it stays — it is the
synchronous/interactive surface), and changing the outbound projection pipeline.

### Why this is *not* the existing consumer

The current `ProjectionConsumer` consumes our *own* emitted events
(`ledger.transactions.v1`) to build the Mongo read model. It is read-side and
idempotent-by-upsert. The new consumer is **write-side**: it accepts external
*commands* and invokes `TransactionService.postTransaction` /
`reverseTransaction`. The two must not share a consumer group, topic, or error
policy.

---

## 2. Current state (what already exists)

Relevant building blocks we will reuse as-is:

| Component | File | Reused for |
|-----------|------|------------|
| Write logic | `service/TransactionService.kt` (`postTransaction`, `reverseTransaction`) | The command handler calls these directly |
| Batch orchestration | `service/BatchTransactionService.kt` | Batch command type |
| Idempotency | `service/IdempotencyService.kt` (per `tenantId` + `Idempotency-Key`) | Command-level dedup |
| Request DTOs | `api/dto/Requests.kt` (`TransactionRequest`, `BatchRequest`) | Command payload body |
| Kafka wiring | `config/KafkaConfig.kt` | Template for a **second**, separate consumer factory |
| Metrics | `observability/LedgerMetrics.kt` | New ingestion metrics |
| Local infra | `docker-compose-test.yml` (Kafka `apache/kafka:3.8.1`, ext listener `localhost:19092`) | Local + load env |

Key facts that shape the design:

- HTTP carries two things the service needs as arguments that are **not** in the
  request body: `X-Tenant-Id` and `Idempotency-Key` headers. A Kafka command
  must carry both explicitly (envelope or Kafka headers).
- `KafkaConfig` defines an **explicit** `ConsumerFactory` bound to a single
  `group.id` from `spring.kafka.consumer.group-id`. We cannot reuse that bean for
  a second group; we need a dedicated factory (see §6 caveat in `kafka.md`).
- The HTTP path returns `201`/`409`/`422` synchronously. Kafka ingestion is
  fire-and-forget — success/failure must be surfaced via offsets, a DLQ, metrics,
  and (optionally) a result topic.

---

## 3. Target architecture

```
                         producer (load gen / upstream service)
                                        │ produce command
                                        ▼
                    Kafka topic  ledger.commands.transactions.v1
                       (key = tenantId[:bucket], N partitions)
                                        │ consume (group ledger-command-ingest-v1)
                                        ▼
                       TransactionCommandConsumer (@KafkaListener)
                                        │ deserialize + validate envelope
                                        ▼
                       CommandHandler  ──► TransactionService.postTransaction
                                        ├► TransactionService.reverseTransaction
                                        └► BatchTransactionService.postBatch
                                        │
            ┌───────────────────────────┼───────────────────────────┐
            ▼ (permanent error)         ▼ (success)                  ▼ (optional)
   ledger.commands.dlq.v1     existing outbox → ledger.transactions.v1   ledger.commands.results.v1
                                        │
                                        ▼  (unchanged outbound path)
                              OutboxPublisher → ProjectionConsumer → Mongo
```

The command consumer is just an **alternate front door** to the same
`TransactionService`. Everything downstream (outbox, projection, Mongo) is
unchanged: a command-driven post still emits `TransactionPosted` exactly like an
HTTP-driven post.

---

## 4. Command message contract

A single envelope on one topic, discriminated by `commandType`. Values are JSON
strings (matches existing `StringSerializer`/`StringDeserializer` setup).

```json
{
  "schemaVersion": 1,
  "commandId": "uuid",                       // producer-generated; dedup/trace
  "commandType": "PostTransaction | ReverseTransaction | PostBatch",
  "tenantId": "t1",                          // replaces X-Tenant-Id header
  "idempotencyKey": "order_123",             // replaces Idempotency-Key header
  "issuedAt": "2026-06-29T10:00:00Z",
  "payload": { /* depends on commandType */ }
}
```

Payload per type:

- **PostTransaction** → a `TransactionRequest` body (currency, entries[], externalRef, metadata).
- **ReverseTransaction** → `{ "txId": "uuid" }`.
- **PostBatch** → a `BatchRequest` body (`items[]`, each with its own `idempotencyKey`).

**Message key** = `tenantId` (or `tenantId:{bucket}` using the same
`partition-buckets` hashing as the outbox, for spread). This guarantees
per-tenant ordering on a partition. Tenant + idempotencyKey are duplicated into
Kafka record headers as well, so a consumer/DLQ inspector can route without
parsing the body.

**Compatibility note:** `schemaVersion` is mandatory; unknown future versions are
routed to DLQ rather than processed. Keep the envelope additive-only.

---

## 5. Component design (new code, later phase)

New files (proposed, mirrors existing package layout):

| New file | Responsibility |
|----------|----------------|
| `config/CommandKafkaConfig.kt` | A **separate** `ConsumerFactory` + `ConcurrentKafkaListenerContainerFactory` bound to group `ledger-command-ingest-v1`, manual acks, explicit fetch tuning, a `DefaultErrorHandler` with backoff + DLQ recoverer. |
| `ingest/TransactionCommandConsumer.kt` | `@KafkaListener` on the command topic using the command container factory. Deserializes the envelope, populates MDC (`tenantId`, `commandId`), delegates to the handler, acks on terminal outcome. |
| `ingest/CommandHandler.kt` | Maps `commandType` → existing service calls. Translates exceptions into retry-vs-DLQ decisions. |
| `ingest/dto/Command.kt` | Envelope + payload DTOs and a Jackson polymorphic mapping on `commandType`. |
| `ingest/CommandDlqProducer.kt` (or reuse pattern from `ProjectionConsumer.sendToDlq`) | Wraps original record + error + timestamp, produces to `ledger.commands.dlq.v1`. |

Design decisions to bake in:

- **Reuse, don't reimplement.** The handler calls `TransactionService` /
  `BatchTransactionService` unchanged — all balance/account/idempotency rules
  already live there. The consumer adds *only* transport concerns.
- **Separate factory, separate group.** Do not touch the projection consumer.
  Set `enable.auto.commit=false`, `AckMode.MANUAL`, and copy the YAML fetch
  tuning (`fetch.min.bytes`, `fetch.max.wait.ms`, `max.partition.fetch.bytes`)
  *into the factory* — recall (`kafka.md` caveat) that an explicit factory
  ignores `spring.kafka.consumer.properties.*`.
- **Concurrency = partitions.** Set listener `concurrency` to ≤ partition count
  for the command topic. This is the primary throughput knob.

---

## 6. Idempotency, ordering & delivery semantics

- **Idempotency:** unchanged — `IdempotencyService.checkAndReserve(tenantId,
  idempotencyKey, body)` already dedups. A redelivered command (at-least-once)
  with the same `(tenantId, idempotencyKey)` replays cleanly and is acked. The
  envelope `commandId` is for tracing/observability, not the dedup key.
- **Delivery:** consumer is **at-least-once** (manual ack after the DB
  transaction commits). Combined with service-level idempotency this is
  **effectively-once** for ledger state.
- **Ordering:** per-tenant ordering preserved by keying on `tenantId`. Posts and
  the reverse of a given txId should share a key so a reverse cannot be processed
  before its post on a different partition — keying by `tenantId` satisfies this
  (both land on the same partition). Cross-tenant ordering is irrelevant.
- **Poison messages:** malformed JSON / unknown `commandType` / unknown
  `schemaVersion` → straight to DLQ, then ack (never block the partition).

---

## 7. Error handling & DLQ (differs from projection consumer)

The projection consumer retries everything 3× then DLQs. The command consumer
must distinguish **permanent** from **transient** failures, because a permanent
business error retried 3× just wastes work:

| Failure | Examples | Action |
|---------|----------|--------|
| Permanent / business | `UnbalancedTransactionException`, `AccountNotFoundException`, `AccountClosedException`, currency mismatch, validation, malformed envelope | **No retry** → DLQ immediately, ack |
| Transient / infra | `ConcurrencyConflictException` (optimistic lock), DB connection failure, timeouts | **Retry with backoff** (e.g. `DefaultErrorHandler` + `ExponentialBackOff`, capped), then DLQ |
| Idempotency replay | `IdempotencyReplayException` | **Success** — ack, optionally emit a `REPLAYED` result |

Implementation: a `DefaultErrorHandler` on the container factory with the
business exceptions added via `addNotRetryableExceptions(...)` and a
`DeadLetterPublishingRecoverer` (or the manual `sendToDlq` pattern already in
`ProjectionConsumer`). DLQ topic: `ledger.commands.dlq.v1`.

**DLQ replay** remains a manual/ops task (same gap as the existing DLQ) — call
it out but defer tooling.

---

## 8. Configuration & topics

New `application.yml` keys (under `ledger.kafka`):

```yaml
ledger:
  kafka:
    topic:
      transactions: ledger.transactions.v1          # existing
      dlq: ledger.outbox.dlq.v1                      # existing
      commands: ledger.commands.transactions.v1      # NEW inbound
      commands-dlq: ledger.commands.dlq.v1           # NEW
      commands-results: ledger.commands.results.v1   # NEW (optional, §9)
    command-ingest:
      group-id: ledger-command-ingest-v1
      concurrency: 4
      enabled: ${COMMAND_INGEST_ENABLED:false}       # feature flag for rollout
```

| Topic | Partitions (dev/load) | Notes |
|-------|----------------------|-------|
| `ledger.commands.transactions.v1` | match throughput target (e.g. 12–32) | keyed by tenantId |
| `ledger.commands.dlq.v1` | small (e.g. 4) | parked failures |
| `ledger.commands.results.v1` | optional | async acks for load harness |

`docker-compose-test.yml` has `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`, so local
runs auto-create. For load/staging, create the command topic explicitly with the
chosen partition count (auto-create defaults to `KAFKA_NUM_PARTITIONS: 4`, too
few for high throughput).

**Feature flag:** gate the `@KafkaListener` with
`@ConditionalOnProperty(ledger.kafka.command-ingest.enabled)` so the consumer
ships dormant and is enabled per environment.

---

## 9. Observability

Since ingestion is async, **lag and outcome counters are the success signal**
(there is no HTTP status to assert):

- **Consumer lag** on `ledger-command-ingest-v1` (Kafka `records-lag-max`,
  scraped via Micrometer / `management` Prometheus endpoint already exposed).
- New `LedgerMetrics` counters: commands received / succeeded / replayed /
  DLQ'd, partitioned by `commandType`; ingest end-to-end latency
  (`issuedAt` → commit).
- DLQ depth on `ledger.commands.dlq.v1`.
- Reuse existing MDC logging pattern (`tenantId`, `txId`); add `commandId`.

**Optional result topic** (`ledger.commands.results.v1`): emit `{commandId,
status, txId, error}` so an async load harness can correlate outcomes without
reading the DB. Recommended for load testing (§10) but optional for production.

---

## 10. Testing strategy

Layered, reusing the existing Testcontainers setup
(`integration/TestcontainersConfiguration.kt`, `BaseIntegrationTest.kt`).

1. **Unit** (`unit/ingest/CommandHandlerTest.kt`): envelope parsing, type
   routing, exception → retry/DLQ classification, with `TransactionService`
   mocked. Mirrors existing `BatchTransactionServiceTest` style.
2. **Unit** consumer deserialization: malformed JSON / unknown type / bad
   schemaVersion → DLQ path.
3. **Integration** (`integration/CommandIngestIT.kt`, `@EmbeddedKafka` or the
   Testcontainers Kafka already used): produce a `PostTransaction` command →
   assert the transaction lands in Postgres, balances update, outbox row written,
   and (end-to-end) the projection appears in Mongo. This proves the command path
   is equivalent to the HTTP path.
4. **Integration** idempotency: produce the same command twice → exactly one
   transaction, second acked as replay.
5. **Integration** error routing: unbalanced command → DLQ, partition not
   blocked; transient failure → retried then DLQ.
6. **Equivalence test:** post the *same* logical transaction via HTTP and via
   Kafka → assert identical ledger state. Guards against drift between the two
   front doors.

Add a Kafka command smoke to the e2e (`e2e/LedgerSmokeTest.kt`) gating CI.

---

## 11. Load-testing migration (HTTP → Kafka)

k6 speaks HTTP, not the Kafka protocol natively. Three options:

| Option | How | Pros | Cons | Recommendation |
|--------|-----|------|------|----------------|
| **A. xk6-kafka** | Build a custom k6 binary with the `xk6-kafka` extension; scripts produce JSON commands to the topic | Keeps k6 ergonomics, thresholds, staging stages; reuses `transfer()` payload builders from `common.js` | Requires a custom k6 build in CI/Docker; assertions become async (no per-request status) | **Primary** |
| **B. kafka-producer-perf-test** | Pre-generate a command file, replay with the bundled Kafka perf tool | Zero new code, max raw produce throughput | Can't build realistic per-tenant/per-account payloads or mixed workloads easily | Sanity/throughput-ceiling only |
| **C. JVM producer harness** | Small Kotlin/Java producer in `ledger-load-tests/` | Full control, reuse DTOs | New harness to maintain, separate from k6 | Fallback if A is painful |

**Recommended approach (Option A):**

1. Add an `xk6-kafka`-based build (Dockerfile / Make target) under
   `ledger-load-tests/`.
2. New `common-kafka.js`: a `produceCommand(envelope)` helper that wraps a
   command around the existing `transfer(a1, a2, amount)` payload, sets key =
   `tenantId`, and produces to `ledger.commands.transactions.v1`.
3. Port scenarios. Throughput-style scripts (`baseline.js`,
   `heavy_throughput.js`, `spike.js`, `batch.js`, `endurance.js`) become
   produce-only loops measuring **produce rate** and, via a results-topic
   consumer or lag scrape, **end-to-end processing rate**.
4. Read-heavy / consistency scripts (`read_after_write.js`, balance checks) keep
   their HTTP reads — only the *write* leg moves to Kafka, since the query API is
   still HTTP.
5. **Seeding stays HTTP.** `seedAccounts()` (account creation) has no command
   equivalent in scope; keep `setup()` creating accounts over HTTP, then drive
   transactions via Kafka.
6. **Success criteria shift:** HTTP asserted `status === 201`. Kafka asserts
   *produce* success + a target on consumer lag draining to ~0 within an SLA, and
   DLQ count == 0. Wire these as k6 thresholds against scraped metrics, or via
   the optional results topic (§9).

Keep the HTTP scenarios as a baseline so we can compare HTTP vs Kafka ingestion
throughput on identical hardware.

---

## 12. Phased rollout

1. **Phase 0 — contract & infra.** Finalize the envelope (§4), add topic configs,
   add command topic to compose + staging. No consumer yet.
2. **Phase 1 — consumer (flag off).** `CommandKafkaConfig`, consumer, handler,
   DLQ, metrics. Ship with `command-ingest.enabled=false`. Unit + integration
   tests green.
3. **Phase 2 — enable in test/staging.** Flip the flag in non-prod. Run the
   equivalence test (§10.6) and a small Kafka load run.
4. **Phase 3 — load-test migration.** Land Option A; run baseline & heavy
   scenarios over Kafka; capture results in `ledger-load-tests/baselines/`.
5. **Phase 4 — production enablement.** Enable behind the flag; monitor lag, DLQ,
   ingest latency; tune partitions/concurrency.

This sequencing aligns with the roadmap's "next phase = Grafana/Prometheus + rate
limiting" — ingest lag/DLQ dashboards fold into that observability work, and
Kafka buffering is a natural complement to rate limiting (backpressure instead of
rejection).

---

## 13. Open decisions

1. **Result topic (§9):** ship `ledger.commands.results.v1` now (better load-test
   correlation, more code) or rely on lag + DB assertions (simpler)?
   *Recommendation: add it — async load testing needs outcome correlation.*
2. **Load-gen tool (§11):** confirm Option A (xk6-kafka) vs Option C (JVM
   harness). *Recommendation: A.*
3. **Partition count** for the command topic at the target throughput (drives
   consumer `concurrency`).
4. **Retain HTTP write path** indefinitely, or deprecate once Kafka ingestion is
   proven? *Recommendation: keep HTTP — it's the synchronous/interactive surface.*
5. **Multi-tenant fairness:** keying purely by `tenantId` can hot-spot one
   partition for a noisy tenant. Revisit `tenantId:{bucket}` if a single tenant
   dominates load.
```
