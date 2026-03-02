/**
 * Heavy Transaction Throughput Test
 *
 * Pushes the system to maximum throughput with a realistic transaction mix.
 * Tests sustained high-volume posting, reads, reversals, and batch operations.
 *
 * Run: BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/heavy_throughput.js
 */
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { postTx, getBalance, listTx } from './common.js';
import { uuidv4, randomItem } from './utils.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TENANT = __ENV.TENANT || 't1';

// Custom metrics
const postCounter = new Counter('throughput_posts');
const batchCounter = new Counter('throughput_batches');
const readCounter = new Counter('throughput_reads');
const reversalCounter = new Counter('throughput_reversals');
const postLatency = new Trend('post_latency_ms');
const batchLatency = new Trend('batch_latency_ms');
const correctnessRate = new Rate('throughput_correctness');

export const options = {
  scenarios: {
    // High-volume single transaction posting
    post_flood: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 500,
      maxVUs: 5000,
      stages: [
        { target: 500, duration: '2m' },
        { target: 1500, duration: '3m' },
        { target: 2500, duration: '5m' },
        { target: 2500, duration: '5m' },
        { target: 1000, duration: '3m' },
        { target: 200, duration: '2m' },
      ],
      exec: 'postFlood',
    },
    // Batch ingestion (50 txns per batch)
    batch_ingestion: {
      executor: 'constant-arrival-rate',
      rate: 30,
      timeUnit: '1s',
      duration: '18m',
      preAllocatedVUs: 100,
      maxVUs: 500,
      startTime: '1m',
      exec: 'batchIngestion',
    },
    // Mixed read workload (balance + list)
    read_mix: {
      executor: 'ramping-arrival-rate',
      startRate: 200,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      stages: [
        { target: 500, duration: '2m' },
        { target: 1500, duration: '5m' },
        { target: 2000, duration: '5m' },
        { target: 1000, duration: '3m' },
        { target: 200, duration: '2m' },
      ],
      startTime: '30s',
      exec: 'readMix',
    },
    // Reversal flow
    reversal_flow: {
      executor: 'constant-arrival-rate',
      rate: 10,
      timeUnit: '1s',
      duration: '15m',
      preAllocatedVUs: 30,
      maxVUs: 100,
      startTime: '3m',
      exec: 'reversalFlow',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300', 'p(99)<800'],
    post_latency_ms: ['p(95)<200'],
    batch_latency_ms: ['p(95)<2000'],
    throughput_correctness: ['rate>0.99'],
  },
};

// 2000 accounts for spreading load
const accounts = Array.from({ length: 2000 }, (_, i) => `THR_A${i + 1}`);

// Track posted txIds for reversals
const postedTxIds = [];

export function postFlood() {
  const a1 = randomItem(accounts);
  let a2 = randomItem(accounts);
  while (a2 === a1) a2 = randomItem(accounts);

  const amount = Math.floor(Math.random() * 10000) + 1;
  const payload = {
    currency: 'EUR',
    entries: [
      { accountId: a1, direction: 'DEBIT', amountMinor: amount },
      { accountId: a2, direction: 'CREDIT', amountMinor: amount },
    ],
    metadata: { scenario: 'heavy_throughput', batch: false },
  };

  const start = Date.now();
  const res = postTx(payload, uuidv4());
  postLatency.add(Date.now() - start);
  postCounter.add(1);

  const ok = check(res, {
    'post success': (r) => r.status === 201 || r.status === 409,
  });
  correctnessRate.add(ok);

  // Store txId for reversal tests
  if (res.status === 201 && postedTxIds.length < 5000) {
    try {
      const body = res.json();
      if (body.txId) postedTxIds.push(body.txId);
    } catch (_) {}
  }
}

export function batchIngestion() {
  const items = [];
  for (let i = 0; i < 50; i++) {
    const a1 = randomItem(accounts);
    let a2 = randomItem(accounts);
    while (a2 === a1) a2 = randomItem(accounts);

    items.push({
      idempotencyKey: uuidv4(),
      transaction: {
        currency: 'EUR',
        entries: [
          { accountId: a1, direction: 'DEBIT', amountMinor: Math.floor(Math.random() * 1000) + 1 },
          { accountId: a2, direction: 'CREDIT', amountMinor: Math.floor(Math.random() * 1000) + 1 },
        ],
        metadata: { scenario: 'heavy_throughput_batch' },
      },
    });
    // Ensure balanced
    items[items.length - 1].transaction.entries[1].amountMinor = items[items.length - 1].transaction.entries[0].amountMinor;
  }

  const start = Date.now();
  const res = http.post(
    `${BASE_URL}/v1/transactions:batch`,
    JSON.stringify({ items }),
    {
      headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': TENANT },
    }
  );
  batchLatency.add(Date.now() - start);
  batchCounter.add(1);

  check(res, { 'batch ok': (r) => r.status === 200 });
}

export function readMix() {
  const r = Math.random();

  if (r < 0.6) {
    const acc = randomItem(accounts);
    const res = getBalance(acc);
    readCounter.add(1);
    check(res, { 'balance ok': (x) => x.status === 200 || x.status === 404 });
  } else if (r < 0.9) {
    const res = listTx(null);
    readCounter.add(1);
    check(res, { 'list ok': (x) => x.status === 200 });
  } else {
    // Health check
    const res = http.get(`${BASE_URL}/healthz`);
    check(res, { 'health ok': (x) => x.status === 200 });
  }
}

export function reversalFlow() {
  if (postedTxIds.length === 0) {
    sleep(1);
    return;
  }

  const txId = postedTxIds[Math.floor(Math.random() * postedTxIds.length)];
  const res = http.post(
    `${BASE_URL}/v1/transactions/${txId}:reverse`,
    null,
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Tenant-Id': TENANT,
        'Idempotency-Key': uuidv4(),
      },
    }
  );
  reversalCounter.add(1);

  check(res, {
    'reversal ok': (r) => [201, 409].includes(r.status),
  });
}
