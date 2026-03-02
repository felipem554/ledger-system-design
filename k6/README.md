# k6 Load Tests

## Prerequisites
- [k6 installed](https://grafana.com/docs/k6/latest/set-up/install-k6/)
- Ledger API running (either locally or via docker-compose)
- Accounts pre-seeded for the tenant under test

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
| `baseline.js` | Retail mix (60% reads, 20% list, 20% post) | 500 | 23m |
| `hotspot.js` | Hot account contention (10 accounts) | 800 | 22m |
| `spike.js` | Burst / autoscaling | 3000 | 10m |
| `batch.js` | Batch ingestion (50 txns/batch) | 1000 effective | 10m |
| `idempotency.js` | Replay storm (70% replays) | 500 | 8m |
| `read_after_write.js` | Consistency: post then read | 50 VUs | 10m |
| **`heavy_idempotency.js`** | **10K replay keys + concurrent dups + balance verification** | **1200** | **15m** |
| **`heavy_throughput.js`** | **Multi-scenario: post flood + batch + reads + reversals** | **2500+** | **20m** |
| **`endurance.js`** | **30-min soak test at 300 RPS** | **300** | **30m** |

## Thresholds

Heavy tests enforce:
- `http_req_failed < 1%`
- `p(95) < 300ms` for individual posts
- `p(95) < 2000ms` for batch operations
- `balance_correctness > 99%` (idempotency test)
- `error_rate < 1%` (endurance test)

## Account Seeding

Before running load tests, seed accounts via the API:

```bash
# Create test accounts for throughput tests
for i in $(seq 1 2000); do
  curl -s -X POST $BASE_URL/v1/accounts \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: $TENANT" \
    -d "{\"name\":\"THR_A$i\",\"type\":\"ASSET\",\"currency\":\"EUR\"}" > /dev/null
done

# Create idempotency test accounts
for acc in IDEM_A1 IDEM_A2; do
  curl -s -X POST $BASE_URL/v1/accounts \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: $TENANT" \
    -d "{\"name\":\"$acc\",\"type\":\"ASSET\",\"currency\":\"EUR\"}" > /dev/null
done
```
