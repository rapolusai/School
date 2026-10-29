# Negative sweep, 2026-10-29

Deliberate attempts to make the features added since the 2026-10-23 sweep fail: subscriptions and billing, the
Excel bulk import, auth follow-ups (password reset and change, Super Admin sessions, shared rate limits, the consent
gate, file storage) and the single-server production setup. Started from SCH3 at `459b0b2`.

How it was tested:

- Backend attacks as Testcontainers integration tests (MockMvc with `URI.create(...)` so percent-encoding reaches
  the server as sent).
- Gate, Origin and rate-limit attacks repeated on real Tomcat behind Caddy: the `infra/single-server` stack run
  locally under docker with the host-built jar. They were run once with the jar from before the fixes and once with
  the jar from after them.
- The ops attacks (killed API, database restart, full disk, bad dumps, broken release) were run against the same
  stack.

Result column: **PASS** means it behaved as expected, **DEFECT** means it was fixed in the commit shown, and **Q**
means it is questionable but by design (listed at the end, not changed).

## 1. Subscriptions and billing (34 attacks)

| # | Attack | Expected | Actual | Result | Fix |
|---|---|---|---|---|---|
| B1 | A Starter school calls a Growth-only path spelled `/api/%74ransport/vehicles` (also `/api/transport/%76ehicles`) | 403 plan gate | **200 with data** (MockMvc, and real Tomcat + Caddy); after the fix 403 | DEFECT (high) | be46ea5 |
| B2 | `/api/library/%72ules`, `/api/%6Ce/library`, `/api/exams/report%2Dcards` | 403 or 404 | 403 / 404 | PASS | |
| B3 | Trailing slash `/api/transport/vehicles/` | 403 or 404 | 403 | PASS | |
| B4 | Upper case `/api/TRANSPORT/vehicles` | 403 or 404 | 404 | PASS | |
| B5 | `;x=1`, `//`, `/./`, `/../` | rejected | 400 (Spring Security firewall) | PASS | |
| B6 | A sandbox subscription order left open is completed after the platform switches to Razorpay, then verified | 404, no payment | **200 with a valid signature; verify records the payment**; after the fix 404 | DEFECT (high) | be46ea5 |
| B7 | The same for a parent's fee order (`/api/payments/sandbox/...`) | 404 | **200**; after the fix 404 | DEFECT (high) | be46ea5 |
| B8 | Webhook for an unknown gateway (`paypal`), and `..%2fsandbox` | 404 / 4xx | 404 / 4xx | PASS | |
| B9 | Gateway name in upper case (`SANDBOX`) for an unknown order | handled, ignored | 200 IGNORED (the name is case-insensitive) | PASS | |
| B10 | Signed webhook with an empty body, a body over 64 KB, non-JSON, or no event id | 400 | 400 | PASS | |
| B11 | Captured event for a different amount | IGNORED, no payment | IGNORED, 0 payments | PASS | |
| B12 | A "failed" event arriving after the capture (out of order) | order stays paid | IGNORED, order PAID | PASS | |
| B13 | The same captured event replayed | DUPLICATE | DUPLICATE | PASS | |
| B14 | A second capture with another payment id | one payment only | ALREADY_RECORDED, 1 payment | PASS | |
| B15 | Order with a non-UUID invoice id, or nobody's invoice or order id | 404 | 404 | PASS | |
| B16 | Sandbox complete for nobody's order; outcome `MAYBE` | 404 / 400 | 404 / 400 | PASS | |
| B17 | A teacher creates an order for the school's invoice | 403 | 403 | PASS | |
| B18 | The Super Admin creates a school invoice order | 4xx | 4xx | PASS | |
| B19 | 4 parallel verifies plus the webhook for one order | one payment | all 200, 1 payment | PASS | |
| B20 | A new order for an invoice that is already paid | 409 | 409 | PASS | |
| B21 | Order for an invoice the Super Admin cancelled; verify of an order made before the cancel | 409; nothing recorded | 409; verify answers CANCELLED, 1 payment total | PASS | |
| B22 | A suspended school's still-valid token changes the plan or cancels | 409 | 409 | PASS | |
| B23 | The same token pays its open invoice | allowed (the way back) | 201 | Q | |
| B24 | Student cap: 298 on roll, cap 300, 4 admits at once | 2 created, 2 refused | 2 x 201, 2 x 409; 300 active | PASS | |
| B25 | Quote or change to an unknown plan, a null plan, a JSON array | 400 | 400 | PASS | |
| B26 | Cancel during a trial | 409 | 409 | PASS | |
| B27 | Resume and withdraw with nothing to take back | harmless | 200, no change | PASS | |
| B28 | The Super Admin changes a school plan through the school endpoint | 4xx | 4xx | PASS | |
| B29 | Upgrade on the last day of a monthly period with 1 student | one-day pro-rata invoice, rounded to the paisa | 2 invoices; unit price correct | PASS | |
| B30 | Cancel twice, resume twice | audited once each | once each | PASS | |
| B31 | Renewal of a monthly period from 31 January 2028 (leap year) | renews with no gap | renewed; next period starts the day after | PASS | |
| B32 | Renewal job run twice the same day | second run does nothing | nothing | PASS | |
| B33 | Yearly period starting 29 February 2028 | a valid end date | ends 27 Feb 2029 (365 days, no gap) | PASS / Q | |
| B34 | Renewal of a period ending 31 March | invoice in the new financial year's series | `AKS/29-30/...`, ACTIVE | PASS | |

Already covered by existing tests and re-run in the final `mvn verify` (not attacked again): CSV and xlsx imports
over the plan cap (PlanLimitsIT, SchoolImportIT), downgrade below usage, cross-school billing ids (BillingIT
family).

## 2. Excel bulk import and staff passwords (14 attacks)

| # | Attack | Expected | Actual | Result | Fix |
|---|---|---|---|---|---|
| I1 | Number cell `1E10000000` | read quickly | **14.5 s of CPU and a 10 M-character string per cell**; after the fix it is kept as written, instantly | DEFECT (high) | 927cdbd |
| I2 | Number cell `1E-50000000` | read quickly | **50 M characters in memory**; after the fix instant | DEFECT (high) | 927cdbd |
| I3 | `1E999999999`, or a date-styled `1E20` | row problem | **500 (ArithmeticException)**; after the fix text, and a row problem (`invalidRows: 1`, under 5 s) | DEFECT (high) | 927cdbd |
| I4 | A new class whose name is over 40 characters, with createMissing on | the check shows the problem | **check said `ready: true`, the import then failed with 409 "already exists"**; after the fix a sheet problem in both | DEFECT (low) | 927cdbd |
| I5 | JSON body instead of multipart | 415 | 415 | PASS | |
| I6 | Wrong part name (`upload`) | 400 | 400 | PASS | |
| I7 | A PNG named `school.xlsx` with the Excel content type | 400 | 400 | PASS | |
| I8 | `dryRun=maybe` | 400 | 400 | PASS | |
| I9 | Formula cells | the saved value is used | saved value | PASS | |
| I10 | Hindi, Arabic (right-to-left) and emoji names | kept as written, searchable | kept; found by search | PASS | |
| I11 | The same 50-row file imported twice at the same moment | imported once | one 200, 50 students, no duplicate class | PASS | |
| I12 | An admin sets their own password through `PUT /api/staff/{ownId}/password` (no current password needed) | refused: use Change password | **200, password changed**; after the fix 409 | DEFECT (medium) | ce8e2f1 |
| I13 | The same endpoint for a parent's or an unknown id | 404 | 404 | PASS | |
| I14 | A teacher sets anyone's password | 403 | 403 | PASS | |

Already covered by existing tests (XlsxReaderTest, SchoolImportIT) and re-run in the final verify: zip bombs, too
many entries, path tricks in entry names, XML entity expansion, over-long cells, duplicate admission numbers in
and across sheets, missing classes with createMissing on and off, 2,500 rows in one go, cross-school isolation,
and roles.

## 3. Auth, consent and files (18 attacks)

| # | Attack | Expected | Actual | Result | Fix |
|---|---|---|---|---|---|
| A1 | 30 wrong passwords in parallel (limit 10) | at most 10 checked | **30 x 401** in MockMvc; real stack 23 x 401 and 7 x 429. After the fix 10 x 401 and 20 x 429 on both | DEFECT (medium) | ce8e2f1 |
| A2 | The same check-then-count gap on change password, Super Admin sign-in and password reset | counted before the check | same code path; fixed by the same change | DEFECT (medium) | ce8e2f1 |
| A3 | `POST /api/auth/refres%68` (also `%72efresh`, `logou%74`) with a foreign Origin and the refresh cookie | 403 | **200, a new access token**; after the fix 403, and the session still works | DEFECT (low) | ce8e2f1 |
| A4 | A spoofed `X-Forwarded-For`, a different one for each guess | the limit still applies | applies (MockMvc without a trusted proxy; through Caddy, which overwrites it) | PASS | |
| A5 | Email in upper case or with spaces to get a fresh counter | same counter | same | PASS | |
| A6 | Change password with a 201-character new password, or `{}` | 400 | 400 | PASS | |
| A7 | Reset confirm with a made-up 20-digit code | 400 | 400 | PASS | |
| A8 | Reset request with a 500-character school code and email | 400 | 400 | PASS | |
| A9 | The Super Admin uses the school change-password endpoint | 4xx | 4xx | PASS | |
| A10 | Super Admin refresh with a school refresh cookie, a forged one, a 5,000-character one | 401 | 401 | PASS | |
| A11 | Super Admin logout with a garbage cookie | 2xx, no effect | 2xx | PASS | |
| A12 | A parent who has not consented calls `/api/me/%63hildren`, `/api/%6De/children`, `/api/me/children/` | 403 consent-required or 404 | 403 / 404. The allow-list is matched on the raw path, so a spelling can only close it, never open it | PASS | (test added) |
| F1 | File named `notes.pdf<NUL>.exe` | refused | 400 | PASS | |
| F2 | `report.exe.pdf` holding a real PDF | stored as a PDF (type from content) | PDF | PASS | |
| F3 | A text file with a NUL byte | refused | 400 | PASS | |
| F4 | An Office file whose zip directory points past the end | not recognised | refused | PASS | |
| F5 | Names of only dots and spaces, or with right-to-left override characters | cleaned | `file.pdf`, `fdp.pdf` | PASS | |
| F6 | A PDF that also holds HTML and script | served as a PDF attachment | type PDF; downloads use `nosniff` and attachment | PASS | |

Already covered by existing tests (FilesIT, PasswordResetIT, ConsentGateIT, ConsentIT, ErasureFollowUpIT) and
re-run in the final verify: upload size limits, another school's file id, path traversal in stored keys, reset token
reuse and expiry, and the consent and erasure flows.

## 4. Single-server production setup (19 attacks)

All run with docker on this machine against `infra/single-server` (project `aksweep`, Caddy with its internal CA,
`FILES_STORE=database`), images wrapped around the host-built jar.

| # | Attack | Expected | Actual | Result | Fix |
|---|---|---|---|---|---|
| O1 | `restore.sh --live --yes` with a truncated dump | refused, live database untouched | **safety dump taken, API and web stopped, live database DROPPED, pg_restore failed: 0 tables, Caddy 502 on every page**. After fix the dump goes into a side database first; the failure leaves the stack serving (244 tables, login 200) | DEFECT (high) | a68f04a |
| O2 | Same with random bytes | refused, untouched | after fix "not a valid archive", stack serving | PASS (after O1 fix) | a68f04a |
| O3 | Live restore of a good dump into an empty database (fixed script) | restored, stack healthy | 243 tables, stack healthy, login 200 | PASS | |
| O4 | `restore.sh --drill` with a truncated dump | fails, nothing live touched | "end of file", fails | PASS | |
| O5 | Drill with random bytes | fails | "not a valid archive" | PASS | |
| O6 | Drill with a wrong `.sha256` | refused before restoring | "checksum mismatch" | PASS | |
| O7 | Drill with a good dump | counts match | 243 tables match | PASS | |
| O8 | `backup.sh` with Postgres stopped | fails loudly, no file left | "service db is not running", exit 1, no file | PASS | |
| O9 | `backup.sh` into a nearly full disk (64 KB tmpfs) | fails, no partial dump | "no space left on device", exit 1, no partial dump | PASS | |
| O10 | Full disk and old copies: are older dumps pruned to make room? | - | pruned only after a successful dump | Q | |
| O11 | API process killed (`kill -9`, like the OOM killer) during 40 downloads | in-flight fail, restarts by itself | 502 for in-flight, restarted (restarts=1), healthy again | PASS | |
| O12 | `docker kill` of the API container | - | not restarted (`unless-stopped` treats it as a manual stop) | Q | |
| O13 | Database restarted while 40 requests run | short errors at most | all 40 x 200 | PASS | |
| O14 | Database stopped: what clients see | quick error | 500 "Something went wrong" in 0.05 s; readiness still UP | Q | |
| O15 | `deploy.sh` of a release whose API exits at start | automatic rollback | "bad is not healthy", rolled back to good in 57 s, login 200 | PASS | |
| O16 | Deploy the tag that is already running | refused | "already running" | PASS | |
| O17 | `--rollback` with one version in the history; to a tag with no images; to `good;id` | refused | refused with a clear message | PASS | |
| O18 | Healthy deploy, `--rollback`, `--rollback <tag>`, `--status` | switch both ways, history kept, old images pruned | as expected (about 55 s each) | PASS | |
| O19 | Gate, Origin and parallel-guess attacks (B1, A1, A3) through Caddy with the fixed jar | 403, 403, 10 x 401 + 20 x 429 | as expected | PASS | |

`deploy.sh` was run from a copy without the git checkout and build steps (`deploy.sh <ref>` builds images from
the network). Everything else in it ran unchanged.

## Defects fixed

| Id | Severity | Summary | Commit | Regression test |
|---|---|---|---|---|
| B1 | high | Plan gates matched the raw URI, so `%74ransport` reached a Growth feature on Starter | be46ea5 | SubscriptionNegativeIT |
| B6/B7 | high | Sandbox checkout pages stayed usable after Razorpay went live; a school or parent could mark an order paid | be46ea5 | SandboxSwitchedOffIT |
| I1-I3 | high | A crafted number cell in an xlsx took seconds of CPU and tens of MB per cell, or gave 500 | 927cdbd | XlsxReaderTest, SchoolImportNegativeIT |
| O1 | high | `restore.sh --live` dropped the live database before knowing the dump was readable | a68f04a | manual stack test (above); runbook updated |
| A1/A2 | medium | Rate limit checked before counting, so parallel guesses all got through | ce8e2f1 | LoginRateLimiterTest, AuthNegativeIT |
| I12 | medium | Admin could change their own password without the current one through the staff endpoint | ce8e2f1 | AuthNegativeIT |
| A3 | low | Origin check skipped for an encoded auth path | ce8e2f1 | AuthNegativeIT |
| I4 | low | Over-long new class name: dry run said ready, the import then failed | 927cdbd | SchoolImportNegativeIT |

cf64d62 is a compile fix for a test added in ce8e2f1.

## Questionable but by design (not changed)

- A suspended school can still pay its open invoice (it is the way back), but cannot change or cancel its plan.
- The monthly billing anchor drifts after a short month (a period from the 31st later renews on the 28th). A
  yearly period that starts on 29 Feb ends on 27 Feb, so the next one starts on 28 Feb rather than 1 Mar.
- A pro-rated upgrade invoice can be below Razorpay's minimum order amount (Rs 1). The app does not check this.
- Production compose defaults to `BILLING_PAYMENTS_GATEWAY=sandbox`. Since B6 no sandbox checkout works once
  Razorpay is set, but until it is set a school can mark its own invoice paid.
- A dry run shows `ready:true` and the import can still refuse on the plan cap (documented).
- Student names accept right-to-left override characters and newlines.
- The sign-in limiter is per address and fixed-window: a botnet can spread guesses.
- The platform login key is not trimmed.
- `restore.sh` skips the checksum when there is no `.sha256` (local) or its download fails (S3).
- An upload is buffered in memory, up to about 120 MB per xlsx.
- `deploy.sh --rollback` with no tag after an earlier rollback can move forward again (it picks the latest
  different tag in the history).
- Readiness does not include the database, so a stopped database gives 500s rather than 503s, while the
  container stays "healthy".
- `docker kill` of the API is not restarted (restart policy `unless-stopped`).
- `backup.sh` prunes old copies only after a successful dump, so a full disk stays full until someone acts.

## Not tested, and why

- `deploy.sh <ref>` end to end: it fetches from git and builds images over the network. The rollout, rollback and
  history parts were run from a copy without those steps.
- Anything on AWS: S3 upload and download, SES, the instance role, Let's Encrypt.
- Real Razorpay calls: none are allowed in tests. The Razorpay branch ran against an unreachable base URL with
  fake keys.
- Deep WhatsApp consent and erasure follow-up scenarios beyond the existing ITs. Only the consent gate's path
  handling was attacked (A12).
- Frontend e2e (Playwright) against the changed backend. The fixes needed no UI change; the frontend checks and
  unit tests were run.
- Trial-end edge days and the renewal job racing a Super Admin reactivation: not reached in the time available;
  the existing BillingRenewals ITs cover the single-threaded paths.
- 29 February renewals on a real calendar: the dates were set in the database, not with a clock jump of the JVM.

## Verification

- `cd backend && mvn -o verify`: 871 tests in 166 report files, 0 failures, 0 errors, 0 skipped.
- Frontend: `npx eslint .` clean, `npx next build` ok, `npx tsc --noEmit` clean, `npx vitest run` 1,037 tests in 135
  files passed.
- `cd e2e && npx tsc --noEmit -p .` clean.
- Shell: `sh -n` and `dash -n` on the changed `restore.sh` (shellcheck is not installed here).
