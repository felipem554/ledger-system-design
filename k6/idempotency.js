import { check } from 'k6';
import { postTx } from './common.js';
import { uuidv4 } from './utils.js';

export const options = {
  scenarios: {
    idempotency: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '8m',
      preAllocatedVUs: 200,
      maxVUs: 2000,
    },
  },
};

// Pre-generated keys to replay
const KEYS = Array.from({length: 5000}, () => uuidv4());

export default function () {
  const key = Math.random() < 0.7 ? KEYS[Math.floor(Math.random()*KEYS.length)] : uuidv4();
  const payload = {
    currency: 'EUR',
    entries: [
      { accountId: 'A1', direction: 'DEBIT', amountMinor: 1 },
      { accountId: 'A2', direction: 'CREDIT', amountMinor: 1 },
    ],
    metadata: { scenario: 'idempotency' },
  };
  const res = postTx(payload, key);
  check(res, { 'post 201/409': (x) => x.status === 201 || x.status === 409 });
}
