# ADR-003: Kafka Partition Key Strategy

## Context
We need high throughput and deterministic routing while accepting that projections are eventually consistent.

## Decision
Partition key = `tenantId + ":" + bucket`, where `bucket = murmur3(txId) % 128`.
This provides deterministic distribution and parallelism without hot partitions.

## Consequences
+ Scales consumers horizontally
+ Avoids per-tenant bottleneck
- No total ordering per tenant (not required because Postgres is SoT)
