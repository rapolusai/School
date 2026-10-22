-- Phase 3, health and safety: each student's medical record (blood group, growth measurements, allergies, chronic
-- conditions, regular medication, vaccinations, doctor and insurance, parents' consent for first aid and hospital
-- transfer), the infirmary log with a small medicine stock, the fire drill register, the safety audit checklist, the
-- incident register, first-aid kit checks, the POCSO committee with its minutes and complaints register, and staff
-- police verification and training records. Every table carries tenant_id and the same tenant_isolation policy as
-- V2/V3; every link between school tables is a composite (tenant_id, id) foreign key. Also adds the built-in Nurse
-- role and the health permissions.
--
-- Personal data: this is sensitive health data, so only what the school needs for a child's safety is kept. No
-- government identity number is ever stored (not Aadhaar, not a health id). The insurance policy number and a staff
-- verification's reference number are shown masked. Reads of a medical record and of a POCSO complaint are audited by
-- the application, not only writes. A complainant's identity is a free-text note read only by the committee.

create schema if not exists health;

-- ---------------------------------------------------------------- medical records

create table health.medical_record (
    id                   uuid primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    student_id           uuid         not null,
    blood_group          varchar(3),
    doctor_name          varchar(200),
    doctor_phone         varchar(15),
    insurance_provider   varchar(200),
    insurance_policy_no  varchar(40),
    consent_first_aid    boolean      not null default false,
    consent_hospital     boolean      not null default false,
    consent_by_name      varchar(200),
    consent_at           timestamptz,
    notes                varchar(1000),
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint uq_medical_record_tenant_id unique (tenant_id, id),
    constraint uq_medical_record_student unique (student_id),
    constraint fk_medical_record_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint ck_medical_record_blood_group check (blood_group is null
        or blood_group in ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-')),
    constraint ck_medical_record_doctor_phone check (doctor_phone is null or doctor_phone ~ '^[0-9]{10}$')
);

-- Height and weight on a day: the growth chart.
create table health.growth_measurement (
    id          uuid primary key,
    tenant_id   uuid          not null references platform.tenant (id),
    student_id  uuid          not null,
    measured_on date          not null,
    height_cm   numeric(5, 1),
    weight_kg   numeric(5, 1),
    recorded_by_name varchar(200),
    created_at  timestamptz   not null,
    constraint uq_growth_measurement_tenant_id unique (tenant_id, id),
    constraint uq_growth_measurement_day unique (student_id, measured_on),
    constraint fk_growth_measurement_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint ck_growth_measurement_height check (height_cm is null or height_cm between 30 and 250),
    constraint ck_growth_measurement_weight check (weight_kg is null or weight_kg between 1 and 250),
    constraint ck_growth_measurement_some check (height_cm is not null or weight_kg is not null)
);
create index ix_growth_measurement_student on health.growth_measurement (student_id, measured_on);

-- Allergies (with a severity), chronic conditions, regular medication and vaccinations, one row each.
create table health.record_item (
    id            uuid primary key,
    tenant_id     uuid         not null references platform.tenant (id),
    student_id    uuid         not null,
    kind          varchar(12)  not null,
    name          varchar(120) not null,
    detail        varchar(500),
    severity      varchar(10),
    item_date     date,
    -- PARENT when a parent keeps it from the family app; STAFF when the nurse or the office did.
    source        varchar(10)  not null,
    added_by_name varchar(200),
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0,
    constraint uq_record_item_tenant_id unique (tenant_id, id),
    constraint fk_record_item_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint ck_record_item_kind check (kind in ('ALLERGY', 'CONDITION', 'MEDICATION', 'VACCINATION')),
    constraint ck_record_item_severity check (severity is null or severity in ('MILD', 'MODERATE', 'SEVERE')),
    constraint ck_record_item_allergy_severity check (kind <> 'ALLERGY' or severity is not null),
    constraint ck_record_item_source check (source in ('PARENT', 'STAFF'))
);
create index ix_record_item_student on health.record_item (student_id, kind);
create index ix_record_item_tenant_kind on health.record_item (tenant_id, kind);

-- ---------------------------------------------------------------- the infirmary

create table health.medicine (
    id            uuid primary key,
    tenant_id     uuid         not null references platform.tenant (id),
    name          varchar(120) not null,
    unit          varchar(20)  not null,
    quantity      integer      not null default 0,
    reorder_level integer      not null default 0,
    expires_on    date,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0,
    constraint uq_medicine_tenant_id unique (tenant_id, id),
    constraint ck_medicine_quantity check (quantity >= 0 and reorder_level >= 0)
);
create unique index uq_medicine_name on health.medicine (tenant_id, lower(name));

-- One visit to the infirmary by a student or a member of staff. Rows are the school's health record: never deleted.
create table health.infirmary_visit (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    person_kind      varchar(10)  not null,
    student_id       uuid,
    staff_user_id    uuid,
    person_name      varchar(200) not null,
    class_name       varchar(60),
    section_id       uuid,
    time_in          timestamptz  not null,
    time_out         timestamptz,
    complaint        varchar(500) not null,
    temperature_c    numeric(4, 1),
    treatment        varchar(1000),
    outcome          varchar(15),
    parent_informed  boolean      not null default false,
    recorded_by_name varchar(200),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_infirmary_visit_tenant_id unique (tenant_id, id),
    constraint fk_infirmary_visit_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_infirmary_visit_staff foreign key (tenant_id, staff_user_id)
        references identity.user_account (tenant_id, id) on delete set null (staff_user_id),
    constraint fk_infirmary_visit_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id) on delete set null (section_id),
    constraint ck_infirmary_visit_kind check (person_kind in ('STUDENT', 'STAFF')),
    constraint ck_infirmary_visit_person check ((person_kind = 'STUDENT' and student_id is not null)
        or person_kind = 'STAFF'),
    constraint ck_infirmary_visit_temperature check (temperature_c is null or temperature_c between 30 and 45),
    constraint ck_infirmary_visit_outcome check (outcome is null
        or outcome in ('BACK_TO_CLASS', 'SENT_HOME', 'HOSPITAL')),
    constraint ck_infirmary_visit_times check ((time_out is null and outcome is null)
        or (time_out is not null and outcome is not null and time_out >= time_in))
);
create index ix_infirmary_visit_day on health.infirmary_visit (tenant_id, time_in);
create index ix_infirmary_visit_student on health.infirmary_visit (student_id);
create index ix_infirmary_visit_staff on health.infirmary_visit (staff_user_id);
create index ix_infirmary_visit_section on health.infirmary_visit (section_id);

-- Medicine given during a visit, taken from the stock.
create table health.medicine_given (
    id          uuid primary key,
    tenant_id   uuid        not null references platform.tenant (id),
    visit_id    uuid        not null,
    medicine_id uuid        not null,
    quantity    integer     not null,
    created_at  timestamptz not null,
    constraint uq_medicine_given_tenant_id unique (tenant_id, id),
    constraint fk_medicine_given_visit foreign key (tenant_id, visit_id)
        references health.infirmary_visit (tenant_id, id) on delete cascade,
    constraint fk_medicine_given_medicine foreign key (tenant_id, medicine_id)
        references health.medicine (tenant_id, id),
    constraint ck_medicine_given_quantity check (quantity between 1 and 1000)
);
create index ix_medicine_given_visit on health.medicine_given (visit_id);
create index ix_medicine_given_medicine on health.medicine_given (medicine_id);

-- ---------------------------------------------------------------- safety registers

create table health.fire_drill (
    id                    uuid primary key,
    tenant_id             uuid         not null references platform.tenant (id),
    drill_date            date         not null,
    kind                  varchar(12)  not null,
    block                 varchar(100) not null,
    time_to_clear_seconds integer      not null,
    attendance            integer      not null,
    issues                varchar(1000),
    next_due              date         not null,
    conducted_by_name     varchar(200),
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0,
    constraint uq_fire_drill_tenant_id unique (tenant_id, id),
    constraint ck_fire_drill_kind check (kind in ('FIRE', 'EVACUATION', 'EARTHQUAKE')),
    constraint ck_fire_drill_time check (time_to_clear_seconds between 1 and 7200),
    constraint ck_fire_drill_attendance check (attendance between 0 and 100000),
    constraint ck_fire_drill_next_due check (next_due > drill_date)
);
create index ix_fire_drill_date on health.fire_drill (tenant_id, drill_date);

create table health.safety_audit (
    id          uuid primary key,
    tenant_id   uuid         not null references platform.tenant (id),
    area        varchar(12)  not null,
    audit_date  date         not null,
    finding     varchar(1000) not null,
    severity    varchar(10)  not null,
    status      varchar(10)  not null,
    due_on      date,
    closed_on   date,
    closure_note varchar(1000),
    audited_by_name varchar(200),
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    version     bigint       not null default 0,
    constraint uq_safety_audit_tenant_id unique (tenant_id, id),
    constraint ck_safety_audit_area check (area in ('ELECTRICAL', 'PLAYGROUND', 'LABS', 'TRANSPORT', 'BUILDING',
        'WATER')),
    constraint ck_safety_audit_severity check (severity in ('LOW', 'MEDIUM', 'HIGH')),
    constraint ck_safety_audit_status check (status in ('OPEN', 'CLOSED')),
    constraint ck_safety_audit_closed check ((status = 'CLOSED') = (closed_on is not null))
);
create index ix_safety_audit_open on health.safety_audit (tenant_id, status, due_on);

create table health.incident (
    id               uuid primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    incident_at      timestamptz   not null,
    kind             varchar(10)   not null,
    severity         varchar(10)   not null,
    student_id       uuid,
    person_name      varchar(200),
    place            varchar(200),
    description      varchar(2000) not null,
    actions          varchar(2000),
    parent_informed  boolean       not null default false,
    reported_by_name varchar(200),
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint uq_incident_tenant_id unique (tenant_id, id),
    constraint fk_incident_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete set null (student_id),
    constraint ck_incident_kind check (kind in ('INJURY', 'BULLYING', 'OTHER')),
    constraint ck_incident_severity check (severity in ('MINOR', 'MODERATE', 'SERIOUS'))
);
create index ix_incident_day on health.incident (tenant_id, incident_at);
create index ix_incident_student on health.incident (student_id);

create table health.first_aid_kit (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    location        varchar(120) not null,
    checked_on      date,
    complete        boolean      not null default true,
    earliest_expiry date,
    missing_items   varchar(500),
    checked_by_name varchar(200),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint uq_first_aid_kit_tenant_id unique (tenant_id, id)
);
create unique index uq_first_aid_kit_location on health.first_aid_kit (tenant_id, lower(location));

-- ---------------------------------------------------------------- POCSO committee

-- One row per school: when the committee was formed. Members and meetings hang off it.
create table health.pocso_committee (
    id         uuid primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    formed_on  date,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null default 0,
    constraint uq_pocso_committee_tenant_id unique (tenant_id, id),
    constraint uq_pocso_committee_tenant unique (tenant_id)
);

create table health.pocso_member (
    id           uuid primary key,
    tenant_id    uuid         not null references platform.tenant (id),
    user_id      uuid         not null,
    name         varchar(200) not null,
    role         varchar(15)  not null,
    appointed_on date,
    created_at   timestamptz  not null,
    constraint uq_pocso_member_tenant_id unique (tenant_id, id),
    constraint uq_pocso_member_user unique (user_id),
    constraint fk_pocso_member_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id) on delete cascade,
    constraint ck_pocso_member_role check (role in ('CHAIRPERSON', 'MEMBER', 'EXTERNAL', 'SECRETARY'))
);
create index ix_pocso_member_tenant on health.pocso_member (tenant_id);

create table health.pocso_meeting (
    id            uuid primary key,
    tenant_id     uuid          not null references platform.tenant (id),
    meeting_date  date          not null,
    minutes       varchar(8000) not null,
    recorded_by_name varchar(200),
    created_at    timestamptz   not null,
    constraint uq_pocso_meeting_tenant_id unique (tenant_id, id)
);
create index ix_pocso_meeting_date on health.pocso_meeting (tenant_id, meeting_date);

-- The complaints register. Read only by the committee; every read is audited. The complainant's identity is kept
-- only as free text in complainant_note, which is never listed, only shown on the detail to the committee.
create table health.pocso_complaint (
    id               uuid primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    complaint_date   date          not null,
    category         varchar(15)   not null,
    description      varchar(4000) not null,
    status           varchar(15)   not null,
    actions          varchar(4000),
    complainant_note varchar(1000),
    recorded_by_name varchar(200),
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint uq_pocso_complaint_tenant_id unique (tenant_id, id),
    constraint ck_pocso_complaint_category check (category in ('ABUSE', 'HARASSMENT', 'NEGLECT', 'OTHER')),
    constraint ck_pocso_complaint_status check (status in ('OPEN', 'UNDER_ENQUIRY', 'REFERRED', 'CLOSED'))
);
create index ix_pocso_complaint_date on health.pocso_complaint (tenant_id, complaint_date);

-- ---------------------------------------------------------------- staff checks

create table health.staff_verification (
    id           uuid primary key,
    tenant_id    uuid         not null references platform.tenant (id),
    user_id      uuid         not null,
    status       varchar(10)  not null,
    requested_on date,
    cleared_on   date,
    reference_no varchar(60),
    expires_on   date,
    note         varchar(500),
    updated_by_name varchar(200),
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    version      bigint       not null default 0,
    constraint uq_staff_verification_tenant_id unique (tenant_id, id),
    constraint uq_staff_verification_user unique (user_id),
    constraint fk_staff_verification_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id) on delete cascade,
    constraint ck_staff_verification_status check (status in ('PENDING', 'REQUESTED', 'CLEARED', 'FLAGGED'))
);
create index ix_staff_verification_tenant on health.staff_verification (tenant_id, status);

create table health.staff_training (
    id         uuid primary key,
    tenant_id  uuid         not null references platform.tenant (id),
    user_id    uuid         not null,
    kind       varchar(12)  not null,
    trained_on date         not null,
    expires_on date,
    provider   varchar(200),
    created_at timestamptz  not null,
    constraint uq_staff_training_tenant_id unique (tenant_id, id),
    constraint fk_staff_training_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id) on delete cascade,
    constraint ck_staff_training_kind check (kind in ('POCSO', 'FIRST_AID')),
    constraint ck_staff_training_expiry check (expires_on is null or expires_on > trained_on)
);
create index ix_staff_training_user on health.staff_training (user_id);
create index ix_staff_training_tenant on health.staff_training (tenant_id, kind, expires_on);

-- ---------------------------------------------------------------- row-level security

alter table health.medical_record enable row level security;
create policy tenant_isolation on health.medical_record
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.growth_measurement enable row level security;
create policy tenant_isolation on health.growth_measurement
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.record_item enable row level security;
create policy tenant_isolation on health.record_item
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.medicine enable row level security;
create policy tenant_isolation on health.medicine
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.infirmary_visit enable row level security;
create policy tenant_isolation on health.infirmary_visit
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.medicine_given enable row level security;
create policy tenant_isolation on health.medicine_given
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.fire_drill enable row level security;
create policy tenant_isolation on health.fire_drill
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.safety_audit enable row level security;
create policy tenant_isolation on health.safety_audit
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.incident enable row level security;
create policy tenant_isolation on health.incident
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.first_aid_kit enable row level security;
create policy tenant_isolation on health.first_aid_kit
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.pocso_committee enable row level security;
create policy tenant_isolation on health.pocso_committee
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.pocso_member enable row level security;
create policy tenant_isolation on health.pocso_member
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.pocso_meeting enable row level security;
create policy tenant_isolation on health.pocso_meeting
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.pocso_complaint enable row level security;
create policy tenant_isolation on health.pocso_complaint
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.staff_verification enable row level security;
create policy tenant_isolation on health.staff_verification
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table health.staff_training enable row level security;
create policy tenant_isolation on health.staff_training
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema health to ${appRole};
grant select, insert, update, delete on health.medical_record, health.growth_measurement, health.record_item,
    health.medicine, health.medicine_given, health.fire_drill, health.safety_audit, health.first_aid_kit,
    health.pocso_committee, health.pocso_member, health.pocso_meeting, health.staff_verification,
    health.staff_training to ${appRole};
-- The infirmary log, the incident register and the complaints register are records: never deleted.
grant select, insert, update on health.infirmary_visit, health.incident, health.pocso_complaint to ${appRole};

-- ---------------------------------------------------------------- the Nurse role and the health permissions

-- New schools get the Nurse role and these permissions from RoleCatalog. Existing schools keep their stored roles,
-- so the built-in Nurse role is added to every school that has none, and the new codes are appended to the matching
-- built-in roles, once. Runs as the owner, which row-level security does not restrict. Idempotent.
insert into identity.role (id, tenant_id, code, name, permissions, system_role, created_at)
select gen_random_uuid(), t.id, 'NURSE', 'Nurse',
       array['dashboard.view', 'students.read', 'academics.read', 'leave.request', 'notices.read', 'timetable.read',
             'health.read', 'health.manage'],
       true, now()
from platform.tenant t
where not exists (select 1 from identity.role r where r.tenant_id = t.id and r.code = 'NURSE');

-- health.read: medical records (class teachers only for the sections they lead), the infirmary log and health reports.
update identity.role
set permissions = array_append(permissions, 'health.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER')
  and not ('health.read' = any (permissions));

-- health.manage: editing medical records, the infirmary log and the medicine stock.
update identity.role
set permissions = array_append(permissions, 'health.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'NURSE')
  and not ('health.manage' = any (permissions));

-- safety.manage: drills, safety audits, incidents, first-aid kits, the POCSO committee, staff verifications and training.
update identity.role
set permissions = array_append(permissions, 'safety.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('safety.manage' = any (permissions));

-- pocso.committee: the complaints register. Only the Principal by default; other committee members are named in
-- health.pocso_member, which grants the same access without a role change.
update identity.role
set permissions = array_append(permissions, 'pocso.committee')
where system_role
  and code = 'PRINCIPAL'
  and not ('pocso.committee' = any (permissions));
