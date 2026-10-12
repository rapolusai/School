-- Phase 2, front office: the visitor log (with a photo kept through the files module, pre-approved visitors and a
-- blocklist), early-pickup gate passes released with a parent's OTP, the pickup persons a parent authorises, the
-- call and enquiry logs with their follow-ups, and the postal and courier register. Every table carries tenant_id
-- and the same tenant_isolation policy as V2/V3; every link between school tables is a composite (tenant_id, id)
-- foreign key. Also adds the built-in Security role and the front office permissions.
--
-- Personal data: visitors' and callers' phone numbers are kept (they are needed to call back and to match the
-- blocklist) and masked in lists; only the kind of identity document a visitor showed is kept, never its number.
-- An OTP is stored only as a salted hash and is never readable again.

create schema if not exists frontoffice;

-- ---------------------------------------------------------------- visitors

create table frontoffice.visitor (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    name             varchar(200) not null,
    phone            varchar(15),
    purpose          varchar(30)  not null,
    purpose_note     varchar(300),
    -- Whom they came to meet: a member of staff (kept by id while the sign-in exists) and the name as typed.
    to_meet_user_id  uuid,
    to_meet_name     varchar(200),
    people_count     integer      not null default 1,
    id_type          varchar(20)  not null default 'NONE',
    badge_no         varchar(20),
    photo_file_id    uuid,
    -- EXPECTED (pre-approved for expected_on), IN (checked in) or OUT (checked out).
    status           varchar(10)  not null,
    expected_on      date,
    checked_in_at    timestamptz,
    checked_out_at   timestamptz,
    added_by_name    varchar(200),
    checked_in_by_name  varchar(200),
    checked_out_by_name varchar(200),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_visitor_tenant_id unique (tenant_id, id),
    constraint fk_visitor_to_meet foreign key (tenant_id, to_meet_user_id)
        references identity.user_account (tenant_id, id) on delete set null (to_meet_user_id),
    constraint ck_visitor_phone check (phone is null or phone ~ '^[0-9]{10}$'),
    constraint ck_visitor_purpose check (purpose in ('MEETING', 'ADMISSION', 'FEES', 'DELIVERY', 'VENDOR',
        'INTERVIEW', 'OFFICIAL', 'OTHER')),
    constraint ck_visitor_people check (people_count between 1 and 50),
    constraint ck_visitor_id_type check (id_type in ('NONE', 'AADHAAR', 'VOTER_ID', 'DRIVING_LICENCE', 'PAN',
        'PASSPORT', 'COMPANY_ID', 'OTHER')),
    constraint ck_visitor_status check (status in ('EXPECTED', 'IN', 'OUT')),
    constraint ck_visitor_times check (
        (status = 'EXPECTED' and expected_on is not null and checked_in_at is null and checked_out_at is null)
        or (status = 'IN' and checked_in_at is not null and checked_out_at is null)
        or (status = 'OUT' and checked_in_at is not null and checked_out_at is not null
            and checked_out_at >= checked_in_at))
);
create index ix_visitor_day on frontoffice.visitor (tenant_id, checked_in_at);
create index ix_visitor_expected on frontoffice.visitor (tenant_id, expected_on) where status = 'EXPECTED';
create index ix_visitor_to_meet on frontoffice.visitor (to_meet_user_id);

-- People the school will not let in, with the reason (every change is audited).
create table frontoffice.blocked_visitor (
    id            uuid primary key,
    tenant_id     uuid         not null references platform.tenant (id),
    name          varchar(200) not null,
    phone         varchar(15),
    reason        varchar(500) not null,
    added_by_name varchar(200),
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0,
    constraint uq_blocked_visitor_tenant_id unique (tenant_id, id),
    constraint ck_blocked_visitor_phone check (phone is null or phone ~ '^[0-9]{10}$')
);
create index ix_blocked_visitor_phone on frontoffice.blocked_visitor (tenant_id, phone) where phone is not null;

-- ---------------------------------------------------------------- early pickup gate passes

create table frontoffice.gate_pass (
    id                   uuid primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    student_id           uuid         not null,
    -- PARENT_APP when the parent asked in the app; OFFICE when the office raised it on the parent's phone call.
    source               varchar(12)  not null,
    requested_by_user_id uuid,
    requested_by_name    varchar(200),
    pickup_at            timestamptz  not null,
    reason               varchar(500) not null,
    -- SELF: the parent collects; OTHER: a named person with a phone number.
    collector_kind       varchar(10)  not null,
    collector_name       varchar(200),
    collector_phone      varchar(15),
    status               varchar(10)  not null,
    -- The OTP is kept only as a salted SHA-256 hash; wrong_attempts counts failed tries at the gate.
    otp_hash             varchar(64),
    otp_expires_at       timestamptz,
    wrong_attempts       integer      not null default 0,
    approved_by_name     varchar(200),
    approved_at          timestamptz,
    decision_note        varchar(500),
    released_by_name     varchar(200),
    released_at          timestamptz,
    collected_by_name    varchar(200),
    closed_at            timestamptz,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint uq_gate_pass_tenant_id unique (tenant_id, id),
    constraint fk_gate_pass_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_gate_pass_requested_by foreign key (tenant_id, requested_by_user_id)
        references identity.user_account (tenant_id, id) on delete set null (requested_by_user_id),
    constraint ck_gate_pass_source check (source in ('PARENT_APP', 'OFFICE')),
    constraint ck_gate_pass_collector check (collector_kind in ('SELF', 'OTHER')),
    constraint ck_gate_pass_collector_named check (collector_kind = 'SELF' or collector_name is not null),
    constraint ck_gate_pass_phone check (collector_phone is null or collector_phone ~ '^[0-9]{10}$'),
    constraint ck_gate_pass_status check (status in ('REQUESTED', 'APPROVED', 'RELEASED', 'REJECTED', 'CANCELLED',
        'EXPIRED', 'LOCKED')),
    constraint ck_gate_pass_attempts check (wrong_attempts between 0 and 5),
    constraint ck_gate_pass_otp check (status = 'REQUESTED' or (otp_hash is not null and otp_expires_at is not null)
        or status in ('REJECTED', 'CANCELLED')),
    constraint ck_gate_pass_released check ((status = 'RELEASED') = (released_at is not null))
);
create index ix_gate_pass_student on frontoffice.gate_pass (student_id);
create index ix_gate_pass_day on frontoffice.gate_pass (tenant_id, pickup_at);
create index ix_gate_pass_open on frontoffice.gate_pass (tenant_id, status) where status in ('REQUESTED', 'APPROVED');
create index ix_gate_pass_requested_by on frontoffice.gate_pass (requested_by_user_id);

-- People a parent authorises to collect the child routinely (shown to the gate with a photo if one was given).
create table frontoffice.pickup_person (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    student_id         uuid         not null,
    name               varchar(200) not null,
    relation           varchar(60)  not null,
    phone              varchar(15)  not null,
    photo_file_id      uuid,
    added_by_user_id   uuid,
    added_by_name      varchar(200),
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    constraint uq_pickup_person_tenant_id unique (tenant_id, id),
    constraint fk_pickup_person_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_pickup_person_added_by foreign key (tenant_id, added_by_user_id)
        references identity.user_account (tenant_id, id) on delete set null (added_by_user_id),
    constraint ck_pickup_person_phone check (phone ~ '^[0-9]{10}$')
);
create index ix_pickup_person_student on frontoffice.pickup_person (student_id);
create index ix_pickup_person_added_by on frontoffice.pickup_person (added_by_user_id);

-- ---------------------------------------------------------------- calls and enquiries

create table frontoffice.call_log (
    id                  uuid primary key,
    tenant_id           uuid         not null references platform.tenant (id),
    direction           varchar(10)  not null,
    caller_name         varchar(200) not null,
    phone               varchar(15),
    about               varchar(500) not null,
    outcome             varchar(500),
    follow_up_on        date,
    assigned_to_user_id uuid,
    assigned_to_name    varchar(200),
    status              varchar(10)  not null,
    logged_by_name      varchar(200),
    called_at           timestamptz  not null,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    version             bigint       not null default 0,
    constraint uq_call_log_tenant_id unique (tenant_id, id),
    constraint fk_call_log_assigned_to foreign key (tenant_id, assigned_to_user_id)
        references identity.user_account (tenant_id, id) on delete set null (assigned_to_user_id),
    constraint ck_call_log_direction check (direction in ('INCOMING', 'OUTGOING')),
    constraint ck_call_log_phone check (phone is null or phone ~ '^[0-9]{10}$'),
    constraint ck_call_log_status check (status in ('OPEN', 'DONE'))
);
create index ix_call_log_day on frontoffice.call_log (tenant_id, called_at);
create index ix_call_log_follow_up on frontoffice.call_log (tenant_id, follow_up_on) where status = 'OPEN';
create index ix_call_log_assigned_to on frontoffice.call_log (assigned_to_user_id);

-- Walk-in and phone enquiries the office answers. An admission enquiry can be converted into an application in the
-- admissions module (application_id); the admissions funnel itself stays in that module.
create table frontoffice.enquiry (
    id                  uuid primary key,
    tenant_id           uuid         not null references platform.tenant (id),
    source              varchar(10)  not null,
    topic               varchar(10)  not null,
    name                varchar(200) not null,
    phone               varchar(15),
    child_name          varchar(200),
    class_name          varchar(40),
    details             varchar(2000) not null,
    status              varchar(10)  not null,
    outcome             varchar(500),
    follow_up_on        date,
    assigned_to_user_id uuid,
    assigned_to_name    varchar(200),
    application_id      uuid,
    logged_by_name      varchar(200),
    enquired_at         timestamptz  not null,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    version             bigint       not null default 0,
    constraint uq_enquiry_tenant_id unique (tenant_id, id),
    constraint fk_enquiry_assigned_to foreign key (tenant_id, assigned_to_user_id)
        references identity.user_account (tenant_id, id) on delete set null (assigned_to_user_id),
    constraint fk_enquiry_application foreign key (tenant_id, application_id)
        references admissions.application (tenant_id, id) on delete set null (application_id),
    constraint ck_enquiry_source check (source in ('WALK_IN', 'PHONE')),
    constraint ck_enquiry_topic check (topic in ('ADMISSION', 'FEES', 'GENERAL')),
    constraint ck_enquiry_phone check (phone is null or phone ~ '^[0-9]{10}$'),
    constraint ck_enquiry_status check (status in ('OPEN', 'FOLLOW_UP', 'CLOSED', 'CONVERTED'))
);
create index ix_enquiry_day on frontoffice.enquiry (tenant_id, enquired_at);
create index ix_enquiry_follow_up on frontoffice.enquiry (tenant_id, follow_up_on)
    where status in ('OPEN', 'FOLLOW_UP');
create index ix_enquiry_assigned_to on frontoffice.enquiry (assigned_to_user_id);
create index ix_enquiry_application on frontoffice.enquiry (application_id);

-- ---------------------------------------------------------------- postal and courier register

create table frontoffice.courier_item (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    direction        varchar(10)  not null,
    item             varchar(300) not null,
    sender           varchar(200) not null,
    receiver         varchar(200) not null,
    item_date        date         not null,
    carrier          varchar(100),
    tracking_no      varchar(60),
    -- The typed name of the person who received (inward) or handed over (outward) the item, as a signature.
    received_by      varchar(200),
    note             varchar(500),
    logged_by_name   varchar(200),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_courier_item_tenant_id unique (tenant_id, id),
    constraint ck_courier_item_direction check (direction in ('INWARD', 'OUTWARD'))
);
create index ix_courier_item_day on frontoffice.courier_item (tenant_id, item_date);

-- ---------------------------------------------------------------- row-level security

alter table frontoffice.visitor enable row level security;
create policy tenant_isolation on frontoffice.visitor
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table frontoffice.blocked_visitor enable row level security;
create policy tenant_isolation on frontoffice.blocked_visitor
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table frontoffice.gate_pass enable row level security;
create policy tenant_isolation on frontoffice.gate_pass
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table frontoffice.pickup_person enable row level security;
create policy tenant_isolation on frontoffice.pickup_person
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table frontoffice.call_log enable row level security;
create policy tenant_isolation on frontoffice.call_log
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table frontoffice.enquiry enable row level security;
create policy tenant_isolation on frontoffice.enquiry
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table frontoffice.courier_item enable row level security;
create policy tenant_isolation on frontoffice.courier_item
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema frontoffice to ${appRole};
grant select, insert, update, delete on frontoffice.blocked_visitor, frontoffice.pickup_person, frontoffice.call_log,
    frontoffice.enquiry, frontoffice.courier_item to ${appRole};
-- The visitor log and gate passes are the school's security record: never deleted.
grant select, insert, update on frontoffice.visitor, frontoffice.gate_pass to ${appRole};

-- ---------------------------------------------------------------- the Security role and the front office permissions

-- New schools get the Security role and these permissions from RoleCatalog. Existing schools keep their stored
-- roles, so the built-in Security role is added to every school that has none, and the new codes are appended to the
-- matching built-in roles, once. Runs as the owner, which row-level security does not restrict. Idempotent.
insert into identity.role (id, tenant_id, code, name, permissions, system_role, created_at)
select gen_random_uuid(), t.id, 'SECURITY', 'Security',
       array['dashboard.view', 'leave.request', 'notices.read', 'gate.pass'],
       true, now()
from platform.tenant t
where not exists (select 1 from identity.role r where r.tenant_id = t.id and r.code = 'SECURITY');

-- frontoffice.read: the visitor log, gate passes, enquiries, calls, the courier register and their reports.
update identity.role
set permissions = array_append(permissions, 'frontoffice.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'FRONT_OFFICE')
  and not ('frontoffice.read' = any (permissions));

-- frontoffice.manage: recording visitors, raising and approving gate passes, logging calls, enquiries and post.
update identity.role
set permissions = array_append(permissions, 'frontoffice.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'FRONT_OFFICE')
  and not ('frontoffice.manage' = any (permissions));

-- gate.pass: the gate screen, which checks visitors in and out and releases children against a parent's OTP.
update identity.role
set permissions = array_append(permissions, 'gate.pass')
where system_role
  and code in ('SCHOOL_ADMIN', 'FRONT_OFFICE', 'SECURITY')
  and not ('gate.pass' = any (permissions));
