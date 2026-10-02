import { check } from 'k6';
import { postBatch, seedAccounts, pickPair, uuidv4 } from './common.js';

const ACCOUNTS = Number(__ENV.ACCOUNTS || 200);
const BATCH_SIZE = Number(__ENV.BATCH_SIZE || 50);

export const options = {
  scenarios: {
    batch: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS, { namePrefix: 'batch' }) };
}

export default function (data) {
  const accounts = data.accounts;
  const items = [];
  for (let i = 0; i < BATCH_SIZE; i++) {
    const [a1, a2] = pickPair(accounts);
    items.push({
      idempotencyKey: uuidv4(),
      transaction: {
        currency: 'EUR',
        entries: [
          { accountId: a1, direction: 'DEBIT', amountMinor: 1 },
          { accountId: a2, direction: 'CREDIT', amountMinor: 1 },
        ],
        metadata: { scenario: 'batch' },
      },
    });
  }

  const res = postBatch(items);
  check(res, {
    'batch 200': (r) => r.status === 200,
    'all items created': (r) => {
      try {
        return r.json('items').every((it) => it.status === 'CREATED' || it.status === 'REPLAYED');
      } catch (_) {
        return false;
      }
    },
  });
}
