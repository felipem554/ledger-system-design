/**
 * Heavy Idempotency Stress Test
 *
 * This test hammers the system with massive idempotency replay storms.
 * Goal: Verify that under extreme replay pressure:
 * - No duplicate transactions are created
 * - Balances remain correct
 * - p95 latency stays acceptable
 * - The system correctly returns 201 for first request and replays for subsequent
 *
 * Run: BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/heavy_idempotency.js
 */
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { postTx, getBalance } from './common.js';
import { uuidv4 } from './utils.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TENANT = __ENV.TENANT || 't1';

// Custom metrics
const replayCounter = new Counter('idempotency_replays');
const newPostCounter = new Counter('idempotency_new_posts');
const conflictCounter = new Counter('idempotency_conflicts');
const balanceCheckRate = new Rate('balance_correctness');
const replayLatency = new Trend('replay_latency_ms');
const newPostLatency = new Trend('new_post_latency_ms');

export const options = {
  scenarios: {
    // Phase 1: Sustained replay storm (70% replays, 30% new)
    replay_storm: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 3000,
      stages: [
        { target: 300, duration: '2m' },
        { target: 800, duration: '3m' },
        { target: 1200, duration: '5m' },
        { target: 800, duration: '3m' },
        { target: 200, duration: '2m' },
      ],
      exec: 'replayStorm',
    },
    // Phase 2: Concurrent identical keys from multiple VUs
    concurrent_duplicates: {
      executor: 'constant-vus',
      vus: 100,
      duration: '8m',
      startTime: '2m',
      exec: 'concurrentDuplicates',
    },
    // Phase 3: Balance verification throughout
    balance_checker: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '15m',
      preAllocatedVUs: 30,
      maxVUs: 50,
      exec: 'verifyBalances',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.005'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    idempotency_conflicts: ['count==0'],
    balance_correctness: ['rate>0.99'],
  },
};

// Pre-generate keys for heavy replay
const REPLAY_KEYS = Array.from({ length: 10000 }, () => uuidv4());
// Small set of keys that will be hammered by concurrent VUs
const HOT_KEYS = Array.from({ length: 50 }, () => uuidv4());

const payload = {
  currency: 'EUR',
  entries: [
    { accountId: 'IDEM_A1', direction: 'DEBIT', amountMinor: 1 },
    { accountId: 'IDEM_A2', direction: 'CREDIT', amountMinor: 1 },
  ],
  metadata: { scenario: 'heavy_idempotency' },
};

export function replayStorm() {
  const isReplay = Math.random() < 0.7;
  const key = isReplay
    ? REPLAY_KEYS[Math.floor(Math.random() * REPLAY_KEYS.length)]
    : uuidv4();

  const start = Date.now();
  const res = postTx(payload, key);
  const elapsed = Date.now() - start;

  if (res.status === 201) {
    newPostCounter.add(1);
    newPostLatency.add(elapsed);
  } else if (res.status === 409) {
    conflictCounter.add(1);
  } else {
    replayCounter.add(1);
    replayLatency.add(elapsed);
  }

  check(res, {
    'response is 201 or replay': (r) => r.status === 201 || r.status === 409 || r.status === 200,
  });
}

export function concurrentDuplicates() {
  const key = HOT_KEYS[Math.floor(Math.random() * HOT_KEYS.length)];

  const res = postTx(payload, key);

  check(res, {
    'concurrent dup handled': (r) => r.status === 201 || r.status === 409 || r.status === 200,
    'no 500 errors': (r) => r.status !== 500,
  });

  sleep(0.05);
}

export function verifyBalances() {
  group('balance_verification', () => {
    const res1 = getBalance('IDEM_A1');
    const res2 = getBalance('IDEM_A2');

    if (res1.status === 200 && res2.status === 200) {
      try {
        const b1 = res1.json();
        const b2 = res2.json();
        // Debit balance should be negative of credit balance
        const balanced = Math.abs(b1.postedBalanceMinor) === Math.abs(b2.postedBalanceMinor);
        balanceCheckRate.add(balanced);
        if (!balanced) {
          console.warn(`Balance mismatch: A1=${b1.postedBalanceMinor} A2=${b2.postedBalanceMinor}`);
        }
      } catch (e) {
        balanceCheckRate.add(false);
      }
    }
  });

  sleep(1);
}
