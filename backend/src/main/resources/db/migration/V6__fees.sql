-- Phase 1, fees: fee heads, fee structures per class and year with instalments, concessions, late fees, student
-- dues, receipts with gap-free numbers, online payment orders and fee reminders. Money is stored in paise (bigint);
-- nothing here uses floating point. Every table carries tenant_id and the same row-level security policy as V1-V3,
-- and every link between school tables is a composite (tenant_id, id) foreign key.

create schema if not exists fees;

-- ---------------------------------------------------------------- fee heads

create table fees.fee_head (
    id            uuid primary key,
    tenant_id     uuid        not null references platform.tenant (id),
    name          varchar(60) not null,
    kind          varchar(20) not null,
    one_time      boolean     not null default false,
    display_order integer     not null,
    active        boolean     not null default true,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    version       bigint      not null default 0,
    constraint ck_fee_head_kind check (kind in ('TUITION', 'ADMISSION', 'ANNUAL', 'EXAM', 'TRANSPORT', 'LAB', 'OTHER')),
    constraint uq_fee_head_tenant_id unique (tenant_id, id)
);
create unique index uq_fee_head_name on fees.fee_head (tenant_id, lower(name));

-- ---------------------------------------------------------------- fee structures (one per class per academic year)

create table fees.fee_structure (
    id               uuid primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    academic_year_id uuid        not null,
    class_id         uuid        not null,
    status           varchar(10) not null,
    published_at     timestamptz,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null default 0,
    constraint ck_fee_structure_status check (status in ('DRAFT', 'PUBLISHED')),
    constraint ck_fee_structure_published check (status = 'DRAFT' or published_at is not null),
    constraint uq_fee_structure_tenant_id unique (tenant_id, id),
    constraint uq_fee_structure_class_year unique (tenant_id, academic_year_id, class_id),
    constraint fk_fee_structure_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_fee_structure_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id)
);
create index ix_fee_structure_class on fees.fee_structure (class_id);

create table fees.fee_instalment (
    id           uuid primary key,
    tenant_id    uuid        not null references platform.tenant (id),
    structure_id uuid        not null,
    seq          integer     not null,
    label        varchar(40) not null,
    due_date     date        not null,
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    version      bigint      not null default 0,
    constraint ck_fee_instalment_seq check (seq between 1 and 12),
    constraint uq_fee_instalment_tenant_id unique (tenant_id, id),
    constraint uq_fee_instalment_seq unique (structure_id, seq),
    constraint fk_fee_instalment_structure foreign key (tenant_id, structure_id)
        references fees.fee_structure (tenant_id, id)
);

-- Each instalment's share of each head. A head's amount in the structure is the sum of its shares.
create table fees.fee_instalment_share (
    id            uuid primary key,
    tenant_id     uuid        not null references platform.tenant (id),
    instalment_id uuid        not null,
    head_id       uuid        not null,
    amount_paise  bigint      not null,
    created_at    timestamptz not null,
    constraint ck_fee_instalment_share_amount check (amount_paise >= 0),
    constraint uq_fee_instalment_share unique (instalment_id, head_id),
    constraint fk_fee_instalment_share_instalment foreign key (tenant_id, instalment_id)
        references fees.fee_instalment (tenant_id, id) on delete cascade,
    constraint fk_fee_instalment_share_head foreign key (tenant_id, head_id) references fees.fee_head (tenant_id, id)
);
create index ix_fee_instalment_share_head on fees.fee_instalment_share (head_id);
create index ix_fee_instalment_share_tenant on fees.fee_instalment_share (tenant_id);

-- ---------------------------------------------------------------- late fee rule (one per school)

create table fees.late_fee_rule (
    id            uuid primary key,
    tenant_id     uuid        not null references platform.tenant (id),
    mode          varchar(10) not null,
    grace_days    integer     not null default 0,
    flat_paise    bigint      not null default 0,
    per_day_paise bigint      not null default 0,
    cap_paise     bigint      not null default 0,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    version       bigint      not null default 0,
    constraint ck_late_fee_rule_mode check (mode in ('NONE', 'FLAT', 'PER_DAY')),
    constraint ck_late_fee_rule_values check (grace_days between 0 and 365 and flat_paise >= 0
        and per_day_paise >= 0 and cap_paise >= 0),
    constraint uq_late_fee_rule_tenant unique (tenant_id)
);

-- ---------------------------------------------------------------- concessions

create table fees.concession (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    student_id       uuid         not null,
    academic_year_id uuid         not null,
    type             varchar(12)  not null,
    mode             varchar(8)   not null,
    percent_bp       integer,
    fixed_paise      bigint,
    reason           varchar(500) not null,
    approved_by      uuid,
    approved_by_name varchar(200) not null,
    status           varchar(10)  not null,
    revoked_at       timestamptz,
    revoked_by       uuid,
    revoked_by_name  varchar(200),
    revoke_reason    varchar(500),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint ck_concession_type check (type in ('SIBLING', 'STAFF_WARD', 'MERIT', 'RTE', 'OTHER')),
    constraint ck_concession_mode check (
        (mode = 'PERCENT' and percent_bp between 1 and 10000 and fixed_paise is null)
        or (mode = 'FIXED' and fixed_paise > 0 and percent_bp is null)),
    constraint ck_concession_status check (status in ('ACTIVE', 'REVOKED')),
    constraint ck_concession_revoked check (status = 'ACTIVE' or (revoked_at is not null and revoke_reason is not null)),
    constraint uq_concession_tenant_id unique (tenant_id, id),
    constraint fk_concession_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_concession_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_concession_approved_by foreign key (tenant_id, approved_by)
        references identity.user_account (tenant_id, id) on delete set null (approved_by),
    constraint fk_concession_revoked_by foreign key (tenant_id, revoked_by)
        references identity.user_account (tenant_id, id) on delete set null (revoked_by)
);
create index ix_concession_student_year on fees.concession (tenant_id, student_id, academic_year_id);

create table fees.concession_head (
    id            uuid primary key,
    tenant_id     uuid        not null references platform.tenant (id),
    concession_id uuid        not null,
    head_id       uuid        not null,
    created_at    timestamptz not null,
    constraint uq_concession_head unique (concession_id, head_id),
    constraint fk_concession_head_concession foreign key (tenant_id, concession_id)
        references fees.concession (tenant_id, id),
    constraint fk_concession_head_head foreign key (tenant_id, head_id) references fees.fee_head (tenant_id, id)
);
create index ix_concession_head_tenant on fees.concession_head (tenant_id);
create index ix_concession_head_head on fees.concession_head (head_id);

-- ---------------------------------------------------------------- student dues (per student, instalment and head)

create table fees.student_due (
    id               uuid primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    student_id       uuid        not null,
    academic_year_id uuid        not null,
    structure_id     uuid        not null,
    instalment_id    uuid        not null,
    head_id          uuid        not null,
    due_date         date        not null,
    gross_paise      bigint      not null,
    concession_paise bigint      not null default 0,
    paid_paise       bigint      not null default 0,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null default 0,
    -- Concessions never take a due below zero, and nothing is ever paid beyond what is owed.
    constraint ck_student_due_amounts check (gross_paise >= 0 and concession_paise between 0 and gross_paise
        and paid_paise between 0 and gross_paise - concession_paise),
    constraint uq_student_due_tenant_id unique (tenant_id, id),
    constraint uq_student_due_cell unique (student_id, instalment_id, head_id),
    constraint fk_student_due_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_student_due_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_student_due_structure foreign key (tenant_id, structure_id)
        references fees.fee_structure (tenant_id, id),
    constraint fk_student_due_instalment foreign key (tenant_id, instalment_id)
        references fees.fee_instalment (tenant_id, id),
    constraint fk_student_due_head foreign key (tenant_id, head_id) references fees.fee_head (tenant_id, id)
);
create index ix_student_due_year on fees.student_due (tenant_id, academic_year_id, due_date);
create index ix_student_due_structure on fees.student_due (structure_id);
create index ix_student_due_instalment on fees.student_due (instalment_id);
create index ix_student_due_head on fees.student_due (head_id);

-- ---------------------------------------------------------------- receipts

-- One row per school and financial year (April to March). Payments lock it FOR UPDATE, so receipt numbers are
-- consecutive and gap-free: a rolled-back payment also rolls back its number.
create table fees.receipt_counter (
    tenant_id      uuid        not null references platform.tenant (id),
    financial_year varchar(7)  not null,
    last_seq       integer     not null default 0,
    updated_at     timestamptz not null,
    primary key (tenant_id, financial_year),
    constraint ck_receipt_counter_year check (financial_year ~ '^[0-9]{4}-[0-9]{2}$')
);

create table fees.receipt (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    receipt_no         varchar(30)  not null,
    financial_year     varchar(7)   not null,
    seq                integer      not null,
    student_id         uuid         not null,
    student_name       varchar(200) not null,
    admission_no       varchar(30)  not null,
    class_label        varchar(80),
    received_on        date         not null,
    received_at        timestamptz  not null,
    mode               varchar(15)  not null,
    cheque_no          varchar(20),
    bank_name          varchar(100),
    reference          varchar(100),
    amount_paise       bigint       not null,
    late_fee_paise     bigint       not null default 0,
    source             varchar(10)  not null,
    gateway            varchar(20),
    gateway_payment_id varchar(64),
    remarks            varchar(200),
    collected_by       uuid,
    collected_by_name  varchar(200) not null,
    status             varchar(10)  not null,
    cancelled_at       timestamptz,
    cancelled_by       uuid,
    cancelled_by_name  varchar(200),
    cancel_reason      varchar(500),
    created_at         timestamptz  not null,
    version            bigint       not null default 0,
    constraint ck_receipt_mode check (mode in ('CASH', 'CHEQUE', 'UPI', 'CARD', 'BANK_TRANSFER', 'ONLINE')),
    constraint ck_receipt_cheque check (mode <> 'CHEQUE' or (cheque_no is not null and bank_name is not null)),
    constraint ck_receipt_source check (source in ('COUNTER', 'ONLINE')),
    constraint ck_receipt_amount check (amount_paise > 0 and late_fee_paise between 0 and amount_paise),
    constraint ck_receipt_status check (status in ('ISSUED', 'CANCELLED')),
    constraint ck_receipt_cancelled check ((status = 'ISSUED' and cancelled_at is null)
        or (status = 'CANCELLED' and cancelled_at is not null and cancel_reason is not null)),
    constraint uq_receipt_tenant_id unique (tenant_id, id),
    constraint uq_receipt_no unique (tenant_id, receipt_no),
    constraint uq_receipt_seq unique (tenant_id, financial_year, seq),
    constraint fk_receipt_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_receipt_collected_by foreign key (tenant_id, collected_by)
        references identity.user_account (tenant_id, id) on delete set null (collected_by),
    constraint fk_receipt_cancelled_by foreign key (tenant_id, cancelled_by)
        references identity.user_account (tenant_id, id) on delete set null (cancelled_by)
);
create index ix_receipt_received on fees.receipt (tenant_id, received_on);
create index ix_receipt_student on fees.receipt (student_id);
create unique index uq_receipt_gateway_payment on fees.receipt (tenant_id, gateway, gateway_payment_id)
    where gateway_payment_id is not null;

-- The money ledger. A payment adds ALLOCATION rows (to dues, the late fee of an instalment, or an advance); a
-- cancellation adds matching REVERSAL rows with negative amounts. Rows are never changed or deleted.
create table fees.payment_allocation (
    id               uuid primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    receipt_id       uuid        not null,
    student_id       uuid        not null,
    line_no          integer     not null,
    entry            varchar(10) not null,
    kind             varchar(10) not null,
    due_id           uuid,
    instalment_id    uuid,
    head_id          uuid,
    head_name        varchar(60),
    instalment_label varchar(40),
    amount_paise     bigint      not null,
    created_at       timestamptz not null,
    constraint ck_payment_allocation_line check (line_no >= 1),
    constraint ck_payment_allocation_entry check ((entry = 'ALLOCATION' and amount_paise > 0)
        or (entry = 'REVERSAL' and amount_paise < 0)),
    constraint ck_payment_allocation_kind check ((kind = 'DUE' and due_id is not null and head_id is not null)
        or (kind = 'LATE_FEE' and due_id is null and instalment_id is not null)
        or (kind = 'ADVANCE' and due_id is null)),
    constraint fk_payment_allocation_receipt foreign key (tenant_id, receipt_id) references fees.receipt (tenant_id, id),
    constraint fk_payment_allocation_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_payment_allocation_due foreign key (tenant_id, due_id) references fees.student_due (tenant_id, id),
    constraint fk_payment_allocation_instalment foreign key (tenant_id, instalment_id)
        references fees.fee_instalment (tenant_id, id),
    constraint fk_payment_allocation_head foreign key (tenant_id, head_id) references fees.fee_head (tenant_id, id)
);
create index ix_payment_allocation_receipt on fees.payment_allocation (receipt_id);
create index ix_payment_allocation_student on fees.payment_allocation (tenant_id, student_id);
create index ix_payment_allocation_due on fees.payment_allocation (due_id);
create index ix_payment_allocation_instalment on fees.payment_allocation (instalment_id);
create index ix_payment_allocation_head on fees.payment_allocation (head_id);

create table fees.late_fee_waiver (
    id             uuid primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    student_id     uuid         not null,
    instalment_id  uuid         not null,
    reason         varchar(500) not null,
    waived_by      uuid,
    waived_by_name varchar(200) not null,
    created_at     timestamptz  not null,
    constraint uq_late_fee_waiver unique (student_id, instalment_id),
    constraint fk_late_fee_waiver_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_late_fee_waiver_instalment foreign key (tenant_id, instalment_id)
        references fees.fee_instalment (tenant_id, id),
    constraint fk_late_fee_waiver_by foreign key (tenant_id, waived_by)
        references identity.user_account (tenant_id, id) on delete set null (waived_by)
);
create index ix_late_fee_waiver_tenant on fees.late_fee_waiver (tenant_id);
create index ix_late_fee_waiver_instalment on fees.late_fee_waiver (instalment_id);

-- ---------------------------------------------------------------- online payments

create table fees.payment_order (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    student_id         uuid         not null,
    gateway            varchar(20)  not null,
    gateway_order_id   varchar(64)  not null,
    amount_paise       bigint       not null,
    currency           varchar(3)   not null default 'INR',
    -- The instalments chosen when the order was made. Looked up again through row-level security when the payment
    -- is recorded, so an id from another school simply matches nothing.
    instalment_ids     uuid[]       not null,
    late_fee_as_of     date         not null,
    status             varchar(10)  not null,
    gateway_payment_id varchar(64),
    receipt_id         uuid,
    failure_reason     varchar(200),
    created_by         uuid,
    created_by_name    varchar(200) not null,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    constraint ck_payment_order_amount check (amount_paise > 0),
    constraint ck_payment_order_status check (status in ('CREATED', 'PAID', 'FAILED')),
    constraint ck_payment_order_paid check (status <> 'PAID' or (receipt_id is not null and gateway_payment_id is not null)),
    constraint uq_payment_order_tenant_id unique (tenant_id, id),
    constraint fk_payment_order_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_payment_order_receipt foreign key (tenant_id, receipt_id) references fees.receipt (tenant_id, id),
    constraint fk_payment_order_created_by foreign key (tenant_id, created_by)
        references identity.user_account (tenant_id, id) on delete set null (created_by)
);
-- Gateway order ids are unique at the gateway; webhooks find the school through this index.
create unique index uq_payment_order_gateway on fees.payment_order (gateway, gateway_order_id);
create index ix_payment_order_student on fees.payment_order (tenant_id, student_id);
create index ix_payment_order_receipt on fees.payment_order (receipt_id);

-- Every verified webhook delivery, so a replayed event is recognised and never records a payment twice.
create table fees.gateway_event (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    gateway            varchar(20)  not null,
    event_id           varchar(100) not null,
    event_type         varchar(60)  not null,
    payment_order_id   uuid         not null,
    gateway_payment_id varchar(64),
    outcome            varchar(20)  not null,
    received_at        timestamptz  not null,
    constraint ck_gateway_event_outcome check (outcome in ('RECORDED', 'ALREADY_RECORDED', 'FAILED', 'IGNORED')),
    constraint uq_gateway_event unique (tenant_id, gateway, event_id),
    constraint fk_gateway_event_order foreign key (tenant_id, payment_order_id)
        references fees.payment_order (tenant_id, id)
);
create index ix_gateway_event_order on fees.gateway_event (payment_order_id);

-- ---------------------------------------------------------------- reminders requested for overdue fees

create table fees.fee_reminder (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    student_id        uuid         not null,
    overdue_paise     bigint       not null,
    days_overdue      integer      not null,
    requested_by      uuid,
    requested_by_name varchar(200) not null,
    created_at        timestamptz  not null,
    constraint ck_fee_reminder_amount check (overdue_paise > 0 and days_overdue > 0),
    constraint fk_fee_reminder_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_fee_reminder_by foreign key (tenant_id, requested_by)
        references identity.user_account (tenant_id, id) on delete set null (requested_by)
);
create index ix_fee_reminder_student on fees.fee_reminder (tenant_id, student_id, created_at);

-- ---------------------------------------------------------------- row-level security

alter table fees.fee_head enable row level security;
create policy tenant_isolation on fees.fee_head
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.fee_structure enable row level security;
create policy tenant_isolation on fees.fee_structure
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.fee_instalment enable row level security;
create policy tenant_isolation on fees.fee_instalment
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.fee_instalment_share enable row level security;
create policy tenant_isolation on fees.fee_instalment_share
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.late_fee_rule enable row level security;
create policy tenant_isolation on fees.late_fee_rule
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.concession enable row level security;
create policy tenant_isolation on fees.concession
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.concession_head enable row level security;
create policy tenant_isolation on fees.concession_head
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.student_due enable row level security;
create policy tenant_isolation on fees.student_due
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.receipt_counter enable row level security;
create policy tenant_isolation on fees.receipt_counter
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.receipt enable row level security;
create policy tenant_isolation on fees.receipt
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.payment_allocation enable row level security;
create policy tenant_isolation on fees.payment_allocation
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.late_fee_waiver enable row level security;
create policy tenant_isolation on fees.late_fee_waiver
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.payment_order enable row level security;
create policy tenant_isolation on fees.payment_order
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.gateway_event enable row level security;
create policy tenant_isolation on fees.gateway_event
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table fees.fee_reminder enable row level security;
create policy tenant_isolation on fees.fee_reminder
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- A payment gateway's webhook arrives without a signed-in user, so no school is selected yet. After the signature is
-- verified, the API finds the school of the order through this function, which reveals nothing but that one id.
create function fees.payment_order_tenant(p_gateway text, p_gateway_order_id text)
    returns uuid
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select o.tenant_id from fees.payment_order o where o.gateway = p_gateway and o.gateway_order_id = p_gateway_order_id
$$;

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema fees to ${appRole};
grant select, insert, update on fees.fee_head, fees.fee_structure, fees.late_fee_rule, fees.concession,
    fees.student_due, fees.receipt_counter, fees.payment_order to ${appRole};
grant select, insert, update, delete on fees.fee_instalment, fees.fee_instalment_share to ${appRole};
-- Unpaid dues of a removed head or instalment are dropped when a structure changes; paid ones never are.
grant delete on fees.student_due to ${appRole};
grant select, insert on fees.concession_head, fees.payment_allocation, fees.late_fee_waiver, fees.gateway_event,
    fees.fee_reminder to ${appRole};
-- Receipts are immutable: the runtime role may add them and may only mark one cancelled, never change or delete it.
grant select, insert on fees.receipt to ${appRole};
grant update (status, cancelled_at, cancelled_by, cancelled_by_name, cancel_reason, version) on fees.receipt
    to ${appRole};
revoke all on function fees.payment_order_tenant(text, text) from public;
grant execute on function fees.payment_order_tenant(text, text) to ${appRole};

-- ---------------------------------------------------------------- new permission for schools that already exist

-- fees.manage: fee structures, concessions, late-fee rules, receipt cancellations and late-fee waivers. New schools
-- get it from RoleCatalog; existing schools' built-in roles get it here once. Runs as the owner. Idempotent.
update identity.role
set permissions = array_append(permissions, 'fees.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'ACCOUNTANT')
  and not ('fees.manage' = any (permissions));
