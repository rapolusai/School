-- Phase 1: staff records, staff attendance (check-in and check-out) and leave.
-- A staff profile belongs 1:1 to a sign-in (identity.user_account) with a staff role. Only what the school needs to run
-- its staff room is stored: no salary or bank details (payroll comes later), no Aadhaar and no PAN.
-- Every school-owned table carries tenant_id, is protected by the same tenant_isolation policy as V2/V3, and links to
-- other school tables through composite (tenant_id, id) foreign keys.

create schema if not exists staff;

-- ---------------------------------------------------------------- departments

create table staff.department (
    id           uuid primary key,
    tenant_id    uuid         not null references platform.tenant (id),
    name         varchar(100) not null,
    -- Leave requests of the department's staff go to its head; without a head they go to the School Admin/Principal.
    head_user_id uuid,
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    version      bigint       not null default 0,
    constraint uq_department_tenant_id unique (tenant_id, id),
    constraint fk_department_head foreign key (tenant_id, head_user_id)
        references identity.user_account (tenant_id, id) on delete set null (head_user_id)
);
create unique index uq_department_name on staff.department (tenant_id, lower(name));
create index ix_department_head on staff.department (head_user_id);

-- ---------------------------------------------------------------- staff profiles

create table staff.staff_profile (
    id                       uuid primary key,
    tenant_id                uuid         not null references platform.tenant (id),
    user_id                  uuid         not null,
    employee_code            varchar(30)  not null,
    designation              varchar(100) not null,
    department_id            uuid,
    employment_type          varchar(20)  not null,
    date_of_joining          date         not null,
    date_of_leaving          date,
    leaving_reason           varchar(500),
    -- Indian mobile numbers, stored as 10 digits without +91 (lists show them masked).
    mobile                   varchar(10)  not null,
    qualifications           varchar(500),
    emergency_contact_name   varchar(200),
    emergency_contact_mobile varchar(10),
    created_at               timestamptz  not null,
    updated_at               timestamptz  not null,
    version                  bigint       not null default 0,
    constraint ck_staff_profile_employment check (employment_type in ('PERMANENT', 'CONTRACT', 'PART_TIME', 'PROBATION')),
    constraint ck_staff_profile_mobile check (mobile ~ '^[6-9][0-9]{9}$'),
    constraint ck_staff_profile_emergency_mobile check (emergency_contact_mobile is null
        or emergency_contact_mobile ~ '^[6-9][0-9]{9}$'),
    -- An emergency contact has both a name and a number, or neither.
    constraint ck_staff_profile_emergency check ((emergency_contact_name is null) = (emergency_contact_mobile is null)),
    constraint ck_staff_profile_leaving check (date_of_leaving is null or date_of_leaving >= date_of_joining),
    constraint ck_staff_profile_leaving_reason check ((date_of_leaving is null) = (leaving_reason is null)),
    constraint uq_staff_profile_tenant_id unique (tenant_id, id),
    constraint uq_staff_profile_user unique (tenant_id, user_id),
    constraint fk_staff_profile_user foreign key (tenant_id, user_id) references identity.user_account (tenant_id, id),
    constraint fk_staff_profile_department foreign key (tenant_id, department_id)
        references staff.department (tenant_id, id)
);
create unique index uq_staff_profile_employee_code on staff.staff_profile (tenant_id, lower(employee_code));
create index ix_staff_profile_department on staff.staff_profile (department_id);

-- ---------------------------------------------------------------- leave types and balances

-- Days are kept as numeric(5,1) in whole and half days, never as floating point.
create table staff.leave_type (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    name              varchar(60)  not null,
    code              varchar(10)  not null,
    yearly_quota      numeric(5, 1) not null default 0,
    carry_forward_cap numeric(5, 1) not null default 0,
    half_day_allowed  boolean      not null default true,
    -- Loss of pay has no quota or balance: it is never refused for lack of balance.
    loss_of_pay       boolean      not null default false,
    active            boolean      not null default true,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint ck_leave_type_quota check (yearly_quota between 0 and 366 and yearly_quota * 2 = trunc(yearly_quota * 2)),
    constraint ck_leave_type_cap check (carry_forward_cap between 0 and 366
        and carry_forward_cap * 2 = trunc(carry_forward_cap * 2)),
    constraint uq_leave_type_tenant_id unique (tenant_id, id)
);
create unique index uq_leave_type_name on staff.leave_type (tenant_id, lower(name));
create unique index uq_leave_type_code on staff.leave_type (tenant_id, lower(code));

-- A balance row exists only where the school set a person's opening balance or allowance for a year by hand. Without
-- one, the opening balance is carried forward from the previous year (up to the type's cap) and the allowance is the
-- type's yearly quota. Leave taken is always counted from approved requests.
create table staff.leave_balance (
    id               uuid primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    user_id          uuid          not null,
    leave_type_id    uuid          not null,
    academic_year_id uuid          not null,
    opening          numeric(5, 1) not null,
    accrued          numeric(5, 1) not null,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint ck_leave_balance_opening check (opening between 0 and 999 and opening * 2 = trunc(opening * 2)),
    constraint ck_leave_balance_accrued check (accrued between 0 and 999 and accrued * 2 = trunc(accrued * 2)),
    constraint uq_leave_balance unique (tenant_id, user_id, leave_type_id, academic_year_id),
    constraint fk_leave_balance_user foreign key (tenant_id, user_id) references identity.user_account (tenant_id, id),
    constraint fk_leave_balance_type foreign key (tenant_id, leave_type_id) references staff.leave_type (tenant_id, id),
    constraint fk_leave_balance_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id)
);
create index ix_leave_balance_type on staff.leave_balance (leave_type_id);
create index ix_leave_balance_year on staff.leave_balance (academic_year_id);

-- ---------------------------------------------------------------- leave requests

create table staff.leave_request (
    id                uuid primary key,
    tenant_id         uuid          not null references platform.tenant (id),
    user_id           uuid          not null,
    leave_type_id     uuid          not null,
    academic_year_id  uuid          not null,
    from_date         date          not null,
    to_date           date          not null,
    half_day          boolean       not null default false,
    -- Working days in the range (Sundays not counted), or 0.5 for a half day.
    days              numeric(5, 1) not null,
    reason            varchar(500)  not null,
    status            varchar(10)   not null,
    -- The department head the request went to; null means the School Admin and Principal (leave.approve).
    approver_user_id  uuid,
    decided_by_id     uuid,
    decided_by_name   varchar(200),
    decided_at        timestamptz,
    decision_comment  varchar(500),
    cancelled_by_id   uuid,
    cancelled_by_name varchar(200),
    cancelled_at      timestamptz,
    cancel_comment    varchar(500),
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    version           bigint        not null default 0,
    constraint ck_leave_request_status check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    constraint ck_leave_request_dates check (to_date >= from_date),
    constraint ck_leave_request_half_day check (not half_day or from_date = to_date),
    constraint ck_leave_request_days check (days > 0 and days * 2 = trunc(days * 2)),
    constraint uq_leave_request_tenant_id unique (tenant_id, id),
    constraint fk_leave_request_user foreign key (tenant_id, user_id) references identity.user_account (tenant_id, id),
    constraint fk_leave_request_type foreign key (tenant_id, leave_type_id) references staff.leave_type (tenant_id, id),
    constraint fk_leave_request_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_leave_request_approver foreign key (tenant_id, approver_user_id)
        references identity.user_account (tenant_id, id) on delete set null (approver_user_id),
    constraint fk_leave_request_decided_by foreign key (tenant_id, decided_by_id)
        references identity.user_account (tenant_id, id) on delete set null (decided_by_id),
    constraint fk_leave_request_cancelled_by foreign key (tenant_id, cancelled_by_id)
        references identity.user_account (tenant_id, id) on delete set null (cancelled_by_id)
);
create index ix_leave_request_user on staff.leave_request (tenant_id, user_id, from_date);
create index ix_leave_request_pending on staff.leave_request (tenant_id, approver_user_id) where status = 'PENDING';
create index ix_leave_request_type on staff.leave_request (leave_type_id);
create index ix_leave_request_year on staff.leave_request (academic_year_id);
create index ix_leave_request_approver on staff.leave_request (approver_user_id);
create index ix_leave_request_decided_by on staff.leave_request (decided_by_id);
create index ix_leave_request_cancelled_by on staff.leave_request (cancelled_by_id);

-- ---------------------------------------------------------------- staff attendance

-- One row per staff member per school day (Asia/Kolkata). SELF rows come from check-in on the web app, ADMIN rows
-- from the daily sheet, LEAVE rows from approved leave. A biometric or RFID device would add a DEVICE source later.
create table staff.attendance (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    user_id          uuid         not null,
    attendance_date  date         not null,
    status           varchar(10)  not null,
    source           varchar(10)  not null,
    check_in_at      timestamptz,
    check_out_at     timestamptz,
    check_in_note    varchar(200),
    check_out_note   varchar(200),
    leave_request_id uuid,
    marked_by_id     uuid,
    marked_by_name   varchar(200),
    updated_by_id    uuid,
    updated_by_name  varchar(200),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint ck_staff_attendance_status check (status in ('PRESENT', 'ABSENT', 'HALF_DAY', 'ON_LEAVE')),
    constraint ck_staff_attendance_source check (source in ('SELF', 'ADMIN', 'LEAVE')),
    constraint ck_staff_attendance_times check (check_out_at is null
        or (check_in_at is not null and check_out_at >= check_in_at)),
    constraint uq_staff_attendance_day unique (tenant_id, user_id, attendance_date),
    constraint fk_staff_attendance_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id),
    constraint fk_staff_attendance_leave foreign key (tenant_id, leave_request_id)
        references staff.leave_request (tenant_id, id) on delete set null (leave_request_id),
    constraint fk_staff_attendance_marked_by foreign key (tenant_id, marked_by_id)
        references identity.user_account (tenant_id, id) on delete set null (marked_by_id),
    constraint fk_staff_attendance_updated_by foreign key (tenant_id, updated_by_id)
        references identity.user_account (tenant_id, id) on delete set null (updated_by_id)
);
create index ix_staff_attendance_date on staff.attendance (tenant_id, attendance_date);
create index ix_staff_attendance_leave on staff.attendance (leave_request_id);
create index ix_staff_attendance_marked_by on staff.attendance (marked_by_id);
create index ix_staff_attendance_updated_by on staff.attendance (updated_by_id);

-- ---------------------------------------------------------------- row-level security

alter table staff.department enable row level security;
create policy tenant_isolation on staff.department
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table staff.staff_profile enable row level security;
create policy tenant_isolation on staff.staff_profile
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table staff.leave_type enable row level security;
create policy tenant_isolation on staff.leave_type
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table staff.leave_balance enable row level security;
create policy tenant_isolation on staff.leave_balance
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table staff.leave_request enable row level security;
create policy tenant_isolation on staff.leave_request
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table staff.attendance enable row level security;
create policy tenant_isolation on staff.attendance
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema staff to ${appRole};
grant select, insert, update, delete on staff.department, staff.staff_profile, staff.leave_type, staff.leave_balance,
    staff.leave_request, staff.attendance to ${appRole};

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.
update identity.role
set permissions = array_append(permissions, 'staff.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('staff.read' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'staff.manage')
where system_role
  and code = 'SCHOOL_ADMIN'
  and not ('staff.manage' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'leave.request')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER', 'ACCOUNTANT', 'FRONT_OFFICE')
  and not ('leave.request' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'leave.approve')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('leave.approve' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'staff_attendance.manage')
where system_role
  and code = 'SCHOOL_ADMIN'
  and not ('staff_attendance.manage' = any (permissions));
