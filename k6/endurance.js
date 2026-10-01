/**
 * Endurance / Soak Test
 *
 * Sustained moderate throughput to detect memory leaks, connection pool
 * exhaustion, and gradual degradation. Trimmed to a 2-minute run for routine
 * use; raise DURATION (and the scenario duration below) for a real soak.
 *
 * Run: BASE_URL=http://localhost:8080 TENANT=t1 k6 run k6/endurance.js
 */
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { postTx, getBalance, listTx, seedAccounts, pickPair, transfer, randomItem, uuidv4 } from './common.js';

const ACCOUNTS = Number(__ENV.ACCOUNTS || 1000);

const errorRate = new Rate('error_rate');
const txCounter = new Counter('endurance_transactions');
const latencyTrend = new Trend('endurance_latency_ms');

export const options = {
  scenarios: {
    sustained_load: {
      executor: 'constant-arrival-rate',
      rate: 300,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 200,
      maxVUs: 1500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300', 'p(99)<600'],
    error_rate: ['rate<0.01'],
  },
};

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS, { namePrefix: 'endurance' }) };
}

export default function (data) {
  const accounts = data.accounts;
  const r = Math.random();
  const start = Date.now();

  if (r < 0.5) {
    // Post transaction
    const [a1, a2] = pickPair(accounts);
    const amount = Math.floor(Math.random() * 5000) + 1;
    const res = postTx(transfer(a1, a2, amount, 'endurance'), uuidv4());
    txCounter.add(1);
    const ok = check(res, { 'post ok': (x) => x.status === 201 || x.status === 409 });
    errorRate.add(!ok);
  } else if (r < 0.8) {
    // Balance read
    const res = getBalance(randomItem(accounts));
    const ok = check(res, { 'balance ok': (x) => x.status === 200 });
    errorRate.add(!ok);
  } else {
    // List transactions
    const res = listTx(null);
    const ok = check(res, { 'list ok': (x) => x.status === 200 });
    errorRate.add(!ok);
  }

  latencyTrend.add(Date.now() - start);
  sleep(0.01);
}
