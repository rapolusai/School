-- Phase 2: expenses and accounts. The school's management accounts: a chart of income and expense heads, cost
-- centres, vendors, expense requests with an approval chain, recurring expenses, a petty cash book, income other than
-- fees (donations, grants, rentals, miscellaneous receipts), fee income summarised per day from the fees module's
-- FeePaymentReceived event, payroll cost per month from PayrollApproved, yearly budgets per head, bank accounts with
-- opening balances per financial year, and bank statement lines for reconciliation.
--
-- This is not a statutory double-entry ledger: nothing here is a journal, and the fees and payroll tables are never
-- written to. Every table carries tenant_id, is protected by the same tenant_isolation policy as V2/V3, and links to
-- other school tables through composite (tenant_id, id) foreign keys. Money is in paise (bigint), never floating
-- point. Financial years are Indian (April to March), written "2026-27".

create schema if not exists accounts;

-- ---------------------------------------------------------------- settings and approvers

create table accounts.settings (
    id                     uuid primary key,
    tenant_id              uuid         not null references platform.tenant (id),
    budget_mode            varchar(10)  not null default 'WARN',
    donation_80g_registered boolean     not null default false,
    donation_80g_number    varchar(60),
    petty_cash_float_paise bigint       not null default 0,
    created_at             timestamptz  not null,
    updated_at             timestamptz  not null,
    version                bigint       not null default 0,
    constraint uq_accounts_settings_tenant unique (tenant_id),
    constraint uq_accounts_settings_tenant_id unique (tenant_id, id),
    constraint ck_accounts_settings_mode check (budget_mode in ('WARN', 'BLOCK')),
    constraint ck_accounts_settings_float check (petty_cash_float_paise >= 0)
);

-- Who approves expenses and up to what amount. A person with accounts.approve who is not listed has no limit.
create table accounts.approver (
    id             uuid primary key,
    tenant_id      uuid        not null references platform.tenant (id),
    user_id        uuid        not null,
    user_name      varchar(200) not null,
    limit_paise    bigint,
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    constraint uq_accounts_approver_tenant_id unique (tenant_id, id),
    constraint uq_accounts_approver_user unique (tenant_id, user_id),
    constraint ck_accounts_approver_limit check (limit_paise is null or limit_paise > 0),
    constraint fk_accounts_approver_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id) on delete cascade
);
create index ix_accounts_approver_user on accounts.approver (user_id);

-- ---------------------------------------------------------------- chart of heads and cost centres

create table accounts.head (
    id             uuid primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    code           varchar(20)  not null,
    name           varchar(80)  not null,
    kind           varchar(10)  not null,
    parent_id      uuid,
    gst_applicable boolean      not null default false,
    fee_head_id    uuid,
    system_code    varchar(30),
    active         boolean      not null default true,
    display_order  integer      not null default 0,
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    version        bigint       not null default 0,
    constraint uq_accounts_head_tenant_id unique (tenant_id, id),
    constraint uq_accounts_head_code unique (tenant_id, code),
    constraint ck_accounts_head_kind check (kind in ('INCOME', 'EXPENSE')),
    constraint ck_accounts_head_code check (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    constraint fk_accounts_head_parent foreign key (tenant_id, parent_id)
        references accounts.head (tenant_id, id),
    constraint fk_accounts_head_fee_head foreign key (tenant_id, fee_head_id)
        references fees.fee_head (tenant_id, id) on delete set null (fee_head_id)
);
create unique index uq_accounts_head_name on accounts.head (tenant_id, lower(name));
create unique index uq_accounts_head_fee_head on accounts.head (tenant_id, fee_head_id) where fee_head_id is not null;
create index ix_accounts_head_parent on accounts.head (parent_id);
create index ix_accounts_head_fee_head on accounts.head (fee_head_id);

create table accounts.cost_centre (
    id         uuid primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    code       varchar(20) not null,
    name       varchar(80) not null,
    active     boolean     not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null default 0,
    constraint uq_accounts_cost_centre_tenant_id unique (tenant_id, id),
    constraint uq_accounts_cost_centre_code unique (tenant_id, code),
    constraint ck_accounts_cost_centre_code check (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$')
);
create unique index uq_accounts_cost_centre_name on accounts.cost_centre (tenant_id, lower(name));

-- ---------------------------------------------------------------- vendors

-- The vendor's PAN, phone and bank details are shown masked in lists; TDS section and rate are data the accountant
-- verifies (194C 1%/2%, 194J 10% by default).
create table accounts.vendor (
    id                  uuid primary key,
    tenant_id           uuid          not null references platform.tenant (id),
    name                varchar(120)  not null,
    gstin               varchar(15),
    pan                 varchar(10),
    phone               varchar(15),
    email               varchar(200),
    address             varchar(300),
    bank_account_number varchar(18),
    bank_ifsc           varchar(11),
    tds_section         varchar(10),
    tds_rate_percent    numeric(5, 2),
    active              boolean       not null default true,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    version             bigint        not null default 0,
    constraint uq_accounts_vendor_tenant_id unique (tenant_id, id),
    constraint ck_accounts_vendor_gstin check (gstin is null
        or gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
    constraint ck_accounts_vendor_pan check (pan is null or pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
    constraint ck_accounts_vendor_phone check (phone is null or phone ~ '^\+?[0-9]{8,15}$'),
    constraint ck_accounts_vendor_account check (bank_account_number is null or bank_account_number ~ '^[0-9]{6,18}$'),
    constraint ck_accounts_vendor_ifsc check (bank_ifsc is null or bank_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
    constraint ck_accounts_vendor_bank check ((bank_account_number is null) = (bank_ifsc is null)),
    constraint ck_accounts_vendor_tds check ((tds_section is null) = (tds_rate_percent is null)
        and (tds_rate_percent is null or (tds_rate_percent >= 0 and tds_rate_percent <= 30)))
);
create unique index uq_accounts_vendor_name on accounts.vendor (tenant_id, lower(name));

-- ---------------------------------------------------------------- bank accounts and opening balances

create table accounts.bank_account (
    id             uuid primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    name           varchar(80)  not null,
    bank_name      varchar(100) not null,
    account_number varchar(18)  not null,
    ifsc           varchar(11)  not null,
    active         boolean      not null default true,
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    version        bigint       not null default 0,
    constraint uq_accounts_bank_account_tenant_id unique (tenant_id, id),
    constraint ck_accounts_bank_account_number check (account_number ~ '^[0-9]{6,18}$'),
    constraint ck_accounts_bank_account_ifsc check (ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$')
);
create unique index uq_accounts_bank_account_name on accounts.bank_account (tenant_id, lower(name));

-- Opening balance of a bank account (or of cash in hand when bank_account_id is null) on 1 April of the year.
create table accounts.opening_balance (
    id              uuid primary key,
    tenant_id       uuid        not null references platform.tenant (id),
    financial_year  varchar(7)  not null,
    bank_account_id uuid,
    amount_paise    bigint      not null default 0,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    constraint uq_accounts_opening_balance_tenant_id unique (tenant_id, id),
    constraint ck_accounts_opening_balance_year check (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
    constraint fk_accounts_opening_balance_account foreign key (tenant_id, bank_account_id)
        references accounts.bank_account (tenant_id, id) on delete cascade
);
create unique index uq_accounts_opening_balance on accounts.opening_balance
    (tenant_id, financial_year, coalesce(bank_account_id, '00000000-0000-0000-0000-000000000000'));
create index ix_accounts_opening_balance_account on accounts.opening_balance (bank_account_id);

-- ---------------------------------------------------------------- number counters (expenses and income receipts)

create table accounts.number_counter (
    tenant_id      uuid        not null references platform.tenant (id),
    kind           varchar(3)  not null,
    financial_year varchar(7)  not null,
    last_seq       integer     not null default 0,
    updated_at     timestamptz not null,
    primary key (tenant_id, kind, financial_year),
    constraint ck_accounts_number_counter_kind check (kind in ('EXP', 'INC')),
    constraint ck_accounts_number_counter_year check (financial_year ~ '^[0-9]{4}-[0-9]{2}$')
);

-- ---------------------------------------------------------------- expenses

create table accounts.recurring_expense (
    id                   uuid primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    name                 varchar(120) not null,
    head_id              uuid         not null,
    cost_centre_id       uuid,
    vendor_id            uuid,
    amount_paise         bigint       not null,
    gst_paise            bigint       not null default 0,
    day_of_month         integer      not null default 1,
    description          varchar(500),
    active               boolean      not null default true,
    last_generated_month date,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint uq_accounts_recurring_tenant_id unique (tenant_id, id),
    constraint ck_accounts_recurring_amount check (amount_paise > 0 and gst_paise >= 0),
    constraint ck_accounts_recurring_day check (day_of_month between 1 and 28),
    constraint fk_accounts_recurring_head foreign key (tenant_id, head_id) references accounts.head (tenant_id, id),
    constraint fk_accounts_recurring_cost_centre foreign key (tenant_id, cost_centre_id)
        references accounts.cost_centre (tenant_id, id) on delete set null (cost_centre_id),
    constraint fk_accounts_recurring_vendor foreign key (tenant_id, vendor_id)
        references accounts.vendor (tenant_id, id) on delete set null (vendor_id)
);
create index ix_accounts_recurring_head on accounts.recurring_expense (head_id);
create index ix_accounts_recurring_cost_centre on accounts.recurring_expense (cost_centre_id);
create index ix_accounts_recurring_vendor on accounts.recurring_expense (vendor_id);

-- An expense request. DRAFT -> SUBMITTED -> APPROVED -> PAID, or REJECTED (with a reason). amount_paise is before
-- GST; total_paise = amount + GST; tds_paise is kept back from the vendor; paid_paise = total - TDS. A paid expense
-- never changes. escalated_above_paise: an approver whose limit was too small passed it on; only an approver with a
-- larger limit (or none) can act now.
create table accounts.expense (
    id                    uuid primary key,
    tenant_id             uuid         not null references platform.tenant (id),
    number                varchar(30),
    financial_year        varchar(7)   not null,
    status                varchar(10)  not null,
    head_id               uuid         not null,
    cost_centre_id        uuid,
    vendor_id             uuid,
    vendor_name           varchar(120),
    expense_date          date         not null,
    amount_paise          bigint       not null,
    gst_paise             bigint       not null default 0,
    total_paise           bigint       not null,
    tds_section           varchar(10),
    tds_rate_percent      numeric(5, 2),
    tds_paise             bigint       not null default 0,
    paid_paise            bigint       not null,
    description           varchar(500) not null,
    bill_number           varchar(60),
    recurring_id          uuid,
    recurring_month       date,
    budget_warning        boolean      not null default false,
    escalated_above_paise bigint,
    requested_by_id       uuid,
    requested_by_name     varchar(200),
    submitted_at          timestamptz,
    approved_by_id        uuid,
    approved_by_name      varchar(200),
    approved_at           timestamptz,
    rejected_by_id        uuid,
    rejected_by_name      varchar(200),
    rejected_at           timestamptz,
    reject_reason         varchar(500),
    paid_on               date,
    payment_mode          varchar(10),
    bank_account_id       uuid,
    payment_reference     varchar(100),
    paid_by_id            uuid,
    paid_by_name          varchar(200),
    paid_at               timestamptz,
    cleared_on            date,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0,
    constraint uq_accounts_expense_tenant_id unique (tenant_id, id),
    constraint uq_accounts_expense_number unique (tenant_id, number),
    constraint ck_accounts_expense_status check (status in ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PAID')),
    constraint ck_accounts_expense_year check (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
    constraint ck_accounts_expense_amounts check (amount_paise > 0 and gst_paise >= 0
        and total_paise = amount_paise + gst_paise and tds_paise >= 0 and tds_paise <= amount_paise
        and paid_paise = total_paise - tds_paise),
    constraint ck_accounts_expense_tds check ((tds_section is null) = (tds_rate_percent is null)),
    constraint ck_accounts_expense_mode check (payment_mode is null
        or payment_mode in ('CASH', 'BANK', 'UPI', 'CHEQUE')),
    constraint ck_accounts_expense_paid check (status <> 'PAID'
        or (paid_on is not null and payment_mode is not null)),
    constraint ck_accounts_expense_rejected check (status <> 'REJECTED' or reject_reason is not null),
    constraint fk_accounts_expense_head foreign key (tenant_id, head_id) references accounts.head (tenant_id, id),
    constraint fk_accounts_expense_cost_centre foreign key (tenant_id, cost_centre_id)
        references accounts.cost_centre (tenant_id, id) on delete set null (cost_centre_id),
    constraint fk_accounts_expense_vendor foreign key (tenant_id, vendor_id)
        references accounts.vendor (tenant_id, id) on delete set null (vendor_id),
    constraint fk_accounts_expense_recurring foreign key (tenant_id, recurring_id)
        references accounts.recurring_expense (tenant_id, id) on delete set null (recurring_id),
    constraint fk_accounts_expense_bank_account foreign key (tenant_id, bank_account_id)
        references accounts.bank_account (tenant_id, id),
    constraint fk_accounts_expense_requested_by foreign key (tenant_id, requested_by_id)
        references identity.user_account (tenant_id, id) on delete set null (requested_by_id),
    constraint fk_accounts_expense_approved_by foreign key (tenant_id, approved_by_id)
        references identity.user_account (tenant_id, id) on delete set null (approved_by_id),
    constraint fk_accounts_expense_rejected_by foreign key (tenant_id, rejected_by_id)
        references identity.user_account (tenant_id, id) on delete set null (rejected_by_id),
    constraint fk_accounts_expense_paid_by foreign key (tenant_id, paid_by_id)
        references identity.user_account (tenant_id, id) on delete set null (paid_by_id)
);
create unique index uq_accounts_expense_recurring_month on accounts.expense (tenant_id, recurring_id, recurring_month)
    where recurring_id is not null;
create index ix_accounts_expense_date on accounts.expense (tenant_id, expense_date);
create index ix_accounts_expense_status on accounts.expense (tenant_id, status);
create index ix_accounts_expense_head on accounts.expense (head_id);
create index ix_accounts_expense_cost_centre on accounts.expense (cost_centre_id);
create index ix_accounts_expense_vendor on accounts.expense (vendor_id);
create index ix_accounts_expense_recurring on accounts.expense (recurring_id);
create index ix_accounts_expense_bank_account on accounts.expense (bank_account_id);
create index ix_accounts_expense_requested_by on accounts.expense (requested_by_id);
create index ix_accounts_expense_approved_by on accounts.expense (approved_by_id);
create index ix_accounts_expense_rejected_by on accounts.expense (rejected_by_id);
create index ix_accounts_expense_paid_by on accounts.expense (paid_by_id);

-- Every decision on an expense, oldest first: approved, passed on (limit too small) or rejected.
create table accounts.expense_approval (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    expense_id      uuid         not null,
    action          varchar(10)  not null,
    approver_id     uuid,
    approver_name   varchar(200) not null,
    limit_paise     bigint,
    note            varchar(500),
    created_at      timestamptz  not null,
    constraint uq_accounts_expense_approval_tenant_id unique (tenant_id, id),
    constraint ck_accounts_expense_approval_action check (action in ('APPROVED', 'ESCALATED', 'REJECTED')),
    constraint fk_accounts_expense_approval_expense foreign key (tenant_id, expense_id)
        references accounts.expense (tenant_id, id) on delete cascade,
    constraint fk_accounts_expense_approval_approver foreign key (tenant_id, approver_id)
        references identity.user_account (tenant_id, id) on delete set null (approver_id)
);
create index ix_accounts_expense_approval_expense on accounts.expense_approval (expense_id);
create index ix_accounts_expense_approval_approver on accounts.expense_approval (approver_id);

-- ---------------------------------------------------------------- petty cash

-- Top-ups come out of the main cash book; spends are small purchases by head. The float is in settings.
create table accounts.petty_cash_entry (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    entry_date       date         not null,
    kind             varchar(10)  not null,
    amount_paise     bigint       not null,
    head_id          uuid,
    description      varchar(300) not null,
    recorded_by_id   uuid,
    recorded_by_name varchar(200),
    created_at       timestamptz  not null,
    constraint uq_accounts_petty_cash_tenant_id unique (tenant_id, id),
    constraint ck_accounts_petty_cash_kind check (kind in ('TOP_UP', 'SPENT')),
    constraint ck_accounts_petty_cash_amount check (amount_paise > 0),
    constraint ck_accounts_petty_cash_head check (kind = 'TOP_UP' or head_id is not null),
    constraint fk_accounts_petty_cash_head foreign key (tenant_id, head_id) references accounts.head (tenant_id, id),
    constraint fk_accounts_petty_cash_recorded_by foreign key (tenant_id, recorded_by_id)
        references identity.user_account (tenant_id, id) on delete set null (recorded_by_id)
);
create index ix_accounts_petty_cash_date on accounts.petty_cash_entry (tenant_id, entry_date);
create index ix_accounts_petty_cash_head on accounts.petty_cash_entry (head_id);
create index ix_accounts_petty_cash_recorded_by on accounts.petty_cash_entry (recorded_by_id);

-- ---------------------------------------------------------------- income other than fees

-- Donations (with the 80G receipt number when the school is registered), grants, rentals and miscellaneous
-- receipts. Receipt numbers are gap-free per financial year (INC/2026-27/000001). The donor's PAN is masked in lists.
create table accounts.income_receipt (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    receipt_no        varchar(30)  not null,
    financial_year    varchar(7)   not null,
    seq               integer      not null,
    kind              varchar(10)  not null,
    head_id           uuid         not null,
    received_on       date         not null,
    amount_paise      bigint       not null,
    payer_name        varchar(200) not null,
    payer_pan         varchar(10),
    payer_address     varchar(300),
    mode              varchar(10)  not null,
    bank_account_id   uuid,
    reference         varchar(100),
    eighty_g_number   varchar(60),
    description       varchar(500),
    status            varchar(10)  not null default 'ISSUED',
    cancel_reason     varchar(500),
    cancelled_by_name varchar(200),
    cancelled_at      timestamptz,
    received_by_id    uuid,
    received_by_name  varchar(200),
    cleared_on        date,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint uq_accounts_income_tenant_id unique (tenant_id, id),
    constraint uq_accounts_income_no unique (tenant_id, receipt_no),
    constraint uq_accounts_income_seq unique (tenant_id, financial_year, seq),
    constraint ck_accounts_income_kind check (kind in ('DONATION', 'GRANT', 'RENTAL', 'MISC')),
    constraint ck_accounts_income_mode check (mode in ('CASH', 'BANK', 'UPI', 'CHEQUE')),
    constraint ck_accounts_income_status check (status in ('ISSUED', 'CANCELLED')),
    constraint ck_accounts_income_amount check (amount_paise > 0),
    constraint ck_accounts_income_pan check (payer_pan is null or payer_pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
    constraint ck_accounts_income_cancelled check (status <> 'CANCELLED' or cancel_reason is not null),
    constraint fk_accounts_income_head foreign key (tenant_id, head_id) references accounts.head (tenant_id, id),
    constraint fk_accounts_income_bank_account foreign key (tenant_id, bank_account_id)
        references accounts.bank_account (tenant_id, id),
    constraint fk_accounts_income_received_by foreign key (tenant_id, received_by_id)
        references identity.user_account (tenant_id, id) on delete set null (received_by_id)
);
create index ix_accounts_income_date on accounts.income_receipt (tenant_id, received_on);
create index ix_accounts_income_head on accounts.income_receipt (head_id);
create index ix_accounts_income_bank_account on accounts.income_receipt (bank_account_id);
create index ix_accounts_income_received_by on accounts.income_receipt (received_by_id);

-- ---------------------------------------------------------------- fee income and payroll cost from other modules

-- Fee receipts per day, kept up to date from FeePaymentReceived and rebuilt from the fees module's own totals on
-- request (the reconciliation screen compares the two).
create table accounts.fee_income_day (
    id            uuid primary key,
    tenant_id     uuid        not null references platform.tenant (id),
    day           date        not null,
    amount_paise  bigint      not null default 0,
    cash_paise    bigint      not null default 0,
    receipt_count integer     not null default 0,
    updated_at    timestamptz not null,
    constraint uq_accounts_fee_income_day_tenant_id unique (tenant_id, id),
    constraint uq_accounts_fee_income_day unique (tenant_id, day)
);

-- A month's payroll cost as approved (gross pay plus the employer's contributions), from PayrollApproved.
create table accounts.payroll_cost (
    id               uuid primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    month            date        not null,
    run_id           uuid        not null,
    staff_count      integer     not null,
    gross_paise      bigint      not null,
    deductions_paise bigint      not null,
    net_paise        bigint      not null,
    employer_paise   bigint      not null,
    approved_at      timestamptz not null,
    created_at       timestamptz not null,
    constraint uq_accounts_payroll_cost_tenant_id unique (tenant_id, id),
    constraint uq_accounts_payroll_cost_month unique (tenant_id, month),
    constraint ck_accounts_payroll_cost_month check (extract(day from month) = 1)
);

-- ---------------------------------------------------------------- budgets

create table accounts.budget (
    id             uuid primary key,
    tenant_id      uuid        not null references platform.tenant (id),
    financial_year varchar(7)  not null,
    head_id        uuid        not null,
    annual_paise   bigint      not null,
    spread         varchar(10) not null default 'EVEN',
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    version        bigint      not null default 0,
    constraint uq_accounts_budget_tenant_id unique (tenant_id, id),
    constraint uq_accounts_budget_head unique (tenant_id, financial_year, head_id),
    constraint ck_accounts_budget_year check (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
    constraint ck_accounts_budget_amount check (annual_paise >= 0),
    constraint ck_accounts_budget_spread check (spread in ('EVEN', 'ENTERED')),
    constraint fk_accounts_budget_head foreign key (tenant_id, head_id) references accounts.head (tenant_id, id)
);
create index ix_accounts_budget_head on accounts.budget (head_id);

-- One row per month of the year (1 = April ... 12 = March).
create table accounts.budget_month (
    id           uuid primary key,
    tenant_id    uuid    not null references platform.tenant (id),
    budget_id    uuid    not null,
    month_index  integer not null,
    amount_paise bigint  not null,
    constraint uq_accounts_budget_month_tenant_id unique (tenant_id, id),
    constraint uq_accounts_budget_month unique (budget_id, month_index),
    constraint ck_accounts_budget_month_index check (month_index between 1 and 12),
    constraint ck_accounts_budget_month_amount check (amount_paise >= 0),
    constraint fk_accounts_budget_month_budget foreign key (tenant_id, budget_id)
        references accounts.budget (tenant_id, id) on delete cascade
);
create index ix_accounts_budget_month_tenant on accounts.budget_month (tenant_id);

-- ---------------------------------------------------------------- bank statements (reconciliation)

-- Lines imported from a bank's CSV statement, each matched (or not) to a paid expense or an income receipt.
create table accounts.bank_statement_line (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    bank_account_id    uuid         not null,
    statement_date     date         not null,
    line_date          date         not null,
    description        varchar(300) not null,
    reference          varchar(100),
    debit_paise        bigint       not null default 0,
    credit_paise       bigint       not null default 0,
    matched_expense_id uuid,
    matched_income_id  uuid,
    imported_by_name   varchar(200),
    created_at         timestamptz  not null,
    constraint uq_accounts_statement_line_tenant_id unique (tenant_id, id),
    constraint ck_accounts_statement_line_amount check (debit_paise >= 0 and credit_paise >= 0
        and (debit_paise > 0 or credit_paise > 0)),
    constraint ck_accounts_statement_line_match check (matched_expense_id is null or matched_income_id is null),
    constraint fk_accounts_statement_line_account foreign key (tenant_id, bank_account_id)
        references accounts.bank_account (tenant_id, id) on delete cascade,
    constraint fk_accounts_statement_line_expense foreign key (tenant_id, matched_expense_id)
        references accounts.expense (tenant_id, id) on delete set null (matched_expense_id),
    constraint fk_accounts_statement_line_income foreign key (tenant_id, matched_income_id)
        references accounts.income_receipt (tenant_id, id) on delete set null (matched_income_id)
);
create index ix_accounts_statement_line_account on accounts.bank_statement_line (bank_account_id, statement_date);
create index ix_accounts_statement_line_expense on accounts.bank_statement_line (matched_expense_id);
create index ix_accounts_statement_line_income on accounts.bank_statement_line (matched_income_id);

-- ---------------------------------------------------------------- row-level security

alter table accounts.settings enable row level security;
create policy tenant_isolation on accounts.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.approver enable row level security;
create policy tenant_isolation on accounts.approver
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.head enable row level security;
create policy tenant_isolation on accounts.head
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.cost_centre enable row level security;
create policy tenant_isolation on accounts.cost_centre
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.vendor enable row level security;
create policy tenant_isolation on accounts.vendor
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.bank_account enable row level security;
create policy tenant_isolation on accounts.bank_account
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.opening_balance enable row level security;
create policy tenant_isolation on accounts.opening_balance
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.number_counter enable row level security;
create policy tenant_isolation on accounts.number_counter
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.recurring_expense enable row level security;
create policy tenant_isolation on accounts.recurring_expense
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.expense enable row level security;
create policy tenant_isolation on accounts.expense
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.expense_approval enable row level security;
create policy tenant_isolation on accounts.expense_approval
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.petty_cash_entry enable row level security;
create policy tenant_isolation on accounts.petty_cash_entry
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.income_receipt enable row level security;
create policy tenant_isolation on accounts.income_receipt
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.fee_income_day enable row level security;
create policy tenant_isolation on accounts.fee_income_day
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.payroll_cost enable row level security;
create policy tenant_isolation on accounts.payroll_cost
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.budget enable row level security;
create policy tenant_isolation on accounts.budget
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.budget_month enable row level security;
create policy tenant_isolation on accounts.budget_month
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table accounts.bank_statement_line enable row level security;
create policy tenant_isolation on accounts.bank_statement_line
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

grant usage on schema accounts to ${appRole};
grant select, insert, update, delete on accounts.settings, accounts.approver, accounts.head, accounts.cost_centre,
    accounts.vendor, accounts.bank_account, accounts.opening_balance, accounts.number_counter,
    accounts.recurring_expense, accounts.expense, accounts.expense_approval, accounts.petty_cash_entry,
    accounts.income_receipt, accounts.fee_income_day, accounts.payroll_cost, accounts.budget, accounts.budget_month,
    accounts.bank_statement_line to ${appRole};

-- ---------------------------------------------------------------- permissions of existing schools (idempotent)

update identity.role
set permissions = array_append(permissions, 'accounts.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'ACCOUNTANT')
  and not ('accounts.read' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'accounts.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'ACCOUNTANT')
  and not ('accounts.manage' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'accounts.approve')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('accounts.approve' = any (permissions));
