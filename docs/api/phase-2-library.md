# Phase 2 API: library

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times are
instants, money is in **paise**, and every change is written to the audit trail.

**In this slice:** the Librarian role; the catalogue (titles and copies, ISBN-10/13 checks, accession numbers, CSV
import with a preview, search); borrowing rules per member type; the circulation desk (issue, return, renew, lost
and damaged copies); reservations with a hold shelf; fines collected with a receipt or waived with a reason; barcode
and spine labels (drawn in the browser); stock verification; reports; the family Library page; optional overdue
reminders to parents; demo data.

**Not in this slice:** online book lookup (see "ISBN lookup"), fines paid through school fees or online (fines stay
in the library), e-books, inter-school loans, and app notifications (the `LibraryHoldReady` event is ready for them).

## Permissions and roles

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `library.read` (new) | every staff role: School Admin, Principal, Teacher, Accountant, Front office, Librarian | catalogue search, a title's copies (without who has them), the borrowing rules |
| `library.manage` (new) | School Admin, Librarian | everything else under `/api/library` |
| `dashboard.view` | everyone | `GET /api/me/library` (their own or their children's books) |

New role **`LIBRARIAN`** ("Librarian"): `dashboard.view`, `academics.read`, `leave.request`, `notices.read`,
`timetable.read`, `library.read`, `library.manage`. Migration `V15__library.sql` adds the role to every existing
school and appends the permissions to the matching roles (idempotent). Parent and Student have neither permission.

## Members

A member is `{type: "STUDENT" | "STAFF", id}`: a student id or a staff user id. The library stores only that pair and
reads names, classes and whether someone left through the students, academics and staff modules' public services.
A student who left or staff who left cannot borrow (`400 errors.memberId`), but their history stays.

```ts
type Member = { type: "STUDENT" | "STAFF"; id: string; name: string;
  code: string | null;      // admission number or employee code
  detail: string | null;    // "Class 5 A" or the designation
  active: boolean };
```

## Settings and borrowing rules

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/library/settings` | `library.manage` | `200 {accessionPrefix, nextAccessionNo, holdDays, remindersEnabled}` |
| PUT | `/api/library/settings` | `library.manage` | `200` same; body `{accessionPrefix, holdDays, remindersEnabled}` |
| GET | `/api/library/rules` | `library.read` | `200 RuleView[]`, one per member type |
| PUT | `/api/library/rules/{STUDENT\|STAFF}` | `library.manage` | `200 RuleView`; body `{maxBooks, loanDays, maxRenewals, finePerDayPaise, maxFinePaise}` |

- `accessionPrefix`: up to 8 capital letters, digits, `/` or `-` (may be empty), default `LIB`. Copies are numbered
  prefix + 5 digits (`LIB00001`) from a per-school counter taken under a row lock, so numbers are never reused. A
  changed prefix applies to new copies only. **The barcode is the accession number.**
- `holdDays` 1–14 (default 3): how many open days a returned copy waits on the hold shelf.
- `remindersEnabled`: parent reminders, **off by default** (see "Reminders").
- Rules: `maxBooks` 1–20, `loanDays` 1–180, `maxRenewals` 0–10, `finePerDayPaise` 0–100,000,
  `maxFinePaise` 0–10,000,000 (0 = no cap). Defaults: STUDENT 2 books, 14 days, 1 renewal, ₹1 a day, at most ₹50;
  STAFF 5 books, 30 days, 2 renewals, no fine. `custom` is true once the school changed a rule.

## Dates and fines

The library is closed on **Sundays and the school's whole-school holidays** (the communication module's calendar).
A loan is due `loanDays` after it is lent; when that day is closed, the next open day. Days late are the open days
after the due date up to the return; the overdue fine is days late × `finePerDayPaise`, capped at `maxFinePaise`.
`LoanView.daysOverdue` counts calendar days (what the desk and the family page show as "N days late") and
`fineSoFarPaise` is the fine if the copy came back today.

## Catalogue — `/api/library`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/titles?q=&subject=&page=0&size=25` | `library.read` | `200 TitlePage`; `q` matches title, subtitle, authors, publisher, subject, ISBN (in part) or an exact accession number; by title; `size` ≤ 100 |
| GET | `/subjects` | `library.read` | `200 string[]` in use |
| GET | `/titles/{id}` | `library.read` | `200 TitleDetail`; without `library.manage` copies carry no loan or hold and `queue` is empty |
| POST | `/titles` | `library.manage` | `201 TitleDetail` with its first `copies` (default 1, 0–100) |
| PUT | `/titles/{id}` | `library.manage` | `200 TitleDetail` (copy fields ignored) |
| DELETE | `/titles/{id}` | `library.manage` | `204`; `409` once any copy was lent or reserved (withdraw copies instead) |
| POST | `/titles/{id}/copies` | `library.manage` | `201 TitleDetail`; body `{count 1–100, pricePaise?, source?, acquiredOn?}` |
| PUT | `/copies/{id}` | `library.manage` | `200 CopyView`; body `{pricePaise, source, acquiredOn, status, statusNote}` |
| GET | `/copies/lookup?accessionNo=` | `library.manage` | `200 {copy, title, loan}` for a scanned number |
| GET | `/isbn?value=` | `library.manage` | `200 IsbnLookup` |
| GET | `/labels?titleId=\|from=&to=\|q=` | `library.manage` | `200 LabelCopy[]` by accession number, at most 500 |
| POST | `/titles/import?dryRun=true` | `library.manage` | `200 ImportResult`; body `{csv}` |

```ts
type TitleForm = { isbn?: string | null; title: string; subtitle?; authors?; publisher?; publicationYear?: number;
  edition?; language?; subject?; callNumber?; shelfLocation?;
  copies?: number; pricePaise?: number | null; source?: "PURCHASE" | "DONATION"; acquiredOn?: string | null };
type CopyStatus = "AVAILABLE" | "ISSUED" | "RESERVED" | "LOST" | "DAMAGED" | "WITHDRAWN";
type CopyView = { id; titleId; accessionNo; status: CopyStatus; statusNote; pricePaise; source; acquiredOn;
  loan: { loanId; member: Member; issuedOn; dueOn; overdue } | null;          // library.manage only
  heldFor: { reservationId; member: Member; holdUntil } | null };             // library.manage only
type IsbnLookup = { input; valid: boolean; problem: string | null; isbn13; isbn10;
  existing: { id; title; authors; copies } | null; suggestion: Suggestion | null };
```

- **ISBN**: ISBN-10 or ISBN-13, with spaces or hyphens and a lower-case `x`; the check digit is verified and an
  ISBN-10 is stored as its ISBN-13 (978 + 9 digits + a new check digit) plus the ISBN-10. A bad ISBN is
  `400 errors.isbn` naming the problem; an ISBN the school already has is `409 errors.isbn` naming that title (add
  copies to it instead). A title may have no ISBN.
- A copy's status can be set to AVAILABLE, DAMAGED, LOST or WITHDRAWN while it is not on loan or kept for a hold
  (`409`); ISSUED and RESERVED come only from the desk (`400 errors.status`).
- Price: 0 to 10,00,000 rupees in paise. `source`: PURCHASE (default) or DONATION.

### ISBN lookup

`BookMetadataLookup` is the seam for finding a book's details outside the school. The only implementation,
`OfflineBookMetadataLookup`, finds nothing: **the product makes no outside calls**. The lookup checks the ISBN
offline and fills the form from the school's own catalogue (`suggestion.source = "CATALOGUE"`). A lookup against
Open Library (free, no key) can be added as another implementation **after the owner approves** sending ISBNs to
it; nothing else changes (`suggestion.source = "ONLINE"`). The web form says "Online lookup is off".

### CSV import

Columns, any order, header row required, only `title` required: `title, isbn, subtitle, authors, publisher, year,
edition, language, subject, call_number, shelf_location, copies, price, source, accession_no, acquired_on`. Unknown
columns are listed in `ignoredColumns`. Up to 2,000 rows and 10,000 copies per file; prices in rupees (`250` or
`250.50`); dates `YYYY-MM-DD`, `D-M-YYYY`, `D/M/YYYY` or `D.M.YYYY`. A row whose ISBN (or, without one, title and
authors) matches a title already in the catalogue, or an earlier row, adds copies to it. A row with `accession_no`
is one copy with that number (the old register's number; must be unused); other copies get the next numbers.
`dryRun=true` (the default) checks every row and returns a preview of the first 50 and up to 500 problems
`{row, column, message}`. A real run writes every row in one transaction, or nothing: any bad row answers
`400` with `result` holding the problems. File-level problems (empty, no header, a column twice, too large) are
`400 errors.csv`.

## Desk — `/api/library` (`library.manage`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/members?q=` | `200 Member[]` (active students and staff by name, admission number or employee code; 2+ characters; at most 20) |
| GET | `/members/{type}/{id}` | `200 MemberAccount`: rule, books out, fines due, holds, recent history, `canBorrow`, `blockedReason` |
| POST | `/loans` | `201 LoanView`; body `{accessionNo, memberType, memberId, issuedOn?}` |
| GET | `/loans?status=ISSUED\|RETURNED\|LOST&overdue=&memberType=&memberId=&page=&size=` | `200 LoanPage` |
| POST | `/loans/{id}/renew` | `200 LoanView` (due date counted again from today) |
| POST | `/loans/{id}/lost` | `200 ReturnResult`; body `{chargePaise?, note?}`; charge defaults to the copy's price |
| POST | `/returns` | `200 ReturnResult`; body `{accessionNo, damaged?, chargePaise?, note?}` |
| GET | `/reservations` | `200 HoldView[]` waiting or ready, by title and queue position |
| POST | `/reservations` | `201 HoldView`; body `{titleId, memberType, memberId}` |
| POST | `/reservations/{id}/cancel` | `200 HoldView` |

- Accession numbers are matched case-insensitively (scanners send whatever case the label has): letters, digits,
  `/` and `-`, up to 30 (`400 errors.accessionNo`). An unknown number is `400 errors.accessionNo`.
- Issue `409`s: the copy is lent, kept for someone else's hold, or lost/damaged/withdrawn; the member is at the
  limit (`errors.memberId`) or already has a copy of the title. `issuedOn` records a book lent while the system was
  not in use: not in the future, at most 60 days back (`400 errors.issuedOn`).
- Renew `409`s: no renewals left, the book is overdue (take it back; the fine is worked out then), someone is
  waiting for the title, or the due date would not move.
- Return: a late copy raises an OVERDUE fine; `damaged` sets the copy aside as DAMAGED with an optional DAMAGED
  charge; otherwise the copy goes to the first member waiting (`heldFor`: put it on the hold shelf until
  `holdUntil`) or back on the shelf. Lost: the copy becomes LOST with a LOST charge.
- Holds: only when no copy is on the shelf (`409` "lend it instead"); one per member per title. A hold not collected
  by `holdUntil` expires and the copy goes to the next member or the shelf.

## Fines (`library.manage`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/library/fines?status=DUE\|PAID\|WAIVED&memberType=&memberId=&page=&size=` | `200 {items, page, size, total, totalDuePaise}` |
| POST | `/api/library/fines/collect` | `200 FineReceipt`; body `{fineIds (1–50), mode, reference?}` |
| POST | `/api/library/fines/{id}/waive` | `200 FineView`; body `{reason}` (required) |
| GET | `/api/library/fines/receipt?no=` | `200 FineReceipt` |

Fines are kept **in the library**, not added to school fee accounts. `mode`: CASH, UPI, CARD, CHEQUE or
BANK_TRANSFER; a cheque or transfer needs `reference` (`400 errors.reference`). One member's fines at a time
(`400 errors.fineIds`); already paid or waived is `409`. Receipts are numbered per school and financial year:
`LIBF/2026-27/000042`. A waiver records who and why in the fine and in the audit trail.

## Stock verification — `/api/library/stock-checks` (`library.manage`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `` | `200 StockCheckRow[]` newest first |
| POST | `` | `201 StockCheckRow`; body `{note?}`; `409` while another is open |
| GET | `/{id}` | `200 {check, missing, latest}` |
| POST | `/{id}/scans` | `200 ScanResult`; body `{accessionNo}`; scanning twice is fine (`alreadySeen`) |
| POST | `/{id}/close` | `200` detail; `409` when already closed |
| GET | `/{id}/missing.csv` | `200 text/csv` |

Expected copies are those that should be on the shelves (AVAILABLE, RESERVED, DAMAGED). A scanned copy the
catalogue says is ISSUED, LOST or WITHDRAWN comes back with `warning` set to that status. Closing changes no copy:
the librarian marks missing copies lost from the title page.

## Reports (`library.manage`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/library/summary` | `200 {issued, overdue, dueToday, holdsReady, finesDuePaise}` |
| GET | `/api/library/reports/overdue?memberType=&classId=` | `200 {asOf, rows: LoanView[], totalFinePaise}` |
| GET | `/api/library/reports/issued?date=` | `200 {date, rows}` (default today) |
| GET | `/api/library/reports/popular?from=&to=` | `200 {from, to, rows: {titleId, title, authors, loans, copies}[]}` (default the last 30 days, at most 400 days) |
| GET | `/api/library/reports/member-history?memberType=&memberId=` | `200 {member, loans, fines, finesDuePaise, finesPaidPaise}` |

Each report also has a `.csv` path with the same parameters (`overdue.csv`, `issued.csv`, `popular.csv`,
`member-history.csv`). Downloads are **UTF-8 CSV with a byte-order mark** (opens in Excel with ₹ and Hindi intact)
rather than `.xlsx`, so no new dependency; amounts are in rupees. The web app lists them in `/app/reports` under
"Library" with print and download.

## Families — `GET /api/me/library` (`dashboard.view`)

```ts
type MyLibrary = { members: { type; id; name; className: string | null;
  loans: { title; authors; accessionNo; issuedOn; dueOn; overdue; daysOverdue; renewals; fineSoFarPaise }[];
  holds: { title; status; position; holdUntil }[];
  fines: { title; kind; amountPaise; status; createdAt; receiptNo }[];   // newest first
  finesDuePaise: number }[] };
```

A parent sees their children, a student their own account, a member of staff their own. Nothing about other
members (who else waits for a book) is shown. The web page is "Library" in the family menu.

## Reminders

Off by default, per school (`remindersEnabled`). When on, the parent of a student with an overdue book gets a
reminder on the first day it is late and once a week while it stays out, and a note when a reserved book is ready to
collect. They go through the notifications outbox on the school's alert channel and language, which is
**simulated** (nothing leaves the system); dedupe keys make each one go once. The job runs at 10:00 India time,
Monday to Saturday: `akshara.library.reminders.enabled` (`LIBRARY_REMINDERS_ENABLED`, default true; false in
tests) and `akshara.library.reminders.cron` (`LIBRARY_REMINDERS_CRON`).

## Events

`LibraryHoldReady(tenantId, reservationId, titleId, title, memberType, memberId, holdUntil)` is published in the
transaction that keeps a copy for the first member in a title's queue.

## Audit

Entities `library_title`, `library_copy`, `library_loan`, `library_fine`, `library_hold`, `library_rule`,
`library_settings`, `library_stock_check`. Actions: `library_title.created|updated|deleted`,
`library_titles.imported`, `library_copy.added|updated`, `library_loan.issued|returned|renewed|lost`,
`library_fine.collected|waived`, `library_hold.placed|cancelled|ready|expired`, `library_rule.updated`,
`library_settings.updated`, `library_stock_check.started|copy_seen|closed`. Labels in English and Hindi.

## Data

Schema `library`: `settings`, `receipt_counter`, `borrowing_rule`, `title`, `copy`, `loan`, `fine`, `reservation`,
`stock_check`, `stock_check_item`. Every table has `tenant_id`, row-level security (USING and WITH CHECK), grants to
the application role only, and composite foreign keys `(tenant_id, id)`. Partial unique indexes keep one open loan
per copy, one active hold per member and title, and one open stock check per school.

## Labels (web only)

`/app/library/labels` draws A4 sheets of 3 × 8 barcode labels (Code 128 SVG of the accession number with the title)
and 5 × 13 spine labels (call number and accession number), with a "start at label" offset for part-used sheets.
The Code 128 encoder is in `frontend/src/lib/code128.ts` and tested against known encodings.

## Demo data

`DemoLibraryData`: the librarian Kavitha Rao (`librarian@demo.akshara.test`), 60 titles with one to three copies
(made-up ISBNs with valid check digits), Arjun Sharma (Class 5 A) with an overdue book, two classmates with books due
soon, the demo teacher with one, Kunal Mehta's late return with the fine paid, and Sneha Iyengar waiting for the book
Arjun has.
