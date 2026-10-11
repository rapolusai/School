-- Phase 2: payroll. Salary components and structures, the staff's bank account, PAN, UAN and ESI number (never
-- Aadhaar), monthly payroll runs with payslips, and the statutory rates they are calculated with.
--
-- Two kinds of tables:
--   * Statutory reference data that is the same for every school (PF and ESI rates and limits, income tax rules per
--     financial year, professional tax slabs per state). It is not school data, has no tenant_id, and the runtime role
--     can only read it. Every row carries the date it applies from, so a new rate is a new row, never a code change.
--     These values must be checked each year: the screens ask schools to confirm them with their accountant.
--   * School data. Every such table carries tenant_id, is protected by the same tenant_isolation policy as V2/V3, and
--     links to other school tables through composite (tenant_id, id) foreign keys.
-- Money is kept in paise (bigint), rates as numeric percentages, never floating point.

create schema if not exists payroll;

-- ---------------------------------------------------------------- statutory reference data (not school-owned)

-- PF (EPF Scheme 1952, EPS 1995, EDLI 1976 with the EPFO administration charges) and ESI (ESI Act 1948) rates and
-- limits. The row with the latest effective_from on or before the first day of a payroll month is used.
create table payroll.statutory_rate (
    id                     uuid primary key,
    effective_from         date          not null,
    pf_employee_percent    numeric(6, 3) not null,
    pf_employer_percent    numeric(6, 3) not null,
    -- PF wages above this are not contributed on unless the school chooses to contribute on full basic + DA.
    pf_wage_ceiling_paise  bigint        not null,
    eps_percent            numeric(6, 3) not null,
    eps_max_paise          bigint        not null,
    edli_percent           numeric(6, 3) not null,
    edli_max_paise         bigint        not null,
    pf_admin_percent       numeric(6, 3) not null,
    pf_admin_min_paise     bigint        not null,
    esi_employee_percent   numeric(6, 3) not null,
    esi_employer_percent   numeric(6, 3) not null,
    -- Employees whose gross wages are at most this at the start of a contribution period are covered by ESI.
    esi_wage_ceiling_paise bigint        not null,
    note                   varchar(500)  not null,
    constraint uq_statutory_rate_from unique (effective_from),
    constraint ck_statutory_rate_values check (pf_wage_ceiling_paise > 0 and eps_max_paise >= 0
        and edli_max_paise >= 0 and pf_admin_min_paise >= 0 and esi_wage_ceiling_paise > 0)
);

-- Income tax under the new regime (section 115BAC) for one financial year: the standard deduction, the section 87A
-- rebate (with marginal relief above the limit) and the health and education cess. The old regime is not supported.
create table payroll.tax_rule (
    id                       uuid primary key,
    financial_year           varchar(7)    not null,
    starts_on                date          not null,
    standard_deduction_paise bigint        not null,
    rebate_income_limit_paise bigint       not null,
    rebate_max_paise         bigint        not null,
    cess_percent             numeric(6, 3) not null,
    note                     varchar(500)  not null,
    constraint uq_tax_rule_year unique (financial_year),
    constraint uq_tax_rule_starts unique (starts_on),
    constraint ck_tax_rule_year check (financial_year ~ '^[0-9]{4}-[0-9]{2}$')
);

-- The tax slabs of a year: income above from_paise and up to to_paise (no upper limit when null) at rate_percent.
create table payroll.tax_slab (
    id          uuid primary key,
    tax_rule_id uuid          not null references payroll.tax_rule (id),
    from_paise  bigint        not null,
    to_paise    bigint,
    rate_percent numeric(6, 3) not null,
    constraint ck_tax_slab_range check (from_paise >= 0 and (to_paise is null or to_paise > from_paise))
);
create index ix_tax_slab_rule on payroll.tax_slab (tax_rule_id);

-- Professional tax slabs per state, as published. A slab applies to monthly gross wages from from_paise up to
-- to_paise (both included; no upper limit when null). applies_to is ALL, or WOMEN and OTHERS where a state has
-- separate slabs for women (Maharashtra). months lists the months a row applies in ("2" for February, "3,9" for a
-- half-yearly tax); null means every month, and a row for specific months replaces the every-month row in them.
create table payroll.pt_slab_default (
    id             uuid primary key,
    state_code     varchar(2)   not null,
    effective_from date         not null,
    applies_to     varchar(10)  not null,
    from_paise     bigint       not null,
    to_paise       bigint,
    amount_paise   bigint       not null,
    months         varchar(40),
    note           varchar(300),
    constraint ck_pt_slab_default_state check (state_code ~ '^[A-Z]{2}$'),
    constraint ck_pt_slab_default_applies check (applies_to in ('ALL', 'WOMEN', 'OTHERS')),
    constraint ck_pt_slab_default_range check (from_paise >= 0 and (to_paise is null or to_paise >= from_paise)
        and amount_paise >= 0),
    constraint ck_pt_slab_default_months check (months is null
        or months ~ '^([1-9]|1[0-2])(,([1-9]|1[0-2]))*$')
);
create index ix_pt_slab_default_state on payroll.pt_slab_default (state_code, effective_from);

-- ---------------------------------------------------------------- school settings

create table payroll.settings (
    id                    uuid primary key,
    tenant_id             uuid         not null references platform.tenant (id),
    -- Salary for a month is paid on this day of the following month (the last day when the month is shorter).
    pay_day               integer      not null default 1,
    -- CALENDAR_DAYS: a month's salary is for all its days; WORKING_DAYS: for its working days (Monday to Saturday
    -- less the school's holidays). Joiners, leavers and loss of pay are prorated on this basis.
    proration_basis       varchar(20)  not null default 'CALENDAR_DAYS',
    pf_enabled            boolean      not null default true,
    -- false: PF is contributed on basic + DA up to the wage ceiling (the default); true: on full basic + DA.
    pf_on_full_wages      boolean      not null default false,
    esi_enabled           boolean      not null default true,
    pt_enabled            boolean      not null default true,
    pt_state              varchar(2),
    pf_establishment_code varchar(30),
    esi_employer_code     varchar(30),
    pt_registration       varchar(30),
    tan                   varchar(10),
    -- The school's own account that salaries are paid from, for the bank advice letter.
    bank_name             varchar(100),
    bank_branch           varchar(100),
    bank_account_number   varchar(18),
    bank_ifsc             varchar(11),
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0,
    constraint uq_payroll_settings_tenant unique (tenant_id),
    constraint uq_payroll_settings_tenant_id unique (tenant_id, id),
    constraint ck_payroll_settings_pay_day check (pay_day between 1 and 31),
    constraint ck_payroll_settings_basis check (proration_basis in ('CALENDAR_DAYS', 'WORKING_DAYS')),
    constraint ck_payroll_settings_state check (pt_state is null or pt_state ~ '^[A-Z]{2}$'),
    constraint ck_payroll_settings_tan check (tan is null or tan ~ '^[A-Z]{4}[0-9]{5}[A-Z]$'),
    constraint ck_payroll_settings_account check (bank_account_number is null
        or bank_account_number ~ '^[0-9]{6,18}$'),
    constraint ck_payroll_settings_ifsc check (bank_ifsc is null or bank_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$')
);

-- The school's own professional tax slabs for a state. When a school has any row for a state, its rows replace the
-- default slabs for that state; deleting them returns to the defaults.
create table payroll.pt_slab (
    id             uuid primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    state_code     varchar(2)   not null,
    effective_from date         not null,
    applies_to     varchar(10)  not null,
    from_paise     bigint       not null,
    to_paise       bigint,
    amount_paise   bigint       not null,
    months         varchar(40),
    created_at     timestamptz  not null,
    constraint uq_pt_slab_tenant_id unique (tenant_id, id),
    constraint ck_pt_slab_state check (state_code ~ '^[A-Z]{2}$'),
    constraint ck_pt_slab_applies check (applies_to in ('ALL', 'WOMEN', 'OTHERS')),
    constraint ck_pt_slab_range check (from_paise >= 0 and (to_paise is null or to_paise >= from_paise)
        and amount_paise >= 0),
    constraint ck_pt_slab_months check (months is null or months ~ '^([1-9]|1[0-2])(,([1-9]|1[0-2]))*$')
);
create index ix_pt_slab_state on payroll.pt_slab (tenant_id, state_code);

-- ---------------------------------------------------------------- salary components and structures

-- Earnings (Basic, DA, HRA, ...) and fixed deductions (loan recovery, other). PF, ESI, professional tax and TDS are
-- calculated by payroll and are not components.
create table payroll.salary_component (
    id            uuid primary key,
    tenant_id     uuid        not null references platform.tenant (id),
    code          varchar(20) not null,
    name          varchar(60) not null,
    kind          varchar(10) not null,
    -- Counts towards PF wages (basic + DA).
    pf_wage       boolean     not null default false,
    -- Counts towards ESI wages (everything paid except, for example, conveyance).
    esi_wage      boolean     not null default true,
    taxable       boolean     not null default true,
    -- Reduced for loss of pay and part months. Fixed deductions are not.
    prorated      boolean     not null default true,
    display_order integer     not null,
    active        boolean     not null default true,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    version       bigint      not null default 0,
    constraint uq_salary_component_tenant_id unique (tenant_id, id),
    constraint ck_salary_component_kind check (kind in ('EARNING', 'DEDUCTION')),
    constraint ck_salary_component_code check (code ~ '^[A-Z][A-Z0-9_]{0,19}$')
);
create unique index uq_salary_component_code on payroll.salary_component (tenant_id, code);
create unique index uq_salary_component_name on payroll.salary_component (tenant_id, lower(name));

-- A staff member's monthly salary from a month on. A revision is a new row; earlier rows stay as history.
create table payroll.salary_structure (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    user_id         uuid         not null,
    -- Always the first day of a month.
    effective_from  date         not null,
    note            varchar(500),
    created_by_id   uuid,
    created_by_name varchar(200),
    created_at      timestamptz  not null,
    constraint uq_salary_structure_tenant_id unique (tenant_id, id),
    constraint uq_salary_structure_month unique (tenant_id, user_id, effective_from),
    constraint ck_salary_structure_month check (extract(day from effective_from) = 1),
    constraint fk_salary_structure_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id),
    constraint fk_salary_structure_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_salary_structure_user on payroll.salary_structure (user_id);
create index ix_salary_structure_created_by on payroll.salary_structure (created_by_id);

create table payroll.salary_structure_line (
    id            uuid primary key,
    tenant_id     uuid   not null references platform.tenant (id),
    structure_id  uuid   not null,
    component_id  uuid   not null,
    monthly_paise bigint not null,
    constraint uq_salary_structure_line_tenant_id unique (tenant_id, id),
    constraint uq_salary_structure_line unique (structure_id, component_id),
    constraint ck_salary_structure_line_amount check (monthly_paise > 0 and monthly_paise <= 10000000000),
    constraint fk_salary_structure_line_structure foreign key (tenant_id, structure_id)
        references payroll.salary_structure (tenant_id, id) on delete cascade,
    constraint fk_salary_structure_line_component foreign key (tenant_id, component_id)
        references payroll.salary_component (tenant_id, id)
);
create index ix_salary_structure_line_tenant on payroll.salary_structure_line (tenant_id);
create index ix_salary_structure_line_component on payroll.salary_structure_line (component_id);

-- ---------------------------------------------------------------- the staff's pay details

-- Only what payroll needs: the bank account salaries go to, PAN (for TDS), UAN (PF), the ESI insurance number, and
-- whether the women's professional tax slab applies. Never Aadhaar. Account numbers and PAN are shown masked; the
-- full values are shown only to payroll managers and every such view is audited.
create table payroll.staff_pay_profile (
    id                  uuid primary key,
    tenant_id           uuid         not null references platform.tenant (id),
    user_id             uuid         not null,
    bank_account_number varchar(18),
    bank_ifsc           varchar(11),
    -- The name on the bank account when it differs from the staff member's name.
    account_holder_name varchar(200),
    pan                 varchar(10),
    uan                 varchar(12),
    esi_number          varchar(10),
    pf_member           boolean      not null default true,
    pt_women_slab       boolean      not null default false,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    version             bigint       not null default 0,
    constraint uq_staff_pay_profile_tenant_id unique (tenant_id, id),
    constraint uq_staff_pay_profile_user unique (tenant_id, user_id),
    constraint ck_staff_pay_profile_account check (bank_account_number is null
        or bank_account_number ~ '^[0-9]{6,18}$'),
    constraint ck_staff_pay_profile_ifsc check (bank_ifsc is null or bank_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
    constraint ck_staff_pay_profile_bank check ((bank_account_number is null) = (bank_ifsc is null)),
    constraint ck_staff_pay_profile_pan check (pan is null or pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
    constraint ck_staff_pay_profile_uan check (uan is null or uan ~ '^[0-9]{12}$'),
    constraint ck_staff_pay_profile_esi check (esi_number is null or esi_number ~ '^[0-9]{10}$'),
    constraint fk_staff_pay_profile_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id)
);

-- ---------------------------------------------------------------- payroll runs and payslips

-- One run per month. DRAFT runs are recalculated freely; APPROVED and PAID runs are locked and never change: a
-- correction is an adjustment in a later run.
create table payroll.payroll_run (
    id                     uuid primary key,
    tenant_id              uuid         not null references platform.tenant (id),
    -- Always the first day of the month.
    month                  date         not null,
    status                 varchar(10)  not null,
    proration_basis        varchar(20)  not null,
    days_in_month          integer      not null,
    working_days           integer      not null,
    staff_count            integer      not null default 0,
    gross_paise            bigint       not null default 0,
    deductions_paise       bigint       not null default 0,
    net_paise              bigint       not null default 0,
    employer_paise         bigint       not null default 0,
    pf_employee_paise      bigint       not null default 0,
    pf_employer_paise      bigint       not null default 0,
    pf_admin_paise         bigint       not null default 0,
    edli_paise             bigint       not null default 0,
    esi_employee_paise     bigint       not null default 0,
    esi_employer_paise     bigint       not null default 0,
    pt_paise               bigint       not null default 0,
    tds_paise              bigint       not null default 0,
    calculated_at          timestamptz  not null,
    created_by_id          uuid,
    created_by_name        varchar(200),
    approved_by_id         uuid,
    approved_by_name       varchar(200),
    approved_at            timestamptz,
    paid_on                date,
    payment_reference      varchar(100),
    paid_by_id             uuid,
    paid_by_name           varchar(200),
    paid_at                timestamptz,
    created_at             timestamptz  not null,
    updated_at             timestamptz  not null,
    version                bigint       not null default 0,
    constraint uq_payroll_run_tenant_id unique (tenant_id, id),
    constraint uq_payroll_run_month unique (tenant_id, month),
    constraint ck_payroll_run_month check (extract(day from month) = 1),
    constraint ck_payroll_run_status check (status in ('DRAFT', 'APPROVED', 'PAID')),
    constraint ck_payroll_run_basis check (proration_basis in ('CALENDAR_DAYS', 'WORKING_DAYS')),
    constraint ck_payroll_run_approved check (status = 'DRAFT' or approved_at is not null),
    constraint ck_payroll_run_paid check (status <> 'PAID' or paid_on is not null),
    constraint fk_payroll_run_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id),
    constraint fk_payroll_run_approved_by foreign key (tenant_id, approved_by_id)
        references identity.user_account (tenant_id, id) on delete set null (approved_by_id),
    constraint fk_payroll_run_paid_by foreign key (tenant_id, paid_by_id)
        references identity.user_account (tenant_id, id) on delete set null (paid_by_id)
);
create index ix_payroll_run_created_by on payroll.payroll_run (created_by_id);
create index ix_payroll_run_approved_by on payroll.payroll_run (approved_by_id);
create index ix_payroll_run_paid_by on payroll.payroll_run (paid_by_id);

-- One staff member's pay for a run. The person's name, code, department and masked identifiers are kept as they were,
-- so a payslip always prints the same. Days are in whole and half days.
create table payroll.payslip (
    id                       uuid primary key,
    tenant_id                uuid          not null references platform.tenant (id),
    run_id                   uuid          not null,
    user_id                  uuid          not null,
    employee_name            varchar(200)  not null,
    employee_code            varchar(30),
    designation              varchar(100),
    department_id            uuid,
    department_name          varchar(100),
    date_of_joining          date,
    date_of_leaving          date,
    structure_from           date          not null,
    pan_masked               varchar(10),
    bank_last4               varchar(4),
    bank_ifsc                varchar(11),
    uan                      varchar(12),
    esi_number               varchar(10),
    basis_days               numeric(5, 1) not null,
    days_on_roll             numeric(5, 1) not null,
    lop_leave_days           numeric(5, 1) not null,
    lop_adjustment_days      numeric(5, 1) not null,
    lop_days                 numeric(5, 1) not null,
    payable_days             numeric(5, 1) not null,
    gross_paise              bigint        not null,
    deductions_paise         bigint        not null,
    net_paise                bigint        not null,
    employer_paise           bigint        not null,
    taxable_paise            bigint        not null,
    pf_member                boolean       not null,
    pf_wages_paise           bigint        not null,
    epf_wages_paise          bigint        not null,
    eps_wages_paise          bigint        not null,
    edli_wages_paise         bigint        not null,
    pf_employee_paise        bigint        not null,
    eps_paise                bigint        not null,
    epf_employer_paise       bigint        not null,
    edli_paise               bigint        not null,
    pf_admin_paise           bigint        not null,
    esi_covered              boolean       not null,
    esi_wages_paise          bigint        not null,
    esi_employee_paise       bigint        not null,
    esi_employer_paise       bigint        not null,
    pt_paise                 bigint        not null,
    tds_calculated_paise     bigint        not null,
    tds_paise                bigint        not null,
    tds_override_paise       bigint,
    tds_override_reason      varchar(500),
    tds_projected_income_paise bigint      not null,
    tds_annual_tax_paise     bigint        not null,
    created_at               timestamptz   not null,
    updated_at               timestamptz   not null,
    version                  bigint        not null default 0,
    constraint uq_payslip_tenant_id unique (tenant_id, id),
    constraint uq_payslip_person unique (tenant_id, run_id, user_id),
    constraint ck_payslip_override check ((tds_override_paise is null) = (tds_override_reason is null)
        and (tds_override_paise is null or tds_override_paise >= 0)),
    constraint fk_payslip_run foreign key (tenant_id, run_id)
        references payroll.payroll_run (tenant_id, id) on delete cascade,
    constraint fk_payslip_user foreign key (tenant_id, user_id) references identity.user_account (tenant_id, id)
);
create index ix_payslip_run on payroll.payslip (run_id);
create index ix_payslip_user on payroll.payslip (tenant_id, user_id);

-- The lines of a payslip: earnings, deductions, and the employer's contributions (shown, not deducted).
create table payroll.payslip_line (
    id            uuid primary key,
    tenant_id     uuid        not null references platform.tenant (id),
    payslip_id    uuid        not null,
    kind          varchar(10) not null,
    code          varchar(20) not null,
    name          varchar(60) not null,
    -- The full monthly amount before proration, for components; null for calculated and adjusted lines.
    full_paise    bigint,
    amount_paise  bigint      not null,
    display_order integer     not null,
    constraint uq_payslip_line_tenant_id unique (tenant_id, id),
    constraint ck_payslip_line_kind check (kind in ('EARNING', 'DEDUCTION', 'EMPLOYER')),
    constraint fk_payslip_line_payslip foreign key (tenant_id, payslip_id)
        references payroll.payslip (tenant_id, id) on delete cascade
);
create index ix_payslip_line_payslip on payroll.payslip_line (payslip_id);
create index ix_payslip_line_tenant on payroll.payslip_line (tenant_id);

-- Manual changes to one person's pay in a draft run, each with a reason: arrears, bonus, a deduction, or a correction
-- to the loss-of-pay days (positive adds days, negative removes them).
create table payroll.adjustment (
    id              uuid primary key,
    tenant_id       uuid          not null references platform.tenant (id),
    run_id          uuid          not null,
    user_id         uuid          not null,
    kind            varchar(20)   not null,
    amount_paise    bigint,
    days            numeric(4, 1),
    reason          varchar(500)  not null,
    created_by_id   uuid,
    created_by_name varchar(200),
    created_at      timestamptz   not null,
    constraint uq_adjustment_tenant_id unique (tenant_id, id),
    constraint ck_adjustment_kind check (kind in ('ARREARS', 'BONUS', 'DEDUCTION', 'LOP_CORRECTION')),
    constraint ck_adjustment_value check (
        (kind = 'LOP_CORRECTION' and amount_paise is null and days is not null and days <> 0
            and days * 2 = trunc(days * 2))
        or (kind <> 'LOP_CORRECTION' and days is null and amount_paise is not null and amount_paise > 0)),
    constraint fk_adjustment_run foreign key (tenant_id, run_id)
        references payroll.payroll_run (tenant_id, id) on delete cascade,
    constraint fk_adjustment_user foreign key (tenant_id, user_id) references identity.user_account (tenant_id, id),
    constraint fk_adjustment_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_adjustment_run on payroll.adjustment (run_id, user_id);
create index ix_adjustment_user on payroll.adjustment (user_id);
create index ix_adjustment_created_by on payroll.adjustment (created_by_id);

-- ---------------------------------------------------------------- row-level security

alter table payroll.settings enable row level security;
create policy tenant_isolation on payroll.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table payroll.pt_slab enable row level security;
create policy tenant_isolation on payroll.pt_slab
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table payroll.salary_component enable row level security;
create policy tenant_isolation on payroll.salary_component
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table payroll.salary_structure enable row level security;
create policy tenant_isolation on payroll.salary_structure
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table payroll.salary_structure_line enable row level security;
create policy tenant_isolation on payroll.salary_structure_line
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table payroll.staff_pay_profile enable row level security;
create policy tenant_isolation on payroll.staff_pay_profile
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table payroll.payroll_run enable row level security;
create policy tenant_isolation on payroll.payroll_run
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table payroll.payslip enable row level security;
create policy tenant_isolation on payroll.payslip
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table payroll.payslip_line enable row level security;
create policy tenant_isolation on payroll.payslip_line
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table payroll.adjustment enable row level security;
create policy tenant_isolation on payroll.adjustment
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema payroll to ${appRole};
-- Statutory reference data is read-only for the API; a new rate or year is added by a migration.
grant select on payroll.statutory_rate, payroll.tax_rule, payroll.tax_slab, payroll.pt_slab_default to ${appRole};
grant select, insert, update, delete on payroll.settings, payroll.pt_slab, payroll.salary_component,
    payroll.salary_structure, payroll.salary_structure_line, payroll.staff_pay_profile, payroll.payroll_run,
    payroll.payslip, payroll.payslip_line, payroll.adjustment to ${appRole};

-- ---------------------------------------------------------------- statutory reference data: seed values
-- VERIFY EVERY YEAR with the school's accountant and the official sources (EPFO, ESIC, the Income Tax Department and
-- each state's professional tax act). A change is a new row with its own effective date, added by a new migration.

insert into payroll.statutory_rate (id, effective_from, pf_employee_percent, pf_employer_percent,
    pf_wage_ceiling_paise, eps_percent, eps_max_paise, edli_percent, edli_max_paise, pf_admin_percent,
    pf_admin_min_paise, esi_employee_percent, esi_employer_percent, esi_wage_ceiling_paise, note)
values ('0198f000-0000-7000-8000-000000000001', '2019-07-01', 12.000, 12.000, 1500000, 8.330, 125000, 0.500, 7500,
    0.500, 50000, 0.750, 3.250, 2100000,
    'EPF 12% + 12% of basic + DA (wage ceiling Rs 15,000); EPS 8.33% of wages up to Rs 15,000 (at most Rs 1,250); '
    || 'EDLI 0.5% (at most Rs 75); EPF administration 0.5% (at least Rs 500 a month). ESI 0.75% + 3.25% while gross '
    || 'wages are within Rs 21,000 (rates from 1 July 2019).');

insert into payroll.tax_rule (id, financial_year, starts_on, standard_deduction_paise, rebate_income_limit_paise,
    rebate_max_paise, cess_percent, note)
values ('0198f000-0000-7000-8000-000000000101', '2025-26', '2025-04-01', 7500000, 120000000, 6000000, 4.000,
    'New tax regime (section 115BAC) as amended by the Finance Act 2025: standard deduction Rs 75,000; section 87A '
    || 'rebate up to Rs 60,000 for taxable income up to Rs 12 lakh, with marginal relief above it; 4% health and '
    || 'education cess. Verify each year.');

insert into payroll.tax_slab (id, tax_rule_id, from_paise, to_paise, rate_percent) values
    ('0198f000-0000-7000-8000-000000000111', '0198f000-0000-7000-8000-000000000101', 0, 40000000, 0.000),
    ('0198f000-0000-7000-8000-000000000112', '0198f000-0000-7000-8000-000000000101', 40000000, 80000000, 5.000),
    ('0198f000-0000-7000-8000-000000000113', '0198f000-0000-7000-8000-000000000101', 80000000, 120000000, 10.000),
    ('0198f000-0000-7000-8000-000000000114', '0198f000-0000-7000-8000-000000000101', 120000000, 160000000, 15.000),
    ('0198f000-0000-7000-8000-000000000115', '0198f000-0000-7000-8000-000000000101', 160000000, 200000000, 20.000),
    ('0198f000-0000-7000-8000-000000000116', '0198f000-0000-7000-8000-000000000101', 200000000, 240000000, 25.000),
    ('0198f000-0000-7000-8000-000000000117', '0198f000-0000-7000-8000-000000000101', 240000000, null, 30.000);

-- Professional tax on monthly gross wages. Amounts in paise.
insert into payroll.pt_slab_default (id, state_code, effective_from, applies_to, from_paise, to_paise, amount_paise,
    months, note) values
    -- Telangana
    ('0198f000-0000-7000-8000-000000000201', 'TG', '2014-06-02', 'ALL', 0, 1500000, 0, null, null),
    ('0198f000-0000-7000-8000-000000000202', 'TG', '2014-06-02', 'ALL', 1500001, 2000000, 15000, null, null),
    ('0198f000-0000-7000-8000-000000000203', 'TG', '2014-06-02', 'ALL', 2000001, null, 20000, null, null),
    -- Andhra Pradesh
    ('0198f000-0000-7000-8000-000000000211', 'AP', '2013-04-01', 'ALL', 0, 1500000, 0, null, null),
    ('0198f000-0000-7000-8000-000000000212', 'AP', '2013-04-01', 'ALL', 1500001, 2000000, 15000, null, null),
    ('0198f000-0000-7000-8000-000000000213', 'AP', '2013-04-01', 'ALL', 2000001, null, 20000, null, null),
    -- Karnataka: Rs 200 a month from Rs 25,000, Rs 300 in February (Rs 2,500 a year from 1 April 2025)
    ('0198f000-0000-7000-8000-000000000221', 'KA', '2025-04-01', 'ALL', 0, 2499999, 0, null, null),
    ('0198f000-0000-7000-8000-000000000222', 'KA', '2025-04-01', 'ALL', 2500000, null, 20000, null, null),
    ('0198f000-0000-7000-8000-000000000223', 'KA', '2025-04-01', 'ALL', 2500000, null, 30000, '2',
        'February: Rs 300'),
    -- Maharashtra: separate slabs for women; Rs 300 instead of Rs 200 in February
    ('0198f000-0000-7000-8000-000000000231', 'MH', '2023-07-01', 'OTHERS', 0, 750000, 0, null, null),
    ('0198f000-0000-7000-8000-000000000232', 'MH', '2023-07-01', 'OTHERS', 750001, 1000000, 17500, null, null),
    ('0198f000-0000-7000-8000-000000000233', 'MH', '2023-07-01', 'OTHERS', 1000001, null, 20000, null, null),
    ('0198f000-0000-7000-8000-000000000234', 'MH', '2023-07-01', 'OTHERS', 1000001, null, 30000, '2',
        'February: Rs 300'),
    ('0198f000-0000-7000-8000-000000000235', 'MH', '2023-07-01', 'WOMEN', 0, 2500000, 0, null, null),
    ('0198f000-0000-7000-8000-000000000236', 'MH', '2023-07-01', 'WOMEN', 2500001, null, 20000, null, null),
    ('0198f000-0000-7000-8000-000000000237', 'MH', '2023-07-01', 'WOMEN', 2500001, null, 30000, '2',
        'February: Rs 300'),
    -- Tamil Nadu: a half-yearly tax on half-yearly income, deducted in September and March. The slabs are the
    -- half-yearly ones divided by six (monthly wages); the amount is the half-yearly tax (Greater Chennai rates; other
    -- local bodies differ).
    ('0198f000-0000-7000-8000-000000000241', 'TN', '2024-04-01', 'ALL', 0, 350000, 0, '3,9', null),
    ('0198f000-0000-7000-8000-000000000242', 'TN', '2024-04-01', 'ALL', 350001, 500000, 18000, '3,9', null),
    ('0198f000-0000-7000-8000-000000000243', 'TN', '2024-04-01', 'ALL', 500001, 750000, 42500, '3,9', null),
    ('0198f000-0000-7000-8000-000000000244', 'TN', '2024-04-01', 'ALL', 750001, 1000000, 93000, '3,9', null),
    ('0198f000-0000-7000-8000-000000000245', 'TN', '2024-04-01', 'ALL', 1000001, 1250000, 102500, '3,9', null),
    ('0198f000-0000-7000-8000-000000000246', 'TN', '2024-04-01', 'ALL', 1250001, null, 125000, '3,9', null),
    -- West Bengal
    ('0198f000-0000-7000-8000-000000000251', 'WB', '2014-04-01', 'ALL', 0, 1000000, 0, null, null),
    ('0198f000-0000-7000-8000-000000000252', 'WB', '2014-04-01', 'ALL', 1000001, 1500000, 11000, null, null),
    ('0198f000-0000-7000-8000-000000000253', 'WB', '2014-04-01', 'ALL', 1500001, 2500000, 13000, null, null),
    ('0198f000-0000-7000-8000-000000000254', 'WB', '2014-04-01', 'ALL', 2500001, 4000000, 15000, null, null),
    ('0198f000-0000-7000-8000-000000000255', 'WB', '2014-04-01', 'ALL', 4000001, null, 20000, null, null);

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- payroll.read: payroll screens, payslips and reports; payroll.manage: settings, structures, pay details, runs,
-- adjustments and the bank, PF, ESI, PT and TDS files; payroll.approve: approving (locking) a month's payroll.
-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.
update identity.role
set permissions = array_append(permissions, 'payroll.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'ACCOUNTANT')
  and not ('payroll.read' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'payroll.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'ACCOUNTANT')
  and not ('payroll.manage' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'payroll.approve')
where system_role
  and code = 'SCHOOL_ADMIN'
  and not ('payroll.approve' = any (permissions));
