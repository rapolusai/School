-- Phase 2, exams and report cards: grading schemes with their grade bands, the scheme each class uses per academic
-- year, terms and exams with their weightage, papers (subject, maximum marks, date sheet slot), mark sheets with one
-- row per student, co-scholastic grades and class teacher remarks per term, and the NEP 2020 holistic progress card.
-- Every table carries tenant_id and the same tenant_isolation policy as V2/V3, and every link between school tables is
-- a composite (tenant_id, id) foreign key, so the database itself refuses a link between two schools.

create schema if not exists exams;

-- ---------------------------------------------------------------- grading schemes

-- A grading scheme turns a percentage into a grade. The built-in schemes (CBSE, ICSE, State) carry a code so they are
-- added only once; schools may edit or delete them like their own. pass_percent applies to every subject and
-- overall_pass_percent to the total.
create table exams.grading_scheme (
    id                   uuid         primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    code                 varchar(20),
    name                 varchar(100) not null,
    kind                 varchar(16)  not null,
    description          varchar(500),
    pass_percent         numeric(5, 2) not null default 33,
    overall_pass_percent numeric(5, 2) not null default 33,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint ck_grading_scheme_kind check (kind in ('SCHOLASTIC', 'CO_SCHOLASTIC')),
    constraint ck_grading_scheme_pass check (pass_percent between 0 and 100 and overall_pass_percent between 0 and 100),
    constraint uq_grading_scheme_tenant_id unique (tenant_id, id),
    constraint uq_grading_scheme_name unique (tenant_id, name),
    constraint uq_grading_scheme_code unique (tenant_id, code)
);

-- The bands of a scheme: a percentage at or above min_percent (and below the next band) gets the grade. Every scheme
-- has a band starting at 0, so every percentage has a grade.
create table exams.grade_band (
    id          uuid         primary key,
    tenant_id   uuid         not null references platform.tenant (id),
    scheme_id   uuid         not null,
    position    integer      not null,
    min_percent numeric(5, 2) not null,
    grade       varchar(10)  not null,
    grade_point numeric(4, 1),
    remark      varchar(100),
    created_at  timestamptz  not null,
    constraint ck_grade_band_min check (min_percent between 0 and 100),
    constraint ck_grade_band_point check (grade_point is null or grade_point between 0 and 10),
    constraint uq_grade_band_tenant_id unique (tenant_id, id),
    constraint uq_grade_band_grade unique (tenant_id, scheme_id, grade),
    constraint uq_grade_band_min unique (tenant_id, scheme_id, min_percent),
    constraint fk_grade_band_scheme foreign key (tenant_id, scheme_id)
        references exams.grading_scheme (tenant_id, id) on delete cascade
);
create index ix_grade_band_scheme on exams.grade_band (tenant_id, scheme_id);

-- Per-school exam settings. A school without a row has ranks off and the holistic progress card up to Class 5.
create table exams.settings (
    id                     uuid        primary key,
    tenant_id              uuid        not null references platform.tenant (id),
    ranks_enabled          boolean     not null default false,
    hpc_up_to_level        integer     not null default 5,
    co_scholastic_scheme_id uuid,
    created_at             timestamptz not null,
    updated_at             timestamptz not null,
    version                bigint      not null default 0,
    constraint uq_exams_settings_tenant unique (tenant_id),
    constraint ck_exams_settings_hpc check (hpc_up_to_level between 0 and 12),
    constraint fk_exams_settings_co_scheme foreign key (tenant_id, co_scholastic_scheme_id)
        references exams.grading_scheme (tenant_id, id) on delete set null (co_scholastic_scheme_id)
);

-- The scheme a class uses in an academic year. A class without a row uses the default for the school's board.
create table exams.class_scheme (
    id               uuid        primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    academic_year_id uuid        not null,
    class_id         uuid        not null,
    scheme_id        uuid        not null,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    constraint uq_class_scheme_tenant_id unique (tenant_id, id),
    constraint uq_class_scheme_class unique (tenant_id, academic_year_id, class_id),
    constraint fk_class_scheme_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_class_scheme_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id) on delete cascade,
    constraint fk_class_scheme_scheme foreign key (tenant_id, scheme_id)
        references exams.grading_scheme (tenant_id, id)
);
create index ix_class_scheme_scheme on exams.class_scheme (tenant_id, scheme_id);

-- ---------------------------------------------------------------- terms and exams

-- A term of an academic year (Term 1, Term 2). weight is its share of the final result; a year's terms add up to 100.
create table exams.term (
    id               uuid         primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    academic_year_id uuid         not null,
    name             varchar(60)  not null,
    starts_on        date         not null,
    ends_on          date         not null,
    weight           numeric(5, 2) not null default 0,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint ck_term_dates check (ends_on >= starts_on),
    constraint ck_term_weight check (weight between 0 and 100),
    constraint uq_term_tenant_id unique (tenant_id, id),
    constraint uq_term_name unique (tenant_id, academic_year_id, name),
    constraint fk_term_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id)
);
create index ix_term_year on exams.term (tenant_id, academic_year_id);

-- An exam in a term (Periodic Test 1, Half-yearly). weight is its share of the term result; a term's exams add up to
-- 100. instructions are printed on hall tickets.
create table exams.exam (
    id               uuid          primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    academic_year_id uuid          not null,
    term_id          uuid          not null,
    name             varchar(100)  not null,
    weight           numeric(5, 2) not null default 0,
    instructions     varchar(2000),
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint ck_exam_weight check (weight between 0 and 100),
    constraint uq_exam_tenant_id unique (tenant_id, id),
    constraint uq_exam_name unique (tenant_id, term_id, name),
    constraint fk_exam_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_exam_term foreign key (tenant_id, term_id)
        references exams.term (tenant_id, id)
);
create index ix_exam_term on exams.exam (tenant_id, term_id);
create index ix_exam_year on exams.exam (tenant_id, academic_year_id);

-- A class an exam covers, with when its date sheet and its results were published (both hidden from families until
-- then).
create table exams.exam_class (
    id                        uuid         primary key,
    tenant_id                 uuid         not null references platform.tenant (id),
    exam_id                   uuid         not null,
    class_id                  uuid         not null,
    datesheet_published_at    timestamptz,
    datesheet_published_by    varchar(200),
    results_published_at      timestamptz,
    results_published_by      varchar(200),
    created_at                timestamptz  not null,
    updated_at                timestamptz  not null,
    version                   bigint       not null default 0,
    constraint uq_exam_class_tenant_id unique (tenant_id, id),
    constraint uq_exam_class unique (tenant_id, exam_id, class_id),
    constraint fk_exam_class_exam foreign key (tenant_id, exam_id)
        references exams.exam (tenant_id, id) on delete cascade,
    constraint fk_exam_class_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id)
);
create index ix_exam_class_class on exams.exam_class (tenant_id, class_id);

-- One subject paper of an exam for a class: its maximum marks (theory, practical, internal assessment) and its slot on
-- the date sheet. calendar_entry_id is the school calendar entry made when the date sheet was published (the
-- communication module owns it, so it is not a foreign key).
create table exams.paper (
    id                uuid          primary key,
    tenant_id         uuid          not null references platform.tenant (id),
    exam_class_id     uuid          not null,
    subject_id        uuid          not null,
    max_theory        numeric(6, 2) not null default 0,
    max_practical     numeric(6, 2) not null default 0,
    max_internal      numeric(6, 2) not null default 0,
    paper_date        date,
    start_time        time,
    duration_minutes  integer,
    room              varchar(60),
    calendar_entry_id uuid,
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    version           bigint        not null default 0,
    constraint ck_paper_max check (max_theory >= 0 and max_practical >= 0 and max_internal >= 0
        and max_theory + max_practical + max_internal > 0 and max_theory + max_practical + max_internal <= 1000),
    constraint ck_paper_duration check (duration_minutes is null or duration_minutes between 15 and 600),
    constraint uq_paper_tenant_id unique (tenant_id, id),
    constraint uq_paper_subject unique (tenant_id, exam_class_id, subject_id),
    constraint fk_paper_exam_class foreign key (tenant_id, exam_class_id)
        references exams.exam_class (tenant_id, id) on delete cascade,
    constraint fk_paper_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id)
);
create index ix_paper_subject on exams.paper (tenant_id, subject_id);
create index ix_paper_date on exams.paper (tenant_id, paper_date);

-- ---------------------------------------------------------------- marks

-- The marks of one paper for one section. DRAFT while teachers enter them; LOCKED once submitted. Only School Admin
-- and Principal unlock a sheet, with a reason. A paper with sheets cannot be deleted (no cascade from paper).
create table exams.mark_sheet (
    id                uuid         primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    paper_id          uuid         not null,
    section_id        uuid         not null,
    status            varchar(10)  not null default 'DRAFT',
    updated_by_name   varchar(200),
    submitted_at      timestamptz,
    submitted_by_name varchar(200),
    unlocked_at       timestamptz,
    unlocked_by_name  varchar(200),
    unlock_reason     varchar(500),
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint ck_mark_sheet_status check (status in ('DRAFT', 'LOCKED')),
    constraint uq_mark_sheet_tenant_id unique (tenant_id, id),
    constraint uq_mark_sheet unique (tenant_id, paper_id, section_id),
    constraint fk_mark_sheet_paper foreign key (tenant_id, paper_id)
        references exams.paper (tenant_id, id),
    constraint fk_mark_sheet_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id) on delete cascade
);
create index ix_mark_sheet_section on exams.mark_sheet (tenant_id, section_id);

-- One student's marks on a sheet. code: AB absent (counts as zero), ML medical and EX exempt (left out of totals).
create table exams.mark (
    id         uuid          primary key,
    tenant_id  uuid          not null references platform.tenant (id),
    sheet_id   uuid          not null,
    student_id uuid          not null,
    theory     numeric(6, 2),
    practical  numeric(6, 2),
    internal   numeric(6, 2),
    code       varchar(2),
    created_at timestamptz   not null,
    updated_at timestamptz   not null,
    constraint ck_mark_code check (code is null or code in ('AB', 'ML', 'EX')),
    constraint ck_mark_values check ((theory is null or theory >= 0) and (practical is null or practical >= 0)
        and (internal is null or internal >= 0)),
    constraint uq_mark_tenant_id unique (tenant_id, id),
    constraint uq_mark_student unique (tenant_id, sheet_id, student_id),
    constraint fk_mark_sheet foreign key (tenant_id, sheet_id)
        references exams.mark_sheet (tenant_id, id) on delete cascade,
    constraint fk_mark_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id)
);
create index ix_mark_student on exams.mark (tenant_id, student_id);

-- ---------------------------------------------------------------- per term, per student

-- Co-scholastic grades (work education, art education, health and physical education, discipline) and the class
-- teacher's remark for a student in a term.
create table exams.term_record (
    id              uuid         primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    term_id         uuid         not null,
    student_id      uuid         not null,
    work_education  varchar(10),
    art_education   varchar(10),
    health_physical varchar(10),
    discipline      varchar(10),
    remark          varchar(500),
    updated_by_name varchar(200),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint uq_term_record_tenant_id unique (tenant_id, id),
    constraint uq_term_record_student unique (tenant_id, term_id, student_id),
    constraint fk_term_record_term foreign key (tenant_id, term_id)
        references exams.term (tenant_id, id),
    constraint fk_term_record_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id)
);
create index ix_term_record_student on exams.term_record (tenant_id, student_id);

-- The NEP 2020 holistic progress card for foundational and preparatory classes: a level per domain and the teacher's,
-- the student's own and the parent's reflections.
create table exams.hpc_entry (
    id                 uuid          primary key,
    tenant_id          uuid          not null references platform.tenant (id),
    term_id            uuid          not null,
    student_id         uuid          not null,
    physical           varchar(12),
    socio_emotional    varchar(12),
    cognitive          varchar(12),
    language           varchar(12),
    numeracy           varchar(12),
    aesthetic          varchar(12),
    teacher_reflection varchar(1000),
    self_reflection    varchar(1000),
    parent_reflection  varchar(1000),
    updated_by_name    varchar(200),
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    version            bigint        not null default 0,
    constraint ck_hpc_entry_levels check (
        (physical is null or physical in ('BEGINNER', 'PROGRESSING', 'PROFICIENT', 'ADVANCED'))
        and (socio_emotional is null or socio_emotional in ('BEGINNER', 'PROGRESSING', 'PROFICIENT', 'ADVANCED'))
        and (cognitive is null or cognitive in ('BEGINNER', 'PROGRESSING', 'PROFICIENT', 'ADVANCED'))
        and (language is null or language in ('BEGINNER', 'PROGRESSING', 'PROFICIENT', 'ADVANCED'))
        and (numeracy is null or numeracy in ('BEGINNER', 'PROGRESSING', 'PROFICIENT', 'ADVANCED'))
        and (aesthetic is null or aesthetic in ('BEGINNER', 'PROGRESSING', 'PROFICIENT', 'ADVANCED'))),
    constraint uq_hpc_entry_tenant_id unique (tenant_id, id),
    constraint uq_hpc_entry_student unique (tenant_id, term_id, student_id),
    constraint fk_hpc_entry_term foreign key (tenant_id, term_id)
        references exams.term (tenant_id, id),
    constraint fk_hpc_entry_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id)
);
create index ix_hpc_entry_student on exams.hpc_entry (tenant_id, student_id);

-- ---------------------------------------------------------------- row-level security

alter table exams.grading_scheme enable row level security;
create policy tenant_isolation on exams.grading_scheme
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.grade_band enable row level security;
create policy tenant_isolation on exams.grade_band
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.settings enable row level security;
create policy tenant_isolation on exams.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.class_scheme enable row level security;
create policy tenant_isolation on exams.class_scheme
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.term enable row level security;
create policy tenant_isolation on exams.term
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.exam enable row level security;
create policy tenant_isolation on exams.exam
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.exam_class enable row level security;
create policy tenant_isolation on exams.exam_class
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.paper enable row level security;
create policy tenant_isolation on exams.paper
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.mark_sheet enable row level security;
create policy tenant_isolation on exams.mark_sheet
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.mark enable row level security;
create policy tenant_isolation on exams.mark
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.term_record enable row level security;
create policy tenant_isolation on exams.term_record
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table exams.hpc_entry enable row level security;
create policy tenant_isolation on exams.hpc_entry
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- built-in grading schemes

-- Adds the built-in schemes to a school once (by code; a scheme the school renamed is left alone): CBSE classes 1 to 8
-- (A1 91-100 down to E, 32 and below), CBSE classes 9 and 10 (the nine-point scale, A1 to E2), CBSE co-scholastic
-- (A, B, C), ICSE (percentage with grades) and a generic State board scale. Pass mark 33% per subject and overall.
-- Runs as the caller: at sign-up that is the runtime role inside the new school, so row-level security checks every
-- insert; in this migration it is the owner. Returns the number of schemes added.
create function exams.seed_default_schemes(p_tenant uuid)
    returns integer
    language plpgsql
    set search_path = pg_catalog
as $$
declare
    s       record;
    v_id    uuid;
    v_added integer := 0;
begin
    for s in
        select * from (values
            ('CBSE_1_8', 'CBSE classes 1 to 8', 'SCHOLASTIC',
             'CBSE eight-grade scale for classes 1 to 8: A1 91-100 down to E, 32 and below.'),
            ('CBSE_9_10', 'CBSE classes 9 and 10', 'SCHOLASTIC',
             'CBSE nine-point scale for classes 9 and 10: A1 91-100 down to E2, 20 and below.'),
            ('CBSE_CO', 'CBSE co-scholastic', 'CO_SCHOLASTIC',
             'Three-point scale for work education, art education, health and physical education and discipline.'),
            ('ICSE', 'ICSE percentage', 'SCHOLASTIC',
             'Percentage with grades, as used by ICSE schools.'),
            ('STATE', 'State board', 'SCHOLASTIC',
             'A general State board scale.')
        ) as t (code, name, kind, description)
    loop
        if exists (select 1 from exams.grading_scheme g
                   where g.tenant_id = p_tenant and (g.code = s.code or g.name = s.name)) then
            continue;
        end if;
        v_id := gen_random_uuid();
        insert into exams.grading_scheme (id, tenant_id, code, name, kind, description, pass_percent,
                                          overall_pass_percent, created_at, updated_at)
        values (v_id, p_tenant, s.code, s.name, s.kind, s.description, 33, 33, now(), now());
        insert into exams.grade_band (id, tenant_id, scheme_id, position, min_percent, grade, grade_point, remark,
                                      created_at)
        select gen_random_uuid(), p_tenant, v_id, b.position, b.min_percent, b.grade, b.grade_point, b.remark, now()
        from (values
            ('CBSE_1_8', 1, 91, 'A1', 10, 'Outstanding'),
            ('CBSE_1_8', 2, 81, 'A2', 9, 'Excellent'),
            ('CBSE_1_8', 3, 71, 'B1', 8, 'Very good'),
            ('CBSE_1_8', 4, 61, 'B2', 7, 'Good'),
            ('CBSE_1_8', 5, 51, 'C1', 6, 'Fair'),
            ('CBSE_1_8', 6, 41, 'C2', 5, 'Average'),
            ('CBSE_1_8', 7, 33, 'D', 4, 'Pass'),
            ('CBSE_1_8', 8, 0, 'E', null, 'Needs improvement'),
            ('CBSE_9_10', 1, 91, 'A1', 10, 'Outstanding'),
            ('CBSE_9_10', 2, 81, 'A2', 9, 'Excellent'),
            ('CBSE_9_10', 3, 71, 'B1', 8, 'Very good'),
            ('CBSE_9_10', 4, 61, 'B2', 7, 'Good'),
            ('CBSE_9_10', 5, 51, 'C1', 6, 'Fair'),
            ('CBSE_9_10', 6, 41, 'C2', 5, 'Average'),
            ('CBSE_9_10', 7, 33, 'D', 4, 'Pass'),
            ('CBSE_9_10', 8, 21, 'E1', null, 'Needs improvement'),
            ('CBSE_9_10', 9, 0, 'E2', null, 'Needs improvement'),
            ('CBSE_CO', 1, 67, 'A', null, 'Outstanding'),
            ('CBSE_CO', 2, 34, 'B', null, 'Very good'),
            ('CBSE_CO', 3, 0, 'C', null, 'Fair'),
            ('ICSE', 1, 90, 'A+', null, 'Excellent'),
            ('ICSE', 2, 80, 'A', null, 'Very good'),
            ('ICSE', 3, 70, 'B+', null, 'Good'),
            ('ICSE', 4, 60, 'B', null, 'Above average'),
            ('ICSE', 5, 50, 'C', null, 'Average'),
            ('ICSE', 6, 40, 'D', null, 'Pass'),
            ('ICSE', 7, 33, 'E', null, 'Pass'),
            ('ICSE', 8, 0, 'F', null, 'Needs improvement'),
            ('STATE', 1, 90, 'A+', null, 'Outstanding'),
            ('STATE', 2, 75, 'A', null, 'Excellent'),
            ('STATE', 3, 60, 'B', null, 'Very good'),
            ('STATE', 4, 45, 'C', null, 'Good'),
            ('STATE', 5, 33, 'D', null, 'Pass'),
            ('STATE', 6, 0, 'E', null, 'Needs improvement')
        ) as b (code, position, min_percent, grade, grade_point, remark)
        where b.code = s.code;
        v_added := v_added + 1;
    end loop;
    return v_added;
end;
$$;

-- Every school that already exists gets the built-in schemes now; new schools get them at sign-up.
select exams.seed_default_schemes(t.id) from platform.tenant t;

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema exams to ${appRole};
grant select, insert, update, delete on exams.grading_scheme, exams.grade_band, exams.settings, exams.class_scheme,
    exams.term, exams.exam, exams.exam_class, exams.paper, exams.mark_sheet, exams.mark, exams.term_record,
    exams.hpc_entry to ${appRole};
revoke all on function exams.seed_default_schemes(uuid) from public;
grant execute on function exams.seed_default_schemes(uuid) to ${appRole};

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- exams.read: date sheets, results, report cards and result analysis (School Admin, Principal, Teacher).
-- exams.setup: grading schemes, terms, exams, papers and publishing date sheets (School Admin, Principal).
-- exams.publish: publishing results, unlocking submitted marks and entering marks for every section (School Admin,
-- Principal). New schools get them from RoleCatalog; existing schools' built-in roles get them here once. Idempotent.
update identity.role
set permissions = array_append(permissions, 'exams.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER')
  and not ('exams.read' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'exams.setup')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('exams.setup' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'exams.publish')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('exams.publish' = any (permissions));
