-- Phase 3: AI assistance. Draft report-card remarks, an early warning list of students at risk, draft question
-- papers and a small writing helper. Every draft comes from an AssistProvider; in this phase the only provider is
-- the deterministic simulated one built from templates and the school's own data (nothing leaves the school). The
-- usage log keeps who asked for what, with a hash of the request and never its text.
--
-- Every table carries tenant_id, is protected by the same tenant_isolation policy as V2/V3, and links to other
-- school tables through composite (tenant_id, id) foreign keys.

create schema if not exists assist;

-- ---------------------------------------------------------------- settings (one row per school)

-- Assistance is off until the School Admin switches it on. The risk weights are the points each signal can add to
-- a student's score (0 to 100 in all); watch_threshold is the score from which a student is listed.
create table assist.settings (
    id                      uuid primary key,
    tenant_id               uuid        not null references platform.tenant (id),
    enabled                 boolean     not null default false,
    remarks_enabled         boolean     not null default true,
    early_warning_enabled   boolean     not null default true,
    papers_enabled          boolean     not null default true,
    writing_enabled         boolean     not null default true,
    monthly_limit           integer     not null default 500,
    language                varchar(2)  not null default 'en',
    weight_attendance_30    integer     not null default 25,
    weight_attendance_90    integer     not null default 15,
    weight_marks_trend      integer     not null default 25,
    weight_fee_dues         integer     not null default 10,
    weight_homework         integer     not null default 25,
    watch_threshold         integer     not null default 30,
    last_run_at             timestamptz,
    last_run_students       integer,
    last_run_listed         integer,
    created_at              timestamptz not null,
    updated_at              timestamptz not null,
    version                 bigint      not null default 0,
    constraint uq_assist_settings_tenant unique (tenant_id),
    constraint uq_assist_settings_tenant_id unique (tenant_id, id),
    constraint ck_assist_settings_language check (language in ('en', 'hi')),
    constraint ck_assist_settings_limit check (monthly_limit between 0 and 100000),
    constraint ck_assist_settings_weights check (
        weight_attendance_30 between 0 and 100 and weight_attendance_90 between 0 and 100
        and weight_marks_trend between 0 and 100 and weight_fee_dues between 0 and 100
        and weight_homework between 0 and 100 and watch_threshold between 1 and 100)
);

-- ---------------------------------------------------------------- usage log

-- One row per call to a provider: who, which feature, which provider, the token estimate and a SHA-256 of the
-- request (never the text). Monthly counts against the limit come from created_at.
create table assist.usage (
    id             uuid         primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    user_id        uuid         not null,
    user_name      varchar(200) not null,
    feature        varchar(20)  not null,
    provider       varchar(60)  not null,
    token_estimate integer      not null,
    request_hash   varchar(64)  not null,
    created_at     timestamptz  not null,
    constraint uq_assist_usage_tenant_id unique (tenant_id, id),
    constraint ck_assist_usage_feature check (feature in ('REMARK', 'EARLY_WARNING', 'PAPER', 'WRITING')),
    constraint ck_assist_usage_tokens check (token_estimate >= 0),
    constraint fk_assist_usage_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id) on delete cascade
);
create index ix_assist_usage_created on assist.usage (tenant_id, created_at desc);
create index ix_assist_usage_user on assist.usage (user_id);

-- ---------------------------------------------------------------- report-card remark drafts

-- A draft remark for one student in a term, overall or for one subject. inputs_json lists exactly the fields that
-- were sent to the provider (first name, class, marks, attendance), so the teacher sees what was used. An accepted
-- overall remark is also written to the exams module's term record (the report card).
create table assist.remark_draft (
    id               uuid          primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    term_id          uuid          not null,
    section_id       uuid          not null,
    student_id       uuid          not null,
    student_name     varchar(200)  not null,
    subject_id       uuid,
    subject_name     varchar(120),
    tone             varchar(12)   not null,
    language         varchar(2)    not null,
    provider         varchar(60)   not null,
    draft_text       varchar(1000) not null,
    accepted_text    varchar(500),
    status           varchar(10)   not null default 'DRAFT',
    inputs_json      text          not null,
    created_by_id    uuid          not null,
    created_by_name  varchar(200)  not null,
    accepted_by_name varchar(200),
    accepted_at      timestamptz,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint uq_assist_remark_draft_tenant_id unique (tenant_id, id),
    constraint ck_assist_remark_draft_tone check (tone in ('ENCOURAGING', 'FORMAL')),
    constraint ck_assist_remark_draft_language check (language in ('en', 'hi')),
    constraint ck_assist_remark_draft_status check (status in ('DRAFT', 'ACCEPTED')),
    constraint fk_assist_remark_draft_term foreign key (tenant_id, term_id)
        references exams.term (tenant_id, id) on delete cascade,
    constraint fk_assist_remark_draft_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id) on delete cascade,
    constraint fk_assist_remark_draft_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_assist_remark_draft_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id) on delete cascade,
    constraint fk_assist_remark_draft_user foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete cascade
);
-- One live draft per student, term and subject (the overall remark has no subject).
create unique index uq_assist_remark_draft_student on assist.remark_draft
    (tenant_id, term_id, student_id, coalesce(subject_id, '00000000-0000-0000-0000-000000000000'));
create index ix_assist_remark_draft_section on assist.remark_draft (tenant_id, term_id, section_id);
create index ix_assist_remark_draft_student on assist.remark_draft (student_id);
create index ix_assist_remark_draft_term on assist.remark_draft (term_id);
create index ix_assist_remark_draft_subject on assist.remark_draft (subject_id);
create index ix_assist_remark_draft_user on assist.remark_draft (created_by_id);

-- ---------------------------------------------------------------- early warning watch list

-- One row per student on the watch list. reasons_json lists each signal's contribution in plain words. Nothing here
-- is shown to parents or students; "parent informed" only records that a message went through the outbox.
create table assist.watch_entry (
    id                 uuid         primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    student_id         uuid         not null,
    student_name       varchar(200) not null,
    section_id         uuid         not null,
    class_label        varchar(80)  not null,
    score              integer      not null,
    level              varchar(6)   not null,
    reasons_json       text         not null,
    computed_at        timestamptz  not null,
    status             varchar(10)  not null default 'OPEN',
    assigned_to_id     uuid,
    assigned_to_name   varchar(200),
    parent_informed_at timestamptz,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    constraint uq_assist_watch_entry_tenant_id unique (tenant_id, id),
    constraint uq_assist_watch_entry_student unique (tenant_id, student_id),
    constraint ck_assist_watch_entry_score check (score between 0 and 100),
    constraint ck_assist_watch_entry_level check (level in ('HIGH', 'MEDIUM', 'LOW')),
    constraint ck_assist_watch_entry_status check (status in ('OPEN', 'FOLLOWING', 'RESOLVED')),
    constraint fk_assist_watch_entry_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_assist_watch_entry_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id) on delete cascade,
    constraint fk_assist_watch_entry_assignee foreign key (tenant_id, assigned_to_id)
        references identity.user_account (tenant_id, id) on delete set null (assigned_to_id)
);
create index ix_assist_watch_entry_section on assist.watch_entry (section_id);
create index ix_assist_watch_entry_assignee on assist.watch_entry (assigned_to_id);

create table assist.watch_note (
    id          uuid          primary key,
    tenant_id   uuid          not null references platform.tenant (id),
    entry_id    uuid          not null,
    author_id   uuid          not null,
    author_name varchar(200)  not null,
    body        varchar(1000) not null,
    created_at  timestamptz   not null,
    constraint uq_assist_watch_note_tenant_id unique (tenant_id, id),
    constraint fk_assist_watch_note_entry foreign key (tenant_id, entry_id)
        references assist.watch_entry (tenant_id, id) on delete cascade,
    constraint fk_assist_watch_note_author foreign key (tenant_id, author_id)
        references identity.user_account (tenant_id, id) on delete cascade
);
create index ix_assist_watch_note_entry on assist.watch_note (entry_id);
create index ix_assist_watch_note_author on assist.watch_note (author_id);

-- The nightly job runs without a school selected, so row-level security hides every settings row from it. This
-- function tells it only which schools have the early warning switched on; it then works as each school in turn.
create function assist.tenants_with_early_warning()
    returns table (tenant_id uuid)
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select s.tenant_id
    from assist.settings s
    where s.enabled and s.early_warning_enabled
$$;

-- ---------------------------------------------------------------- draft question papers

-- A draft paper built from the lessons module's question bank: its blueprint and the chosen questions (copied as
-- text, with their marks, so the paper prints the same even after the bank changes). Print only; no export.
create table assist.paper_draft (
    id               uuid         primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    class_id         uuid         not null,
    subject_id       uuid         not null,
    class_name       varchar(80)  not null,
    subject_name     varchar(120) not null,
    title            varchar(160) not null,
    duration_minutes integer      not null,
    total_marks      numeric(7, 2) not null,
    instructions     varchar(2000),
    blueprint_json   text         not null,
    sections_json    text         not null,
    provider         varchar(60)  not null,
    created_by_id    uuid         not null,
    created_by_name  varchar(200) not null,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_assist_paper_draft_tenant_id unique (tenant_id, id),
    constraint ck_assist_paper_draft_duration check (duration_minutes between 10 and 240),
    constraint fk_assist_paper_draft_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id) on delete cascade,
    constraint fk_assist_paper_draft_subject foreign key (tenant_id, subject_id)
        references academics.subject (tenant_id, id) on delete cascade,
    constraint fk_assist_paper_draft_user foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete cascade
);
create index ix_assist_paper_draft_class on assist.paper_draft (class_id);
create index ix_assist_paper_draft_subject on assist.paper_draft (subject_id);
create index ix_assist_paper_draft_user on assist.paper_draft (created_by_id);

-- ---------------------------------------------------------------- row-level security

alter table assist.settings enable row level security;
create policy tenant_isolation on assist.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table assist.usage enable row level security;
create policy tenant_isolation on assist.usage
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table assist.remark_draft enable row level security;
create policy tenant_isolation on assist.remark_draft
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table assist.watch_entry enable row level security;
create policy tenant_isolation on assist.watch_entry
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table assist.watch_note enable row level security;
create policy tenant_isolation on assist.watch_note
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table assist.paper_draft enable row level security;
create policy tenant_isolation on assist.paper_draft
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema assist to ${appRole};
grant select, insert, update, delete on assist.settings, assist.usage, assist.remark_draft, assist.watch_entry,
    assist.watch_note, assist.paper_draft to ${appRole};
revoke all on function assist.tenants_with_early_warning() from public;
grant execute on function assist.tenants_with_early_warning() to ${appRole};

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.
update identity.role
set permissions = array_append(permissions, 'assist.use')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER')
  and not ('assist.use' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'assist.manage')
where system_role
  and code = 'SCHOOL_ADMIN'
  and not ('assist.manage' = any (permissions));
