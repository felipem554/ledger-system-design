import { check } from 'k6';
import { postTx, seedAccounts, pickPair, transfer, uuidv4 } from './common.js';

const ACCOUNTS = Number(__ENV.ACCOUNTS || 500);

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 200,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 5000,
      stages: [
        { target: 200, duration: '20s' },
        { target: 3000, duration: '30s' },
        { target: 3000, duration: '40s' },
        { target: 200, duration: '30s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS, { namePrefix: 'spike' }) };
}

export default function (data) {
  const [a1, a2] = pickPair(data.accounts);
  const res = postTx(transfer(a1, a2, 10, 'spike'), uuidv4());
  check(res, { 'post 201/409': (x) => x.status === 201 || x.status === 409 });
}
