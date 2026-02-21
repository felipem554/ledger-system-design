# k6 Load Tests

Run with:
- `BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/baseline.js`

Scenarios:
- smoke.js
- baseline.js (retail mix)
- hotspot.js (hot accounts contention)
- spike.js (autoscaling / burst)
- batch.js (batch ingestion)
- idempotency.js (retry storm)
- read_after_write.js (consistency)
