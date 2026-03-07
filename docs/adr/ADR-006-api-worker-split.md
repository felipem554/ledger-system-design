# ADR-006: API / Worker Process Split

## Status
Accepted

## Context

The ledger platform was running as a single monolithic process (`ledger-api`) that handled three distinct responsibilities:
1. **HTTP API** — serving REST endpoints for transactions, accounts, idempotency
2. **Outbox Publisher** — polling PostgreSQL for unpublished events and producing to Kafka
3. **Projection Consumer** — consuming from Kafka and writing to MongoDB

With a single process, Kafka adds architectural complexity without delivering its core benefit: **decoupled, independently scalable producers and consumers**. A slow MongoDB projection blocks the same JVM that serves user requests. A traffic spike competing for CPU starves the outbox publisher.

## Decision

Split the runtime into two deployment modes using the same JAR artifact, controlled by the `LEDGER_MODE` environment variable:

| Mode | Value | Active Components |
|------|-------|-------------------|
| **API** | `LEDGER_MODE=api` | HTTP controllers, tenant filter, health endpoints |
| **Worker** | `LEDGER_MODE=worker` | OutboxPublisher (`@Scheduled`), ProjectionConsumer (`@KafkaListener`), KafkaConfig |
| **All** (dev) | `LEDGER_MODE=all` | Everything (backward compatible, default) |

Component activation uses Spring's `@ConditionalOnProperty(name = ["ledger.mode"], havingValue = "worker", matchIfMissing = true)`.

### Deployment Topology

**Docker Compose (development):**
- 2x `ledger-api` instances (mode=api) behind NGINX load balancer
- 2x `ledger-worker` instances (mode=worker)
- NGINX on port 8080 round-robins to API instances

**Kubernetes (production):**
- API Deployment: 3–30 replicas, HPA on CPU utilization (70%)
- Worker Deployment: 2–20 replicas, HPA on CPU + Kafka consumer lag (`kafka_consumer_lag` metric)
- Service + Ingress route only to API pods (`component: api`)
- Separate PDBs for API (minAvailable: 2) and Worker (minAvailable: 1)

## Consequences

### Benefits
- **Independent scaling** — API scales on HTTP load, workers scale on event backlog
- **Fault isolation** — slow MongoDB projection doesn't impact API latency
- **Deployment independence** — deploy API hotfixes without triggering Kafka consumer rebalance
- **Outbox concurrency** — multiple workers use `FOR UPDATE SKIP LOCKED` to poll the outbox table in parallel without conflicts
- **Kafka consumer groups** — multiple worker instances join the same consumer group, distributing 128 partitions across workers
- **Resource optimization** — API gets more CPU/memory for request handling, workers get less but tuned for throughput

### Trade-offs
- **Single artifact** — simpler CI (one Docker image) but both modes carry unused code in classpath
- **Shared schema** — both modes connect to PostgreSQL; API writes, workers read the outbox. Flyway runs on first boot regardless of mode
- **Observability** — Prometheus now scrapes 4+ targets instead of 1; dashboards need `job` label filtering
- **Local dev complexity** — `docker compose up` now starts 6 app containers instead of 1 (mitigated by `LEDGER_MODE=all` default for standalone runs)

## Alternatives Considered

1. **Separate Gradle modules** — would produce two JARs with distinct classpaths. Rejected: adds build complexity and dependency management overhead for a codebase this size. Can be adopted later if the services diverge significantly.
2. **Spring profiles** — using `@Profile("worker")` instead of `@ConditionalOnProperty`. Rejected: profiles affect all beans and are harder to combine (e.g., `test` + `worker`). Property-based activation is more granular.
3. **Separate repositories** — full microservice split. Rejected: premature. The shared domain model and outbox pattern create tight coupling that would require a shared library. Single repo with runtime split provides 80% of the benefit with 20% of the cost.
