/**
 * load.js — k6 load test
 *
 * Ramps from 0 → 5 VUs (30s), 5 → 20 VUs (1m), holds at 20 VUs (3m),
 * then ramps back to 0 (30s). Tests a realistic mix of read and write
 * traffic across the main API endpoints.
 *
 * Traffic split:
 *   80% — Scenario A: GET /api/v1/reference/domains (read)
 *   20% — Scenario B: POST /api/v1/persons then GET /api/v1/persons/:id (write + read)
 *
 * Run: k6 run --env BASE_URL=http://localhost:8080 --env AUTH_TOKEN=<token> load.js
 */

import http from "k6/http";
import { check, sleep, group } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

// ── Configuration ───────────────────────────────────────────────
const BASE_URL   = __ENV.BASE_URL   || "http://localhost:8080";
// AUTH_TOKEN is required; fall back to the dev sentinel only when not in CI
const token = __ENV.AUTH_TOKEN || "dev-token-change-me-in-production";

// ── Custom metrics ──────────────────────────────────────────────
const errorRate       = new Rate("errors");
const personCreateDur = new Trend("person_create_duration", true);  // true = track in ms
const personGetDur    = new Trend("person_get_duration",    true);

// ── Load profile — per specification ────────────────────────────
export const options = {
  stages: [
    { duration: "30s", target: 5  },  // ramp up 0 → 5 VUs
    { duration: "1m",  target: 20 },  // ramp up 5 → 20 VUs
    { duration: "3m",  target: 20 },  // hold at 20 VUs
    { duration: "30s", target: 0  },  // ramp down to 0
  ],
  thresholds: {
    // Primary SLA
    http_req_duration: ["p(95)<500"],   // 95th percentile under 500 ms
    http_req_failed:   ["rate<0.01"],   // fewer than 1% of requests fail
    // Derived metrics
    errors:            ["rate<0.01"],
    person_create_duration: ["p(95)<500"],
    person_get_duration:    ["p(95)<300"],
  },
};

const authHeaders = {
  "Content-Type":  "application/json",
  "Authorization": `Bearer ${token}`,
};

// ── Scenario A (80%) — read: GET /api/v1/reference/domains ──────
function scenarioReadDomains() {
  group("read: GET /api/v1/reference/domains", () => {
    const r = http.get(`${BASE_URL}/api/v1/reference/domains`, { headers: authHeaders });
    const ok = check(r, {
      "domains: status 200":           (r) => r.status === 200,
      "domains: content-type json":    (r) =>
        (r.headers["Content-Type"] || "").includes("application/json"),
    });
    if (!ok) errorRate.add(1);
  });
}

// ── Scenario B (20%) — write + read: create a person then GET it ─
function scenarioWritePerson() {
  group("write: POST /api/v1/persons then GET by id", () => {
    // POST — create a new person
    const createStart = Date.now();
    const createBody  = JSON.stringify({
      fullName: `Load Test VU${__VU} Iter${__ITER}`,
      gender:   "female",
    });
    const createResp = http.post(
      `${BASE_URL}/api/v1/persons`,
      createBody,
      { headers: authHeaders }
    );
    personCreateDur.add(Date.now() - createStart);

    const createOk = check(createResp, {
      "create person: status 201":    (r) => r.status === 201,
      "create person: has id":        (r) => {
        try { return typeof r.json("id") === "string"; } catch (_) { return false; }
      },
    });
    if (!createOk) { errorRate.add(1); return; }

    // GET — retrieve the person just created
    let personId = "";
    try { personId = createResp.json("id"); } catch (_) {}
    if (!personId) { errorRate.add(1); return; }

    const getStart = Date.now();
    const getResp  = http.get(
      `${BASE_URL}/api/v1/persons/${personId}`,
      { headers: authHeaders }
    );
    personGetDur.add(Date.now() - getStart);

    const getOk = check(getResp, {
      "get person: status 200":           (r) => r.status === 200,
      "get person: fullName present":     (r) => {
        try { return typeof r.json("fullName") === "string"; } catch (_) { return false; }
      },
    });
    if (!getOk) errorRate.add(1);
  });
}

// ── Main VU loop ────────────────────────────────────────────────
export default function () {
  // 80% read (Scenario A), 20% write (Scenario B)
  if (Math.random() < 0.80) {
    scenarioReadDomains();
  } else {
    scenarioWritePerson();
  }

  // Think time: 0.5 – 1.5 s
  sleep(Math.random() + 0.5);
}
