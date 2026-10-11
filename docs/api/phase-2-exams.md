# Phase 2 API: exams and report cards

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, `Authorization: Bearer
<accessToken>`, errors as RFC 9457 problem details `{ type, title, status, detail, errors?: { field: message } }`.

- Ids from another school answer `404`, like ids that do not exist (row-level security filters them first). An id
  from another school **inside a body** (a term's academic year, an exam's classes, a paper's subject) answers `400`
  with a field error.
- `409` means the request clashes with what is there: a duplicate name, overlapping terms, a stale mark sheet version,
  marks already entered, results already published, or sheets not yet locked. `detail` says why.
- Marks, maximum marks, percentages and weights have at most two decimals and are worked out with `BigDecimal`:
  a percentage is rounded once (half up) to two decimals, and grades and pass/fail are decided on that figure.
- "Today" is today in India. Every change is audited (actions at the end); marks themselves never go into the audit
  details, only counts.

## Permissions

| Permission | Seeded roles | Allows |
| --- | --- | --- |
| `exams.read` (new) | School Admin, Principal, Teacher | the exam structure, date sheets, hall tickets, results, report cards, result analysis (whole school, read only) |
| `exams.manage` (existing) | School Admin, Principal, Teacher | entering and submitting marks, co-scholastic grades, remarks and the holistic progress card for the teacher's own scope |
| `exams.setup` (new) | School Admin, Principal | grading schemes, class schemes, exam settings, terms, exams, papers and weightage; publishing date sheets |
| `exams.publish` (new) | School Admin, Principal | publishing and withdrawing results, unlocking submitted marks, entering marks for every section |
| `dashboard.view` / `child.view` | Student / Parent | the family endpoints under `/api/me` |

A teacher's scope with `exams.manage` (and no `exams.publish`) is the subjects they teach in each section (teacher
assignments and timetable) plus every subject of the sections they are class teacher of; only the class teacher
enters co-scholastic grades, remarks and the holistic progress card. `V13__exams.sql` adds the three new permissions
to the built-in roles of every existing school; new schools get them from `RoleCatalog`. Parents and students get
`403` from every `/api/exams` endpoint.

## Grading — `/api/exams`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `/grading-schemes` | read | | `200 GradingScheme[]` |
| GET | `/grading-schemes/{id}` | read | | `200 GradingScheme` |
| POST | `/grading-schemes` | setup | `SchemeRequest` | `201 GradingScheme` |
| PUT | `/grading-schemes/{id}` | setup | `SchemeRequest` | `200 GradingScheme` |
| DELETE | `/grading-schemes/{id}` | setup | | `204`; `409` while any class uses it or it is the co-scholastic scheme |
| POST | `/grading-schemes/restore` | setup | | `200 GradingScheme[]`: adds back built-in schemes that were deleted |
| GET / PUT | `/settings` | read / setup | `ExamSettingsRequest` | `200 ExamSettings` |
| GET | `/class-schemes?yearId=` | read | | `200 ClassSchemes` (current year by default) |
| PUT | `/class-schemes?yearId=` | setup | `{ classIds, schemeId \| null }` | `200 ClassSchemes`; `null` goes back to the board's default |

Every school gets five built-in schemes at sign-up (and existing schools in V13), each with pass mark 33% per subject
and overall: **CBSE classes 1 to 8** (A1 91%+ … E), **CBSE classes 9 and 10** (A1 … E2, with grade points),
**CBSE co-scholastic** (A, B, C), **ICSE percentage** and **State board**. Schools may edit, copy or delete them.
A class uses one scheme per academic year; without its own choice it uses the board's default (CBSE: classes 9 and
up the nine-point scale, others 1 to 8; ICSE: ICSE; State, IB and Cambridge: State). A scheme has 1–20 bands with
distinct grades and starting percentages, one starting at 0%, grade points 0–10 with one decimal or none.
Settings: `ranksEnabled` (rank in section on results and cards, off by default), `hpcUpToLevel` 0–12 (classes up to
this level get the NEP 2020 holistic progress card; default 5; LKG and UKG are level 0) and the co-scholastic scheme.

## Terms, exams, papers and date sheets — `/api/exams` (writes need `exams.setup`)

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/structure?yearId=` | | `200 ExamStructure`: the year's terms with their exams and classes (read) |
| POST | `/terms` | `{ academicYearId, name, startsOn, endsOn }` | `201 ExamStructure`; inside the year, no overlap, unique name |
| PUT / DELETE | `/terms/{id}` | as POST (year ignored) | `200 ExamStructure`; `409` to delete a term with exams |
| PUT | `/years/{yearId}/term-weights` | `{ weights: [{ id, weight }] }` | `200`; every term once, adding up to exactly 100 |
| PUT | `/terms/{termId}/exam-weights` | same | `200`; every exam of the term once, adding up to 100 |
| POST | `/` | `{ termId, name, instructions?, classIds }` | `201 ExamStructure` |
| PUT / DELETE | `/{id}` | as POST (term ignored) | `200`; `409` to drop a class, or delete the exam, once marks are entered |
| GET | `/{examId}/classes/{classId}` | | `200 ExamClassDetail`: papers, clashes, the class's subjects, publication state (read) |
| PUT | `/{examId}/classes/{classId}/papers` | `{ papers: PaperRequest[] }` (max 40) | `200 ExamClassDetail`; replaces the list |
| POST | `/{examId}/classes/{classId}/date-sheet/publish` | | `200`; every paper dated, no clashes; again after changes |
| POST | `/{examId}/classes/{classId}/date-sheet/withdraw` | | `200`; hides it and removes its calendar entries |
| GET | `/{examId}/classes/{classId}/hall-tickets?sectionId=` | | `200 HallTickets` for printing (read) |

```ts
type PaperRequest = { subjectId: string; maxTheory: number; maxPractical: number | null; maxInternal: number | null;
  date: string | null; startTime: string | null; durationMinutes: number | null; room: string | null };
```

A paper's subject must be taught in the class; its maximum marks add up to 1–1,000 (a part with 0 is not part of the
paper). Date, start time and duration (15–600 minutes) come together or not at all, inside the academic year. A paper
with marks cannot be removed, its maximum cannot go below a mark already entered, and it is fixed once results are
published. **Clashes** (`OVERLAP`, `HOLIDAY`, `SUNDAY`) are listed on the detail and block publishing: two papers of
the class at the same time in this or any other exam of the year, or a paper on a whole-school holiday or a Sunday.
Publishing shows the date sheet to the class's families and adds each paper to the school calendar as an `EXAM`
entry for the class (through the communication module's `CalendarService`; later changes update the entries).

## Marks — `/api/exams`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `/marks?yearId=` | read, manage or publish | | `200 MarksOverview`: every sheet in scope with its state (progress view) |
| GET | `/papers/{paperId}/sections/{sectionId}/marks` | read, manage or publish | | `200 MarkSheet` |
| PUT | `/papers/{paperId}/sections/{sectionId}/marks` | manage or publish | `{ version, entries }` | `200 MarkSheet` (draft) |
| POST | `/papers/{paperId}/sections/{sectionId}/marks/submit` | manage or publish | `{ version }` | `200 MarkSheet` (locked) |
| POST | `/papers/{paperId}/sections/{sectionId}/marks/unlock` | publish | `{ reason }` (1–500) | `200 MarkSheet` (draft again) |

Each entry is `{ studentId, theory, practical, internal, code }`: marks for each part of the paper (0 to that part's
maximum, two decimals), or a code with no marks: **AB** absent (counts as zero), **ML** medical and **EX** exempt
(both left out of the totals). All blank clears a line; lines not sent stay. `version` is the sheet's version as read
(`null` before the first save); an older version answers `409` with `errors.version` instead of overwriting someone
else's marks. Submitting needs marks or a code for every student of the section and locks the sheet; only
`exams.publish` unlocks it, with a reason that is audited and shown on the sheet, and not while the results are
published. Staff outside their scope get `403`; a section not in the exam's class `404`.

## Results, term records and report cards — `/api/exams`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/{examId}/sections/{sectionId}/results` | read | `200 SectionResults`: each student's subjects, total, %, grade, GPA, result, rank |
| POST | `/{examId}/classes/{classId}/results/publish` | publish | `200 ExamClassDetail`; `409` while any sheet is not locked |
| POST | `/{examId}/classes/{classId}/results/withdraw` | publish | `200`; body `{ reason }`; unsent result messages are skipped |
| GET / PUT | `/terms/{termId}/sections/{sectionId}/records` | read, manage or publish / class teacher or publish | `200 TermRecords` |
| GET | `/report-cards?sectionId=&termId=` or `&yearId=` | read | `200 ReportCards` for every student of the section |
| GET | `/report-cards/students/{studentId}?termId=` or `?yearId=` | read | `200 ReportCards` with one card |
| GET | `/{examId}/classes/{classId}/analysis` | read | `200 ExamAnalysis` |
| GET | `/{examId}/classes/{classId}/analysis.csv` | read | `200 text/csv` (UTF-8 with BOM), audited as `report.exported` |

**Result rule.** A subject passes at the scheme's pass mark (33% by default). An exam's result is `INCOMPLETE` while
any subject's marks are missing, `PASS` when every counted subject passes and the overall percentage reaches the
overall pass mark, else `NEEDS_IMPROVEMENT`. The GPA averages the counted subjects' grade points (schemes with grade
points only). Ranks follow standard competition ranking ("1, 2, 2, 4"), only when the school turns them on.
**Term cards** have the term's exams as columns and each subject's percentage weighted by exam weight; **year cards**
have the terms as columns weighted by term weight. Exams or terms without a percentage yet are left out and the rest
re-weighted. Cards carry attendance over the term or year (attendance module), co-scholastic grades, the class
teacher's remark (≤ 500) and, up to `hpcUpToLevel`, the holistic progress card: a level (`BEGINNER`, `PROGRESSING`,
`PROFICIENT`, `ADVANCED`) for six domains and the teacher's, child's and parent's reflections (≤ 1,000 each).
**Publishing** is explicit and per exam and class; it is audited (`results.published`) with the publisher's name,
and families see only published results (staff see every exam). The analysis gives averages, highest and lowest,
pass rates, the grade spread, each section, the students below the pass mark and the change from the previous exam
of the class.

## Families

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/me/results?yearId=` | `dashboard.view` (a student's own sign-in) | `200 FamilyResults` |
| GET | `/api/me/report-card?termId=` or `?yearId=` | `dashboard.view` | `200 ReportCards` (published results only) |
| GET | `/api/me/children/{studentId}/results?yearId=` | `child.view` | `200 FamilyResults` |
| GET | `/api/me/children/{studentId}/report-card?termId=` or `?yearId=` | `child.view` | `200 ReportCards` |

`FamilyResults` lists the published exam results (percent, grade, result), which terms have published results, and
the published date sheets still to come. A sign-in not linked to a student, or a child who is not the parent's, is
`404`. The family app shows them on the dashboard's Results card and at `/app/family/results`.

## Events and messages

- `ExamResultsPublished(tenantId, examId, examClassId, classId, academicYearId, examName, className, publishedAt)`
  is published inside the publishing transaction.
- The exams module listens to it and queues one `exams.results_published` message (English and Hindi) per student to
  the primary guardian through the notifications outbox, with the school's alert channel, language and quiet hours.
  Delivery is the existing simulated provider; no real SMS or WhatsApp is sent. Publishing again after a withdrawal
  sends a new message.
- On `StudentDataErased` (privacy module) the remarks and reflections written about the student are cleared; marks
  and grades stay as academic records on the anonymised student.
- The staff dashboard summary gains `exams: { marksToEnter, resultsToPublish } | null`: sheets of papers already
  written that the person may fill in and has not locked, and (with `exams.publish`) exam classes whose sheets are all
  locked but whose results are not published.

## Database

`V13__exams.sql` adds `exams.grading_scheme`, `grade_band`, `exam_settings`, `class_scheme`, `term`, `exam`,
`exam_class`, `paper`, `mark_sheet`, `mark`, `term_record` and `hpc_entry`, each with `tenant_id`, the
`tenant_isolation` policy, grants to the runtime role only and composite `(tenant_id, id)` foreign keys. The demo
school gets two terms (50/50), Periodic Test 1 for Class 5 with locked marks, records and published results, and the
Half-Yearly Examination for Classes 2 and 5 with its date sheet published.

The end-to-end spec `e2e/tests/exams.spec.ts` covers marks entry at 390px (a mark above the maximum, a draft, AB,
submit and lock), the principal publishing, the queued messages, and the parent's report card at 390px.

## Audit actions

`grading_scheme.created`, `.updated`, `.deleted`, `.defaults_added`; `class_scheme.updated`;
`exam_settings.updated`; `exam_term.created`, `.updated`, `.deleted`, `.weights_updated`; `exam.created`,
`.updated`, `.deleted`, `.weights_updated`; `date_sheet.updated`, `.published`, `.withdrawn`; `mark_sheet.saved`,
`.locked`, `.unlocked` (with the reason); `term_record.saved`; `results.published`, `results.withdrawn` (with the
reason); `report.exported` for the CSV.

## Decisions

- `exams.read` is a whole-school read for staff (teachers see every class's results and analysis), like
  `attendance.read`; entering marks stays scoped.
- The analysis download is CSV (UTF-8 with a byte order mark so Excel opens it) rather than .xlsx: the reports
  module's `Xlsx` writer is package-private, and modules use each other only through public types.
- Results can be published only when every sheet of the exam's class is locked, and unlocking is refused while they
  are published (withdraw first).
- Erasure clears free text only; marks stay.
