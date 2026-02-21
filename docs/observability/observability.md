# Observability

## Metrics
Expose Prometheus metrics at `/metrics` using Micrometer.

Required metric families:
- `http_server_requests_seconds_bucket`
- `http_server_in_flight_requests`
- `ledger_postings_total{result}`
- `ledger_posting_latency_seconds_bucket`
- `ledger_idempotency_hits_total`
- `ledger_outbox_lag_seconds`
- `kafka_consumer_lag` (exporter or app metric)

## Logging
- JSON structured logs
- include: trace_id, tenant_id, tx_id (when available)
- sample INFO logs on hot paths

## Tracing
OpenTelemetry with 1–5% sampling, always sample errors.
