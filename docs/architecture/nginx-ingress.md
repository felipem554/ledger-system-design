# NGINX Ingress (Reverse Proxy / Gateway)

## Why NGINX Ingress
- Single public entry point (clients never reach pods directly)
- TLS termination
- Request limits and timeouts
- Basic rate limiting
- Simple routing and versioning

## Ledger-specific policies
- Write endpoints require Idempotency-Key
- Avoid proxy retries for POST by default; if enabled, only for network errors AND only when Idempotency-Key is present
- Enforce max request body size (e.g., 256KB)
- Enforce max connections / in-flight to protect Postgres pools

## Recommended annotations (example)
- proxy-body-size: 256k
- proxy-read-timeout: 10s (reads)
- proxy-send-timeout: 10s
- rate limiting: per IP at ingress, per-tenant in app
