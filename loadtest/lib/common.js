// Shared helpers for the k6 load tests (loadtest/README.md). Accounts are found by name, so the seed script and
// the scenarios agree without passing files around: school n of run R is "load-R-n", its admin is
// admin@load-R-n.test, teachers teacher-a@ / teacher-b@, parents parent-a-1@ ... parent-b-5@.
import http from "k6/http";
import { check } from "k6";

export const BASE = (__ENV.BASE_URL || "http://localhost:3100").replace(/\/+$/, "");
export const RUN = __ENV.RUN_ID || "r1";
export const SCHOOLS = Number(__ENV.SCHOOLS || 5);
export const STUDENTS_PER_SECTION = Number(__ENV.STUDENTS_PER_SECTION || 40);
export const SECTIONS = ["A", "B"];
export const PARENTS_PER_SECTION = 5;

/** Load-test accounts only. Never a real password: pass LOAD_PASSWORD, at least 10 characters. */
export const PASSWORD = __ENV.LOAD_PASSWORD || "Load-test-only-2026";

export function schoolCode(n) {
  return `load-${RUN}-${n}`;
}

export function email(n, who) {
  return `${who}@${schoolCode(n)}.test`;
}

export const JSON_HEADERS = { "Content-Type": "application/json", Accept: "application/json" };

export function authed(token, name) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` }, tags: { name } };
}

/** Signs in and returns the access token, or null (the check records the failure). */
export function login(n, who) {
  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ schoolCode: schoolCode(n), email: email(n, who), password: PASSWORD }),
    { headers: JSON_HEADERS, tags: { name: "POST /api/auth/login" } },
  );
  const ok = check(res, { "sign-in 200": (r) => r.status === 200 });
  return ok ? res.json("accessToken") : null;
}

/** Today in India as YYYY-MM-DD. */
export function indiaDate(days = 0) {
  return new Date(Date.now() + 330 * 60_000 + days * 86_400_000).toISOString().slice(0, 10);
}

/** The Indian academic year (April to March) containing today. */
export function academicYear() {
  const [y, m] = indiaDate().split("-").map(Number);
  const start = m >= 4 ? y : y - 1;
  return { name: `${start}-${String((start + 1) % 100).padStart(2, "0")}`, startsOn: `${start}-04-01`, endsOn: `${start + 1}-03-31` };
}

/** A school-day weekday for attendance: today, or the last weekday before it. */
export function lastWeekday() {
  for (let d = 0; d > -7; d--) {
    const date = indiaDate(d);
    const day = new Date(`${date}T00:00:00Z`).getUTCDay();
    if (day !== 0 && day !== 6) return date;
  }
  return indiaDate();
}

/** Thresholds every scenario keeps: few errors, and most requests fast. Tune per scenario with env vars. */
export const THRESHOLDS = {
  http_req_failed: [`rate<${__ENV.MAX_ERROR_RATE || 0.01}`],
  http_req_duration: [`p(95)<${__ENV.P95_MS || 800}`, `p(99)<${__ENV.P99_MS || 2000}`],
  checks: ["rate>0.99"],
};
