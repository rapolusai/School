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
| `privacy.spec.ts` | the admin names the grievance officer and publishes the privacy notice; anyone reads it at `/privacy/<code>`; the parent must accept it before the dashboard (optional choices start unticked), withdraws WhatsApp and asks for a copy of the child's data; the parent gets 403 from the staff queue and another school 404; staff make the data file and download it, the parent downloads the same ZIP, and after the student leaves staff erase the data with the admission number typed again |
