import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { randomItem, uuidv4, cursorPager } from './utils.js';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const TENANT = __ENV.TENANT || 't1';

export function headers(idempotencyKey) {
  const h = {
    'Content-Type': 'application/json',
    'X-Tenant-Id': TENANT,
  };
  if (idempotencyKey) h['Idempotency-Key'] = idempotencyKey;
  return h;
}

export function postTx(payload, idemKey) {
  return http.post(`${BASE_URL}/v1/transactions`, JSON.stringify(payload), { headers: headers(idemKey) });
}

export function postBatch(items) {
  return http.post(`${BASE_URL}/v1/transactions:batch`, JSON.stringify({ items }), { headers: headers() });
}

export function reverseTx(txId, idemKey) {
  return http.post(`${BASE_URL}/v1/transactions/${txId}:reverse`, null, { headers: headers(idemKey || uuidv4()) });
}

export function getBalance(accountId) {
  return http.get(`${BASE_URL}/v1/accounts/${accountId}/balance`, { headers: headers() });
}

export function listTx(cursor) {
  const url = cursor ? `${BASE_URL}/v1/transactions?cursor=${encodeURIComponent(cursor)}&limit=100` : `${BASE_URL}/v1/transactions?limit=100`;
  return http.get(url, { headers: headers() });
}

// --- Seeding ---------------------------------------------------------------
// Account IDs are server-generated UUIDs, so load scenarios cannot reference
// fixed names like "A1". Call seedAccounts() from a scenario's setup() to create
// a real pool once; k6 passes the returned data to every VU. Accounts are
// created in parallel batches via http.batch to keep setup fast even for
// thousands of accounts.
export function seedAccounts(count, opts) {
  const { type = 'ASSET', currency = 'EUR', chunk = 50, namePrefix = 'load' } = opts || {};
  const ids = [];
  for (let start = 0; start < count; start += chunk) {
    const n = Math.min(chunk, count - start);
    const reqs = [];
    for (let i = 0; i < n; i++) {
      reqs.push({
        method: 'POST',
        url: `${BASE_URL}/v1/accounts`,
        body: JSON.stringify({ name: `${namePrefix}-${start + i}`, type, currency }),
        params: { headers: headers() },
      });
    }
    const responses = http.batch(reqs);
    for (const r of responses) {
      if (r.status !== 201) {
        throw new Error(`seedAccounts: create failed (status ${r.status}): ${r.body}`);
      }
      ids.push(r.json('id'));
    }
  }
  return ids;
}

// --- Idempotency accounting ------------------------------------------------
// The API answers a replay with the same 201 as the original post, so k6 cannot
// classify a single response. Instead, count how many pool keys ended up with an
// idempotency record: each one is exactly one new transaction, and every other
// accepted post on a pool key was a replay.
export function countExistingIdempotencyKeys(keys, chunk) {
  const size = chunk || 100;
  let found = 0;
  for (let i = 0; i < keys.length; i += size) {
    const reqs = keys.slice(i, i + size).map((k) => ({
      method: 'GET',
      url: `${BASE_URL}/v1/idempotency/${encodeURIComponent(k)}`,
      params: {
        headers: headers(),
        tags: { name: 'idempotency_lookup' },
        responseCallback: http.expectedStatuses(200, 404),
      },
    }));
    for (const r of http.batch(reqs)) {
      if (r.status === 200) found++;
    }
  }
  return found;
}

const freshAccepted = new Counter('idempotency_fresh_accepted');
const pooledAccepted = new Counter('idempotency_pooled_accepted');
const poolKeysCreated = new Counter('idempotency_pool_keys_created');
const idemConflicts = new Counter('idempotency_conflicts');
const idemPostErrors = new Counter('idempotency_post_errors');

export function recordIdempotentPost(res, pooledKey) {
  if (res.status === 201) {
    (pooledKey ? pooledAccepted : freshAccepted).add(1);
  } else if (res.status === 409) {
    idemConflicts.add(1);
  } else {
    idemPostErrors.add(1);
  }
}

export function recordPoolKeysCreated(n) {
  poolKeysCreated.add(n);
}

export function idempotencyReport(data) {
  const count = (m) => (data.metrics[m] ? data.metrics[m].values.count : 0);
  const fresh = count('idempotency_fresh_accepted');
  const pooled = count('idempotency_pooled_accepted');
  const created = count('idempotency_pool_keys_created');
  const conflicts = count('idempotency_conflicts');
  const errors = count('idempotency_post_errors');
  const accepted = fresh + pooled;
  const replays = pooled - created;
  const pct = (n) => (accepted ? ((100 * n) / accepted).toFixed(1) : '0.0');
  return [
    '',
    '  IDEMPOTENCY BREAKDOWN',
    `    accepted posts (201) ....: ${accepted}`,
    `    new transactions ........: ${fresh + created}  (fresh keys ${fresh} + pool keys ${created})`,
    `    replays .................: ${replays}  (${pct(replays)}% of accepted)`,
    `    409 conflicts ...........: ${conflicts}`,
    `    other errors ............: ${errors}`,
    '',
  ].join('\n');
}

// Two distinct random account IDs from a seeded pool.
export function pickPair(accounts) {
  const a = randomItem(accounts);
  let b = randomItem(accounts);
  while (b === a && accounts.length > 1) b = randomItem(accounts);
  return [a, b];
}

// A balanced double-entry transfer between two accounts.
export function transfer(a1, a2, amountMinor, scenario, currency) {
  return {
    currency: currency || 'EUR',
    entries: [
      { accountId: a1, direction: 'DEBIT', amountMinor },
      { accountId: a2, direction: 'CREDIT', amountMinor },
    ],
    metadata: scenario ? { scenario } : undefined,
  };
}

export { randomItem, uuidv4, cursorPager };
