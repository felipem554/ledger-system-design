# k6 Load Tests

These cover the **HTTP** surface (reads + the HTTP write path). For the
**Kafka command-stream** write load test, see the [parent README](../README.md)
(`./gradlew kafkaLoadTest`). All scenarios below are trimmed to ≤ 2 minutes.

## Prerequisites
- [k6 installed](https://grafana.com/docs/k6/latest/set-up/install-k6/)
- Ledger API running (either locally or via docker-compose)

Accounts are **seeded automatically**: each scenario's `setup()` creates a fresh
pool of accounts via the API and shares the real (server-generated) account IDs
with every VU. No manual pre-seeding is required.

## Running

```bash
# Set environment variables
export BASE_URL=http://localhost:8080
export TENANT=t1

# Quick health check
k6 run k6/smoke.js

# Standard scenarios
k6 run k6/baseline.js          # Retail mix workload (reads + writes)
k6 run k6/hotspot.js            # Hot account contention
k6 run k6/spike.js              # Autoscaling / burst traffic
k6 run k6/batch.js              # Batch transaction ingestion
k6 run k6/idempotency.js        # Idempotency retry storm
k6 run k6/read_after_write.js   # Read-after-write consistency

# Heavy stress scenarios (new)
k6 run k6/heavy_idempotency.js  # Heavy idempotency stress (10K+ replay keys, concurrent dups)
k6 run k6/heavy_throughput.js   # Max throughput (2500+ TPS posts + batches + reads + reversals)
k6 run k6/endurance.js          # 30-minute soak test for leak detection
```

## Scenarios

| Script | Purpose | Peak RPS | Duration |
|--------|---------|----------|----------|
| `smoke.js` | Health check | 1 | 20s |
| `baseline.js` | Retail mix (60% reads, 20% list, 20% post) | 500 | 2m |
| `hotspot.js` | Hot account contention (10 accounts) | 800 | 2m |
| `spike.js` | Burst / autoscaling | 3000 | 2m |
| `batch.js` | Batch ingestion (50 txns/batch) | 1000 effective | 2m |
| `idempotency.js` | Replay storm (70% replays) | 500 | 2m |
| `read_after_write.js` | Consistency: post then read | 50 VUs | 2m |
| **`heavy_idempotency.js`** | **10K replay keys + concurrent dups + balance verification** | **1200** | **2m** |
| **`heavy_throughput.js`** | **Multi-scenario: post flood + batch + reads + reversals** | **2500+** | **2m** |
| **`endurance.js`** | **Soak test at 300 RPS (raise duration for a real soak)** | **300** | **2m** |

> Durations were trimmed to ≤ 2 minutes. The per-scenario stage timings live in
> each script's `options`; raise them for longer stress/soak runs.

## Thresholds

Heavy tests enforce:
- `http_req_failed < 1%`
- `p(95) < 300ms` for individual posts
- `p(95) < 2000ms` for batch operations
- `balance_correctness > 99%` (idempotency test)
- `error_rate < 1%` (endurance test)

## Account Seeding

Account IDs are server-generated UUIDs, so scenarios cannot reference fixed names
like `A1`. Instead, each script's `setup()` calls `seedAccounts(n)` (see
`common.js`), which creates `n` accounts in parallel via `http.batch` and returns
their IDs. k6 passes that data to every VU as the first argument of the scenario
function. Re-running a script seeds a fresh pool (old accounts are harmless).

## Environment variables

| Var | Default | Applies to | Purpose |
|-----|---------|-----------|---------|
| `BASE_URL` | `http://localhost:8080` | all | Target API base URL |
| `TENANT` | `t1` | all | `X-Tenant-Id` used for accounts + traffic |
| `ACCOUNTS` | per-script | baseline / spike / endurance / heavy_throughput / read_after_write | Size of the seeded account pool |
| `HOT_ACCOUNTS` | `10` | hotspot | Size of the contended hot-account pool |
| `BATCH_SIZE` | `50` | batch | Items per batch request |

```bash
# Smaller pool for a quick local run
ACCOUNTS=200 BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/baseline.js
```

> Tip: scenario durations are defined in each script's `options`. For a quick
> functional check without editing them, interrupt the run after a few seconds
> (`timeout -s INT 20 k6 run k6/baseline.js`) — k6 still prints a full summary.
