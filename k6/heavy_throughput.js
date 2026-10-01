/**
 * Heavy Transaction Throughput Test
 *
 * Pushes the system to maximum throughput with a realistic transaction mix.
 * Tests sustained high-volume posting, reads, reversals, and batch operations.
 *
 * Run: BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/heavy_throughput.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  BASE_URL, postTx, postBatch, reverseTx, getBalance, listTx,
  seedAccounts, pickPair, transfer, randomItem, uuidv4,
} from './common.js';

const ACCOUNTS = Number(__ENV.ACCOUNTS || 2000);

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
        { target: 500, duration: '20s' },
        { target: 1500, duration: '20s' },
        { target: 2500, duration: '30s' },
        { target: 2500, duration: '20s' },
        { target: 1000, duration: '20s' },
        { target: 200, duration: '10s' },
      ],
      exec: 'postFlood',
    },
    // Batch ingestion (50 txns per batch)
    batch_ingestion: {
      executor: 'constant-arrival-rate',
      rate: 30,
      timeUnit: '1s',
      duration: '100s',
      preAllocatedVUs: 100,
      maxVUs: 500,
      startTime: '10s',
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
        { target: 500, duration: '20s' },
        { target: 1500, duration: '30s' },
        { target: 2000, duration: '30s' },
        { target: 1000, duration: '20s' },
        { target: 200, duration: '15s' },
      ],
      startTime: '5s',
      exec: 'readMix',
    },
    // Reversal flow
    reversal_flow: {
      executor: 'constant-arrival-rate',
      rate: 10,
      timeUnit: '1s',
      duration: '90s',
      preAllocatedVUs: 30,
      maxVUs: 100,
      startTime: '20s',
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

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS, { namePrefix: 'throughput' }) };
}

export function postFlood(data) {
  const [a1, a2] = pickPair(data.accounts);
  const amount = Math.floor(Math.random() * 10000) + 1;

  const start = Date.now();
  const res = postTx(transfer(a1, a2, amount, 'heavy_throughput'), uuidv4());
  postLatency.add(Date.now() - start);
  postCounter.add(1);

  const ok = check(res, { 'post success': (r) => r.status === 201 || r.status === 409 });
  correctnessRate.add(ok);
}

export function batchIngestion(data) {
  const items = [];
  for (let i = 0; i < 50; i++) {
    const [a1, a2] = pickPair(data.accounts);
    const amount = Math.floor(Math.random() * 1000) + 1;
    items.push({
      idempotencyKey: uuidv4(),
      transaction: transfer(a1, a2, amount, 'heavy_throughput_batch'),
    });
  }

  const start = Date.now();
  const res = postBatch(items);
  batchLatency.add(Date.now() - start);
  batchCounter.add(1);

  check(res, { 'batch ok': (r) => r.status === 200 });
}

export function readMix(data) {
  const r = Math.random();

  if (r < 0.6) {
    const res = getBalance(randomItem(data.accounts));
    readCounter.add(1);
    check(res, { 'balance ok': (x) => x.status === 200 });
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

export function reversalFlow(data) {
  // Self-contained: post a transaction, then reverse it. (k6 VUs don't share
  // state across scenarios, so we can't rely on txIds produced by post_flood.)
  const [a1, a2] = pickPair(data.accounts);
  const posted = postTx(transfer(a1, a2, 100, 'heavy_throughput_reversal'), uuidv4());
  if (posted.status !== 201) return;

  let txId;
  try {
    txId = posted.json('txId');
  } catch (_) {
    return;
  }

  const res = reverseTx(txId, uuidv4());
  reversalCounter.add(1);
  check(res, { 'reversal ok': (r) => [201, 409].includes(r.status) });
}
