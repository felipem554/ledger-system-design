# Capacity Planning & Sizing (Guidelines)

These are first-pass formulas to guide sizing. Validate with k6 and production telemetry.

## Core variables
- T = transactions per second (tx/s)
- E = average entries per transaction
- W = write ops per tx in Postgres (approx)
- P = Kafka partitions
- C = consumer replicas (<= P)

## Postgres write pressure estimate
For each tx we do roughly:
- 1 insert transactions_min
- E inserts entries
- E account_state updates (or fewer if you dedupe accounts per tx)
- 1 insert idempotency
- 1 insert outbox

So:
W ≈ 3 + (E inserts) + (E updates)
W ≈ 3 + 2E

Write ops/sec ≈ T * (3 + 2E)

Example:
T=2000 tx/s, E=4 => ops/sec ≈ 2000*(3+8)=22,000 row ops/sec

## Kafka partitions
Goal: allow parallelism with headroom.
Rule of thumb:
P >= max(128, T / 50)
- If T=2000 => 2000/50=40 => choose 128
- If T=10,000 => 10,000/50=200 => choose 256

## Consumer replicas
C = min(P, desired parallelism)
Start with:
C ≈ min(P, ceil(T / 200))  (projection workers)
Then tune based on lag and Mongo write throughput.

## API pods
API pods depend on latency and DB pool capacity.
Rule of thumb start:
API replicas ≈ ceil(T / 200)  (with good DB latency)
Then tune with HPA based on CPU + p95 latency.

## Bottlenecks to watch
- Postgres connection pool wait time
- Row lock contention on hot accounts
- Outbox lag growth
- Mongo slow writes / index pressure
