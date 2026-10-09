-- Phase 1: day-wise attendance per section, and the notifications outbox that other modules use to send SMS,
-- WhatsApp and email messages (absence alerts first). Every school-owned table carries tenant_id, is protected by the
-- same tenant_isolation policy as V2/V3, and links to other school tables through composite (tenant_id, id) keys.

create schema if not exists attendance;
create schema if not exists notifications;

-- ---------------------------------------------------------------- attendance registers

-- One register per section per school day. Who marked it first, and who last changed it, are kept by name as well,
-- so the register still reads correctly after a person is removed.
create table attendance.register (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    section_id       uuid         not null,
    academic_year_id uuid         not null,
    attendance_date  date         not null,
    marked_by_id     uuid,
    marked_by_name   varchar(200),
    marked_at        timestamptz  not null,
    updated_by_id    uuid,
    updated_by_name  varchar(200),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_register_tenant_id unique (tenant_id, id),
    constraint uq_register_section_date unique (tenant_id, section_id, attendance_date),
    constraint fk_register_section foreign key (tenant_id, section_id) references academics.section (tenant_id, id),
    constraint fk_register_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_register_marked_by foreign key (tenant_id, marked_by_id)
        references identity.user_account (tenant_id, id) on delete set null (marked_by_id),
    constraint fk_register_updated_by foreign key (tenant_id, updated_by_id)
        references identity.user_account (tenant_id, id) on delete set null (updated_by_id)
);
create index ix_register_date on attendance.register (tenant_id, attendance_date);
create index ix_register_year on attendance.register (academic_year_id);
create index ix_register_marked_by on attendance.register (marked_by_id);
create index ix_register_updated_by on attendance.register (updated_by_id);

-- One mark per student per register: P(resent), A(bsent), L(ate), H(alf day) or E(xcused leave).
create table attendance.entry (
    id          uuid primary key,
    tenant_id   uuid        not null references platform.tenant (id),
    register_id uuid        not null,
    student_id  uuid        not null,
    status      varchar(10) not null,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    constraint ck_entry_status check (status in ('PRESENT', 'ABSENT', 'LATE', 'HALF_DAY', 'LEAVE')),
    constraint uq_entry_register_student unique (register_id, student_id),
    constraint fk_entry_register foreign key (tenant_id, register_id)
        references attendance.register (tenant_id, id) on delete cascade,
    constraint fk_entry_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade
);
create index ix_entry_student on attendance.entry (tenant_id, student_id);

-- ---------------------------------------------------------------- notifications outbox

-- Messages are written in the same transaction as the change that causes them and sent later by the dispatcher.
-- The recipient is kept only as long as the school keeps its message log; lists show it masked.
create table notifications.message (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    channel          varchar(10)  not null,
    recipient        varchar(254) not null,
    recipient_name   varchar(200),
    template_key     varchar(60)  not null,
    language         varchar(5)   not null,
    params           jsonb        not null default '{}'::jsonb,
    body             text         not null,
    status           varchar(10)  not null,
    attempts         integer      not null default 0,
    last_error       varchar(500),
    next_attempt_at  timestamptz  not null,
    fallback_channel varchar(10),
    related_type     varchar(40),
    related_id       uuid,
    related_label    varchar(200),
    dedupe_key       varchar(200),
    provider_ref     varchar(100),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    sent_at          timestamptz,
    version          bigint       not null default 0,
    constraint ck_message_channel check (channel in ('SMS', 'WHATSAPP', 'EMAIL')),
    constraint ck_message_fallback check (fallback_channel is null or fallback_channel in ('SMS', 'WHATSAPP', 'EMAIL')),
    constraint ck_message_status check (status in ('QUEUED', 'SENT', 'FAILED', 'SIMULATED', 'SKIPPED')),
    constraint ck_message_attempts check (attempts >= 0),
    constraint uq_message_tenant_id unique (tenant_id, id)
);
-- The same logical message (for example one absence alert per student per day) is queued at most once per school.
create unique index uq_message_dedupe on notifications.message (tenant_id, dedupe_key) where dedupe_key is not null;
create index ix_message_due on notifications.message (next_attempt_at) where status = 'QUEUED';
create index ix_message_created on notifications.message (tenant_id, created_at desc);
create index ix_message_related on notifications.message (tenant_id, related_id);

-- Per-school message settings. A school without a row uses the defaults below.
create table notifications.settings (
    id                     uuid primary key,
    tenant_id              uuid        not null references platform.tenant (id),
    absence_alerts_enabled boolean     not null default true,
    absence_alert_channel  varchar(20) not null default 'WHATSAPP_SMS',
    alert_language         varchar(5)  not null default 'en',
    quiet_hours_enabled    boolean     not null default true,
    quiet_hours_start      time        not null default '21:00',
    quiet_hours_end        time        not null default '07:00',
    created_at             timestamptz not null,
    updated_at             timestamptz not null,
    version                bigint      not null default 0,
    constraint uq_settings_tenant unique (tenant_id),
    constraint ck_settings_channel check (absence_alert_channel in ('WHATSAPP_SMS', 'SMS')),
    constraint ck_settings_language check (alert_language in ('en', 'hi'))
);

-- The dispatcher runs without a school selected, so row-level security hides every message from it. This function
-- tells it only which schools have messages due; it then reads and sends them as each school in turn.
create function notifications.tenants_with_due_messages(p_now timestamptz, p_limit integer)
    returns table (tenant_id uuid)
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select m.tenant_id
    from notifications.message m
    where m.status = 'QUEUED' and m.next_attempt_at <= p_now
    group by m.tenant_id
    order by min(m.next_attempt_at)
    limit p_limit
$$;

-- ---------------------------------------------------------------- row-level security

alter table attendance.register enable row level security;
create policy tenant_isolation on attendance.register
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table attendance.entry enable row level security;
create policy tenant_isolation on attendance.entry
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table notifications.message enable row level security;
create policy tenant_isolation on notifications.message
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table notifications.settings enable row level security;
create policy tenant_isolation on notifications.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema attendance, notifications to ${appRole};
grant select, insert, update, delete on attendance.register, attendance.entry to ${appRole};
grant select, insert, update, delete on notifications.message, notifications.settings to ${appRole};
revoke all on function notifications.tenants_with_due_messages(timestamptz, integer) from public;
grant execute on function notifications.tenants_with_due_messages(timestamptz, integer) to ${appRole};

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.
update identity.role
set permissions = array_append(permissions, 'attendance.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('attendance.manage' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'messages.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('messages.read' = any (permissions));
