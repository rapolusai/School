# Phase 3 API: AI assistance

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, `Authorization: Bearer
<accessToken>`, errors as RFC 9457 problem details `{ type, title, status, detail, errors?: { field: message } }`.

- Ids from another school answer `404`, like ids that do not exist (row-level security filters them first).
- `400` with a field error for a bad body; `409` when the request clashes with what is there (`detail` says why).
- Every draft is labelled **Simulated draft** while the simulated provider is in use: responses carry
  `provider: "simulated"` and `simulated: true`, and every screen shows the label.
- Nothing here is shown to parents or students. Every draft, acceptance and change is audited with the provider name.

## Switches and limits (`409`)

| Problem `type` | When |
| --- | --- |
| `urn:akshara:assist:off` | the school has not switched assistance on (it is **off by default**; the demo school has it on) |
| `urn:akshara:assist:feature-off` | the feature (remarks, early warning, papers, writing help) is switched off |
| `urn:akshara:assist:limit-reached` | this month's draft limit is used up (months in India time) |

Each remark drafted, paper drafted and text improved counts one against the monthly limit. Running the watch list does
not count.

## Permissions

| Permission | Seeded roles | Allows |
| --- | --- | --- |
| `assist.use` | School Admin, Principal, Teacher | every `/api/assist` endpoint below except settings changes and the usage log |
| `assist.manage` | School Admin | `PUT /settings`, `GET /usage` |

`V26__assist.sql` adds both to the built-in roles of every existing school; new schools get them from `RoleCatalog`.
Accepting an **overall** remark writes the report card remark through the exams module, so it needs the same reach as
editing term records there (class teacher of the section, or exams publish): otherwise `403`.

## Endpoints — `/api/assist`

| Method | Path | Permission | Body / query | Success |
| --- | --- | --- | --- | --- |
| GET | `/settings` | use | | `200 SettingsView` (switches, `monthlyLimit`, `language` en/hi, `weights`, `provider`, `simulated`, `usedThisMonth`, last run) |
| PUT | `/settings` | manage | `{ enabled, remarksEnabled, earlyWarningEnabled, papersEnabled, writingEnabled, monthlyLimit 0..100000, language, weights: { attendance30, attendance90, marksTrend, feeDues, homework, threshold } }` | `200 SettingsView`; `400 weights.attendance30` when every weight is 0 |
| GET | `/usage?month=YYYY-MM` | manage | | `200 { month, limit, used, tokens, byFeature, rows[] }` rows: user, feature, provider, token estimate, request hash (never the text) |
| GET | `/remarks?termId&sectionId` | use | | `200 RemarkSheet`: students with current report card remark, overall and per-subject drafts, `canAccept` |
| POST | `/remarks/drafts` | use | `{ termId, sectionId, tone: ENCOURAGING\|FORMAL, studentIds?: (max 60), subjectId? }` | `200 RemarkDraftView[]` each with `inputs` and `inputLines` (what was used) |
| PUT | `/remarks/{id}` | use | `{ text ≤500 }` | `200`; `409` once accepted |
| POST | `/remarks/{id}/accept` | use | `{ text }` | `200`; overall remarks saved to the report card |
| GET | `/watch-list?classId&status` | use | | `200 WatchList { lastRunAt, high, medium, low, assignees, entries[] }` |
| POST | `/watch-list/run` | use | | `200 { at, students, listed }` |
| GET | `/watch-list/{id}` | use | | `200 { entry, notes[] }` |
| PUT | `/watch-list/{id}/assignment` | use | `{ userId }` (null clears) | `200`; `400 userId` unless an active user with `assist.use` |
| PUT | `/watch-list/{id}/status` | use | `{ status: OPEN\|FOLLOWING\|RESOLVED }` | `200` |
| POST | `/watch-list/{id}/notes` | use | `{ body ≤1000 }` | `201 { entry, notes }` |
| POST | `/watch-list/{id}/inform-parent` | use | | `200`; queues a message (circular template) to the primary guardian in the notifications outbox; `409` without a phone |
| POST | `/papers/draft` | use | `{ classId, subjectId, title, durationMinutes 10..240, blueprint }` | `200 PaperView` (not saved, `id` null, `warnings[]`); `409` when the bank has no questions |
| GET | `/papers` | use | | `200 PaperRow[]` newest first |
| POST | `/papers` | use | `{ classId, subjectId, title, durationMinutes, instructions, blueprint, sections[1..10], version: null }` | `201 PaperView` |
| GET / PUT / DELETE | `/papers/{id}` | use | PUT: as POST with `version` | `200` / `200` (`409` stale version) / `204` |
| POST | `/writing/improve` | use | `{ text ≤4000 }` | `200 { text, provider, simulated, tokenEstimate }`; `400 text` when blank or when it holds phone or Aadhaar numbers |

Blueprint: `{ sections: [{ name, questions 1..50, marksEach }] (1..10), easyPercent, mediumPercent, hardPercent
(add up to 100, or all 0 for 40/40/20), bloomLevels[], topicIds[] }`. Questions come from the lessons question bank
for the class and subject (shared or own), filtered by syllabus topic and Bloom level, exact marks first.

## Reports — `/api/assist/reports` (use)

| Path | Result |
| --- | --- |
| `/at-risk?classId` and `/at-risk.csv` | the watch list by class (high, medium, low) and every entry with reasons |
| `/drafts-accepted?from=YYYY-MM&to=YYYY-MM` and `.csv` | drafts made and accepted by teacher and month (last 6 months by default, at most 24) |

CSV files are UTF-8 with a byte order mark; cells starting with `= + - @` are defused.

## Early warning score

Nightly at 01:30 India time (`akshara.assist.early-warning.cron`, off in the migrate profile) for every school with
the feature on, and on demand with **Run now**. Rule based and explainable: each signal adds up to its weight in points
times a severity, and each contribution is listed as a reason.

| Signal | Severity |
| --- | --- |
| Attendance, last 30 days / last 90 days | below 60%: full; below 75%: 0.6; below 85%: 0.3 |
| Marks trend, last two exams with results | a drop of 20+ points: full; 15+: 0.8; 8+: 0.5. No drop but below 33%: full, below 40%: 0.6 |
| Fee dues | overdue more than 90 days: full; 45: 0.6; 15: 0.3 |
| Online homework handed in, last 30 days | below 40%: full; 60%: 0.6; 80%: 0.3 |

Default weights 25 / 15 / 25 / 10 / 25, listing threshold 30. Level HIGH at 70, MEDIUM at 50, LOW at the threshold.
Students who fall below the threshold leave the list unless someone is following them up. Infirmary and discipline
signals are **not used**: no module exposes them yet.

## Providers and privacy

`AssistProvider` (`name()`, `generate(AssistRequest)`) is the one seam. The only implementation is
`SimulatedAssistProvider`: deterministic templates in English and Hindi, nothing leaves the server. Selection is
`akshara.assist.provider` (`ASSIST_PROVIDER`, default `simulated`). A real provider is another `@Component`
implementing the interface, conditional on its own property value, with its key in the environment.

What a request holds, and therefore what would leave the school with a real provider: a student's **first name**, the
class and section label, percentage and grade overall and per subject, attendance percentage, the tone, the language;
for papers, title, duration and section shape (question text stays in the school); for writing help, the text the user
typed. `AssistRequest` refuses keys naming Aadhaar, phone, address, email, health, guardian or surname fields, and any
value that looks like a phone or Aadhaar number. The usage log keeps a SHA-256 hash of the request, never its text.

Before any real provider is switched on, the school's **parent consent and DPDP privacy notice must cover it** (who the
processor is, what is sent, where it is processed, how long it is kept) and the provider must be bound by a data
processing agreement. Until then only the simulated provider may be used.

## Where things are saved

- Accepted **overall** remarks: the report card remark (exams term records). Subject remarks and every draft: `assist.remark_draft`.
- Draft papers: `assist.paper_draft`. Print only (A4); there is no export.

## Audit actions

`assist_settings.updated`, `assist_remark.drafted`, `assist_remark.accepted`, `assist_remark.edited`,
`assist_watch.computed`, `assist_watch.assigned`, `assist_watch.status`, `assist_watch.note_added`,
`assist_watch.parent_informed`, `assist_paper.drafted`, `assist_paper.saved`, `assist_paper.updated`,
`assist_paper.deleted`, `assist_writing.improved`. No domain events are published.
