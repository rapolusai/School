-- Phase 2, lessons and e-learning: the syllabus of each class and subject per academic year (units and topics in
-- order, with planned periods and a target month), teachers' lesson plans with approval (taught plans drive syllabus
-- coverage), study material for students and parents, a school-wide question bank, and online tests taken in the
-- browser with their attempts and answers. Online test scores are kept apart from exam marks and never feed report
-- cards. Every table carries tenant_id and the same tenant_isolation policy as V2/V3, and every link between school
-- tables is a composite (tenant_id, id) foreign key, so the database itself refuses a link between two schools.

create schema if not exists lessons;

-- Per-school settings. A school without a row does not message families about new online tests.
create table lessons.settings (
    id                         uuid        primary key,
    tenant_id                  uuid        not null references platform.tenant (id),
    test_notifications_enabled boolean     not null default false,
    created_at                 timestamptz not null,
    updated_at                 timestamptz not null,
    version                    bigint      not null default 0,
    constraint uq_lessons_settings_tenant unique (tenant_id),
    constraint uq_lessons_settings_tenant_id unique (tenant_id, id)
);

-- ---------------------------------------------------------------- syllabus

-- The syllabus of one subject for one class in an academic year.
create table lessons.syllabus (
    id               uuid         primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    academic_year_id uuid         not null,
    class_id         uuid         not null,
    subject_id       uuid         not null,
    updated_by_name  varchar(200),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_syllabus_tenant_id unique (tenant_id, id),
    constraint uq_syllabus_subject unique (tenant_id, academic_year_id, class_id, subject_id),
    constraint fk_syllabus_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_syllabus_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id),
    constraint fk_syllabus_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id)
);
create index ix_syllabus_class on lessons.syllabus (tenant_id, class_id);
create index ix_syllabus_subject on lessons.syllabus (tenant_id, subject_id);

-- A unit (chapter) of a syllabus, in order.
create table lessons.syllabus_unit (
    id          uuid         primary key,
    tenant_id   uuid         not null references platform.tenant (id),
    syllabus_id uuid         not null,
    position    integer      not null,
    title       varchar(200) not null,
    created_at  timestamptz  not null,
    constraint uq_syllabus_unit_tenant_id unique (tenant_id, id),
    constraint fk_syllabus_unit_syllabus foreign key (tenant_id, syllabus_id)
        references lessons.syllabus (tenant_id, id) on delete cascade
);
create index ix_syllabus_unit_syllabus on lessons.syllabus_unit (tenant_id, syllabus_id);

-- A topic of a unit, in order: the periods planned for it and the month it should be taught by (the first day of
-- that month). Coverage counts the topics taught against these.
create table lessons.syllabus_topic (
    id              uuid         primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    syllabus_id     uuid         not null,
    unit_id         uuid         not null,
    position        integer      not null,
    title           varchar(200) not null,
    planned_periods integer      not null default 1,
    target_month    date,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    constraint ck_syllabus_topic_periods check (planned_periods between 1 and 200),
    constraint ck_syllabus_topic_month check (target_month is null or extract(day from target_month) = 1),
    constraint uq_syllabus_topic_tenant_id unique (tenant_id, id),
    constraint fk_syllabus_topic_syllabus foreign key (tenant_id, syllabus_id)
        references lessons.syllabus (tenant_id, id) on delete cascade,
    constraint fk_syllabus_topic_unit foreign key (tenant_id, unit_id)
        references lessons.syllabus_unit (tenant_id, id) on delete cascade
);
create index ix_syllabus_topic_syllabus on lessons.syllabus_topic (tenant_id, syllabus_id);
create index ix_syllabus_topic_unit on lessons.syllabus_topic (tenant_id, unit_id);

-- ---------------------------------------------------------------- lesson plans

-- A teacher's plan for a section and subject in a week (Monday), optionally on a day of it, usually for a syllabus
-- topic. DRAFT and RETURNED plans are edited by their teacher and submitted; approvers approve or return them with a
-- comment; an approved plan is marked TAUGHT on the day it was taught, which counts its topic as covered.
create table lessons.lesson_plan (
    id               uuid          primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    academic_year_id uuid          not null,
    section_id       uuid          not null,
    subject_id       uuid          not null,
    topic_id         uuid,
    teacher_id       uuid          not null,
    teacher_name     varchar(200)  not null,
    title            varchar(200)  not null,
    week_start       date          not null,
    plan_date        date,
    periods          integer       not null default 1,
    objectives       varchar(2000),
    teaching_method  varchar(2000),
    resources        varchar(2000),
    resource_links   varchar(2000),
    homework_idea    varchar(1000),
    assessment_note  varchar(1000),
    status           varchar(12)   not null default 'DRAFT',
    submitted_at     timestamptz,
    reviewed_at      timestamptz,
    reviewed_by_id   uuid,
    reviewed_by_name varchar(200),
    review_comment   varchar(1000),
    taught_on        date,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint ck_lesson_plan_status check (status in ('DRAFT', 'SUBMITTED', 'APPROVED', 'RETURNED', 'TAUGHT')),
    constraint ck_lesson_plan_week check (extract(isodow from week_start) = 1),
    constraint ck_lesson_plan_date check (plan_date is null or (plan_date >= week_start and plan_date < week_start + 7)),
    constraint ck_lesson_plan_periods check (periods between 1 and 20),
    constraint ck_lesson_plan_taught check ((status = 'TAUGHT') = (taught_on is not null)),
    constraint uq_lesson_plan_tenant_id unique (tenant_id, id),
    constraint fk_lesson_plan_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_lesson_plan_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id),
    constraint fk_lesson_plan_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id),
    constraint fk_lesson_plan_topic foreign key (tenant_id, topic_id)
        references lessons.syllabus_topic (tenant_id, id),
    constraint fk_lesson_plan_teacher foreign key (tenant_id, teacher_id)
        references identity.user_account (tenant_id, id)
);
create index ix_lesson_plan_teacher on lessons.lesson_plan (tenant_id, teacher_id, week_start);
create index ix_lesson_plan_section on lessons.lesson_plan (tenant_id, section_id, subject_id);
create index ix_lesson_plan_status on lessons.lesson_plan (tenant_id, status);
create index ix_lesson_plan_topic on lessons.lesson_plan (tenant_id, topic_id);

-- ---------------------------------------------------------------- study material

-- Notes, files (files module, owner lessons/material) and a link for a class (every section, or one), subject and
-- optionally a topic, shown to the class's students and their parents from publish_on.
create table lessons.material (
    id               uuid          primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    academic_year_id uuid          not null,
    class_id         uuid          not null,
    section_id       uuid,
    subject_id       uuid          not null,
    topic_id         uuid,
    title            varchar(200)  not null,
    notes            varchar(5000),
    link_url         varchar(1000),
    publish_on       date          not null,
    created_by_id    uuid,
    created_by_name  varchar(200)  not null,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint uq_material_tenant_id unique (tenant_id, id),
    constraint fk_material_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_material_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id),
    constraint fk_material_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id),
    constraint fk_material_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id),
    constraint fk_material_topic foreign key (tenant_id, topic_id)
        references lessons.syllabus_topic (tenant_id, id) on delete set null (topic_id),
    constraint fk_material_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_material_class on lessons.material (tenant_id, academic_year_id, class_id);
create index ix_material_section on lessons.material (tenant_id, section_id);
create index ix_material_topic on lessons.material (tenant_id, topic_id);
create index ix_material_created_by on lessons.material (tenant_id, created_by_id);

-- ---------------------------------------------------------------- question bank

-- A question for a class and subject, optionally of a syllabus topic. answer_text: TRUE or FALSE for true/false, the
-- accepted answers one per line for fill in the blank, and the model answer for a short answer. Options (MCQ) are
-- in question_option. shared questions are visible to every teacher of the school.
create table lessons.question (
    id              uuid          primary key,
    tenant_id       uuid          not null references platform.tenant (id),
    class_id        uuid          not null,
    subject_id      uuid          not null,
    topic_id        uuid,
    type            varchar(16)   not null,
    difficulty      varchar(8)    not null,
    bloom_level     varchar(12),
    text            varchar(2000) not null,
    answer_text     varchar(1000),
    explanation     varchar(2000),
    marks           numeric(5, 2) not null default 1,
    shared          boolean       not null default true,
    created_by_id   uuid,
    created_by_name varchar(200)  not null,
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null,
    version         bigint        not null default 0,
    constraint ck_question_type check (type in ('MCQ_SINGLE', 'MCQ_MULTI', 'TRUE_FALSE', 'SHORT_ANSWER',
                                                'FILL_BLANK')),
    constraint ck_question_difficulty check (difficulty in ('EASY', 'MEDIUM', 'HARD')),
    constraint ck_question_bloom check (bloom_level is null or bloom_level in ('REMEMBER', 'UNDERSTAND', 'APPLY',
                                                                              'ANALYSE', 'EVALUATE', 'CREATE')),
    constraint ck_question_marks check (marks > 0 and marks <= 100),
    constraint uq_question_tenant_id unique (tenant_id, id),
    constraint fk_question_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id),
    constraint fk_question_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id),
    constraint fk_question_topic foreign key (tenant_id, topic_id)
        references lessons.syllabus_topic (tenant_id, id) on delete set null (topic_id),
    constraint fk_question_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_question_class_subject on lessons.question (tenant_id, class_id, subject_id);
create index ix_question_topic on lessons.question (tenant_id, topic_id);
create index ix_question_created_by on lessons.question (tenant_id, created_by_id);

-- An option of a multiple choice question, in order; correct marks the right one(s).
create table lessons.question_option (
    id          uuid          primary key,
    tenant_id   uuid          not null references platform.tenant (id),
    question_id uuid          not null,
    position    integer       not null,
    text        varchar(500)  not null,
    correct     boolean       not null default false,
    created_at  timestamptz   not null,
    constraint uq_question_option_tenant_id unique (tenant_id, id),
    constraint fk_question_option_question foreign key (tenant_id, question_id)
        references lessons.question (tenant_id, id) on delete cascade
);
create index ix_question_option_question on lessons.question_option (tenant_id, question_id);

-- ---------------------------------------------------------------- online tests

-- An online test of a class and subject for one or more of its sections, open between opens_at and closes_at; each
-- attempt lasts duration_minutes (and ends at closes_at at the latest). Students see their score once
-- results_released_at is set, and the correct answers too when show_answers is WITH_RESULTS.
create table lessons.online_test (
    id                  uuid          primary key,
    tenant_id           uuid          not null references platform.tenant (id),
    academic_year_id    uuid          not null,
    class_id            uuid          not null,
    subject_id          uuid          not null,
    title               varchar(200)  not null,
    instructions        varchar(2000),
    opens_at            timestamptz   not null,
    closes_at           timestamptz   not null,
    duration_minutes    integer       not null,
    attempts_allowed    integer       not null default 1,
    shuffle_questions   boolean       not null default false,
    shuffle_options     boolean       not null default false,
    show_answers        varchar(16)   not null default 'WITH_RESULTS',
    status              varchar(10)   not null default 'DRAFT',
    published_at        timestamptz,
    results_released_at timestamptz,
    released_by_name    varchar(200),
    created_by_id       uuid,
    created_by_name     varchar(200)  not null,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    version             bigint        not null default 0,
    constraint ck_online_test_window check (closes_at > opens_at),
    constraint ck_online_test_duration check (duration_minutes between 1 and 300),
    constraint ck_online_test_attempts check (attempts_allowed between 1 and 5),
    constraint ck_online_test_show check (show_answers in ('WITH_RESULTS', 'NEVER')),
    constraint ck_online_test_status check (status in ('DRAFT', 'PUBLISHED')),
    constraint uq_online_test_tenant_id unique (tenant_id, id),
    constraint fk_online_test_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_online_test_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id),
    constraint fk_online_test_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id),
    constraint fk_online_test_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_online_test_year on lessons.online_test (tenant_id, academic_year_id, class_id);
create index ix_online_test_created_by on lessons.online_test (tenant_id, created_by_id);

-- The sections a test is for.
create table lessons.test_section (
    id         uuid        primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    test_id    uuid        not null,
    section_id uuid        not null,
    created_at timestamptz not null,
    constraint uq_test_section_tenant_id unique (tenant_id, id),
    constraint uq_test_section unique (tenant_id, test_id, section_id),
    constraint fk_test_section_test foreign key (tenant_id, test_id)
        references lessons.online_test (tenant_id, id) on delete cascade,
    constraint fk_test_section_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id)
);
create index ix_test_section_section on lessons.test_section (tenant_id, section_id);

-- The bank questions of a test, in order, with the marks each carries in it. A question in a test cannot be deleted.
create table lessons.test_question (
    id          uuid          primary key,
    tenant_id   uuid          not null references platform.tenant (id),
    test_id     uuid          not null,
    question_id uuid          not null,
    position    integer       not null,
    marks       numeric(5, 2) not null,
    created_at  timestamptz   not null,
    constraint ck_test_question_marks check (marks > 0 and marks <= 100),
    constraint uq_test_question_tenant_id unique (tenant_id, id),
    constraint uq_test_question unique (tenant_id, test_id, question_id),
    constraint fk_test_question_test foreign key (tenant_id, test_id)
        references lessons.online_test (tenant_id, id) on delete cascade,
    constraint fk_test_question_question foreign key (tenant_id, question_id)
        references lessons.question (tenant_id, id)
);
create index ix_test_question_question on lessons.test_question (tenant_id, question_id);

-- A student's attempt at a test. deadline_at is the start plus the duration, or the test's close if sooner; answers
-- after it are refused and the attempt is submitted automatically. score counts objective answers and marked short
-- answers; pending_marking counts short answers still to be marked.
create table lessons.test_attempt (
    id              uuid          primary key,
    tenant_id       uuid          not null references platform.tenant (id),
    test_id         uuid          not null,
    student_id      uuid          not null,
    attempt_no      integer       not null,
    started_at      timestamptz   not null,
    deadline_at     timestamptz   not null,
    submitted_at    timestamptz,
    auto_submitted  boolean       not null default false,
    score           numeric(7, 2),
    max_score       numeric(7, 2) not null,
    pending_marking integer       not null default 0,
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null,
    version         bigint        not null default 0,
    constraint ck_test_attempt_no check (attempt_no between 1 and 5),
    constraint uq_test_attempt_tenant_id unique (tenant_id, id),
    constraint uq_test_attempt unique (tenant_id, test_id, student_id, attempt_no),
    constraint fk_test_attempt_test foreign key (tenant_id, test_id)
        references lessons.online_test (tenant_id, id),
    constraint fk_test_attempt_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id)
);
create index ix_test_attempt_student on lessons.test_attempt (tenant_id, student_id);

-- The answer to one question of an attempt, in the order the student saw them. option_order is the order the options
-- were shown in and selected_options the chosen ones (option ids, comma separated); response is the typed answer.
-- marks is null until marked (automatically for objective questions, by the teacher for short answers).
create table lessons.test_answer (
    id               uuid          primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    attempt_id       uuid          not null,
    test_question_id uuid          not null,
    position         integer       not null,
    option_order     varchar(400),
    selected_options varchar(400),
    response         varchar(1000),
    saved_at         timestamptz,
    marks            numeric(5, 2),
    marked_by_name   varchar(200),
    marked_at        timestamptz,
    feedback         varchar(500),
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    constraint uq_test_answer_tenant_id unique (tenant_id, id),
    constraint uq_test_answer unique (tenant_id, attempt_id, test_question_id),
    constraint fk_test_answer_attempt foreign key (tenant_id, attempt_id)
        references lessons.test_attempt (tenant_id, id) on delete cascade,
    constraint fk_test_answer_question foreign key (tenant_id, test_question_id)
        references lessons.test_question (tenant_id, id)
);
create index ix_test_answer_question on lessons.test_answer (tenant_id, test_question_id);

-- ---------------------------------------------------------------- row-level security

alter table lessons.settings enable row level security;
create policy tenant_isolation on lessons.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.syllabus enable row level security;
create policy tenant_isolation on lessons.syllabus
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.syllabus_unit enable row level security;
create policy tenant_isolation on lessons.syllabus_unit
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.syllabus_topic enable row level security;
create policy tenant_isolation on lessons.syllabus_topic
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.lesson_plan enable row level security;
create policy tenant_isolation on lessons.lesson_plan
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.material enable row level security;
create policy tenant_isolation on lessons.material
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.question enable row level security;
create policy tenant_isolation on lessons.question
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.question_option enable row level security;
create policy tenant_isolation on lessons.question_option
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.online_test enable row level security;
create policy tenant_isolation on lessons.online_test
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.test_section enable row level security;
create policy tenant_isolation on lessons.test_section
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.test_question enable row level security;
create policy tenant_isolation on lessons.test_question
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.test_attempt enable row level security;
create policy tenant_isolation on lessons.test_attempt
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table lessons.test_answer enable row level security;
create policy tenant_isolation on lessons.test_answer
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema lessons to ${appRole};
grant select, insert, update, delete on lessons.settings, lessons.syllabus, lessons.syllabus_unit,
    lessons.syllabus_topic, lessons.lesson_plan, lessons.material, lessons.question, lessons.question_option,
    lessons.online_test, lessons.test_section, lessons.test_question, lessons.test_attempt, lessons.test_answer
    to ${appRole};

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- lessons.read: lesson plans, the syllabus and its coverage, and study material (School Admin, Principal, Teacher).
-- lessons.manage: a teacher's own plans, material, question bank and online tests (School Admin, Teacher).
-- lessons.approve: approving or returning lesson plans, and every section and subject (School Admin, Principal).
-- New schools get them from RoleCatalog; existing schools' built-in roles get them here once. Idempotent.
update identity.role
set permissions = array_append(permissions, 'lessons.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER')
  and not ('lessons.read' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'lessons.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'TEACHER')
  and not ('lessons.manage' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'lessons.approve')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('lessons.approve' = any (permissions));
