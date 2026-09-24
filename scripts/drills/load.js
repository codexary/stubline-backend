import http from 'k6/http';
import { check } from 'k6';
export const options = { vus: 2, duration: __ENV.DURATION || '90s' };
export default function () {
  const response = http.get('https://host.docker.internal:8080/events/concert-1/seats', { timeout: '2s' });
  check(response, { 'seat lookup status 200': r => r.status === 200 });
}
