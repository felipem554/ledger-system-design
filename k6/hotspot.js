import { check, sleep } from 'k6';
import { postTx, getBalance, seedAccounts, pickPair, transfer, randomItem, uuidv4 } from './common.js';

// Intentionally small pool: concentrate writes on a few "hot" accounts to
// exercise row-level contention on account_state.
const HOT_ACCOUNTS = Number(__ENV.HOT_ACCOUNTS || 10);

export const options = {
  scenarios: {
    hotspot: {
      executor: 'ramping-arrival-rate',
      startRate: 20,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 1500,
      stages: [
        { target: 200, duration: '30s' },
        { target: 800, duration: '40s' },
        { target: 800, duration: '30s' },
        { target: 200, duration: '20s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<300'],
  },
};

export function setup() {
  return { accounts: seedAccounts(HOT_ACCOUNTS, { namePrefix: 'hot' }) };
}

export default function (data) {
  const accounts = data.accounts;
  const r = Math.random();

  if (r < 0.2) {
    const res = getBalance(randomItem(accounts));
    check(res, { 'balance 200': (x) => x.status === 200 });
  } else {
    const [a1, a2] = pickPair(accounts);
    const res = postTx(transfer(a1, a2, 1, 'hotspot'), uuidv4());
    check(res, { 'post ok-ish': (x) => [201, 409, 400].includes(x.status) });
  }
  sleep(0.05);
}
