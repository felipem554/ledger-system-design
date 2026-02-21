import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomItem, uuidv4, cursorPager } from './utils.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TENANT = __ENV.TENANT || 't1';

function headers(idempotencyKey) {
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

export function getBalance(accountId) {
  return http.get(`${BASE_URL}/v1/accounts/${accountId}/balance`, { headers: headers() });
}

export function listTx(cursor) {
  const url = cursor ? `${BASE_URL}/v1/transactions?cursor=${encodeURIComponent(cursor)}&limit=100` : `${BASE_URL}/v1/transactions?limit=100`;
  return http.get(url, { headers: headers() });
}
