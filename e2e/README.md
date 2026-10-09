# Akshara end-to-end tests

Playwright tests that drive the real stack: the Next.js frontend with its `/api` proxy to
the Spring Boot backend. Every run creates fresh schools with unique codes
(`e2e-<tag>-<time>-<random>`), so tests do not depend on seed data or on each other.

## Run

Start the backend and the frontend first (frontend on port 3000 by default), then:

```bash
npm install
npx playwright install chromium        # skip if browsers are already installed
E2E_BASE_URL=http://localhost:3000 npm test
```

- `npm run test:desktop` – every spec at 1440×900
- `npm run test:responsive` – the layout spec at desktop, tablet (820×1180) and mobile (390×844)
- `npm run typecheck` – `tsc --noEmit`
- `npm run report` – open the last HTML report (written when `CI` is set)

`@playwright/test` is pinned to 1.56.1, which uses Chromium revision 1194.

## Specs

| File | Covers |
| --- | --- |
| `signup.spec.ts` | 3-step sign-up wizard → automatic sign-in → dashboard with trial banner |
| `auth.spec.ts` | sign out and back in; wrong password message; signed-out `/app/users` → `/login` |
| `users.spec.ts` | admin adds a Teacher; teacher has no Users/Audit nav and sees access denied |
| `isolation.spec.ts` | school A lists only its users; A's token gets 404 for a user of school B |
| `responsive.spec.ts` | no horizontal scroll; bottom bar on mobile, icon rail on tablet, sidebar on desktop |
| `students.spec.ts` | admin sets up a year, class and section; admits a student; searches; opens the record; gives the mother a sign-in; the parent sees the child and gets 403 from `/api/students`; another school gets 404 |
| `admissions.spec.ts` | a parent sends an enquiry from `/enquire/<code>` without signing in; the admin moves it on the board from enquiry to application to offered and admits the child; the timeline, offer letter and student record; admitting again changes nothing; another school gets 404; no horizontal scroll at 820 and 390 px |
| `attendance.spec.ts` | class teacher marks Class 5 A at 390px with one absence; teacher gets 403 from `/api/messages`; the admin's message log shows the alert as Simulated with a masked number (waits for the 15-second dispatcher) |
| `fees.spec.ts` | fee structure published through the API; an accountant collects Quarter 1 in cash, sees receipt `RCPT/<year>/000001` with the amount in words, prints it (only the receipt prints) and finds it in the register; a parent sees the overdue quarter on the dashboard, pays it through the sandbox checkout and lands on the receipt; another student's fees are 404 and staff fee routes 403 |
| `staff.spec.ts` | a teacher checks in and out from the dashboard and applies for two days of casual leave (working days and balance after shown); the principal approves it from the dashboard's waiting card; the teacher sees the balance go from 12 to 10 at 390px; the admin's daily staff sheet shows the self check-in |
| `communication.spec.ts` | a class teacher drafts a circular for Class 5 A and submits it; the principal approves it, which sends it; the Class 5 A parent sees it on the dashboard and the notice board and reads it (the principal sees the read receipt); the Class 5 B parent and another school do not get it; teacher 403 on approve, parent 403 on `/api/notices`. The admin adds a whole-school holiday for today on the calendar; the attendance screen shows the school closed, the API answers 409 to a register for that day, and the teacher sees the holiday on the dashboard and calendar |
| `timetable-homework.spec.ts` | the admin finds a teacher clash in the clash check, opens Class 5 A and moves the period to a free teacher, and saves; the teacher sets homework for Class 5 A with a PDF; the student opens it from the dashboard at 390px, downloads the PDF and hands in an answer (and gets 403 from staff endpoints); the teacher reviews it with a grade and remark, which the student's API shows |
