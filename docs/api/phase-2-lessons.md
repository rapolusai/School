# Phase 2 API: lessons and e-learning

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, `Authorization: Bearer
<accessToken>`, errors as RFC 9457 problem details `{ type, title, status, detail, errors?: { field: message } }`.

- Ids from another school answer `404`, like ids that do not exist (row-level security filters them first). An id
  from another school **inside a body** (a section, subject, topic or bank question) answers `400` with a field error.
- `403` with a field error means the record or class belongs to someone else's teaching (see Permissions).
- `409` means the request clashes with what is there: a plan in the wrong state, a stale `version`, a topic that
  lesson plans use, a question in a published test, a test students have started, results already released.
  `detail` says why.
- `yearId` (or `academicYearId` in a body) is optional everywhere and means the current academic year.
- "Today" is today in India. Every change is audited (actions at the end). Online test scores are kept apart from
  exam marks and never reach report cards.

## Permissions

| Permission | Seeded roles | Allows |
| --- | --- | --- |
| `lessons.read` | School Admin, Principal, Teacher | every lesson plan, syllabus, the coverage report, study material, online tests and their results (whole school, read only) |
| `lessons.manage` | School Admin, Teacher | writing lesson plans, syllabi, study material, bank questions and online tests for the sections and subjects the teacher teaches; marking and releasing their tests |
| `lessons.approve` | School Admin, Principal | the approval queue, approving and returning plans, changing any syllabus |
| `settings.manage` | School Admin | switching messages about new online tests on or off |
| `dashboard.view` / `child.view` | Student / Parent | the family endpoints under `/api/me` |

A teacher's reach with `lessons.manage` is the sections and subjects they teach in the year (teacher assignments and
the timetable). With `lessons.approve` as well (School Admin) it is every section and subject. A record (plan,
material, question, test) is changed by its author, or by someone with both `lessons.approve` and `lessons.manage`.
Nobody approves their own plan. `V16__lessons.sql` adds the three permissions to the built-in roles of every existing
school; new schools get them from `RoleCatalog`. Parents and students get `403` from every `/api/lessons` endpoint.

## Settings and pickers — `/api/lessons`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `/settings` | read, manage, approve or `settings.manage` | | `200 { testNotificationsEnabled }` |
| PUT | `/settings` | `settings.manage` | `{ testNotificationsEnabled }` | `200` the same |
| GET | `/options?yearId=` | read | | `200 Options`: years, classes with sections and subjects, `userId`, `wholeSchool`, `canManage`, `canApprove`, `teaching: [{ sectionId, subjectId }]` |
| GET | `/topics?yearId=&classId=&subjectId=` | read | | `200 [{ id, title, unitTitle, targetMonth }]`, the syllabus topics in order (empty without a syllabus) |

`testNotificationsEnabled` is `false` until the school switches it on (see Notifications).

## Syllabus — `/api/lessons/syllabi`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `?yearId=` | read | | `200 SyllabusRow[]` (class, subject, units, topics, planned periods, last change) |
| GET | `/{id}` | read | | `200 SyllabusDetail` with `units[].topics[]` (`plannedPeriods`, `targetMonth`, `hasPlans`), `version`, `canEdit` |
| POST | | manage or approve | `SyllabusForm` | `201 SyllabusDetail`; `409` when the class already has one for the subject in the year |
| PUT | `/{id}` | manage or approve | `SyllabusForm` with `version` | `200 SyllabusDetail`; `409` on a stale version or when a removed topic has lesson plans |
| DELETE | `/{id}` | manage or approve | | `204`; `409` while lesson plans use it |
| POST | `/copy` | manage or approve | `{ fromYearId, toYearId, classIds? }` | `200 { copied, skipped }` |
| POST | `/import?dryRun=true&yearId=` | manage or approve | `{ csv }` | `200 SyllabusImportResult`; a real run with a bad row is `400` with the result |

`SyllabusForm` is `{ academicYearId?, classId, subjectId, version?, units: [{ id?, title, topics: [{ id?, title,
plannedPeriods, targetMonth? }] }] }`. Units and topics keep their order; ones with an `id` are kept (and the plans that
point at them), ones without are added, ones left out are removed. Titles 1–200 characters, planned periods 1–200,
target month `YYYY-MM` within the year, at most 40 units, 60 topics a unit and 600 in all. The class and subject
cannot change. Teachers change syllabi of the subjects they teach in the class; approvers any.

**Copy** puts each syllabus of `fromYearId` into `toYearId` with every target month moved by the months between the
two years' starts. Pairs that already have a syllabus in the target year, or that the caller may not change, are
skipped. Leaving `classIds` out copies every class.

**CSV import** — one topic per row, header names case-insensitive: `class`, `subject`, `unit`, `topic`, optional
`periods` (1 by default) and `target_month` (`2026-07` or `Jul 2026`). Rows of the same class and subject make one
syllabus, units in the order they first appear. A class and subject that already has a syllabus in the year is
refused. The result is `{ dryRun, committed, totalRows, validRows, errors: [{ row, column, message }], preview: [...],
ignoredColumns }`. A dry run only checks; a real run writes everything in one transaction, or nothing when any row has
a problem.

## Lesson plans — `/api/lessons/plans`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `?yearId=&mine=&status=&sectionId=&subjectId=&teacherId=&from=&to=` | read | | `200 PlanRow[]`, by week |
| GET | `/queue?yearId=` | approve | | `200 PlanRow[]`: submitted plans of other people, oldest submission first |
| GET | `/{id}` | read | | `200 PlanDetail` with `canEdit`, `canSubmit`, `canReview`, `canMarkTaught` |
| POST | | manage | `PlanForm` | `201 PlanDetail` (a draft) |
| PUT | `/{id}` | manage (own) | `PlanForm` with `version` | `200 PlanDetail`; only draft or returned plans |
| DELETE | `/{id}` | manage (own) | | `204`; only draft or returned plans |
| POST | `/{id}/submit` | manage (own) | | `200 PlanDetail`, status `SUBMITTED` |
| POST | `/{id}/approve` | approve | `{ comment? }` (optional body) | `200 PlanDetail`, status `APPROVED` |
| POST | `/{id}/return` | approve | `{ comment }` (required) | `200 PlanDetail`, status `RETURNED` |
| POST | `/{id}/taught` | manage (own) | `{ taughtOn }` | `200 PlanDetail`, status `TAUGHT`; also changes the day of a taught plan |
| POST | `/{id}/untaught` | manage (own) | | `200 PlanDetail`, back to `APPROVED` |

`PlanForm` is `{ academicYearId?, sectionId, subjectId, topicId?, title?, weekStart, planDate?, periods, objectives?,
teachingMethod?, resources?, resourceLinks?, homeworkIdea?, assessmentNote?, version? }`. The section and subject must
be ones the teacher teaches. `weekStart` may be any day; it is stored as that week's Monday, and the week must overlap
the academic year. `planDate` (optional) is a day of that week. A topic must belong to the class's syllabus for the
subject in the year; without a title the plan takes the topic's title, and one of the two is required. `periods`
1–20; objectives, method and resources up to 2,000 characters, homework idea and assessment up to 1,000;
`resourceLinks` one `http(s)://` link per line.

Statuses: `DRAFT` → `SUBMITTED` → `APPROVED` or `RETURNED` (with the approver's comment, shown to the teacher, who
edits and submits again) → `TAUGHT` on a day that is not in the future and inside the year. A taught plan counts its
topic as covered in its section.

## Syllabus coverage — `/api/lessons`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/coverage?yearId=&classId=&sectionId=&subjectId=&behindOnly=` | read | `200 CoverageReport { yearId, yearName, month, sections, averagePercent, behind, rows }` |
| GET | `/coverage.csv?…` (same filters) | read | `200 text/csv`, `syllabus-coverage.csv` |
| GET | `/coverage/detail?yearId=&sectionId=&subjectId=` | read | `200 { summary, units: [{ title, topics: [{ title, targetMonth, taught, taughtOn, teacherName, overdue, plans }] }] }` |

One row per section and subject that has a syllabus: `topics`, `taught`, `percent` (one decimal, half up; 0 without
topics), `due` (topics whose target month has ended), `overdue` (due but not taught), `expectedPercent` (due share),
`behind` (any topic overdue), `lastTaughtOn`, and the teacher of the section's latest plan for the subject. A topic is
taught once a plan for it in that section is marked taught. The report is in the reports hub under Academics (for
`lessons.read`) with filters, CSV and print. The CSV is UTF-8 with a byte order mark (Excel shows Hindi names), CRLF
line ends, formula-like text defused; columns `Class, Section, Subject, Teacher, Topics, Taught, Percent covered,
Due by now, Overdue, Behind schedule, Last taught`.

## Study material — `/api/lessons/material`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `?yearId=&classId=&sectionId=&subjectId=&mine=` | read | | `200 MaterialView[]` (newest publish date first; a section filter includes whole-class material) |
| GET | `/{id}` | read | | `200 MaterialView` |
| POST | | manage | `MaterialForm` | `201 MaterialView` |
| PUT | `/{id}` | manage (own) | `MaterialForm` | `200 MaterialView` |
| DELETE | `/{id}` | manage (own) | | `204`, its files too |
| POST | `/{id}/files` | manage (own) | multipart `file` | `200 MaterialView`; at most 5 files, homework attachment limits and types |
| DELETE | `/{id}/files/{fileId}` | manage (own) | | `200 MaterialView` |

`MaterialForm` is `{ academicYearId?, classId, sectionId? (null: every section), subjectId, topicId?, title, notes?,
linkUrl?, publishOn? }`: title up to 200, notes up to 5,000, an `http(s)://` link up to 1,000, a publish date in the
year (today by default). Teachers add material for subjects they teach in the class. Students of the class (and their
parents) see it from the publish date.

## Question bank — `/api/lessons/questions` (`lessons.manage`)

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `?classId=&subjectId=&topicId=&type=&difficulty=&q=&mine=` | | `200 QuestionView[]` the caller may use: shared ones and their own (approvers who manage: all) |
| GET | `/{id}` | | `200 QuestionView` (options with `correct`, `usedInTests`, `locked`) |
| POST | | `QuestionForm` | `201 QuestionView` |
| PUT | `/{id}` | `QuestionForm` | `200 QuestionView`; `409` while the question is in a published test |
| DELETE | `/{id}` | | `204`; `409` while it is in any test |
| POST | `/import?dryRun=true` | `{ csv }` | `200 QuestionImportResult`; a real run with a bad row is `400` with the result |

`QuestionForm` is `{ classId, subjectId, topicId?, type, difficulty, bloomLevel?, text, answerText?, explanation?,
marks, shared?, options? }`. Types: `MCQ_SINGLE` (2–6 options, exactly one correct), `MCQ_MULTI` (at least one
correct), `TRUE_FALSE` (`answerText` `TRUE` or `FALSE`), `SHORT_ANSWER` (`answerText` is an optional model answer
for the marker) and `FILL_BLANK` (`answerText` holds the accepted answers, one per line or separated by `|`).
Difficulty `EASY`, `MEDIUM`, `HARD`; Bloom `REMEMBER` … `CREATE` (optional); marks 0.25–100 with two decimals;
question up to 2,000 characters, each option up to 500, explanation up to 2,000. `q` searches the question text.
Questions are shared with every teacher of the school unless the author keeps them private.

**CSV import** — one question per row: `class`, `subject`, `type` (mcq, mcq_multi, true_false, short_answer,
fill_blank), `difficulty` (easy, medium, hard), `marks` (1 by default), `question`, `option_1` … `option_6`, `answer`
(option numbers such as `2` or `1;3`, `TRUE`/`FALSE`, accepted answers separated by `|`, or a model answer), and
optionally `explanation`, `topic` (a topic of the class's syllabus this year), `bloom`, `shared` (yes by default).
Every row is checked like a question typed in the app; all or nothing.

## Online tests — `/api/lessons/tests`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `?yearId=&mine=` | read | | `200 TestRow[]` with state, sections, questions, total marks, students, submitted, answers to mark, released |
| GET | `/{id}` | read | | `200 TestDetail` with its questions (bank question, marks), `canEdit`, `attempts` |
| POST | | manage | `TestForm` | `201 TestDetail` (a draft) |
| PUT | `/{id}` | manage (own) | `TestForm` with `version` | `200 TestDetail`; only a draft; class and subject only while it has no questions |
| DELETE | `/{id}` | manage (own) | | `204`; `409` once students have taken it |
| PUT | `/{id}/questions` | manage (own) | `{ questions: [{ questionId, marks? }] }` | `200 TestDetail`; replaces the list in order (draft only) |
| POST | `/{id}/questions/random` | manage (own) | `{ count, topicId?, difficulty?, type? }` | `200 TestDetail`; adds `count` (1–50) random bank questions not in it yet |
| POST | `/{id}/publish` | manage (own) | | `200 TestDetail`; needs questions and a closing time ahead |
| POST | `/{id}/unpublish` | manage (own) | | `200 TestDetail`; only before anyone starts it |
| POST | `/{id}/close` | manage (own) | | `200 TestDetail`; closes now, attempts in progress end now |
| GET | `/{id}/results` | read | | `200 TestResults` |
| GET | `/{id}/attempts/{attemptId}` | read | | `200 AttemptReview` (every answer with the correct ones, marks, feedback, `canMark`) |
| PUT | `/{id}/attempts/{attemptId}/marks` | manage (own) | `{ marks: [{ answerId, marks, feedback? }] }` | `200 AttemptReview`; submitted attempts only, marks 0 to the question's marks |
| POST | `/{id}/release` | manage (own) | | `200 TestResults`; `409` until the test has closed and every answer is marked |

`TestForm` is `{ academicYearId?, classId, subjectId, sectionIds (1–30 of the class, ones the teacher teaches the
subject in), title, instructions?, opensAt, closesAt, durationMinutes (1–300), attemptsAllowed? (1–5, default 1),
shuffleQuestions, shuffleOptions, showAnswers (WITH_RESULTS or NEVER), version? }`; it closes after it opens and at
most 90 days later. A test has at most 100 questions from the bank of its class and subject; a question's marks in
the test default to its bank marks. States for staff: `DRAFT`, `SCHEDULED` (published, not open yet), `OPEN`,
`CLOSED`. Bank questions in a published test are locked.

`TestResults` gives per student (in the test's sections) the status (`NOT_STARTED`, `IN_PROGRESS`, `SUBMITTED`,
`MARKING`), the best submitted attempt (highest score, then earliest), score, percent and answers to mark; per
question the answered count, fully correct count and percent, and average marks; and `releaseBlocker` (why the results
cannot be released yet, or null).

**Marking.** Objective answers are marked when the attempt is submitted: one-answer MCQ full marks for exactly the
correct option; several-answer MCQ partial marks, marks × (right picks − wrong picks) / correct options, never below 0;
true/false on a match; fill in the blank on a match with any accepted answer, ignoring case and extra spaces. Short
answers wait for the teacher (a blank one is 0). Marks are rounded to two decimals, half up.

## Students and parents — `/api/me`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/study` | `dashboard.view` (student) | `200 Study { studentId, studentName, classLabel, today, material, tests }` |
| GET | `/children/{studentId}/study` | `child.view` (parent) | `200 Study` of their own child; `404` otherwise |
| GET | `/tests/{testId}` | `dashboard.view` (student) | `200 TestIntro { test, instructions, attempts }`; `404` when not in the student's section |
| POST | `/tests/{testId}/attempts` | `dashboard.view` (student) | `200 LearnerAttempt`: starts an attempt or returns the one in progress; `409` before it opens, after it closes or with no attempts left |
| GET | `/attempts/{attemptId}` | `dashboard.view` (student) | `200 LearnerAttempt` |
| PUT | `/attempts/{attemptId}/answers/{answerId}` | `dashboard.view` (student) | `{ optionIds?, text? }` → `200 LearnerAttempt`; `409` after the deadline (the attempt is then submitted) |
| POST | `/attempts/{attemptId}/submit` | `dashboard.view` (student) | `200 LearnerAttempt` |

A student sees the material of their class (whole-class or their section) from its publish date, and the published
tests of their section (states `UPCOMING`, `OPEN`, `CLOSED`). An attempt's questions (and options) are in the order the
student sees them, shuffled if the teacher chose so; correct answers are never sent while it runs. The server keeps
the clock: the deadline is the duration from the start, or the test's close if sooner; `secondsLeft` and `serverNow`
come with every response; answers are taken until the deadline plus a 10-second grace; afterwards the attempt is
submitted automatically with what was saved. Scores, and the correct answers and explanations when the test shows
them, appear only after the teacher releases the results. A parent sees the child's material and released scores,
read only. Another family's child, or another student's attempt, is `404`.

## Dashboard

`GET /api/dashboard` has `lessons: { plansToSubmit, answersToMark, plansToApprove }` (null without
`lessons.manage` or `lessons.approve`): the signed-in teacher's draft or returned plans for this week or earlier, the
short answers waiting in their online tests, and for approvers the plans waiting for approval. The staff dashboard
shows them as tiles linking to Lessons; families get a Study card on their home page and a Study menu item.

## Notifications

Off by default. When a school switches `testNotificationsEnabled` on, publishing a test queues one message per student
to the primary parent or guardian (`lessons.online_test`, on the school's alert channel and language) and one to the
student's own sign-in email (`lessons.online_test_student`) through the notifications outbox, which simulates sending
and respects quiet hours; dedupe keys make each go once per test and student. No message is sent anywhere else.

## Privacy

Only what the feature needs is stored: answers, marks and feedback, with the student record's id. When a student's
data is erased (`StudentDataErased`), the text they typed in online tests and the teachers' feedback on it are
cleared; scores stay as records of the test. Lessons tell school setup (`AcademicsUsage`) which sections and years are
in use, so neither is deleted from under plans, material, syllabi or tests.

## Audit

Actions: `lessons_settings.updated`; `syllabus.created|updated|deleted|copied|imported`;
`lesson_plan.created|updated|deleted|submitted|approved|returned|taught|untaught`;
`study_material.created|updated|deleted|file_added|file_removed`; `question.created|updated|deleted|imported`;
`online_test.created|updated|deleted|questions_set|published|unpublished|closed|marked|released`;
`test_attempt.started|submitted`. Entity types: `lessons_settings`, `syllabus`, `lesson_plan`, `study_material`,
`question`, `online_test`, `test_attempt`. Details carry titles and counts, never answers or marks.

## Events

The module publishes no new events. It listens to `StudentDataErased` (privacy) and implements `AcademicsUsage`; the
dashboard reads `LessonsInsights`.

## Demo data

The demo school gets, in the current year: the Class 5 syllabus for Mathematics, English and Science (Environmental
Studies), with the topics due so far taught in 5 A (English one topic behind); Ravi Kumar's lesson plans (taught,
approved, one waiting for the principal, a draft and one returned with a comment); two pieces of study material; 30
Mathematics bank questions; a closed online test that Arjun took, marked and released; and an open test.
