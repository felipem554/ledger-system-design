import { check } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import {
  postTx, seedAccounts, transfer, uuidv4,
  recordIdempotentPost, recordPoolKeysCreated, countExistingIdempotencyKeys, idempotencyReport,
} from './common.js';

const POOL_SIZE = 5000;

export const options = {
  scenarios: {
    idempotency: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 200,
      maxVUs: 2000,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    idempotency_conflicts: ['count==0'],
  },
};

// Keys must be derived from setup() data: init code runs once per VU, so a
// random pool built at module level would be private to each VU and replays
// would almost never collide.
const poolKey = (runId, i) => `${runId}-replay-${i}`;

export function setup() {
  return { runId: uuidv4(), accounts: seedAccounts(2, { namePrefix: 'idem' }) };
}

export default function (data) {
  const [a1, a2] = data.accounts;
  const pooled = Math.random() < 0.7;
  const key = pooled ? poolKey(data.runId, Math.floor(Math.random() * POOL_SIZE)) : uuidv4();
  const res = postTx(transfer(a1, a2, 1, 'idempotency'), key);
  recordIdempotentPost(res, pooled);
  check(res, { 'post 201': (x) => x.status === 201 });
}

export function teardown(data) {
  const keys = Array.from({ length: POOL_SIZE }, (_, i) => poolKey(data.runId, i));
  recordPoolKeysCreated(countExistingIdempotencyKeys(keys));
}

export function handleSummary(data) {
  return { stdout: textSummary(data, { indent: ' ', enableColors: false }) + idempotencyReport(data) };
}
