// Steady load through the public edge while the release shifts traffic. Read from stdin by the release script.
import http from "k6/http";

export const options = {
  scenarios: {
    steady: { executor: "constant-arrival-rate", rate: Number(__ENV.RATE || 12), timeUnit: "1s", duration: "30m",
              preAllocatedVUs: 10, maxVUs: 50 },
  },
  summaryTrendStats: ["p(95)", "max"],
};

export default function () {
  http.get(`${__ENV.EDGE}/api/public/version`, { tags: { name: "version" } });
}

// One JSON line the release script can read
export function handleSummary(d) {
  const m = d.metrics;
  return { stdout: JSON.stringify({ requests: m.http_reqs.values.count, failed: m.http_req_failed.values.passes,
    p95: Math.round(m.http_req_duration.values["p(95)"]), max: Math.round(m.http_req_duration.values.max) }) + "\n" };
}
