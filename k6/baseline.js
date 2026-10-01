import { check, sleep } from 'k6';
import { postTx, getBalance, listTx, seedAccounts, pickPair, transfer, randomItem, uuidv4 } from './common.js';

const ACCOUNTS = Number(__ENV.ACCOUNTS || 2000);

export const options = {
  scenarios: {
    baseline: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      stages: [
        { target: 200, duration: '30s' },
        { target: 500, duration: '30s' },
        { target: 500, duration: '40s' },
        { target: 200, duration: '20s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200'],
  },
};

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS, { namePrefix: 'baseline' }) };
}

export default function (data) {
  const accounts = data.accounts;
  const r = Math.random();

  if (r < 0.6) {
    const res = getBalance(randomItem(accounts));
    check(res, { 'balance 200': (x) => x.status === 200 });
  } else if (r < 0.8) {
    const res = listTx(null);
    check(res, { 'list 200': (x) => x.status === 200 });
  } else {
    const [a1, a2] = pickPair(accounts);
    const res = postTx(transfer(a1, a2, 100, 'baseline'), uuidv4());
    check(res, { 'post 201 or 409': (x) => x.status === 201 || x.status === 409 });
  }
  sleep(0.1);
}
