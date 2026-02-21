# Runbook: Outbox Lag

## Symptom
- `ledger_outbox_lag_seconds` increases steadily
- Kafka consumer lag increases

## Likely causes
- Outbox publisher down or throttled
- Kafka broker issues
- Projection worker slow (Mongo indexes, IO)
- Mongo unavailable

## Steps
1) Check outbox backlog in Postgres (unpublished rows)
2) Check Kafka broker health and topic partitions
3) Scale projection workers (HPA) if lag is consumer-side
4) Inspect Mongo write latency and slow queries
5) If DLQ is growing: examine poison messages

## Mitigation
- Temporarily increase worker replicas
- Reduce batch sizes if Mongo is overloaded
- Pause non-essential consumers
