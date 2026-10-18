// Capacity test: web intake and USSD sessions at a fixed arrival rate, run by scripts/load/capacity.sh.
// Web: a signed-in patient submits symptoms; the core runs the rules in the request, stores the consultation and
// queues the model suggestion (outbox, Kafka, anonymizer, model). USSD: the aggregator's callbacks for a whole session
// up to the booking (language, PIN, who is sick, consent, symptoms, advice, book), against the USSD gateway.
import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";

const EDGE = __ENV.EDGE;          // http://haproxy:9080
const GW = __ENV.GW;              // http://ussd-gateway:8300
const DURATION = __ENV.DURATION || "60s";

export const options = {
  scenarios: {
    web: { executor: "constant-arrival-rate", exec: "web", rate: Number(__ENV.WEB_PER_MIN), timeUnit: "1m",
           duration: DURATION, preAllocatedVUs: 20, maxVUs: 200 },
    ussd: { executor: "constant-arrival-rate", exec: "ussd", rate: Number(__ENV.USSD_PER_MIN), timeUnit: "1m",
            duration: DURATION, preAllocatedVUs: 20, maxVUs: 200 },
  },
  // Thresholds only make k6 report these sub-metrics; the runner judges the results
  thresholds: {
    "http_req_duration{scenario:web}": ["p(95)<100000"],
    "http_req_duration{scenario:ussd}": ["p(95)<100000"],
    "http_req_failed{scenario:web}": ["rate<2"],
    "http_req_failed{scenario:ussd}": ["rate<2"],
    "checks{scenario:ussd}": ["rate>=0"],
    "checks{scenario:web}": ["rate>=0"],
  },
  summaryTrendStats: ["p(50)", "p(95)", "p(99)", "max"],
};

const SYMPTOMS = [["cough", "fever"], ["headache"], ["diarrhoea", "fever"], ["cough"]];

export function web() {
  const n = exec.scenario.iterationInTest;
  const r = http.post(`${EDGE}/api/consultations`, JSON.stringify({
    patientId: __ENV.PATIENT, channel: "WEB", symptoms: SYMPTOMS[n % SYMPTOMS.length], durationDays: 1 + (n % 4),
  }), { headers: { "Content-Type": "application/json", Cookie: __ENV.COOKIE, "X-XSRF-TOKEN": __ENV.XSRF,
                   "Idempotency-Key": `${__ENV.RUN}-${__ENV.GEN}-${n}` } });
  check(r, { "intake triaged": (x) => x.status === 201 || x.status === 200 });
}

export function ussd() {
  const sid = `load-${__ENV.RUN}-${__ENV.GEN}-${exec.scenario.iterationInTest}`;
  let text = "";
  const step = (answer) => {
    if (answer !== undefined) text = text === "" ? String(answer) : `${text}*${answer}`;
    return http.post(`${GW}/ussd/callback`, { sessionId: sid, phoneNumber: __ENV.PHONE, serviceCode: "*909*8#", text });
  };
  step(); step(2); let r = step(1234);
  const who = /^(\d)\. Keza/m.exec(r.body || "");
  if (!check(r, { "who is sick": () => who !== null })) return;
  r = step(who[1]);
  if (/Agree/.test(r.body || "")) r = step(2);
  step(1); step(3); step(0); r = step(2);
  check(r, { "advice in session": (x) => /^CON URGENT/.test(x.body || "") });
  r = step(1);
  check(r, { "booked": (x) => /^END/.test(x.body || "") });
}

export function handleSummary(d) {
  const m = d.metrics;
  const pick = (k) => (m[k] ? m[k].values : {});
  const out = {};
  for (const s of ["web", "ussd"]) {
    const dur = pick(`http_req_duration{scenario:${s}}`);
    out[s] = { requests: (pick(`http_req_failed{scenario:${s}}`).passes || 0) + (pick(`http_req_failed{scenario:${s}}`).fails || 0),
               failed: pick(`http_req_failed{scenario:${s}}`).passes || 0,
               checksOk: pick(`checks{scenario:${s}}`).passes || 0, checksFailed: pick(`checks{scenario:${s}}`).fails || 0,
               p50: dur["p(50)"], p95: dur["p(95)"], p99: dur["p(99)"], max: dur.max };
  }
  out.dropped = pick("dropped_iterations").count || 0;
  return { stdout: "SUMMARY " + JSON.stringify(out) + "\n" };
}
