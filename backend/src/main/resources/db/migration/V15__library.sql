-- Phase 2, library: the school's catalogue (titles with ISBNs and their copies, each with an accession number that is
-- also its barcode), borrowing rules per member type, circulation (loans, renewals, returns, lost and damaged copies),
-- holds (a waiting queue per title), fines with their collection or waiver, and stock verification. Members are the
-- school's own active students and staff; only their ids are kept here, never names or contact details.
-- Every table carries tenant_id and the same tenant_isolation policy as V2/V3, and every link between school tables is
-- a composite (tenant_id, id) foreign key. Also adds the built-in Librarian role and the library permissions.

create schema if not exists library;

-- ---------------------------------------------------------------- settings (one row per school, made on first use)

create table library.settings (
    id                 uuid primary key,
    tenant_id          uuid        not null references platform.tenant (id),
    -- Accession numbers are the prefix and a running number: LIB00001, LIB00002, ... (also the barcode on the copy).
    accession_prefix   varchar(8)  not null default 'LIB',
    last_accession_seq bigint      not null default 0,
    -- School days a returned copy is kept on the hold shelf for the member who reserved it.
    hold_days          integer     not null default 3,
    -- Overdue reminders to parents through the notifications outbox. Off until the school switches them on.
    reminders_enabled  boolean     not null default false,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    version            bigint      not null default 0,
    constraint uq_library_settings_tenant unique (tenant_id),
    constraint ck_library_settings_prefix check (accession_prefix ~ '^[A-Z0-9/-]{0,8}$'),
    constraint ck_library_settings_seq check (last_accession_seq >= 0),
    constraint ck_library_settings_hold_days check (hold_days between 1 and 14)
);

-- Fine receipts are numbered per financial year, like fee receipts: LIBF/2026-27/000001.
create table library.receipt_counter (
    tenant_id      uuid        not null references platform.tenant (id),
    financial_year varchar(7)  not null,
    last_seq       integer     not null default 0,
    updated_at     timestamptz not null,
    primary key (tenant_id, financial_year),
    constraint ck_library_receipt_counter_year check (financial_year ~ '^[0-9]{4}-[0-9]{2}$')
);

-- ---------------------------------------------------------------- borrowing rules (per member type, editable)

create table library.borrowing_rule (
    id                 uuid primary key,
    tenant_id          uuid        not null references platform.tenant (id),
    member_type        varchar(10) not null,
    max_books          integer     not null,
    loan_days          integer     not null,
    max_renewals       integer     not null,
    fine_per_day_paise bigint      not null,
    max_fine_paise     bigint      not null,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    version            bigint      not null default 0,
    constraint uq_borrowing_rule_member_type unique (tenant_id, member_type),
    constraint ck_borrowing_rule_member_type check (member_type in ('STUDENT', 'STAFF')),
    constraint ck_borrowing_rule_max_books check (max_books between 1 and 20),
    constraint ck_borrowing_rule_loan_days check (loan_days between 1 and 180),
    constraint ck_borrowing_rule_renewals check (max_renewals between 0 and 10),
    constraint ck_borrowing_rule_fine check (fine_per_day_paise between 0 and 100000),
    constraint ck_borrowing_rule_max_fine check (max_fine_paise between 0 and 10000000)
);

-- ---------------------------------------------------------------- catalogue: titles and copies

create table library.title (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    -- ISBNs are stored as digits only; an ISBN-10 is also kept as its ISBN-13 (978 + 9 digits + new check digit).
    isbn13           varchar(13),
    isbn10           varchar(10),
    title            varchar(300) not null,
    subtitle         varchar(300),
    authors          varchar(500),
    publisher        varchar(200),
    publication_year integer,
    edition          varchar(60),
    language         varchar(40),
    subject          varchar(100),
    -- Dewey decimal class or the school's own call number, printed on the spine label.
    call_number      varchar(40),
    shelf_location   varchar(60),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_title_tenant_id unique (tenant_id, id),
    constraint ck_title_isbn13 check (isbn13 is null or isbn13 ~ '^97[89][0-9]{10}$'),
    constraint ck_title_isbn10 check (isbn10 is null or isbn10 ~ '^[0-9]{9}[0-9X]$'),
    constraint ck_title_year check (publication_year is null or publication_year between 1400 and 2100)
);
create unique index uq_title_isbn13 on library.title (tenant_id, isbn13) where isbn13 is not null;
create index ix_title_name on library.title (tenant_id, lower(title));

create table library.copy (
    id           uuid primary key,
    tenant_id    uuid         not null references platform.tenant (id),
    title_id     uuid         not null,
    accession_no varchar(30)  not null,
    price_paise  bigint,
    source       varchar(10)  not null,
    acquired_on  date,
    status       varchar(10)  not null,
    status_note  varchar(300),
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    version      bigint       not null default 0,
    constraint uq_copy_tenant_id unique (tenant_id, id),
    constraint fk_copy_title foreign key (tenant_id, title_id) references library.title (tenant_id, id),
    constraint ck_copy_accession check (accession_no ~ '^[A-Z0-9/-]{1,30}$'),
    constraint ck_copy_price check (price_paise is null or price_paise between 0 and 100000000),
    constraint ck_copy_source check (source in ('PURCHASE', 'DONATION')),
    constraint ck_copy_status check (status in ('AVAILABLE', 'ISSUED', 'RESERVED', 'LOST', 'DAMAGED', 'WITHDRAWN'))
);
create unique index uq_copy_accession on library.copy (tenant_id, accession_no);
create index ix_copy_title on library.copy (title_id);
create index ix_copy_status on library.copy (tenant_id, status);

-- ---------------------------------------------------------------- loans

-- One row per copy lent to a member. A member is a student (student_id) or a member of staff (staff_user_id), never
-- both. A copy has at most one open loan.
create table library.loan (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    copy_id          uuid         not null,
    title_id         uuid         not null,
    member_type      varchar(10)  not null,
    student_id       uuid,
    staff_user_id    uuid,
    issued_on        date         not null,
    due_on           date         not null,
    renewals         integer      not null default 0,
    status           varchar(10)  not null,
    returned_on      date,
    closed_at        timestamptz,
    -- Names are kept as well, so the history still reads correctly after a sign-in is removed.
    issued_by_id     uuid,
    issued_by_name   varchar(200),
    returned_by_id   uuid,
    returned_by_name varchar(200),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_loan_tenant_id unique (tenant_id, id),
    constraint fk_loan_copy foreign key (tenant_id, copy_id) references library.copy (tenant_id, id),
    constraint fk_loan_title foreign key (tenant_id, title_id) references library.title (tenant_id, id),
    constraint fk_loan_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_loan_staff foreign key (tenant_id, staff_user_id) references identity.user_account (tenant_id, id),
    constraint fk_loan_issued_by foreign key (tenant_id, issued_by_id)
        references identity.user_account (tenant_id, id) on delete set null (issued_by_id),
    constraint fk_loan_returned_by foreign key (tenant_id, returned_by_id)
        references identity.user_account (tenant_id, id) on delete set null (returned_by_id),
    constraint ck_loan_member check ((member_type = 'STUDENT' and student_id is not null and staff_user_id is null)
        or (member_type = 'STAFF' and staff_user_id is not null and student_id is null)),
    constraint ck_loan_status check (status in ('ISSUED', 'RETURNED', 'LOST')),
    constraint ck_loan_dates check (due_on >= issued_on and (returned_on is null or returned_on >= issued_on)),
    constraint ck_loan_renewals check (renewals between 0 and 10),
    constraint ck_loan_open check ((status = 'ISSUED') = (closed_at is null)),
    constraint ck_loan_returned check ((status = 'RETURNED') = (returned_on is not null))
);
create unique index uq_loan_open_copy on library.loan (copy_id) where status = 'ISSUED';
create index ix_loan_student on library.loan (tenant_id, student_id) where student_id is not null;
create index ix_loan_staff on library.loan (tenant_id, staff_user_id) where staff_user_id is not null;
create index ix_loan_open_due on library.loan (tenant_id, due_on) where status = 'ISSUED';
create index ix_loan_issued_on on library.loan (tenant_id, issued_on);
create index ix_loan_title on library.loan (title_id);
create index ix_loan_issued_by on library.loan (issued_by_id);
create index ix_loan_returned_by on library.loan (returned_by_id);

-- ---------------------------------------------------------------- fines (kept in the library, not in fees)

-- What a member owes the library: an overdue fine worked out on return, or a charge for a lost or damaged copy. Paid
-- at the library desk (with a receipt number) or waived with a reason; the row stays as the record.
create table library.fine (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    loan_id           uuid         not null,
    copy_id           uuid         not null,
    title_id          uuid         not null,
    member_type       varchar(10)  not null,
    student_id        uuid,
    staff_user_id     uuid,
    kind              varchar(10)  not null,
    amount_paise      bigint       not null,
    days_late         integer,
    note              varchar(300),
    status            varchar(10)  not null,
    receipt_no        varchar(30),
    payment_mode      varchar(15),
    payment_reference varchar(60),
    paid_at           timestamptz,
    collected_by_id   uuid,
    collected_by_name varchar(200),
    waive_reason      varchar(500),
    waived_at         timestamptz,
    waived_by_id      uuid,
    waived_by_name    varchar(200),
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint uq_fine_tenant_id unique (tenant_id, id),
    constraint fk_fine_loan foreign key (tenant_id, loan_id) references library.loan (tenant_id, id),
    constraint fk_fine_copy foreign key (tenant_id, copy_id) references library.copy (tenant_id, id),
    constraint fk_fine_title foreign key (tenant_id, title_id) references library.title (tenant_id, id),
    constraint fk_fine_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_fine_staff foreign key (tenant_id, staff_user_id) references identity.user_account (tenant_id, id),
    constraint fk_fine_collected_by foreign key (tenant_id, collected_by_id)
        references identity.user_account (tenant_id, id) on delete set null (collected_by_id),
    constraint fk_fine_waived_by foreign key (tenant_id, waived_by_id)
        references identity.user_account (tenant_id, id) on delete set null (waived_by_id),
    constraint ck_fine_member check ((member_type = 'STUDENT' and student_id is not null and staff_user_id is null)
        or (member_type = 'STAFF' and staff_user_id is not null and student_id is null)),
    constraint ck_fine_kind check (kind in ('OVERDUE', 'LOST', 'DAMAGED')),
    constraint ck_fine_amount check (amount_paise > 0 and amount_paise <= 100000000),
    constraint ck_fine_status check (status in ('DUE', 'PAID', 'WAIVED')),
    constraint ck_fine_mode check (payment_mode is null
        or payment_mode in ('CASH', 'UPI', 'CARD', 'CHEQUE', 'BANK_TRANSFER')),
    constraint ck_fine_paid check ((status = 'PAID') = (paid_at is not null and receipt_no is not null
        and payment_mode is not null)),
    constraint ck_fine_waived check ((status = 'WAIVED') = (waived_at is not null and waive_reason is not null))
);
create index ix_fine_student on library.fine (tenant_id, student_id) where student_id is not null;
create index ix_fine_staff on library.fine (tenant_id, staff_user_id) where staff_user_id is not null;
create index ix_fine_status on library.fine (tenant_id, status);
create index ix_fine_receipt on library.fine (tenant_id, receipt_no) where receipt_no is not null;
create index ix_fine_loan on library.fine (loan_id);
create index ix_fine_copy on library.fine (copy_id);
create index ix_fine_title on library.fine (title_id);
create index ix_fine_collected_by on library.fine (collected_by_id);
create index ix_fine_waived_by on library.fine (waived_by_id);

-- ---------------------------------------------------------------- holds (reservations), a queue per title

-- A member waits for a title whose copies are all out. When a copy comes back it is kept for the first member in the
-- queue (READY, the copy RESERVED) until hold_until; then it passes to the next member or back to the shelf.
create table library.reservation (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    title_id        uuid         not null,
    member_type     varchar(10)  not null,
    student_id      uuid,
    staff_user_id   uuid,
    status          varchar(10)  not null,
    copy_id         uuid,
    ready_at        timestamptz,
    hold_until      date,
    closed_at       timestamptz,
    created_by_id   uuid,
    created_by_name varchar(200),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint uq_reservation_tenant_id unique (tenant_id, id),
    constraint fk_reservation_title foreign key (tenant_id, title_id) references library.title (tenant_id, id),
    constraint fk_reservation_copy foreign key (tenant_id, copy_id) references library.copy (tenant_id, id),
    constraint fk_reservation_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_reservation_staff foreign key (tenant_id, staff_user_id)
        references identity.user_account (tenant_id, id),
    constraint fk_reservation_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id),
    constraint ck_reservation_member check ((member_type = 'STUDENT' and student_id is not null
        and staff_user_id is null) or (member_type = 'STAFF' and staff_user_id is not null and student_id is null)),
    constraint ck_reservation_status check (status in ('WAITING', 'READY', 'FULFILLED', 'CANCELLED', 'EXPIRED')),
    constraint ck_reservation_ready check (status <> 'READY' or (copy_id is not null and hold_until is not null)),
    constraint ck_reservation_closed check ((status in ('WAITING', 'READY')) = (closed_at is null))
);
create unique index uq_reservation_active_student on library.reservation (title_id, student_id)
    where status in ('WAITING', 'READY') and student_id is not null;
create unique index uq_reservation_active_staff on library.reservation (title_id, staff_user_id)
    where status in ('WAITING', 'READY') and staff_user_id is not null;
create unique index uq_reservation_ready_copy on library.reservation (copy_id) where status = 'READY';
create index ix_reservation_queue on library.reservation (tenant_id, title_id, created_at)
    where status in ('WAITING', 'READY');
create index ix_reservation_student on library.reservation (student_id);
create index ix_reservation_staff on library.reservation (staff_user_id);
create index ix_reservation_copy on library.reservation (copy_id);
create index ix_reservation_created_by on library.reservation (created_by_id);

-- ---------------------------------------------------------------- stock verification

-- Staff scan the copies on the shelves; afterwards the copies that should be there but were not seen are listed.
create table library.stock_check (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    status          varchar(10)  not null,
    note            varchar(200),
    started_by_id   uuid,
    started_by_name varchar(200),
    started_at      timestamptz  not null,
    closed_by_name  varchar(200),
    closed_at       timestamptz,
    -- When the check is closed: how many copies should have been on the shelves, and how many of them were not seen.
    expected_count  integer,
    missing_count   integer,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint uq_stock_check_tenant_id unique (tenant_id, id),
    constraint fk_stock_check_started_by foreign key (tenant_id, started_by_id)
        references identity.user_account (tenant_id, id) on delete set null (started_by_id),
    constraint ck_stock_check_status check (status in ('OPEN', 'CLOSED')),
    constraint ck_stock_check_closed check ((status = 'CLOSED') = (closed_at is not null)),
    constraint ck_stock_check_counts check (
        ((status = 'CLOSED') = (expected_count is not null and missing_count is not null))
        and (missing_count is null or missing_count between 0 and expected_count))
);
-- At most one stock check is open at a time.
create unique index uq_stock_check_open on library.stock_check (tenant_id) where status = 'OPEN';
create index ix_stock_check_started_by on library.stock_check (started_by_id);

create table library.stock_check_item (
    id             uuid primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    stock_check_id uuid         not null,
    copy_id        uuid         not null,
    seen_at        timestamptz  not null,
    seen_by_id     uuid,
    constraint uq_stock_check_item unique (stock_check_id, copy_id),
    constraint fk_stock_check_item_check foreign key (tenant_id, stock_check_id)
        references library.stock_check (tenant_id, id) on delete cascade,
    constraint fk_stock_check_item_copy foreign key (tenant_id, copy_id)
        references library.copy (tenant_id, id) on delete cascade,
    constraint fk_stock_check_item_seen_by foreign key (tenant_id, seen_by_id)
        references identity.user_account (tenant_id, id) on delete set null (seen_by_id)
);
create index ix_stock_check_item_copy on library.stock_check_item (copy_id);
create index ix_stock_check_item_tenant on library.stock_check_item (tenant_id);
create index ix_stock_check_item_seen_by on library.stock_check_item (seen_by_id);

-- The overdue reminder job runs without a school selected, so row-level security hides every loan from it. This
-- function tells it only which schools have reminders on and a student's loan past its due date; it then works as
-- each school in turn.
create function library.tenants_with_overdue_reminders(p_today date)
    returns table (tenant_id uuid)
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select distinct l.tenant_id
    from library.loan l
    join library.settings s on s.tenant_id = l.tenant_id
    where s.reminders_enabled and l.status = 'ISSUED' and l.member_type = 'STUDENT' and l.due_on < p_today
$$;

-- ---------------------------------------------------------------- row-level security

alter table library.settings enable row level security;
create policy tenant_isolation on library.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table library.receipt_counter enable row level security;
create policy tenant_isolation on library.receipt_counter
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table library.borrowing_rule enable row level security;
create policy tenant_isolation on library.borrowing_rule
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table library.title enable row level security;
create policy tenant_isolation on library.title
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table library.copy enable row level security;
create policy tenant_isolation on library.copy
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table library.loan enable row level security;
create policy tenant_isolation on library.loan
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table library.fine enable row level security;
create policy tenant_isolation on library.fine
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table library.reservation enable row level security;
create policy tenant_isolation on library.reservation
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table library.stock_check enable row level security;
create policy tenant_isolation on library.stock_check
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table library.stock_check_item enable row level security;
create policy tenant_isolation on library.stock_check_item
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema library to ${appRole};
grant select, insert, update, delete on library.settings, library.borrowing_rule, library.title, library.copy,
    library.reservation, library.stock_check, library.stock_check_item to ${appRole};
grant select, insert, update on library.receipt_counter to ${appRole};
-- Loans and fines are the library's record of what was lent and what was owed and paid: never deleted.
grant select, insert, update on library.loan, library.fine to ${appRole};
revoke all on function library.tenants_with_overdue_reminders(date) from public;
grant execute on function library.tenants_with_overdue_reminders(date) to ${appRole};

-- ---------------------------------------------------------------- the Librarian role and the library permissions

-- New schools get the Librarian role and these permissions from RoleCatalog. Existing schools keep their stored
-- roles, so the built-in Librarian role is added to every school that has none, and the new codes are appended to the
-- matching built-in roles, once. Runs as the owner, which row-level security does not restrict. Idempotent.
insert into identity.role (id, tenant_id, code, name, permissions, system_role, created_at)
select gen_random_uuid(), t.id, 'LIBRARIAN', 'Librarian',
       array['dashboard.view', 'academics.read', 'leave.request', 'notices.read', 'timetable.read', 'library.read',
             'library.manage'],
       true, now()
from platform.tenant t
where not exists (select 1 from identity.role r where r.tenant_id = t.id and r.code = 'LIBRARIAN');

-- library.read: search the catalogue (every member of staff).
update identity.role
set permissions = array_append(permissions, 'library.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER', 'ACCOUNTANT', 'FRONT_OFFICE', 'LIBRARIAN')
  and not ('library.read' = any (permissions));

-- library.manage: the catalogue, the desk, fines, holds, labels, stock checks, reports and settings.
update identity.role
set permissions = array_append(permissions, 'library.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'LIBRARIAN')
  and not ('library.manage' = any (permissions));
