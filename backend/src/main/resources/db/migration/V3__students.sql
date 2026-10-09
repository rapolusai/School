-- Phase 1, slice 1: students, their guardians and their enrollment in a section for an academic year.
-- Only what the school needs to run admissions and class lists is stored. Aadhaar numbers are never stored.

create schema if not exists students;

create table students.student (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    admission_no    varchar(30)  not null,
    first_name      varchar(100) not null,
    last_name       varchar(100),
    date_of_birth   date         not null,
    gender          varchar(10)  not null,
    admission_date  date         not null,
    status          varchar(20)  not null,
    blood_group     varchar(3),
    address         varchar(500),
    previous_school varchar(200),
    apaar_id        varchar(12),
    user_account_id uuid,
    left_on         date,
    leaving_reason  varchar(500),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint ck_student_gender check (gender in ('MALE', 'FEMALE', 'OTHER')),
    constraint ck_student_status check (status in ('ACTIVE', 'TRANSFERRED', 'ALUMNI', 'WITHDRAWN')),
    constraint ck_student_blood_group check (blood_group is null
        or blood_group in ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-')),
    constraint ck_student_apaar check (apaar_id is null or apaar_id ~ '^[0-9]{12}$'),
    -- Active students have not left; transferred and withdrawn students record the day they left.
    constraint ck_student_left check ((status = 'ACTIVE' and left_on is null) or status <> 'ACTIVE'),
    constraint ck_student_left_on check (status not in ('TRANSFERRED', 'WITHDRAWN') or left_on is not null),
    constraint uq_student_tenant_id unique (tenant_id, id),
    constraint fk_student_user foreign key (tenant_id, user_account_id)
        references identity.user_account (tenant_id, id) on delete set null (user_account_id)
);
create unique index uq_student_admission_no on students.student (tenant_id, lower(admission_no));
create unique index uq_student_user on students.student (user_account_id) where user_account_id is not null;
create index ix_student_tenant_name on students.student (tenant_id, lower(first_name), lower(last_name));

create table students.guardian (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    name            varchar(200) not null,
    relation        varchar(10)  not null,
    phone           varchar(10)  not null,
    email           varchar(254),
    occupation      varchar(100),
    user_account_id uuid,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint ck_guardian_relation check (relation in ('FATHER', 'MOTHER', 'GUARDIAN')),
    -- Indian mobile numbers, stored as 10 digits without +91.
    constraint ck_guardian_phone check (phone ~ '^[6-9][0-9]{9}$'),
    constraint uq_guardian_tenant_id unique (tenant_id, id),
    constraint fk_guardian_user foreign key (tenant_id, user_account_id)
        references identity.user_account (tenant_id, id) on delete set null (user_account_id)
);
create index ix_guardian_phone on students.guardian (tenant_id, phone);
create unique index uq_guardian_user on students.guardian (user_account_id) where user_account_id is not null;

create table students.student_guardian (
    id          uuid primary key,
    tenant_id   uuid        not null references platform.tenant (id),
    student_id  uuid        not null,
    guardian_id uuid        not null,
    is_primary  boolean     not null default false,
    created_at  timestamptz not null,
    constraint uq_student_guardian unique (student_id, guardian_id),
    constraint fk_student_guardian_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_student_guardian_guardian foreign key (tenant_id, guardian_id)
        references students.guardian (tenant_id, id)
);
create index ix_student_guardian_guardian on students.student_guardian (guardian_id);
create index ix_student_guardian_tenant on students.student_guardian (tenant_id);
-- One primary contact per student.
create unique index uq_student_guardian_primary on students.student_guardian (student_id) where is_primary;

create table students.enrollment (
    id               uuid primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    student_id       uuid        not null,
    academic_year_id uuid        not null,
    section_id       uuid        not null,
    roll_no          integer,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null default 0,
    constraint ck_enrollment_roll_no check (roll_no is null or roll_no between 1 and 999),
    -- One enrollment per student per year; roll numbers are unique inside a section for a year when set.
    constraint uq_enrollment_student_year unique (student_id, academic_year_id),
    constraint uq_enrollment_roll_no unique (academic_year_id, section_id, roll_no),
    constraint fk_enrollment_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_enrollment_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_enrollment_section foreign key (tenant_id, section_id) references academics.section (tenant_id, id)
);
create index ix_enrollment_year_section on students.enrollment (tenant_id, academic_year_id, section_id);
create index ix_enrollment_section on students.enrollment (section_id);

-- ---------------------------------------------------------------- row-level security

alter table students.student enable row level security;
create policy tenant_isolation on students.student
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table students.guardian enable row level security;
create policy tenant_isolation on students.guardian
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table students.student_guardian enable row level security;
create policy tenant_isolation on students.student_guardian
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table students.enrollment enable row level security;
create policy tenant_isolation on students.enrollment
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema students to ${appRole};
grant select, insert, update, delete on students.student, students.guardian, students.student_guardian,
    students.enrollment to ${appRole};
