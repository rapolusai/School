-- Phase 1, slice 2: admissions. Enquiries and applications move through a pipeline (enquiry, application,
-- test or interview, offer, admitted) until the child becomes a student or the application is closed.
-- Every school-owned table carries tenant_id and is protected by row-level security, exactly like V1 to V3.
-- Only what the admissions office needs is stored: no Aadhaar numbers, no addresses, no documents.

create schema if not exists admissions;

-- ---------------------------------------------------------------- applications

create table admissions.application (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    stage             varchar(20)  not null,
    stage_changed_at  timestamptz  not null,
    -- The child, as the student record will need it.
    first_name        varchar(100) not null,
    last_name         varchar(100),
    date_of_birth     date         not null,
    gender            varchar(10),
    previous_school   varchar(200),
    -- What the family applies for.
    class_id          uuid         not null,
    academic_year_id  uuid         not null,
    source            varchar(20)  not null,
    assigned_to_id    uuid,
    follow_up_on      date,
    message           varchar(1000),
    -- DPDP consent given on the public enquiry form: which consent text was shown, and when it was accepted.
    consent_version   varchar(40),
    consent_at        timestamptz,
    -- Application fee, paid or waived. Money is stored in paise.
    fee_status        varchar(10),
    fee_amount_paise  bigint,
    fee_method        varchar(20),
    fee_reference     varchar(100),
    fee_on            date,
    -- Offer letter.
    offered_on        date,
    offer_valid_until date,
    -- The student record created when the child was admitted.
    student_id        uuid,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint ck_application_stage check (stage in ('ENQUIRY', 'APPLICATION', 'ASSESSMENT', 'OFFERED', 'ADMITTED',
        'REJECTED', 'WITHDRAWN')),
    constraint ck_application_source check (source in ('WALK_IN', 'WEBSITE', 'PHONE', 'REFERRAL', 'OTHER')),
    constraint ck_application_gender check (gender is null or gender in ('MALE', 'FEMALE', 'OTHER')),
    constraint ck_application_consent check ((consent_version is null) = (consent_at is null)),
    constraint ck_application_fee check (
        (fee_status is null and fee_amount_paise is null and fee_method is null and fee_reference is null
            and fee_on is null)
        or (fee_status = 'PAID' and fee_amount_paise > 0 and fee_method is not null and fee_on is not null)
        or (fee_status = 'WAIVED' and fee_amount_paise is null and fee_method is null and fee_on is not null)),
    constraint ck_application_fee_method check (fee_method is null
        or fee_method in ('CASH', 'UPI', 'CARD', 'BANK_TRANSFER')),
    constraint ck_application_offer check (offer_valid_until is null
        or (offered_on is not null and offer_valid_until >= offered_on)),
    -- An admitted application always points at the student it became.
    constraint ck_application_admitted check (stage <> 'ADMITTED' or student_id is not null),
    constraint uq_application_tenant_id unique (tenant_id, id),
    constraint fk_application_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id),
    constraint fk_application_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_application_assigned_to foreign key (tenant_id, assigned_to_id)
        references identity.user_account (tenant_id, id) on delete set null (assigned_to_id),
    constraint fk_application_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id)
);
create index ix_application_tenant_stage on admissions.application (tenant_id, stage, stage_changed_at);
create index ix_application_year on admissions.application (tenant_id, academic_year_id);
create index ix_application_class on admissions.application (class_id);
create index ix_application_assigned_to on admissions.application (assigned_to_id);
-- A student comes from at most one application.
create unique index uq_application_student on admissions.application (student_id) where student_id is not null;

-- Parents or guardians named on an application. They become student guardians only when the child is admitted.
create table admissions.application_guardian (
    id             uuid primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    application_id uuid         not null,
    name           varchar(200) not null,
    relation       varchar(10)  not null,
    phone          varchar(10)  not null,
    email          varchar(254),
    is_primary     boolean      not null default false,
    position       smallint     not null,
    created_at     timestamptz  not null,
    constraint ck_application_guardian_relation check (relation in ('FATHER', 'MOTHER', 'GUARDIAN')),
    -- Indian mobile numbers, stored as 10 digits without +91.
    constraint ck_application_guardian_phone check (phone ~ '^[6-9][0-9]{9}$'),
    constraint fk_application_guardian_application foreign key (tenant_id, application_id)
        references admissions.application (tenant_id, id) on delete cascade
);
create index ix_application_guardian_application on admissions.application_guardian (application_id);
create index ix_application_guardian_phone on admissions.application_guardian (tenant_id, phone);
create index ix_application_guardian_tenant on admissions.application_guardian (tenant_id);
create unique index uq_application_guardian_primary on admissions.application_guardian (application_id)
    where is_primary;

-- What happened to an application and who did it: stage changes, notes, fees, offers and assessment slots.
create table admissions.timeline_entry (
    id             uuid primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    application_id uuid         not null,
    at             timestamptz  not null,
    kind           varchar(30)  not null,
    actor_id       uuid,
    actor_name     varchar(200),
    from_stage     varchar(20),
    to_stage       varchar(20),
    note           varchar(2000),
    details        jsonb        not null default '{}'::jsonb,
    constraint ck_timeline_entry_kind check (kind in ('CREATED', 'STAGE_CHANGED', 'NOTE', 'UPDATED', 'FEE_RECORDED',
        'OFFER_UPDATED', 'SLOT_SCHEDULED', 'SLOT_RESCHEDULED', 'SLOT_OUTCOME', 'SLOT_CANCELLED')),
    constraint fk_timeline_entry_application foreign key (tenant_id, application_id)
        references admissions.application (tenant_id, id) on delete cascade
);
create index ix_timeline_entry_application on admissions.timeline_entry (application_id, at);
create index ix_timeline_entry_tenant on admissions.timeline_entry (tenant_id);

-- Entrance tests and interviews.
create table admissions.assessment_slot (
    id             uuid primary key,
    tenant_id      uuid          not null references platform.tenant (id),
    application_id uuid          not null,
    kind           varchar(10)   not null,
    scheduled_at   timestamptz   not null,
    mode           varchar(10)   not null,
    location       varchar(200),
    meeting_link   varchar(500),
    interviewer_id uuid,
    status         varchar(10)   not null,
    outcome_notes  varchar(2000),
    created_at     timestamptz   not null,
    updated_at     timestamptz   not null,
    version        bigint        not null default 0,
    constraint ck_assessment_slot_kind check (kind in ('TEST', 'INTERVIEW')),
    constraint ck_assessment_slot_mode check (mode in ('IN_PERSON', 'ONLINE')),
    constraint ck_assessment_slot_status check (status in ('SCHEDULED', 'DONE', 'CANCELLED')),
    constraint ck_assessment_slot_place check ((mode = 'IN_PERSON' and location is not null)
        or (mode = 'ONLINE' and meeting_link is not null)),
    constraint fk_assessment_slot_application foreign key (tenant_id, application_id)
        references admissions.application (tenant_id, id) on delete cascade,
    constraint fk_assessment_slot_interviewer foreign key (tenant_id, interviewer_id)
        references identity.user_account (tenant_id, id) on delete set null (interviewer_id)
);
create index ix_assessment_slot_upcoming on admissions.assessment_slot (tenant_id, status, scheduled_at);
create index ix_assessment_slot_application on admissions.assessment_slot (application_id);
create index ix_assessment_slot_interviewer on admissions.assessment_slot (interviewer_id);

-- ---------------------------------------------------------------- row-level security

alter table admissions.application enable row level security;
create policy tenant_isolation on admissions.application
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table admissions.application_guardian enable row level security;
create policy tenant_isolation on admissions.application_guardian
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table admissions.timeline_entry enable row level security;
create policy tenant_isolation on admissions.timeline_entry
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table admissions.assessment_slot enable row level security;
create policy tenant_isolation on admissions.assessment_slot
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema admissions to ${appRole};
grant select, insert, update, delete on admissions.application, admissions.application_guardian,
    admissions.timeline_entry, admissions.assessment_slot to ${appRole};

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.
update identity.role
set permissions = array_append(permissions, 'admissions.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'FRONT_OFFICE')
  and not ('admissions.read' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'admissions.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'FRONT_OFFICE')
  and not ('admissions.manage' = any (permissions));
