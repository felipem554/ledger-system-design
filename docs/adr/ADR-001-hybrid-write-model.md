# ADR-001: Hybrid Write Model (Postgres SoT, Mongo Projection)

## Context
We require high-throughput ledger posting with strong correctness guarantees and scalable read queries.

## Decision
- Use PostgreSQL as the **source of truth** for: idempotency, balances (account_state), minimal journal, outbox.
- Use Kafka for durable event distribution.
- Use MongoDB as the **projection store** for transaction history and flexible queries.

## Consequences
+ Strong ACID correctness on write path
+ Read scaling independent from write store
+ Replay/rebuild of read models from Kafka
- History views are eventually consistent (bounded by outbox lag)
