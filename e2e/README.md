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
| `attendance.spec.ts` | class teacher marks Class 5 A at 390px with one absence; teacher gets 403 from `/api/messages`; the admin's message log shows the alert as Simulated with a masked number (waits for the 15-second dispatcher) |
