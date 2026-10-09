-- Phase 1: file attachments, the timetable (bell schedule, teacher assignments, section timetables, substitutions) and
-- homework (assignments to sections, submissions, reviews, reminders). Every school-owned table carries tenant_id, is
-- protected by the same tenant_isolation policy as V2/V3, and links to other school tables through composite
-- (tenant_id, id) keys, so the database itself refuses a link between two schools.

create schema if not exists files;
create schema if not exists timetable;
create schema if not exists homework;

-- ---------------------------------------------------------------- files

-- Uploaded files live in the database for the pilot, so they are protected by row-level security like every other
-- school row (see docs/adr/0004-file-storage.md). The owning module decides who may read a file; owner_id points at
-- that module's record (a homework, a submission) and is checked by the owner, not by a foreign key.
create table files.stored_file (
    id            uuid         primary key,
    tenant_id     uuid         not null references platform.tenant (id),
    owner_module  varchar(30)  not null,
    owner_type    varchar(40)  not null,
    owner_id      uuid         not null,
    original_name varchar(255) not null,
    content_type  varchar(100) not null,
    size_bytes    integer      not null,
    sha256        varchar(64)  not null,
    bytes         bytea        not null,
    uploaded_by   uuid,
    created_at    timestamptz  not null,
    constraint ck_stored_file_size check (size_bytes between 1 and 5242880 and octet_length(bytes) = size_bytes),
    constraint ck_stored_file_sha256 check (sha256 ~ '^[0-9a-f]{64}$'),
    constraint uq_stored_file_tenant_id unique (tenant_id, id),
    constraint fk_stored_file_uploaded_by foreign key (tenant_id, uploaded_by)
        references identity.user_account (tenant_id, id) on delete set null (uploaded_by)
);
-- PDFs, images and Office files are already compressed: store them as they are.
alter table files.stored_file alter column bytes set storage external;
create index ix_stored_file_owner on files.stored_file (tenant_id, owner_module, owner_type, owner_id);
create index ix_stored_file_uploaded_by on files.stored_file (uploaded_by);

-- ---------------------------------------------------------------- timetable: bell schedule

-- Per-school timetable settings. A school without a row works Monday to Saturday with one schedule for every day.
create table timetable.settings (
    id                uuid        primary key,
    tenant_id         uuid        not null references platform.tenant (id),
    working_days      varchar(27) not null default 'MON,TUE,WED,THU,FRI,SAT',
    saturday_schedule boolean     not null default false,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    version           bigint      not null default 0,
    constraint uq_timetable_settings_tenant unique (tenant_id),
    constraint ck_timetable_settings_days check (working_days ~
        '^(MON|TUE|WED|THU|FRI|SAT|SUN)(,(MON|TUE|WED|THU|FRI|SAT|SUN)){0,6}$')
);

-- The periods and breaks of a school day. WEEKDAY applies to every working day, SATURDAY to Saturdays when the school
-- uses a separate Saturday schedule. Teaching periods are numbered 1, 2, 3… in time order; breaks have no number, so
-- adding a break never renumbers the periods that timetables refer to.
create table timetable.period (
    id         uuid        primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    schedule   varchar(10) not null,
    position   integer     not null,
    number     integer,
    label      varchar(40) not null,
    starts_at  time        not null,
    ends_at    time        not null,
    is_break   boolean     not null,
    created_at timestamptz not null,
    constraint ck_period_schedule check (schedule in ('WEEKDAY', 'SATURDAY')),
    constraint ck_period_number check ((is_break and number is null) or (not is_break and number between 1 and 16)),
    constraint ck_period_position check (position between 1 and 30),
    constraint ck_period_times check (ends_at > starts_at),
    constraint uq_period_position unique (tenant_id, schedule, position),
    constraint uq_period_number unique (tenant_id, schedule, number)
);

-- ---------------------------------------------------------------- timetable: who teaches what

-- One teacher per subject per section per academic year, with the periods a week the subject should get.
create table timetable.teacher_assignment (
    id               uuid        primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    academic_year_id uuid        not null,
    section_id       uuid        not null,
    subject_id       uuid        not null,
    teacher_id       uuid        not null,
    periods_per_week integer     not null,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null default 0,
    constraint ck_teacher_assignment_periods check (periods_per_week between 0 and 60),
    constraint uq_teacher_assignment unique (tenant_id, academic_year_id, section_id, subject_id),
    constraint uq_teacher_assignment_tenant_id unique (tenant_id, id),
    constraint fk_teacher_assignment_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_teacher_assignment_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id) on delete cascade,
    constraint fk_teacher_assignment_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id) on delete cascade,
    constraint fk_teacher_assignment_teacher foreign key (tenant_id, teacher_id)
        references identity.user_account (tenant_id, id)
);
create index ix_teacher_assignment_teacher on timetable.teacher_assignment (tenant_id, academic_year_id, teacher_id);
create index ix_teacher_assignment_section on timetable.teacher_assignment (section_id);
create index ix_teacher_assignment_subject on timetable.teacher_assignment (subject_id);
create index ix_teacher_assignment_teacher_fk on timetable.teacher_assignment (teacher_id);

-- ---------------------------------------------------------------- timetable: the weekly grid

-- One cell of a section's weekly timetable: day (1 = Monday … 7 = Sunday) and teaching period number. A section
-- cannot have two subjects in one period. A teacher booked in two sections at once is refused when a timetable is
-- saved; it can still arise when a teacher assignment changes, so it is reported (not enforced) here.
create table timetable.slot (
    id               uuid        primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    academic_year_id uuid        not null,
    section_id       uuid        not null,
    day_of_week      integer     not null,
    period_no        integer     not null,
    subject_id       uuid        not null,
    teacher_id       uuid,
    room             varchar(40),
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    constraint ck_slot_day check (day_of_week between 1 and 7),
    constraint ck_slot_period check (period_no between 1 and 16),
    constraint uq_slot_cell unique (tenant_id, academic_year_id, section_id, day_of_week, period_no),
    constraint uq_slot_tenant_id unique (tenant_id, id),
    constraint fk_slot_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_slot_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id) on delete cascade,
    constraint fk_slot_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id) on delete cascade,
    constraint fk_slot_teacher foreign key (tenant_id, teacher_id)
        references identity.user_account (tenant_id, id) on delete set null (teacher_id)
);
create index ix_slot_teacher on timetable.slot (tenant_id, academic_year_id, teacher_id, day_of_week, period_no);
create index ix_slot_section on timetable.slot (section_id);
create index ix_slot_subject on timetable.slot (subject_id);
create index ix_slot_teacher_fk on timetable.slot (teacher_id);

-- ---------------------------------------------------------------- timetable: absences and substitutions

-- A teacher marked absent for a day, so their periods can be covered. (Approved staff leave can feed this later.)
create table timetable.teacher_absence (
    id              uuid         primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    absence_date    date         not null,
    teacher_id      uuid         not null,
    reason          varchar(200),
    created_by_id   uuid,
    created_by_name varchar(200),
    created_at      timestamptz  not null,
    constraint uq_teacher_absence unique (tenant_id, absence_date, teacher_id),
    constraint uq_teacher_absence_tenant_id unique (tenant_id, id),
    constraint fk_teacher_absence_teacher foreign key (tenant_id, teacher_id)
        references identity.user_account (tenant_id, id),
    constraint fk_teacher_absence_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_teacher_absence_teacher on timetable.teacher_absence (teacher_id);
create index ix_teacher_absence_created_by on timetable.teacher_absence (created_by_id);

-- Who covers one period of an absent teacher on a date. The period's section and subject are kept as they were on
-- the day, so the substitution sheet stays correct after the timetable changes. A substitute covers one class at a
-- time, and a period is covered by one substitute.
create table timetable.substitution (
    id                    uuid         primary key,
    tenant_id             uuid         not null references platform.tenant (id),
    absence_id            uuid         not null,
    sub_date              date         not null,
    section_id            uuid         not null,
    period_no             integer      not null,
    subject_id            uuid         not null,
    absent_teacher_id     uuid         not null,
    substitute_teacher_id uuid         not null,
    created_by_id         uuid,
    created_by_name       varchar(200),
    created_at            timestamptz  not null,
    constraint ck_substitution_period check (period_no between 1 and 16),
    constraint ck_substitution_people check (substitute_teacher_id <> absent_teacher_id),
    constraint uq_substitution_period unique (tenant_id, sub_date, section_id, period_no),
    constraint uq_substitution_substitute unique (tenant_id, sub_date, substitute_teacher_id, period_no),
    constraint fk_substitution_absence foreign key (tenant_id, absence_id)
        references timetable.teacher_absence (tenant_id, id) on delete cascade,
    constraint fk_substitution_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id) on delete cascade,
    constraint fk_substitution_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id) on delete cascade,
    constraint fk_substitution_absent_teacher foreign key (tenant_id, absent_teacher_id)
        references identity.user_account (tenant_id, id),
    constraint fk_substitution_substitute foreign key (tenant_id, substitute_teacher_id)
        references identity.user_account (tenant_id, id),
    constraint fk_substitution_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_substitution_absence on timetable.substitution (absence_id);
create index ix_substitution_section on timetable.substitution (section_id);
create index ix_substitution_subject on timetable.substitution (subject_id);
create index ix_substitution_absent_teacher on timetable.substitution (absent_teacher_id);
create index ix_substitution_substitute on timetable.substitution (substitute_teacher_id);
create index ix_substitution_created_by on timetable.substitution (created_by_id);

-- ---------------------------------------------------------------- homework

-- Homework set for one subject in one or more sections. Instructions are plain text. Students see it from the day it
-- is assigned; it can be changed until its due date. due_reminder_at records that the evening-before reminder ran.
create table homework.homework (
    id                uuid          primary key,
    tenant_id         uuid          not null references platform.tenant (id),
    academic_year_id  uuid          not null,
    subject_id        uuid          not null,
    title             varchar(200)  not null,
    instructions      varchar(5000) not null default '',
    assigned_on       date          not null,
    due_on            date          not null,
    online_submission boolean       not null,
    created_by_id     uuid,
    created_by_name   varchar(200),
    due_reminder_at   timestamptz,
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    version           bigint        not null default 0,
    constraint ck_homework_dates check (due_on >= assigned_on),
    constraint uq_homework_tenant_id unique (tenant_id, id),
    constraint fk_homework_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_homework_subject foreign key (tenant_id, subject_id) references academics.subject (tenant_id, id),
    constraint fk_homework_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_homework_due on homework.homework (tenant_id, due_on);
create index ix_homework_year on homework.homework (academic_year_id);
create index ix_homework_subject on homework.homework (subject_id);
create index ix_homework_created_by on homework.homework (created_by_id);

create table homework.homework_section (
    id          uuid        primary key,
    tenant_id   uuid        not null references platform.tenant (id),
    homework_id uuid        not null,
    section_id  uuid        not null,
    created_at  timestamptz not null,
    constraint uq_homework_section unique (homework_id, section_id),
    constraint fk_homework_section_homework foreign key (tenant_id, homework_id)
        references homework.homework (tenant_id, id) on delete cascade,
    constraint fk_homework_section_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id) on delete cascade
);
create index ix_homework_section_section on homework.homework_section (tenant_id, section_id);

-- A student's answer: text and/or files (in files.stored_file). It can be sent again until a teacher reviews it.
create table homework.submission (
    id               uuid          primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    homework_id      uuid          not null,
    student_id       uuid          not null,
    body             varchar(5000),
    submitted_at     timestamptz   not null,
    late             boolean       not null,
    attempts         integer       not null default 1,
    status           varchar(12)   not null,
    grade            varchar(10),
    remark           varchar(500),
    submitted_by_id  uuid,
    reviewed_by_id   uuid,
    reviewed_by_name varchar(200),
    reviewed_at      timestamptz,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint ck_submission_status check (status in ('SUBMITTED', 'REVIEWED', 'NEEDS_REDO')),
    constraint ck_submission_attempts check (attempts >= 1),
    constraint ck_submission_reviewed check (status = 'SUBMITTED' or reviewed_at is not null),
    constraint uq_submission unique (homework_id, student_id),
    constraint uq_submission_tenant_id unique (tenant_id, id),
    constraint fk_submission_homework foreign key (tenant_id, homework_id)
        references homework.homework (tenant_id, id),
    constraint fk_submission_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_submission_submitted_by foreign key (tenant_id, submitted_by_id)
        references identity.user_account (tenant_id, id) on delete set null (submitted_by_id),
    constraint fk_submission_reviewed_by foreign key (tenant_id, reviewed_by_id)
        references identity.user_account (tenant_id, id) on delete set null (reviewed_by_id)
);
create index ix_submission_student on homework.submission (tenant_id, student_id);
create index ix_submission_submitted_by on homework.submission (submitted_by_id);
create index ix_submission_reviewed_by on homework.submission (reviewed_by_id);

-- Per-school homework settings. Reminders to parents are off until the school switches them on.
create table homework.settings (
    id                uuid        primary key,
    tenant_id         uuid        not null references platform.tenant (id),
    reminders_enabled boolean     not null default false,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    version           bigint      not null default 0,
    constraint uq_homework_settings_tenant unique (tenant_id)
);

-- The reminder job runs without a school selected, so row-level security hides all homework from it. This function
-- tells it only which schools have homework due on the day with the evening-before reminder still to send; it then
-- works as each school in turn.
create function homework.tenants_with_due_reminders(p_due_on date)
    returns table (tenant_id uuid)
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select distinct h.tenant_id
    from homework.homework h
    join homework.settings s on s.tenant_id = h.tenant_id
    where s.reminders_enabled and h.online_submission and h.due_on = p_due_on and h.due_reminder_at is null
$$;

-- ---------------------------------------------------------------- row-level security

alter table files.stored_file enable row level security;
create policy tenant_isolation on files.stored_file
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table timetable.settings enable row level security;
create policy tenant_isolation on timetable.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table timetable.period enable row level security;
create policy tenant_isolation on timetable.period
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table timetable.teacher_assignment enable row level security;
create policy tenant_isolation on timetable.teacher_assignment
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table timetable.slot enable row level security;
create policy tenant_isolation on timetable.slot
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table timetable.teacher_absence enable row level security;
create policy tenant_isolation on timetable.teacher_absence
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table timetable.substitution enable row level security;
create policy tenant_isolation on timetable.substitution
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table homework.homework enable row level security;
create policy tenant_isolation on homework.homework
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table homework.homework_section enable row level security;
create policy tenant_isolation on homework.homework_section
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table homework.submission enable row level security;
create policy tenant_isolation on homework.submission
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table homework.settings enable row level security;
create policy tenant_isolation on homework.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema files, timetable, homework to ${appRole};
grant select, insert, update, delete on files.stored_file to ${appRole};
grant select, insert, update, delete on timetable.settings, timetable.period, timetable.teacher_assignment,
    timetable.slot, timetable.teacher_absence, timetable.substitution to ${appRole};
grant select, insert, update, delete on homework.homework, homework.homework_section, homework.submission,
    homework.settings to ${appRole};
revoke all on function homework.tenants_with_due_reminders(date) from public;
grant execute on function homework.tenants_with_due_reminders(date) to ${appRole};

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.
update identity.role
set permissions = array_append(permissions, 'timetable.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER', 'ACCOUNTANT', 'FRONT_OFFICE')
  and not ('timetable.read' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'timetable.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('timetable.manage' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'homework.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER')
  and not ('homework.manage' = any (permissions));
