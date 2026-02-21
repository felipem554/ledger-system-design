# Ledger System Documentation Bundle (v2)

This repository contains system design documentation and operational blueprints for a
high-throughput distributed ledger using:
- NGINX Ingress (reverse proxy)
- Kotlin service (API + workers)
- PostgreSQL as source of truth (balances, idempotency, outbox)
- Kafka event log
- MongoDB projection store (history/read model)
- Prometheus + Grafana observability
- k6 load testing suite
- Helm chart for Kubernetes deployment
- Docker Compose dev environment

## Quick start (local)
1. `cd docker`
2. `docker compose up -d`
3. Run the Ledger API locally on :8080 (implementation project)
4. Import dashboards from `docker/grafana/dashboards`

## Load tests
`BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/baseline.js`

## Capacity calculator
`scripts/capacity_calc.py --tps 2000 --entries 4`
