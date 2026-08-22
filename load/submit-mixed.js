// k6 load test: mixed workload against the Schedula API.
// Usage:
//   SCHEDULA_URL=http://localhost:8080 SCHEDULA_KEY=sk_... k6 run load/submit-mixed.js
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.SCHEDULA_URL || "http://localhost:8080";
const KEY = __ENV.SCHEDULA_KEY || "";
const PARAMS = { headers: { "Content-Type": "application/json", "X-API-Key": KEY } };

export const options = {
  scenarios: {
    immediate: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 200),        // jobs/sec
      timeUnit: "1s",
      duration: __ENV.DURATION || "60s",
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
};

export default function () {
  const payload = JSON.stringify({
    jobType: Math.random() < 0.2 ? "sleep" : "log",
    payload: { msg: `load-${__VU}-${__ITER}`, ms: 20 },
    requiredCapabilities: [],
  });
  const res = http.post(`${BASE}/v1/jobs`, payload, PARAMS);
  check(res, { "accepted (2xx)": (r) => r.status >= 200 && r.status < 300,
               "not throttled": (r) => r.status !== 429 });
}
