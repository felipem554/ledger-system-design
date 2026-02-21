import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from './utils.js';

export const options = {
  scenarios: {
    batch: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '10m',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TENANT = __ENV.TENANT || 't1';

export default function () {
  const items = [];
  for (let i = 0; i < 50; i++) {
    items.push({
      idempotencyKey: uuidv4(),
      transaction: {
        currency: 'EUR',
        entries: [
          { accountId: 'A1', direction: 'DEBIT', amountMinor: 1 },
          { accountId: 'A2', direction: 'CREDIT', amountMinor: 1 },
        ],
        metadata: { scenario: 'batch' },
      },
    });
  }

  const res = http.post(`${BASE_URL}/v1/transactions:batch`, JSON.stringify({ items }), {
    headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': TENANT },
  });

  check(res, { 'batch 200': (r) => r.status === 200 });
}
