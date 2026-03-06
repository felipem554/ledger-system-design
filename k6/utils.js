import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export function uuidv4() {
  // lightweight UUID-ish for load tests
  const s4 = () => Math.floor((1 + Math.random()) * 0x10000).toString(16).substring(1);
  return `${s4()}${s4()}-${s4()}-${s4()}-${s4()}-${s4()}${s4()}${s4()}`;
}

export function randomItem(arr) {
  return arr[randomIntBetween(0, arr.length - 1)];
}

export function cursorPager(res) {
  try {
    const body = res.json();
    return body.nextCursor || null;
  } catch (_) {
    return null;
  }
}
