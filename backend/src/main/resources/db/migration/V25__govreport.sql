-- Phase 3, government reporting: the school's government profile per academic year (UDISE+ codes, recognition,
-- infrastructure counts), the government fields of each student (APAAR ID, PEN, social category, religion, mother
-- tongue, BPL, CWSN, RTE, previous school UDISE), the code lists every export and rule reads (kept as data rows with
-- effective dates and a "verify" flag so the school can correct them without a release), the RTE 25% intake with its
-- applications, lottery runs and allotments (immutable once run), and the scholarship and scheme register. Nothing
-- here talks to a government portal: the exports are files the school uploads itself. Aadhaar is never stored.
-- Every table carries tenant_id and the same tenant_isolation policy as V2/V3, and every link between school tables
-- is a composite (tenant_id, id) foreign key.

create schema if not exists govreport;

-- ---------------------------------------------------------------- code lists (data, not code)

-- One row per code of a list: UDISE social categories, religions, mother tongues, CWSN types, UDISE school category
-- and management codes, the RTE income limit per state, scheme names. value holds a number where a code carries one
-- (an income limit in paise). Each row is in force between effective_from and effective_to (open when null) and
-- stays marked verify until the school confirms it against the current portal template.
create table govreport.code_list (
    id             uuid         primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    list_code      varchar(30)  not null,
    code           varchar(30)  not null,
    label          varchar(120) not null,
    value          varchar(60),
    state_code     varchar(2),
    effective_from date         not null,
    effective_to   date,
    verify         boolean      not null default true,
    active         boolean      not null default true,
    display_order  integer      not null default 0,
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    version        bigint       not null default 0,
    constraint uq_code_list_tenant_id unique (tenant_id, id),
    constraint uq_code_list_code unique (tenant_id, list_code, code, effective_from),
    constraint ck_code_list_list check (list_code in ('SOCIAL_CATEGORY', 'RELIGION', 'MOTHER_TONGUE', 'CWSN_TYPE',
        'SCHOOL_CATEGORY', 'MANAGEMENT', 'RTE_INCOME_LIMIT', 'SCHEME', 'RTE_DOCUMENT')),
    constraint ck_code_list_dates check (effective_to is null or effective_to >= effective_from),
    constraint ck_code_list_code check (code ~ '^[A-Z0-9_]{1,30}$')
);
create index ix_code_list_list on govreport.code_list (tenant_id, list_code);

-- ---------------------------------------------------------------- the school's government profile

-- One row per academic year: what the UDISE+ school profile and the CBSE OASIS data sheet ask for. Teacher counts
-- are derived from the staff module at export time and are not stored here.
create table govreport.school_profile (
    id                     uuid         primary key,
    tenant_id              uuid         not null references platform.tenant (id),
    academic_year_id       uuid         not null,
    udise_code             varchar(11),
    board                  varchar(20),
    affiliation_no         varchar(60),
    affiliation_valid_until date,
    school_category_code   varchar(30),
    management_code        varchar(30),
    dise_block_code        varchar(10),
    dise_district_code     varchar(10),
    state_code             varchar(2),
    recognition_authority  varchar(120),
    recognition_no         varchar(60),
    recognition_valid_until date,
    lowest_class           varchar(20),
    highest_class          varchar(20),
    medium_of_instruction  varchar(60),
    classrooms             integer,
    other_rooms            integer,
    toilets_boys           integer,
    toilets_girls          integer,
    toilets_cwsn           integer,
    drinking_water         boolean,
    drinking_water_source  varchar(60),
    electricity            boolean,
    library                boolean,
    library_books          integer,
    playground             boolean,
    computers              integer,
    internet               boolean,
    ramps                  boolean,
    boundary_wall          boolean,
    created_at             timestamptz  not null,
    updated_at             timestamptz  not null,
    version                bigint       not null default 0,
    constraint uq_school_profile_tenant_id unique (tenant_id, id),
    constraint uq_school_profile_year unique (tenant_id, academic_year_id),
    constraint fk_school_profile_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint ck_school_profile_udise check (udise_code is null or udise_code ~ '^[0-9]{11}$'),
    constraint ck_school_profile_counts check (
        coalesce(classrooms, 0) >= 0 and coalesce(other_rooms, 0) >= 0 and coalesce(toilets_boys, 0) >= 0
        and coalesce(toilets_girls, 0) >= 0 and coalesce(toilets_cwsn, 0) >= 0 and coalesce(library_books, 0) >= 0
        and coalesce(computers, 0) >= 0)
);

-- ---------------------------------------------------------------- the government fields of a student

-- One row per student, made when any field is first entered. APAAR ID (12 digits) and PEN may be stored; Aadhaar
-- never is. A parent may fill the mother tongue, and the APAAR ID once; the office then locks the APAAR ID.
create table govreport.student_record (
    id                    uuid         primary key,
    tenant_id             uuid         not null references platform.tenant (id),
    student_id            uuid         not null,
    apaar_id              varchar(12),
    apaar_locked          boolean      not null default false,
    pen                   varchar(20),
    social_category       varchar(30),
    religion              varchar(30),
    mother_tongue         varchar(30),
    bpl                   boolean,
    cwsn_type             varchar(30),
    rte_admitted          boolean,
    previous_school_udise varchar(11),
    updated_by_id         uuid,
    updated_by_name       varchar(200),
    updated_via           varchar(10)  not null default 'OFFICE',
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0,
    constraint uq_student_record_tenant_id unique (tenant_id, id),
    constraint uq_student_record_student unique (tenant_id, student_id),
    constraint fk_student_record_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_student_record_updated_by foreign key (tenant_id, updated_by_id)
        references identity.user_account (tenant_id, id) on delete set null (updated_by_id),
    constraint ck_student_record_apaar check (apaar_id is null or apaar_id ~ '^[0-9]{12}$'),
    constraint ck_student_record_pen check (pen is null or pen ~ '^[A-Za-z0-9]{1,20}$'),
    constraint ck_student_record_udise check (previous_school_udise is null or previous_school_udise ~ '^[0-9]{11}$'),
    constraint ck_student_record_via check (updated_via in ('OFFICE', 'FAMILY', 'IMPORT'))
);
create index ix_student_record_updated_by on govreport.student_record (updated_by_id);

-- ---------------------------------------------------------------- RTE 25% admissions

-- The RTE intake of an academic year: the entry class, its seats and the 25% RTE seats, the income limit and the
-- distance rule in force (copied from the code lists when the intake is made, so a later correction does not change
-- a lottery already run) and the documents a family must bring.
create table govreport.rte_intake (
    id                   uuid         primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    academic_year_id     uuid         not null,
    entry_class_id       uuid         not null,
    total_seats          integer      not null,
    rte_seats            integer      not null,
    state_code           varchar(2),
    income_limit_paise   bigint       not null,
    distance_band_1_km   numeric(5, 2) not null default 1,
    distance_band_2_km   numeric(5, 2) not null default 3,
    distance_band_3_km   numeric(5, 2) not null default 6,
    documents            jsonb        not null default '[]'::jsonb,
    applications_close_on date,
    status               varchar(12)  not null default 'OPEN',
    current_run_id       uuid,
    created_by_id        uuid,
    created_by_name      varchar(200),
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint uq_rte_intake_tenant_id unique (tenant_id, id),
    constraint uq_rte_intake_year_class unique (tenant_id, academic_year_id, entry_class_id),
    constraint fk_rte_intake_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_rte_intake_class foreign key (tenant_id, entry_class_id)
        references academics.school_class (tenant_id, id),
    constraint fk_rte_intake_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id),
    constraint ck_rte_intake_seats check (total_seats between 1 and 2000 and rte_seats between 0 and total_seats),
    constraint ck_rte_intake_income check (income_limit_paise >= 0),
    constraint ck_rte_intake_bands check (distance_band_1_km > 0 and distance_band_2_km >= distance_band_1_km
        and distance_band_3_km >= distance_band_2_km),
    constraint ck_rte_intake_status check (status in ('OPEN', 'LOTTERY_DONE', 'CLOSED'))
);
create index ix_rte_intake_year on govreport.rte_intake (tenant_id, academic_year_id);

-- An application in the intake: an admissions application marked RTE, with the family's category, declared income,
-- distance from the school, the documents seen and the screening outcome. The child's name is read from admissions.
create table govreport.rte_application (
    id                   uuid         primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    intake_id            uuid         not null,
    application_id       uuid         not null,
    category_code        varchar(30),
    annual_income_paise  bigint,
    income_declared      boolean      not null default false,
    distance_km          numeric(5, 2),
    documents            jsonb        not null default '{}'::jsonb,
    eligibility          varchar(12)  not null default 'PENDING',
    ineligible_reason    varchar(300),
    screened_by_id       uuid,
    screened_by_name     varchar(200),
    screened_at          timestamptz,
    withdrawn            boolean      not null default false,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint uq_rte_application_tenant_id unique (tenant_id, id),
    constraint uq_rte_application_intake unique (tenant_id, intake_id, application_id),
    constraint fk_rte_application_intake foreign key (tenant_id, intake_id)
        references govreport.rte_intake (tenant_id, id),
    constraint fk_rte_application_application foreign key (tenant_id, application_id)
        references admissions.application (tenant_id, id),
    constraint fk_rte_application_screened_by foreign key (tenant_id, screened_by_id)
        references identity.user_account (tenant_id, id) on delete set null (screened_by_id),
    constraint ck_rte_application_income check (annual_income_paise is null or annual_income_paise >= 0),
    constraint ck_rte_application_distance check (distance_km is null or distance_km >= 0),
    constraint ck_rte_application_eligibility check (eligibility in ('PENDING', 'ELIGIBLE', 'INELIGIBLE'))
);
create index ix_rte_application_intake on govreport.rte_application (tenant_id, intake_id);
create index ix_rte_application_screened_by on govreport.rte_application (screened_by_id);

-- A lottery run: the random seed, when and by whom, the witness, and the reason when it reruns an earlier one.
-- Never updated or deleted: a rerun is a new run.
create table govreport.rte_lottery_run (
    id              uuid         primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    intake_id       uuid         not null,
    run_no          integer      not null,
    seed            bigint       not null,
    seats           integer      not null,
    applicants      integer      not null,
    witness_name    varchar(200) not null,
    reason          varchar(500),
    ran_by_id       uuid,
    ran_by_name     varchar(200),
    ran_at          timestamptz  not null,
    created_at      timestamptz  not null,
    constraint uq_rte_lottery_run_tenant_id unique (tenant_id, id),
    constraint uq_rte_lottery_run_no unique (tenant_id, intake_id, run_no),
    constraint fk_rte_lottery_run_intake foreign key (tenant_id, intake_id)
        references govreport.rte_intake (tenant_id, id),
    constraint fk_rte_lottery_run_ran_by foreign key (tenant_id, ran_by_id)
        references identity.user_account (tenant_id, id) on delete set null (ran_by_id),
    constraint ck_rte_lottery_run_counts check (run_no >= 1 and seats >= 0 and applicants >= 0)
);
create index ix_rte_lottery_run_ran_by on govreport.rte_lottery_run (ran_by_id);

alter table govreport.rte_intake add constraint fk_rte_intake_current_run foreign key (tenant_id, current_run_id)
    references govreport.rte_lottery_run (tenant_id, id);
create index ix_rte_intake_current_run on govreport.rte_intake (current_run_id);

-- The ordered result of a run: every eligible applicant with their position, ALLOTTED up to the seats and
-- WAITLISTED after, with the waitlist number. Immutable with the run.
create table govreport.rte_allotment (
    id                  uuid        primary key,
    tenant_id           uuid        not null references platform.tenant (id),
    run_id              uuid        not null,
    rte_application_id  uuid        not null,
    position            integer     not null,
    outcome             varchar(10) not null,
    waitlist_no         integer,
    distance_band       integer     not null,
    created_at          timestamptz not null,
    constraint uq_rte_allotment_tenant_id unique (tenant_id, id),
    constraint uq_rte_allotment_position unique (tenant_id, run_id, position),
    constraint uq_rte_allotment_application unique (tenant_id, run_id, rte_application_id),
    constraint fk_rte_allotment_run foreign key (tenant_id, run_id)
        references govreport.rte_lottery_run (tenant_id, id),
    constraint fk_rte_allotment_application foreign key (tenant_id, rte_application_id)
        references govreport.rte_application (tenant_id, id),
    constraint ck_rte_allotment_outcome check (outcome in ('ALLOTTED', 'WAITLISTED')),
    constraint ck_rte_allotment_waitlist check ((outcome = 'WAITLISTED') = (waitlist_no is not null)),
    constraint ck_rte_allotment_position check (position >= 1 and distance_band between 1 and 4)
);

-- ---------------------------------------------------------------- scholarships and schemes

-- A student's enrolment in a central or state scheme for a year (the scheme from the SCHEME code list).
create table govreport.scheme_enrolment (
    id               uuid         primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    student_id       uuid         not null,
    academic_year_id uuid         not null,
    scheme_code      varchar(30)  not null,
    status           varchar(12)  not null,
    amount_paise     bigint,
    reference_no     varchar(60),
    applied_on       date,
    notes            varchar(500),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_scheme_enrolment_tenant_id unique (tenant_id, id),
    constraint uq_scheme_enrolment_student unique (tenant_id, student_id, academic_year_id, scheme_code),
    constraint fk_scheme_enrolment_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_scheme_enrolment_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint ck_scheme_enrolment_status check (status in ('APPLIED', 'APPROVED', 'RECEIVED', 'REJECTED')),
    constraint ck_scheme_enrolment_amount check (amount_paise is null or amount_paise >= 0)
);
create index ix_scheme_enrolment_year on govreport.scheme_enrolment (tenant_id, academic_year_id, scheme_code);
create index ix_scheme_enrolment_student on govreport.scheme_enrolment (tenant_id, student_id);

-- ---------------------------------------------------------------- row-level security

alter table govreport.code_list enable row level security;
create policy tenant_isolation on govreport.code_list
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table govreport.school_profile enable row level security;
create policy tenant_isolation on govreport.school_profile
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table govreport.student_record enable row level security;
create policy tenant_isolation on govreport.student_record
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table govreport.rte_intake enable row level security;
create policy tenant_isolation on govreport.rte_intake
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table govreport.rte_application enable row level security;
create policy tenant_isolation on govreport.rte_application
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table govreport.rte_lottery_run enable row level security;
create policy tenant_isolation on govreport.rte_lottery_run
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table govreport.rte_allotment enable row level security;
create policy tenant_isolation on govreport.rte_allotment
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table govreport.scheme_enrolment enable row level security;
create policy tenant_isolation on govreport.scheme_enrolment
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema govreport to ${appRole};
grant select, insert, update on govreport.code_list, govreport.school_profile, govreport.student_record,
    govreport.rte_intake, govreport.rte_application to ${appRole};
grant select, insert, update, delete on govreport.scheme_enrolment to ${appRole};
-- A lottery, once run, is a record: never changed or removed. A rerun is a new run.
grant select, insert on govreport.rte_lottery_run, govreport.rte_allotment to ${appRole};

-- ---------------------------------------------------------------- the government reporting permissions

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.

-- govreport.read: the school's government profile, students' government fields, exports, RTE and schemes.
update identity.role
set permissions = array_append(permissions, 'govreport.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'FRONT_OFFICE')
  and not ('govreport.read' = any (permissions));

-- govreport.manage: editing the profile, fields and code lists, running the RTE lottery, the scheme register.
update identity.role
set permissions = array_append(permissions, 'govreport.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('govreport.manage' = any (permissions));
