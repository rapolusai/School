-- Phase 2, transport: the school's vehicles with their document expiry dates, drivers and attendants (members of
-- staff, only their ids plus licence and police verification details are kept here), routes with their ordered stops
-- and fee zones, students allocated to a route and stop, and the daily trips run from the attendant's phone: who
-- boarded and was dropped where, the bus's latest position and a log of positions. GPS devices fitted in a vehicle
-- post positions with a per-vehicle token (only its SHA-256 hash is stored). Transport fees are charged through the
-- fees module (student dues), so nothing about money is kept here except each fee zone's annual fee.
-- Every table carries tenant_id and the same tenant_isolation policy as V2/V3, and every link between school tables is
-- a composite (tenant_id, id) foreign key. Also adds the built-in Transport manager and Bus attendant roles and the
-- transport permissions.

create schema if not exists transport;

-- ---------------------------------------------------------------- settings (one row per school, made on first use)

-- Which messages parents get through the notifications outbox (channel, language and quiet hours are the school's
-- message settings, as for absence alerts).
create table transport.settings (
    id                 uuid primary key,
    tenant_id          uuid        not null references platform.tenant (id),
    boarded_alerts     boolean     not null default true,
    dropped_alerts     boolean     not null default true,
    approaching_alerts boolean     not null default false,
    delay_alerts       boolean     not null default true,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    version            bigint      not null default 0,
    constraint uq_transport_settings_tenant unique (tenant_id)
);

-- ---------------------------------------------------------------- fee zones (distance slabs)

-- A stop's distance slab decides the student's transport fee: the annual fee, split evenly over the instalments of the
-- student's fee structure that fall within the allocation.
create table transport.fee_zone (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    name             varchar(60)  not null,
    min_km           numeric(5,1) not null,
    max_km           numeric(5,1),
    annual_fee_paise bigint       not null,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_fee_zone_tenant_id unique (tenant_id, id),
    constraint ck_fee_zone_km check (min_km >= 0 and (max_km is null or max_km > min_km)),
    constraint ck_fee_zone_fee check (annual_fee_paise between 0 and 100000000)
);
create unique index uq_fee_zone_name on transport.fee_zone (tenant_id, lower(name));

-- ---------------------------------------------------------------- vehicles

create table transport.vehicle (
    id                     uuid primary key,
    tenant_id              uuid          not null references platform.tenant (id),
    registration_no        varchar(20)   not null,
    vehicle_type           varchar(10)   not null,
    seats                  integer       not null,
    gps_device_id          varchar(60),
    insurance_expiry       date,
    fitness_expiry         date,
    permit_expiry          date,
    pollution_expiry       date,
    status                 varchar(12)   not null,
    -- The GPS device's token: only its SHA-256 (hex) and the last four characters, to recognise it, are kept.
    device_token_hash      varchar(64),
    device_token_hint      varchar(4),
    device_token_issued_at timestamptz,
    -- The latest position from the attendant's phone or the device, trip or not.
    last_latitude          numeric(9,6),
    last_longitude         numeric(9,6),
    last_position_at       timestamptz,
    created_at             timestamptz   not null,
    updated_at             timestamptz   not null,
    version                bigint        not null default 0,
    constraint uq_vehicle_tenant_id unique (tenant_id, id),
    constraint ck_vehicle_registration check (registration_no ~ '^[A-Z0-9][A-Z0-9 -]{2,18}[A-Z0-9]$'),
    constraint ck_vehicle_type check (vehicle_type in ('BUS', 'MINI_BUS', 'VAN', 'CAR')),
    constraint ck_vehicle_seats check (seats between 1 and 100),
    constraint ck_vehicle_status check (status in ('ACTIVE', 'MAINTENANCE', 'RETIRED')),
    constraint ck_vehicle_token check ((device_token_hash is null) = (device_token_issued_at is null)
        and (device_token_hash is null or device_token_hash ~ '^[0-9a-f]{64}$')),
    constraint ck_vehicle_position check ((last_latitude is null) = (last_position_at is null)
        and (last_longitude is null) = (last_position_at is null)
        and (last_latitude is null or last_latitude between -90 and 90)
        and (last_longitude is null or last_longitude between -180 and 180))
);
create unique index uq_vehicle_registration on transport.vehicle (tenant_id, registration_no);
create unique index uq_vehicle_device_token on transport.vehicle (device_token_hash) where device_token_hash is not null;

-- ---------------------------------------------------------------- drivers and attendants (members of staff)

create table transport.crew_member (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    user_id            uuid         not null,
    kind               varchar(10)  not null,
    licence_no         varchar(30),
    licence_expiry     date,
    police_verified_on date,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    constraint uq_crew_member_tenant_id unique (tenant_id, id),
    constraint uq_crew_member_user unique (tenant_id, user_id),
    constraint fk_crew_member_user foreign key (tenant_id, user_id) references identity.user_account (tenant_id, id),
    constraint ck_crew_member_kind check (kind in ('DRIVER', 'ATTENDANT')),
    constraint ck_crew_member_licence check (kind <> 'DRIVER' or (licence_no is not null and licence_expiry is not null)),
    constraint ck_crew_member_licence_no check (licence_no is null or licence_no ~ '^[A-Z0-9 /-]{5,30}$')
);

-- ---------------------------------------------------------------- routes and their stops

create table transport.route (
    id             uuid primary key,
    tenant_id      uuid         not null references platform.tenant (id),
    name           varchar(60)  not null,
    vehicle_id     uuid,
    driver_id      uuid,
    attendant_id   uuid,
    runs_morning   boolean      not null default true,
    runs_afternoon boolean      not null default true,
    active         boolean      not null default true,
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    version        bigint       not null default 0,
    constraint uq_route_tenant_id unique (tenant_id, id),
    constraint fk_route_vehicle foreign key (tenant_id, vehicle_id) references transport.vehicle (tenant_id, id),
    constraint fk_route_driver foreign key (tenant_id, driver_id)
        references transport.crew_member (tenant_id, id) on delete set null (driver_id),
    constraint fk_route_attendant foreign key (tenant_id, attendant_id)
        references transport.crew_member (tenant_id, id) on delete set null (attendant_id),
    constraint ck_route_trips check (runs_morning or runs_afternoon)
);
create unique index uq_route_name on transport.route (tenant_id, lower(name));
create index ix_route_vehicle on transport.route (vehicle_id);
create index ix_route_driver on transport.route (driver_id);
create index ix_route_attendant on transport.route (attendant_id);

-- Stops in pickup order (seq 1 is the first pickup in the morning; the afternoon trip runs them in reverse).
create table transport.stop (
    id          uuid primary key,
    tenant_id   uuid          not null references platform.tenant (id),
    route_id    uuid          not null,
    seq         integer       not null,
    name        varchar(100)  not null,
    landmark    varchar(150),
    pickup_time time,
    drop_time   time,
    latitude    numeric(9,6),
    longitude   numeric(9,6),
    fee_zone_id uuid,
    created_at  timestamptz   not null,
    updated_at  timestamptz   not null,
    version     bigint        not null default 0,
    constraint uq_stop_tenant_id unique (tenant_id, id),
    constraint uq_stop_route_id unique (tenant_id, route_id, id),
    -- Checked at commit, so a route's stops can be renumbered in one transaction.
    constraint uq_stop_seq unique (route_id, seq) deferrable initially deferred,
    constraint fk_stop_route foreign key (tenant_id, route_id) references transport.route (tenant_id, id),
    constraint fk_stop_fee_zone foreign key (tenant_id, fee_zone_id) references transport.fee_zone (tenant_id, id),
    constraint ck_stop_seq check (seq between 1 and 100),
    constraint ck_stop_position check ((latitude is null) = (longitude is null)
        and (latitude is null or latitude between -90 and 90)
        and (longitude is null or longitude between -180 and 180))
);
create index ix_stop_fee_zone on transport.stop (fee_zone_id);

-- ---------------------------------------------------------------- students on routes

-- A student's place on a route and stop from start_date (to end_date when set). At most one ACTIVE allocation per
-- student; leaving transport ends it (ENDED, with its end date). Allocating beyond the vehicle's seats needs a reason.
create table transport.allocation (
    id                   uuid primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    student_id           uuid         not null,
    route_id             uuid         not null,
    -- Cleared only when the route's stop is removed after the allocation ended.
    stop_id              uuid,
    stop_name            varchar(100) not null,
    start_date           date         not null,
    end_date             date,
    status               varchar(10)  not null,
    over_capacity_reason varchar(300),
    end_reason           varchar(300),
    created_by_id        uuid,
    created_by_name      varchar(200),
    ended_by_id          uuid,
    ended_by_name        varchar(200),
    ended_at             timestamptz,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint uq_allocation_tenant_id unique (tenant_id, id),
    constraint fk_allocation_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_allocation_route foreign key (tenant_id, route_id) references transport.route (tenant_id, id),
    constraint fk_allocation_stop foreign key (tenant_id, route_id, stop_id)
        references transport.stop (tenant_id, route_id, id) on delete set null (stop_id),
    constraint fk_allocation_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id),
    constraint fk_allocation_ended_by foreign key (tenant_id, ended_by_id)
        references identity.user_account (tenant_id, id) on delete set null (ended_by_id),
    constraint ck_allocation_status check (status in ('ACTIVE', 'ENDED')),
    constraint ck_allocation_dates check (end_date is null or end_date >= start_date),
    constraint ck_allocation_active_stop check (status <> 'ACTIVE' or stop_id is not null),
    constraint ck_allocation_ended check (((status = 'ENDED') = (ended_at is not null))
        and (status <> 'ENDED' or end_date is not null))
);
create unique index uq_allocation_active_student on transport.allocation (tenant_id, student_id)
    where status = 'ACTIVE';
create index ix_allocation_route on transport.allocation (tenant_id, route_id, status);
create index ix_allocation_student on transport.allocation (student_id);
create index ix_allocation_stop on transport.allocation (stop_id);
create index ix_allocation_created_by on transport.allocation (created_by_id);
create index ix_allocation_ended_by on transport.allocation (ended_by_id);

-- ---------------------------------------------------------------- trips

-- One run of a route on a day: MORNING (pickup, stops in order) or AFTERNOON (drop, stops in reverse). Started from
-- the attendant's phone; reached_stop_id is the furthest stop the bus has reached (by position or by a mark there).
create table transport.trip (
    id                   uuid primary key,
    tenant_id            uuid          not null references platform.tenant (id),
    route_id             uuid          not null,
    trip_date            date          not null,
    kind                 varchar(10)   not null,
    status               varchar(10)   not null,
    vehicle_id           uuid,
    started_at           timestamptz   not null,
    started_by_id        uuid,
    started_by_name      varchar(200),
    ended_at             timestamptz,
    ended_by_name        varchar(200),
    reached_stop_id      uuid,
    last_latitude        numeric(9,6),
    last_longitude       numeric(9,6),
    last_accuracy_m      integer,
    last_position_at     timestamptz,
    last_position_source varchar(10),
    delay_minutes        integer,
    delay_reason         varchar(300),
    delayed_at           timestamptz,
    created_at           timestamptz   not null,
    updated_at           timestamptz   not null,
    version              bigint        not null default 0,
    constraint uq_trip_tenant_id unique (tenant_id, id),
    constraint uq_trip_run unique (tenant_id, route_id, trip_date, kind),
    constraint fk_trip_route foreign key (tenant_id, route_id) references transport.route (tenant_id, id),
    constraint fk_trip_vehicle foreign key (tenant_id, vehicle_id) references transport.vehicle (tenant_id, id),
    constraint fk_trip_reached_stop foreign key (tenant_id, route_id, reached_stop_id)
        references transport.stop (tenant_id, route_id, id) on delete set null (reached_stop_id),
    constraint fk_trip_started_by foreign key (tenant_id, started_by_id)
        references identity.user_account (tenant_id, id) on delete set null (started_by_id),
    constraint ck_trip_kind check (kind in ('MORNING', 'AFTERNOON')),
    constraint ck_trip_status check (status in ('RUNNING', 'COMPLETED')),
    constraint ck_trip_ended check ((status = 'COMPLETED') = (ended_at is not null)),
    constraint ck_trip_position check ((last_latitude is null) = (last_position_at is null)
        and (last_longitude is null) = (last_position_at is null)
        and (last_position_source is null) = (last_position_at is null)
        and (last_position_source is null or last_position_source in ('PHONE', 'DEVICE'))),
    constraint ck_trip_delay check ((delay_minutes is null) = (delayed_at is null)
        and (delay_minutes is null or delay_minutes between 1 and 600))
);
create index ix_trip_date on transport.trip (tenant_id, trip_date);
create index ix_trip_route on transport.trip (route_id);
create index ix_trip_vehicle on transport.trip (vehicle_id, status);
create index ix_trip_reached_stop on transport.trip (reached_stop_id);
create index ix_trip_started_by on transport.trip (started_by_id);

-- What happened on a trip, in order: a student boarded, was dropped or was absent (the latest mark for a student is
-- the one that counts), or the attendant reported a delay. Kept as the boarding log: never changed or deleted.
create table transport.trip_event (
    id          uuid primary key,
    tenant_id   uuid          not null references platform.tenant (id),
    trip_id     uuid          not null,
    kind        varchar(10)   not null,
    student_id  uuid,
    stop_id     uuid,
    stop_name   varchar(100),
    recorded_at timestamptz   not null,
    minutes     integer,
    note        varchar(300),
    by_id       uuid,
    by_name     varchar(200),
    constraint fk_trip_event_trip foreign key (tenant_id, trip_id) references transport.trip (tenant_id, id),
    constraint fk_trip_event_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_trip_event_stop foreign key (tenant_id, stop_id)
        references transport.stop (tenant_id, id) on delete set null (stop_id),
    constraint fk_trip_event_by foreign key (tenant_id, by_id)
        references identity.user_account (tenant_id, id) on delete set null (by_id),
    constraint ck_trip_event_kind check (kind in ('BOARDED', 'DROPPED', 'ABSENT', 'DELAYED')),
    constraint ck_trip_event_student check ((kind = 'DELAYED') = (student_id is null)),
    constraint ck_trip_event_minutes check ((kind = 'DELAYED') = (minutes is not null)
        and (minutes is null or minutes between 1 and 600))
);
create index ix_trip_event_trip on transport.trip_event (trip_id, recorded_at);
create index ix_trip_event_student on transport.trip_event (tenant_id, student_id, recorded_at)
    where student_id is not null;
create index ix_trip_event_stop on transport.trip_event (stop_id);
create index ix_trip_event_by on transport.trip_event (by_id);

-- The trip log: every position received while a trip runs, from the attendant's phone or the vehicle's GPS device.
create table transport.position (
    id          uuid primary key,
    tenant_id   uuid          not null references platform.tenant (id),
    trip_id     uuid          not null,
    vehicle_id  uuid,
    recorded_at timestamptz   not null,
    received_at timestamptz   not null,
    latitude    numeric(9,6)  not null,
    longitude   numeric(9,6)  not null,
    accuracy_m  integer,
    speed_kmh   numeric(5,1),
    source      varchar(10)   not null,
    constraint fk_position_trip foreign key (tenant_id, trip_id) references transport.trip (tenant_id, id),
    constraint fk_position_vehicle foreign key (tenant_id, vehicle_id) references transport.vehicle (tenant_id, id),
    constraint ck_position_lat check (latitude between -90 and 90),
    constraint ck_position_lng check (longitude between -180 and 180),
    constraint ck_position_accuracy check (accuracy_m is null or accuracy_m between 0 and 100000),
    constraint ck_position_speed check (speed_kmh is null or speed_kmh between 0 and 300),
    constraint ck_position_source check (source in ('PHONE', 'DEVICE'))
);
create index ix_position_trip on transport.position (trip_id, recorded_at);
create index ix_position_vehicle on transport.position (vehicle_id);

-- A GPS device posts without signing in, so no school is selected and row-level security hides every vehicle. This
-- function only says which school a vehicle belongs to when the device's token (its SHA-256) matches; the API then
-- works as that school. Retired vehicles accept no positions.
create function transport.device_tenant(p_vehicle_id uuid, p_token_hash text)
    returns uuid
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select v.tenant_id
    from transport.vehicle v
    where v.id = p_vehicle_id and v.device_token_hash = p_token_hash and v.status <> 'RETIRED'
$$;

-- ---------------------------------------------------------------- row-level security

alter table transport.settings enable row level security;
create policy tenant_isolation on transport.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table transport.fee_zone enable row level security;
create policy tenant_isolation on transport.fee_zone
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table transport.vehicle enable row level security;
create policy tenant_isolation on transport.vehicle
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table transport.crew_member enable row level security;
create policy tenant_isolation on transport.crew_member
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table transport.route enable row level security;
create policy tenant_isolation on transport.route
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table transport.stop enable row level security;
create policy tenant_isolation on transport.stop
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table transport.allocation enable row level security;
create policy tenant_isolation on transport.allocation
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table transport.trip enable row level security;
create policy tenant_isolation on transport.trip
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table transport.trip_event enable row level security;
create policy tenant_isolation on transport.trip_event
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table transport.position enable row level security;
create policy tenant_isolation on transport.position
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema transport to ${appRole};
grant select, insert, update, delete on transport.settings, transport.fee_zone, transport.vehicle,
    transport.crew_member, transport.route, transport.stop to ${appRole};
-- Allocations and trips are the record of who travelled when: ended, never deleted.
grant select, insert, update on transport.allocation, transport.trip to ${appRole};
-- The boarding log and the trip log are append-only.
grant select, insert on transport.trip_event, transport.position to ${appRole};
revoke all on function transport.device_tenant(uuid, text) from public;
grant execute on function transport.device_tenant(uuid, text) to ${appRole};

-- ---------------------------------------------------------------- roles and the transport permissions

-- New schools get these roles and permissions from RoleCatalog. Existing schools keep their stored roles, so the
-- built-in Transport manager and Bus attendant roles are added to every school that has none, and the new codes are
-- appended to the matching built-in roles, once. Runs as the owner, which row-level security does not restrict.
-- Idempotent.
insert into identity.role (id, tenant_id, code, name, permissions, system_role, created_at)
select gen_random_uuid(), t.id, 'TRANSPORT_MANAGER', 'Transport manager',
       array['dashboard.view', 'academics.read', 'leave.request', 'notices.read', 'timetable.read', 'transport.read',
             'transport.manage', 'transport.trip'],
       true, now()
from platform.tenant t
where not exists (select 1 from identity.role r where r.tenant_id = t.id and r.code = 'TRANSPORT_MANAGER');

insert into identity.role (id, tenant_id, code, name, permissions, system_role, created_at)
select gen_random_uuid(), t.id, 'BUS_ATTENDANT', 'Bus attendant',
       array['dashboard.view', 'leave.request', 'notices.read', 'transport.trip'],
       true, now()
from platform.tenant t
where not exists (select 1 from identity.role r where r.tenant_id = t.id and r.code = 'BUS_ATTENDANT');

-- transport.read: routes, vehicles, the live board and the transport reports.
update identity.role
set permissions = array_append(permissions, 'transport.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TRANSPORT_MANAGER')
  and not ('transport.read' = any (permissions));

-- transport.manage: vehicles, crew, routes and stops, fee zones, allocations and alert settings.
update identity.role
set permissions = array_append(permissions, 'transport.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'TRANSPORT_MANAGER')
  and not ('transport.manage' = any (permissions));

-- transport.trip: run a trip from a phone (the crew of the route; managers may run any route's trip).
update identity.role
set permissions = array_append(permissions, 'transport.trip')
where system_role
  and code in ('SCHOOL_ADMIN', 'TRANSPORT_MANAGER', 'BUS_ATTENDANT')
  and not ('transport.trip' = any (permissions));
