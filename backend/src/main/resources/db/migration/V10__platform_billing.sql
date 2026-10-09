-- Phase 1, Super Admin console: subscriptions, GST invoices for the subscription (Akshara is the seller), payments
-- the Super Admin records by hand, renewals and suspension. Money is in paise (bigint); nothing uses floating point.
--
-- Two kinds of tables, following V1:
--   * Platform data, like platform.tenant: billing.subscription (a school's plan cycle, period, billing details and
--     suspension) and billing.invoice_counter (Akshara's one invoice series). The Super Admin reads them across
--     schools; a school reads only its own subscription, through the API. Not row-level secured, and listed as
--     "not school-owned" in RowLevelSecurityCoverageIT.
--   * Documents a school can read, its invoices and the payments against them: tenant_id, the same tenant_isolation
--     policy as V2/V3, composite (tenant_id, id) keys between them, and append-only grants like fees.receipt.

create schema if not exists billing;

-- ---------------------------------------------------------------- subscriptions (platform data)

-- One row per school, made the first time its billing details are saved or it starts paying. The plan itself stays on
-- platform.tenant. A school on trial has no billing cycle yet; a paying school has all of cycle, billed students and
-- the current period (dates in India, inclusive). The next renewal is the day after the period ends.
create table billing.subscription (
    id                       uuid primary key,
    tenant_id                uuid         not null references platform.tenant (id),
    billing_cycle            varchar(10),
    billed_students          integer,
    period_start             date,
    period_end               date,
    paid_since               date,
    legal_name               varchar(200),
    billing_address          varchar(500),
    state_code               varchar(2),
    gstin                    varchar(15),
    suspended_at             timestamptz,
    suspension_reason        varchar(500),
    status_before_suspension varchar(20),
    created_at               timestamptz  not null,
    updated_at               timestamptz  not null,
    version                  bigint       not null default 0,
    constraint uq_subscription_tenant unique (tenant_id),
    constraint ck_subscription_cycle check (billing_cycle is null or billing_cycle in ('MONTHLY', 'YEARLY')),
    constraint ck_subscription_paid check (
        (billing_cycle is null and billed_students is null and period_start is null and period_end is null
            and paid_since is null)
        or (billing_cycle is not null and billed_students between 1 and 100000 and period_start is not null
            and period_end >= period_start and paid_since is not null)),
    constraint ck_subscription_state check (state_code is null or state_code ~ '^[0-9]{2}$'),
    constraint ck_subscription_gstin check (gstin is null or gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
    constraint ck_subscription_suspension check (
        (suspended_at is null and suspension_reason is null and status_before_suspension is null)
        or (suspended_at is not null and suspension_reason is not null
            and status_before_suspension in ('TRIAL', 'ACTIVE', 'PAST_DUE')))
);

-- ---------------------------------------------------------------- invoice numbers (platform data)

-- Akshara issues one series of invoice numbers per financial year (April to March, India), across all schools. Issuing
-- an invoice locks this row FOR UPDATE until the transaction ends, so numbers are consecutive and gap-free: an invoice
-- that rolls back gives its number back.
create table billing.invoice_counter (
    financial_year varchar(7)  primary key,
    last_seq       integer     not null default 0,
    updated_at     timestamptz not null,
    constraint ck_invoice_counter_year check (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
    constraint ck_invoice_counter_seq check (last_seq >= 0)
);

-- ---------------------------------------------------------------- invoices (school documents)

-- A GST tax invoice for one period of a school's subscription. Everything printed on it, the seller's and the buyer's
-- details included, is copied in when it is issued, so a reprint always shows what was issued. 18% GST: CGST + SGST
-- (9% each) when the school's state is the seller's state, otherwise IGST 18%.
create table billing.invoice (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    invoice_no        varchar(16)  not null,
    financial_year    varchar(7)   not null,
    seq               integer      not null,
    invoice_date      date         not null,
    due_date          date         not null,
    plan              varchar(20)  not null,
    billing_cycle     varchar(10)  not null,
    period_start      date         not null,
    period_end        date         not null,
    billed_students   integer      not null,
    unit_price_paise  bigint       not null,
    taxable_paise     bigint       not null,
    tax_split         varchar(10)  not null,
    cgst_paise        bigint       not null default 0,
    sgst_paise        bigint       not null default 0,
    igst_paise        bigint       not null default 0,
    total_paise       bigint       not null,
    sac_code          varchar(8)   not null,
    seller_name       varchar(200) not null,
    seller_address    varchar(500) not null,
    seller_state_code varchar(2)   not null,
    seller_gstin      varchar(15)  not null,
    buyer_name        varchar(200) not null,
    buyer_address     varchar(500),
    buyer_state_code  varchar(2)   not null,
    buyer_gstin       varchar(15),
    status            varchar(10)  not null,
    paid_paise        bigint       not null default 0,
    paid_on           date,
    issued_by_name    varchar(200) not null,
    cancelled_at      timestamptz,
    cancelled_by_name varchar(200),
    cancel_reason     varchar(500),
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint ck_invoice_year check (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
    constraint ck_invoice_seq check (seq >= 1),
    constraint ck_invoice_dates check (due_date >= invoice_date and period_end >= period_start),
    constraint ck_invoice_plan check (plan in ('STARTER', 'GROWTH', 'ENTERPRISE')),
    constraint ck_invoice_cycle check (billing_cycle in ('MONTHLY', 'YEARLY')),
    constraint ck_invoice_students check (billed_students between 1 and 100000),
    constraint ck_invoice_tax check (
        (tax_split = 'CGST_SGST' and igst_paise = 0 and cgst_paise >= 0 and sgst_paise >= 0)
        or (tax_split = 'IGST' and cgst_paise = 0 and sgst_paise = 0 and igst_paise >= 0)),
    constraint ck_invoice_amounts check (unit_price_paise >= 0 and taxable_paise >= 0
        and total_paise = taxable_paise + cgst_paise + sgst_paise + igst_paise
        and paid_paise between 0 and total_paise),
    constraint ck_invoice_status check (status in ('ISSUED', 'PAID', 'CANCELLED')),
    constraint ck_invoice_paid check (status <> 'PAID' or (paid_paise = total_paise and paid_on is not null)),
    constraint ck_invoice_cancelled check ((status = 'CANCELLED') = (cancelled_at is not null)
        and (status <> 'CANCELLED' or (cancel_reason is not null and paid_paise = 0))),
    constraint uq_invoice_tenant_id unique (tenant_id, id),
    -- Akshara's numbers are unique across all schools.
    constraint uq_invoice_no unique (invoice_no),
    constraint uq_invoice_seq unique (financial_year, seq)
);
-- At most one live (not cancelled) invoice per school and period.
create unique index uq_invoice_period on billing.invoice (tenant_id, period_start) where status <> 'CANCELLED';
create index ix_invoice_tenant_date on billing.invoice (tenant_id, invoice_date desc);
create index ix_invoice_unpaid on billing.invoice (due_date) where status = 'ISSUED';

-- Payments against an invoice, recorded by the Super Admin from the bank statement. Never changed or deleted.
create table billing.invoice_payment (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    invoice_id       uuid         not null,
    amount_paise     bigint       not null,
    mode             varchar(15)  not null,
    reference        varchar(100) not null,
    paid_on          date         not null,
    recorded_by_id   uuid,
    recorded_by_name varchar(200) not null,
    created_at       timestamptz  not null,
    constraint ck_invoice_payment_amount check (amount_paise > 0),
    constraint ck_invoice_payment_mode check (mode in ('BANK_TRANSFER', 'UPI', 'CHEQUE', 'CARD')),
    constraint fk_invoice_payment_invoice foreign key (tenant_id, invoice_id) references billing.invoice (tenant_id, id)
);
create index ix_invoice_payment_invoice on billing.invoice_payment (invoice_id);
create index ix_invoice_payment_tenant on billing.invoice_payment (tenant_id);

-- ---------------------------------------------------------------- row-level security

alter table billing.invoice enable row level security;
create policy tenant_isolation on billing.invoice
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table billing.invoice_payment enable row level security;
create policy tenant_isolation on billing.invoice_payment
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- cross-school counts for the Super Admin

-- The Super Admin works without a school selected, so row-level security hides every school row from it. Like
-- platform.tenant_user_counts() in V1, these functions return counts and sums only, never a school's rows.

-- Active students per school: the platform health page and the "billed students" suggestion.
create function billing.tenant_student_counts()
    returns table (tenant_id uuid, active_students bigint)
    language sql stable security definer
    set search_path = pg_catalog
as $$ select s.tenant_id, count(*) from students.student s where s.status = 'ACTIVE' group by s.tenant_id $$;

-- Messages waiting in and failed out of the notifications outbox, across all schools.
create function billing.outbox_counts()
    returns table (queued bigint, failed bigint)
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select count(*) filter (where m.status = 'QUEUED'), count(*) filter (where m.status = 'FAILED')
    from notifications.message m
$$;

-- Unpaid invoices per school as of a day: how many and how much, and how much of it is past its due date. The
-- renewals list uses it; it shows no invoice numbers, dates of other documents or payments.
create function billing.unpaid_invoice_totals(p_today date)
    returns table (tenant_id uuid, unpaid_count bigint, unpaid_paise bigint, overdue_count bigint,
                   overdue_paise bigint, oldest_overdue_due date)
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select i.tenant_id,
           count(*),
           sum(i.total_paise - i.paid_paise),
           count(*) filter (where i.due_date < p_today),
           coalesce(sum(i.total_paise - i.paid_paise) filter (where i.due_date < p_today), 0),
           min(i.due_date) filter (where i.due_date < p_today)
    from billing.invoice i
    where i.status = 'ISSUED'
    group by i.tenant_id
$$;

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema billing to ${appRole};
grant select, insert, update on billing.subscription, billing.invoice_counter to ${appRole};
-- Invoices are immutable once issued: the runtime role may add them and may only record that they were paid or
-- cancelled, never change or delete what was billed.
grant select, insert on billing.invoice to ${appRole};
grant update (status, paid_paise, paid_on, cancelled_at, cancelled_by_name, cancel_reason, updated_at, version)
    on billing.invoice to ${appRole};
grant select, insert on billing.invoice_payment to ${appRole};
revoke all on function billing.tenant_student_counts(), billing.outbox_counts(), billing.unpaid_invoice_totals(date)
    from public;
grant execute on function billing.tenant_student_counts(), billing.outbox_counts(), billing.unpaid_invoice_totals(date)
    to ${appRole};

-- ---------------------------------------------------------------- new permission for schools that already exist

-- billing.read: the school's Billing page (plan, trial, renewal date, invoices) and the trial / overdue banner. New
-- schools get it from RoleCatalog; existing schools' built-in School Admin role gets it here once. Runs as the owner,
-- which row-level security does not restrict. Idempotent.
update identity.role
set permissions = array_append(permissions, 'billing.read')
where system_role
  and code = 'SCHOOL_ADMIN'
  and not ('billing.read' = any (permissions));
