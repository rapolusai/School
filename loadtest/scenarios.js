// The busy hours of a school day, against schools made by seed.js (same RUN_ID and SCHOOLS):
//
//   morning   9:00-9:30: every class teacher signs in, opens today's register and saves it.
//   parents   evening: parents sign in and open the dashboard, attendance, homework, fees and notices.
//   office    all day: admins search students, open records, read reports and the message log.
//
//   k6 run -e BASE_URL=https://staging.example.com -e RUN_ID=r1 -e SCHOOLS=50 -e PROFILE=peak loadtest/scenarios.js
//
// PROFILE=smoke (a minute, a few users) checks the scripts; PROFILE=peak runs all three together at the rates below,
// scaled by SCALE (default 1). Results: the summary k6 prints, and the dashboard of the environment.
import http from "k6/http";
import { check, group, sleep } from "k6";
import { authed, BASE, lastWeekday, login, PARENTS_PER_SECTION, SCHOOLS, SECTIONS, THRESHOLDS } from "./lib/common.js";

const PROFILE = __ENV.PROFILE || "smoke";
const SCALE = Number(__ENV.SCALE || 1);

function rate(perSecond) {
  return Math.max(1, Math.round(perSecond * SCALE));
}

const PROFILES = {
  smoke: {
    morning: { executor: "constant-vus", vus: 2, duration: "1m", exec: "morning" },
    parents: { executor: "constant-vus", vus: 2, duration: "1m", exec: "parents" },
    office: { executor: "constant-vus", vus: 1, duration: "1m", exec: "office" },
  },
  peak: {
    // Every class teacher of every school within the first 30 minutes, front-loaded.
    morning: {
      executor: "ramping-arrival-rate",
      exec: "morning",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: 50,
      maxVUs: 500,
      stages: [
        { target: rate(5), duration: "2m" },
        { target: rate(5), duration: "8m" },
        { target: rate(1), duration: "5m" },
      ],
    },
    parents: {
      executor: "constant-arrival-rate",
      exec: "parents",
      rate: rate(10),
      timeUnit: "1s",
      duration: "15m",
      preAllocatedVUs: 50,
      maxVUs: 800,
    },
    office: {
      executor: "constant-arrival-rate",
      exec: "office",
      rate: rate(2),
      timeUnit: "1s",
      duration: "15m",
      preAllocatedVUs: 10,
      maxVUs: 200,
    },
  },
};

export const options = {
  scenarios: PROFILES[PROFILE],
  thresholds: {
    ...THRESHOLDS,
    "http_req_duration{name:PUT /api/attendance/registers}": [`p(95)<${__ENV.REGISTER_P95_MS || 1500}`],
  },
};

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function schoolNumber() {
  return 1 + Math.floor(Math.random() * SCHOOLS);
}

/** A class teacher marks today's register: open it, mark everyone present except a few, save. */
export function morning() {
  const n = schoolNumber();
  const section = pick(SECTIONS).toLowerCase();
  const token = login(n, `teacher-${section}`);
  if (!token) return;
  group("register", () => {
    const sections = http.get(`${BASE}/api/attendance/sections`, authed(token, "GET /api/attendance/sections"));
    check(sections, { "sections 200": (r) => r.status === 200 });
    const mine = (sections.json("sections") || []).find((s) => s.canMark) || null;
    const sectionId = mine && (mine.sectionId || mine.id);
    if (!sectionId) return;
    const date = lastWeekday();
    const register = http.get(`${BASE}/api/attendance/registers/${sectionId}/${date}`, authed(token, "GET /api/attendance/registers"));
    if (!check(register, { "register 200": (r) => r.status === 200 })) return;
    if (register.json("holiday")) return;
    const entries = (register.json("entries") || [])
      .filter((e) => e.inSection !== false)
      .map((e, i) => ({ studentId: e.studentId, status: i % 17 === 3 ? "ABSENT" : "PRESENT" }));
    sleep(5 + Math.random() * 20); // the teacher takes the roll
    const saved = http.put(
      `${BASE}/api/attendance/registers/${sectionId}/${date}`,
      JSON.stringify({ entries }),
      authed(token, "PUT /api/attendance/registers"),
    );
    check(saved, { "register saved": (r) => r.status === 200 });
  });
}

/** A parent looks at their child's day. */
export function parents() {
  const n = schoolNumber();
  const who = `parent-${pick(SECTIONS).toLowerCase()}-${1 + Math.floor(Math.random() * PARENTS_PER_SECTION)}`;
  const token = login(n, who);
  if (!token) return;
  group("parent app", () => {
    const children = http.get(`${BASE}/api/me/children`, authed(token, "GET /api/me/children"));
    check(children, { "children 200": (r) => r.status === 200 });
    const child = (children.json() || [])[0];
    if (!child) return;
    const id = child.id || child.studentId;
    for (const [path, name] of [
      [`/api/me/children/${id}/attendance`, "GET /api/me/children/{id}/attendance"],
      [`/api/me/children/${id}/homework`, "GET /api/me/children/{id}/homework"],
      [`/api/me/children/${id}/fees`, "GET /api/me/children/{id}/fees"],
      ["/api/notices/board", "GET /api/notices/board"],
    ]) {
      const res = http.get(`${BASE}${path}`, authed(token, name));
      check(res, { [`${name} ok`]: (r) => r.status === 200 || r.status === 404 });
      sleep(1 + Math.random() * 3);
    }
  });
}

/** The office: search students, open a record, look at today's attendance and the message log. */
export function office() {
  const n = schoolNumber();
  const token = login(n, "admin");
  if (!token) return;
  group("office", () => {
    const list = http.get(`${BASE}/api/students?q=Student1&size=25`, authed(token, "GET /api/students?q"));
    check(list, { "search 200": (r) => r.status === 200 });
    const first = (list.json("items") || [])[0];
    if (first) {
      const one = http.get(`${BASE}/api/students/${first.id}`, authed(token, "GET /api/students/{id}"));
      check(one, { "record 200": (r) => r.status === 200 });
    }
    for (const [path, name] of [
      ["/api/attendance/today", "GET /api/attendance/today"],
      ["/api/messages?size=25", "GET /api/messages"],
      ["/api/users", "GET /api/users"],
    ]) {
      const res = http.get(`${BASE}${path}`, authed(token, name));
      check(res, { [`${name} 200`]: (r) => r.status === 200 });
      sleep(1 + Math.random() * 2);
    }
  });
}
