import { check, sleep } from 'k6';
import { postTx, getBalance } from './common.js';
import { uuidv4 } from './utils.js';

export const options = {
  scenarios: {
    raw: {
      executor: 'constant-vus',
      vus: 50,
      duration: '10m',
    },
  },
};

export default function () {
  const res = postTx({
    currency: 'EUR',
    entries: [
      { accountId: 'A1', direction: 'DEBIT', amountMinor: 1 },
      { accountId: 'A2', direction: 'CREDIT', amountMinor: 1 },
    ],
    metadata: { scenario: 'read_after_write' },
  }, uuidv4());

  check(res, { 'posted': (r) => r.status === 201 || r.status === 409 });
  // poll balance a few times
  for (let i = 0; i < 5; i++) {
    const b = getBalance('A1');
    check(b, { 'balance ok': (x) => x.status === 200 });
    sleep(0.2);
  }
}
