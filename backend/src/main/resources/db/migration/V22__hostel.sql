-- Phase 3, hostel: hostels with their blocks, rooms and beds; boarders' bed allotments with their history; the
-- weekly mess menu and the daily mess headcount; outing and home-leave passes a parent asks for and the warden
-- decides and releases against; the night roll call with its alerts; and complaints and maintenance requests per
-- room. Every table carries tenant_id and the same tenant_isolation policy as V2/V3; every link between school tables
-- is a composite (tenant_id, id) foreign key. Also adds the built-in Warden role and the hostel permissions.
--
-- Personal data: only ids and names of boarders and wardens are kept here; parents' phone numbers are read from the
-- students module when a message is queued and never stored in this schema.

create schema if not exists hostel;

-- ---------------------------------------------------------------- hostels, blocks, rooms and beds

create table hostel.hostel (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    name            varchar(120) not null,
    type            varchar(10)  not null,
    -- The member of staff who runs the hostel day to day (hostel.warden); null until one is assigned.
    warden_user_id  uuid,
    address         varchar(300),
    active          boolean      not null default true,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint uq_hostel_tenant_id unique (tenant_id, id),
    constraint uq_hostel_name unique (tenant_id, name),
    constraint fk_hostel_warden foreign key (tenant_id, warden_user_id)
        references identity.user_account (tenant_id, id) on delete set null (warden_user_id),
    constraint ck_hostel_type check (type in ('BOYS', 'GIRLS', 'STAFF'))
);
create index ix_hostel_warden on hostel.hostel (warden_user_id);

create table hostel.block (
    id          uuid primary key,
    tenant_id   uuid         not null references platform.tenant (id),
    hostel_id   uuid         not null,
    name        varchar(80)  not null,
    floors      integer      not null default 1,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    version     bigint       not null default 0,
    constraint uq_block_tenant_id unique (tenant_id, id),
    constraint uq_block_name unique (tenant_id, hostel_id, name),
    constraint fk_block_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id),
    constraint ck_block_floors check (floors between 1 and 30)
);
create index ix_block_hostel on hostel.block (hostel_id);

create table hostel.room (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    hostel_id          uuid         not null,
    block_id           uuid         not null,
    floor              integer      not null default 1,
    number             varchar(20)  not null,
    capacity           integer      not null,
    type               varchar(12)  not null,
    -- The monthly hostel fee of the room in paise; the fees module is told through the HostelAllotted event.
    monthly_fee_paise  bigint       not null default 0,
    active             boolean      not null default true,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    constraint uq_room_tenant_id unique (tenant_id, id),
    constraint uq_room_number unique (tenant_id, block_id, number),
    constraint fk_room_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id),
    constraint fk_room_block foreign key (tenant_id, block_id) references hostel.block (tenant_id, id),
    constraint ck_room_floor check (floor between 0 and 30),
    constraint ck_room_capacity check (capacity between 1 and 40),
    constraint ck_room_type check (type in ('SINGLE', 'DOUBLE', 'TRIPLE', 'DORMITORY')),
    constraint ck_room_fee check (monthly_fee_paise >= 0)
);
create index ix_room_hostel on hostel.room (hostel_id);
create index ix_room_block on hostel.room (block_id);

create table hostel.bed (
    id          uuid primary key,
    tenant_id   uuid         not null references platform.tenant (id),
    hostel_id   uuid         not null,
    room_id     uuid         not null,
    label       varchar(20)  not null,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    constraint uq_bed_tenant_id unique (tenant_id, id),
    constraint uq_bed_label unique (tenant_id, room_id, label),
    constraint fk_bed_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id),
    constraint fk_bed_room foreign key (tenant_id, room_id) references hostel.room (tenant_id, id)
);
create index ix_bed_room on hostel.bed (room_id);

-- ---------------------------------------------------------------- allotments

-- A boarder's stay in one bed. A room change closes the old row (reason ROOM_CHANGE) and opens a new one, so the
-- rows of a student are their room history. Rows are never deleted.
create table hostel.allotment (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    student_id        uuid         not null,
    hostel_id         uuid         not null,
    room_id           uuid         not null,
    bed_id            uuid         not null,
    from_date         date         not null,
    to_date           date,
    guardian_consent  boolean      not null default false,
    status            varchar(10)  not null,
    vacate_reason     varchar(300),
    allotted_by_name  varchar(200),
    vacated_by_name   varchar(200),
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint uq_allotment_tenant_id unique (tenant_id, id),
    constraint fk_allotment_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_allotment_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id),
    constraint fk_allotment_room foreign key (tenant_id, room_id) references hostel.room (tenant_id, id),
    constraint fk_allotment_bed foreign key (tenant_id, bed_id) references hostel.bed (tenant_id, id),
    constraint ck_allotment_status check (status in ('ACTIVE', 'VACATED')),
    constraint ck_allotment_dates check (to_date is null or to_date >= from_date),
    constraint ck_allotment_vacated check ((status = 'VACATED') = (to_date is not null))
);
create unique index uq_allotment_active_bed on hostel.allotment (tenant_id, bed_id) where status = 'ACTIVE';
create unique index uq_allotment_active_student on hostel.allotment (tenant_id, student_id) where status = 'ACTIVE';
create index ix_allotment_student on hostel.allotment (student_id);
create index ix_allotment_hostel on hostel.allotment (hostel_id, status);

-- ---------------------------------------------------------------- mess

-- The weekly menu: one dish per hostel, weekday (1 = Monday) and meal.
create table hostel.menu_item (
    id           uuid primary key,
    tenant_id    uuid         not null references platform.tenant (id),
    hostel_id    uuid         not null,
    day_of_week  integer      not null,
    meal         varchar(10)  not null,
    dish         varchar(200) not null,
    veg          boolean      not null default true,
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    constraint uq_menu_item_tenant_id unique (tenant_id, id),
    constraint uq_menu_item_slot unique (tenant_id, hostel_id, day_of_week, meal),
    constraint fk_menu_item_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id),
    constraint ck_menu_item_day check (day_of_week between 1 and 7),
    constraint ck_menu_item_meal check (meal in ('BREAKFAST', 'LUNCH', 'SNACKS', 'DINNER'))
);

-- How many boarders ate each meal, counted by the warden.
create table hostel.mess_attendance (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    hostel_id       uuid         not null,
    day             date         not null,
    meal            varchar(10)  not null,
    head_count      integer      not null,
    marked_by_name  varchar(200),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    constraint uq_mess_attendance_tenant_id unique (tenant_id, id),
    constraint uq_mess_attendance_slot unique (tenant_id, hostel_id, day, meal),
    constraint fk_mess_attendance_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id),
    constraint ck_mess_attendance_meal check (meal in ('BREAKFAST', 'LUNCH', 'SNACKS', 'DINNER')),
    constraint ck_mess_attendance_count check (head_count between 0 and 5000)
);

-- ---------------------------------------------------------------- outing and home-leave passes

create table hostel.pass (
    id                   uuid primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    student_id           uuid         not null,
    hostel_id            uuid         not null,
    -- OUTING: a day pass (from_date = to_date); HOME_LEAVE: nights away at home.
    kind                 varchar(10)  not null,
    source               varchar(12)  not null,
    requested_by_user_id uuid,
    requested_by_name    varchar(200),
    from_date            date         not null,
    to_date              date         not null,
    -- When the boarder must be back; a return after it is flagged late.
    return_by            timestamptz  not null,
    reason               varchar(500) not null,
    status               varchar(10)  not null,
    decided_by_name      varchar(200),
    decided_at           timestamptz,
    decision_note        varchar(500),
    out_at               timestamptz,
    out_by_name          varchar(200),
    in_at                timestamptz,
    in_by_name           varchar(200),
    late                 boolean      not null default false,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint uq_pass_tenant_id unique (tenant_id, id),
    constraint fk_pass_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_pass_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id),
    constraint fk_pass_requested_by foreign key (tenant_id, requested_by_user_id)
        references identity.user_account (tenant_id, id) on delete set null (requested_by_user_id),
    constraint ck_pass_kind check (kind in ('OUTING', 'HOME_LEAVE')),
    constraint ck_pass_source check (source in ('PARENT_APP', 'WARDEN')),
    constraint ck_pass_dates check (to_date >= from_date),
    constraint ck_pass_status check (status in ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'OUT', 'RETURNED')),
    constraint ck_pass_out check (status not in ('OUT', 'RETURNED') or out_at is not null),
    constraint ck_pass_in check ((status = 'RETURNED') = (in_at is not null))
);
create index ix_pass_student on hostel.pass (student_id);
create index ix_pass_hostel_day on hostel.pass (hostel_id, from_date);
create index ix_pass_open on hostel.pass (tenant_id, status) where status in ('REQUESTED', 'APPROVED', 'OUT');
create index ix_pass_requested_by on hostel.pass (requested_by_user_id);

-- ---------------------------------------------------------------- night roll call

create table hostel.roll_call (
    id             uuid primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    hostel_id      uuid         not null,
    night          date         not null,
    taken_by_name  varchar(200),
    taken_at       timestamptz  not null,
    present        integer      not null default 0,
    on_leave       integer      not null default 0,
    absent         integer      not null default 0,
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    version        bigint       not null default 0,
    constraint uq_roll_call_tenant_id unique (tenant_id, id),
    constraint uq_roll_call_night unique (tenant_id, hostel_id, night),
    constraint fk_roll_call_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id)
);

create table hostel.roll_call_entry (
    id            uuid primary key,
    tenant_id     uuid         not null references platform.tenant (id),
    roll_call_id  uuid         not null,
    student_id    uuid         not null,
    status        varchar(10)  not null,
    note          varchar(300),
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint uq_roll_call_entry_tenant_id unique (tenant_id, id),
    constraint uq_roll_call_entry_student unique (tenant_id, roll_call_id, student_id),
    constraint fk_roll_call_entry_roll_call foreign key (tenant_id, roll_call_id)
        references hostel.roll_call (tenant_id, id) on delete cascade,
    constraint fk_roll_call_entry_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint ck_roll_call_entry_status check (status in ('PRESENT', 'ON_LEAVE', 'ABSENT'))
);
create index ix_roll_call_entry_student on hostel.roll_call_entry (student_id);

-- A boarder missing at roll call, or back late from a pass: shown to the warden until resolved.
create table hostel.alert (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    hostel_id         uuid         not null,
    student_id        uuid         not null,
    kind              varchar(20)  not null,
    day               date         not null,
    message           varchar(300) not null,
    resolved          boolean      not null default false,
    resolved_by_name  varchar(200),
    resolved_at       timestamptz,
    resolution_note   varchar(300),
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint uq_alert_tenant_id unique (tenant_id, id),
    constraint fk_alert_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id),
    constraint fk_alert_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint ck_alert_kind check (kind in ('ABSENT_AT_ROLL_CALL', 'LATE_RETURN'))
);
create index ix_alert_open on hostel.alert (hostel_id, resolved, day);
create index ix_alert_student on hostel.alert (student_id);

-- ---------------------------------------------------------------- complaints and maintenance requests

create table hostel.complaint (
    id                 uuid primary key,
    tenant_id          uuid          not null references platform.tenant (id),
    hostel_id          uuid          not null,
    room_id            uuid          not null,
    category           varchar(12)   not null,
    description        varchar(1000) not null,
    status             varchar(12)   not null,
    raised_by_name     varchar(200),
    resolution_note    varchar(500),
    resolved_at        timestamptz,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    version            bigint        not null default 0,
    constraint uq_complaint_tenant_id unique (tenant_id, id),
    constraint fk_complaint_hostel foreign key (tenant_id, hostel_id) references hostel.hostel (tenant_id, id),
    constraint fk_complaint_room foreign key (tenant_id, room_id) references hostel.room (tenant_id, id),
    constraint ck_complaint_category check (category in ('PLUMBING', 'ELECTRICAL', 'FURNITURE', 'CLEANING', 'OTHER')),
    constraint ck_complaint_status check (status in ('OPEN', 'IN_PROGRESS', 'RESOLVED'))
);
create index ix_complaint_hostel on hostel.complaint (hostel_id, status);

-- ---------------------------------------------------------------- row-level security

alter table hostel.hostel enable row level security;
create policy tenant_isolation on hostel.hostel
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.block enable row level security;
create policy tenant_isolation on hostel.block
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.room enable row level security;
create policy tenant_isolation on hostel.room
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.bed enable row level security;
create policy tenant_isolation on hostel.bed
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.allotment enable row level security;
create policy tenant_isolation on hostel.allotment
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.menu_item enable row level security;
create policy tenant_isolation on hostel.menu_item
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.mess_attendance enable row level security;
create policy tenant_isolation on hostel.mess_attendance
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.pass enable row level security;
create policy tenant_isolation on hostel.pass
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.roll_call enable row level security;
create policy tenant_isolation on hostel.roll_call
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.roll_call_entry enable row level security;
create policy tenant_isolation on hostel.roll_call_entry
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.alert enable row level security;
create policy tenant_isolation on hostel.alert
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table hostel.complaint enable row level security;
create policy tenant_isolation on hostel.complaint
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema hostel to ${appRole};
grant select, insert, update, delete on hostel.hostel, hostel.block, hostel.room, hostel.bed, hostel.menu_item,
    hostel.mess_attendance, hostel.roll_call_entry, hostel.complaint to ${appRole};
-- Allotments, passes, roll calls and alerts are the hostel's register of who was where: never deleted.
grant select, insert, update on hostel.allotment, hostel.pass, hostel.roll_call, hostel.alert to ${appRole};

-- ---------------------------------------------------------------- the Warden role and the hostel permissions

-- New schools get the Warden role and these permissions from RoleCatalog. Existing schools keep their stored roles,
-- so the built-in Warden role is added to every school that has none, and the new codes are appended to the matching
-- built-in roles, once. Runs as the owner, which row-level security does not restrict. Idempotent.
insert into identity.role (id, tenant_id, code, name, permissions, system_role, created_at)
select gen_random_uuid(), t.id, 'WARDEN', 'Warden',
       array['dashboard.view', 'leave.request', 'notices.read', 'hostel.warden'],
       true, now()
from platform.tenant t
where not exists (select 1 from identity.role r where r.tenant_id = t.id and r.code = 'WARDEN');

-- hostel.read: hostels, rooms, the occupancy board, boarders, passes, roll calls, complaints and the reports.
update identity.role
set permissions = array_append(permissions, 'hostel.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('hostel.read' = any (permissions));

-- hostel.manage: setting up hostels, blocks, rooms and beds, assigning wardens, allotting and vacating boarders.
update identity.role
set permissions = array_append(permissions, 'hostel.manage')
where system_role
  and code = 'SCHOOL_ADMIN'
  and not ('hostel.manage' = any (permissions));

-- hostel.warden: the day-to-day of one's own hostel on a phone: roll call, passes, complaints, mess attendance.
update identity.role
set permissions = array_append(permissions, 'hostel.warden')
where system_role
  and code in ('SCHOOL_ADMIN', 'WARDEN')
  and not ('hostel.warden' = any (permissions));
