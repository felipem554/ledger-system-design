import { check, sleep } from 'k6';
import http from 'k6/http';

export const options = { vus: 1, duration: '20s' };

export default function () {
  const res = http.get(`${__ENV.BASE_URL || 'http://localhost:8080'}/healthz`);
  check(res, { 'health ok': (r) => r.status === 200 });
  sleep(1);
}
