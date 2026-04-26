/**
 * smoke.js — k6 smoke test
 *
 * 1 VU, 1 iteration. Verifies the service is up and returning correct
 * HTTP status codes and Content-Type headers for key endpoints.
 *
 * Run: k6 run --env BASE_URL=http://localhost:8080 --env AUTH_TOKEN=<token> smoke.js
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Rate } from "k6/metrics";

// ── Configuration ──────────────────────────────────────────────
const BASE_URL   = __ENV.BASE_URL   || "http://localhost:8080";
const AUTH_TOKEN = __ENV.AUTH_TOKEN || "dev-token-change-me-in-production";

const errorRate = new Rate("errors");

export const options = {
  vus:        1,
  iterations: 1,
  thresholds: {
    errors:            ["rate<0.01"],
    http_req_duration: ["p(95)<2000"],
    http_req_failed:   ["rate<0.01"],
  },
};

const authHeaders = {
  "Content-Type":  "application/json",
  "Authorization": `Bearer ${AUTH_TOKEN}`,
};

// ── Main function ───────────────────────────────────────────────
export default function () {

  // 1. Health check — unauthenticated, no Content-Type required
  const health = http.get(`${BASE_URL}/health`);
  const healthOk = check(health, {
    "health: status 200":              (r) => r.status === 200,
    "health: body has status ok":      (r) => {
      try { return r.json("status") === "ok"; } catch (_) { return false; }
    },
    "health: content-type is json":    (r) =>
      (r.headers["Content-Type"] || "").includes("application/json"),
  });
  if (!healthOk) errorRate.add(1);

  sleep(0.1);

  // 2. Reference domains — authenticated, checks Content-Type
  const domains = http.get(`${BASE_URL}/api/v1/reference/domains`, { headers: authHeaders });
  const domainsOk = check(domains, {
    "domains: status 200":             (r) => r.status === 200,
    "domains: content-type is json":   (r) =>
      (r.headers["Content-Type"] || "").includes("application/json"),
    "domains: body is non-empty":      (r) => r.body.length > 2,
  });
  if (!domainsOk) errorRate.add(1);

  sleep(0.1);

  // 3. Create a person — POST with JSON body
  const createPayload = JSON.stringify({
    fullName:      "Smoke Test User",
    gender:        "male",
    preferredName: "Smokey",
  });
  const createPerson = http.post(
    `${BASE_URL}/api/v1/persons`,
    createPayload,
    { headers: authHeaders }
  );
  const createOk = check(createPerson, {
    "create person: status 201":         (r) => r.status === 201,
    "create person: content-type json":  (r) =>
      (r.headers["Content-Type"] || "").includes("application/json"),
    "create person: has id field":       (r) => {
      try { return typeof r.json("id") === "string"; } catch (_) { return false; }
    },
  });
  if (!createOk) errorRate.add(1);

  // If person was created successfully, fetch it by id
  if (createPerson.status === 201) {
    let personId = "";
    try { personId = createPerson.json("id"); } catch (_) {}

    if (personId) {
      sleep(0.1);
      const getPerson = http.get(
        `${BASE_URL}/api/v1/persons/${personId}`,
        { headers: authHeaders }
      );
      const getOk = check(getPerson, {
        "get person: status 200":           (r) => r.status === 200,
        "get person: content-type json":    (r) =>
          (r.headers["Content-Type"] || "").includes("application/json"),
        "get person: fullName matches":     (r) => {
          try { return r.json("fullName") === "Smoke Test User"; } catch (_) { return false; }
        },
      });
      if (!getOk) errorRate.add(1);
    }
  }

  sleep(0.1);

  // 4. List persons
  const listPersons = http.get(`${BASE_URL}/api/v1/persons`, { headers: authHeaders });
  const listOk = check(listPersons, {
    "list persons: status 200":          (r) => r.status === 200,
    "list persons: content-type json":   (r) =>
      (r.headers["Content-Type"] || "").includes("application/json"),
  });
  if (!listOk) errorRate.add(1);

  sleep(0.1);
}
