import http from 'k6/http';
import { check } from 'k6';
export const options = { vus: 32, duration: '20s', thresholds: { checks: ['rate>0.95'], http_req_failed: ['rate<0.05'] } };
export default function () {
  const response = http.get('http://host.docker.internal:8080/events/concert-1/seats', { timeout: '2s' });
  check(response, { 'seat lookup completed within 2s': (r) => r.status === 200 });
}
