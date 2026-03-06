import { check } from 'k6';
import { postTx } from './common.js';
import { uuidv4 } from './utils.js';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 5000,
      stages: [
        { target: 200, duration: '1m' },
        { target: 3000, duration: '2m' },
        { target: 3000, duration: '5m' },
        { target: 200, duration: '2m' },
      ],
    },
  },
};

export default function () {
  const payload = {
    currency: 'EUR',
    entries: [
      { accountId: 'A1', direction: 'DEBIT', amountMinor: 10 },
      { accountId: 'A2', direction: 'CREDIT', amountMinor: 10 },
    ],
    metadata: { scenario: 'spike' },
  };
  const res = postTx(payload, uuidv4());
  check(res, { 'post 201/409': (x) => x.status === 201 || x.status === 409 });
}
