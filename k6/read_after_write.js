import { check, sleep } from 'k6';
import { postTx, getBalance, seedAccounts, pickPair, transfer, uuidv4 } from './common.js';

const ACCOUNTS = Number(__ENV.ACCOUNTS || 100);

export const options = {
  scenarios: {
    raw: {
      executor: 'constant-vus',
      vus: 50,
      duration: '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS, { namePrefix: 'raw' }) };
}

export default function (data) {
  const [a1, a2] = pickPair(data.accounts);
  const res = postTx(transfer(a1, a2, 1, 'read_after_write'), uuidv4());
  check(res, { 'posted': (r) => r.status === 201 || r.status === 409 });

  // poll the debited account's balance a few times (read-after-write consistency)
  for (let i = 0; i < 5; i++) {
    const b = getBalance(a1);
    check(b, { 'balance ok': (x) => x.status === 200 });
    sleep(0.2);
  }
}
