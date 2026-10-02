# Observability

## Metrics
Prometheus metrics are exposed at `/actuator/prometheus` via Micrometer
(common tag `application=ledger-api`).

Emitted by `ledger-api` today:
- `http_server_requests_seconds_*` — Spring MVC request timings
- JVM / process metrics (`jvm_*`, `process_*`, `hikaricp_*`)
- `ledger_postings_total{result}` — `result` = `success` | `concurrency_conflict`
- `ledger_posting_latency_seconds` — posting latency (p50/p95/p99 published)
- `ledger_idempotency_hits_total`
- `ledger_outbox_lag_seconds` — **note:** currently set from the number of
  unpublished outbox rows, not their age; treat it as a backlog gauge until it is
  changed to measure `now() - min(created_at)`
- `ledger_commands_total{type,result}` — command ingestion lifecycle;
  `result` = `received` | `succeeded` | `replayed` | `failed` | `retried` | `dlq`
- `ledger_command_ingest_latency_seconds` — producer `issuedAt` → terminal outcome

Not yet available (target state):
- `http_server_in_flight_requests` — not emitted by the app; don't build panels on it
- `kafka_consumer_lag` — needs a Kafka exporter (or app-side consumer metrics)

Dashboards: the service ships *Ledger — Overview* (`ledger-overview.json`) and
*Ledger — JVM & HTTP* (`jvm-runtime.json`), provisioned in the **Ledger** Grafana
folder by `ledger-api`'s and `ledger-infra`'s Docker stacks.

## Logging
- JSON structured logs (single-line console pattern)
- fields: `timestamp`, `level`, `logger`, `message`, `traceId`, `tenantId`, `txId`
  (MDC: `TenantFilter` sets `tenantId`/`traceId` per request, the posting path
  sets `txId`; the command consumer sets `tenantId`, `commandId`, `txId`)
- never log full financial payloads (the command consumer already avoids this)
- sample INFO logs on hot paths

## Tracing
Target: OpenTelemetry with 1–5% sampling, always sample errors. Not yet wired in
`ledger-api`: today `traceId` is taken from the `X-Trace-Id` request header (or
generated) and echoed back in the response, which gives log correlation but no
spans.

## Actuator exposure
`health`, `prometheus`, and `info` are exposed unauthenticated with
`health.show-details: always`. Locking these down is Phase 0 of the
[security plan](../security/security-plan.md).
