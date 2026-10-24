// Creates the load-test schools through the public API, the way a real school would set itself up:
// sign-up, the current year, Class 5 with sections A and B (each with a class teacher), STUDENTS_PER_SECTION
// students per section from the CSV import, and sign-ins for the first PARENTS_PER_SECTION parents of each section.
//
//   k6 run -e BASE_URL=https://staging.example.com -e RUN_ID=r1 -e SCHOOLS=50 loadtest/seed.js
//
// Each school is one iteration, so SCHOOLS schools are made by up to 5 virtual users. Safe to re-run with a new
// RUN_ID; an existing school code answers 409 and is skipped.
import http from "k6/http";
import { check, fail } from "k6";
import exec from "k6/execution";
import {
  academicYear,
  authed,
  BASE,
  email,
  JSON_HEADERS,
  login,
  PARENTS_PER_SECTION,
  PASSWORD,
  schoolCode,
  SCHOOLS,
  SECTIONS,
  STUDENTS_PER_SECTION,
} from "./lib/common.js";

export const options = {
  scenarios: {
    seed: { executor: "shared-iterations", vus: Math.min(5, SCHOOLS), iterations: SCHOOLS, maxDuration: "60m" },
  },
  thresholds: { checks: ["rate>0.99"] },
};

function post(token, path, body, name, expected = [200, 201]) {
  const res = http.post(`${BASE}${path}`, JSON.stringify(body), authed(token, name));
  if (!check(res, { [`${name} ok`]: (r) => expected.includes(r.status) })) {
    console.error(`${name}: ${res.status} ${res.body && res.body.slice(0, 300)}`);
  }
  return res;
}

export default function () {
  // Across all virtual users, so every school is made exactly once.
  const n = exec.scenario.iterationInTest + 1;
  const code = schoolCode(n);

  const signup = http.post(
    `${BASE}/api/public/signup`,
    JSON.stringify({
      schoolName: `Load Test School ${n}`,
      schoolCode: code,
      board: "CBSE",
      city: "Hyderabad",
      adminName: "Load Admin",
      adminEmail: email(n, "admin"),
      password: PASSWORD,
    }),
    { headers: JSON_HEADERS, tags: { name: "POST /api/public/signup" } },
  );
  if (signup.status === 409) {
    console.log(`${code} exists; skipped`);
    return;
  }
  if (!check(signup, { "sign-up 201": (r) => r.status === 201 })) fail(`sign-up of ${code}: ${signup.status}`);

  const admin = login(n, "admin");
  if (!admin) fail(`admin of ${code} cannot sign in`);

  const year = academicYear();
  post(admin, "/api/academics/years", { ...year, current: true }, "create year");
  const classId = post(admin, "/api/academics/classes", { name: "Class 5" }, "create class").json("id");

  const csv = ["admission_no,first_name,last_name,date_of_birth,gender,class,section,guardian_name,guardian_relation,guardian_phone,guardian_email,roll_no"];
  for (const section of SECTIONS) {
    const teacher = `teacher-${section.toLowerCase()}`;
    const teacherId = post(
      admin,
      "/api/users",
      { name: `Teacher ${section}`, email: email(n, teacher), password: PASSWORD, roles: ["TEACHER"] },
      "create teacher",
    ).json("id");
    post(admin, `/api/academics/classes/${classId}/sections`, { name: section, capacity: 60, classTeacherId: teacherId }, "create section");
    for (let i = 1; i <= STUDENTS_PER_SECTION; i++) {
      const phone = `98${String(n).padStart(3, "0")}${section === "A" ? 1 : 2}${String(i).padStart(4, "0")}`;
      csv.push(
        [`L${n}/${section}/${i}`, `Student${i}`, section, "2016-06-01", i % 2 ? "MALE" : "FEMALE", "Class 5", section,
          `Parent ${section}${i}`, "MOTHER", phone, `guardian-${section.toLowerCase()}-${i}@${code}.test`, i].join(","),
      );
    }
  }
  post(admin, "/api/students/import?dryRun=false", { csv: csv.join("\n") }, "import students");

  // Parent sign-ins for the first students of each section.
  const page = http.get(`${BASE}/api/students?size=${SECTIONS.length * STUDENTS_PER_SECTION}`, authed(admin, "list students"));
  const students = page.json("items") || [];
  for (const section of SECTIONS) {
    for (let i = 1; i <= PARENTS_PER_SECTION; i++) {
      const student = students.find((s) => s.admissionNo === `L${n}/${section}/${i}`);
      if (!student) continue;
      const detail = http.get(`${BASE}/api/students/${student.id}`, authed(admin, "student detail")).json();
      const guardian = detail.guardians && detail.guardians[0];
      if (!guardian) continue;
      post(
        admin,
        `/api/students/${student.id}/guardians/${guardian.id}/sign-in`,
        { mode: "CREATE", email: email(n, `parent-${section.toLowerCase()}-${i}`), password: PASSWORD },
        "parent sign-in",
        [200, 201],
      );
    }
  }
  console.log(`${code}: ready`);
}
