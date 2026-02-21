import { check, sleep } from 'k6';
import { postTx, getBalance, listTx } from './common.js';
import { uuidv4, randomItem, cursorPager } from './utils.js';

export const options = {
  scenarios: {
    baseline: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      stages: [
        { target: 200, duration: '3m' },
        { target: 500, duration: '7m' },
        { target: 500, duration: '10m' },
        { target: 200, duration: '3m' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200'],
  },
};

const accounts = Array.from({length: 2000}, (_, i) => `A${i+1}`);

export default function () {
  const r = Math.random();

  if (r < 0.6) {
    const id = randomItem(accounts);
    const res = getBalance(id);
    check(res, { 'balance 200': (x) => x.status === 200 });
  } else if (r < 0.8) {
    const res = listTx(null);
    check(res, { 'list 200': (x) => x.status === 200 });
  } else {
    const a1 = randomItem(accounts);
    const a2 = randomItem(accounts);
    if (a1 === a2) return;
    const payload = {
      currency: 'EUR',
      entries: [
        { accountId: a1, direction: 'DEBIT', amountMinor: 100 },
        { accountId: a2, direction: 'CREDIT', amountMinor: 100 },
      ],
      metadata: { scenario: 'baseline' },
    };
    const res = postTx(payload, uuidv4());
    check(res, { 'post 201 or 409': (x) => x.status === 201 || x.status === 409 });
  }
  sleep(0.1);
}
