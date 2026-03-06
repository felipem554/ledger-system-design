import { check, sleep } from 'k6';
import { postTx, getBalance } from './common.js';
import { uuidv4, randomItem } from './utils.js';

export const options = {
  scenarios: {
    hotspot: {
      executor: 'ramping-arrival-rate',
      startRate: 20,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 1500,
      stages: [
        { target: 200, duration: '2m' },
        { target: 800, duration: '8m' },
        { target: 800, duration: '10m' },
        { target: 200, duration: '2m' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<300'],
  },
};

const hot = ['CLEARING', 'FEES', 'SETTLEMENT', 'TREASURY', 'HOT1', 'HOT2', 'HOT3', 'HOT4', 'HOT5', 'HOT6'];

export default function () {
  const r = Math.random();
  const a1 = randomItem(hot);
  const a2 = randomItem(hot);
  if (a1 === a2) return;

  if (r < 0.2) {
    const res = getBalance(a1);
    check(res, { 'balance 200': (x) => x.status === 200 });
  } else {
    const payload = {
      currency: 'EUR',
      entries: [
        { accountId: a1, direction: 'DEBIT', amountMinor: 1 },
        { accountId: a2, direction: 'CREDIT', amountMinor: 1 },
      ],
      metadata: { scenario: 'hotspot' },
    };
    const res = postTx(payload, uuidv4());
    check(res, { 'post ok-ish': (x) => [201,409,400].includes(x.status) });
  }
  sleep(0.05);
}
