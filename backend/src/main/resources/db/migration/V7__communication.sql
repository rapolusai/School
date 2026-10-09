-- Phase 1: communication. Circulars (announcements) to parents, students and staff with optional approval, scheduled
-- sending, in-app notice boards with read receipts, and the school calendar (holidays, events, exams, PTMs) with
-- reminders. Every school-owned table carries tenant_id, is protected by the same tenant_isolation policy as V2/V3, and
-- links to other school tables through composite (tenant_id, id) keys. SMS, WhatsApp and email go through the
-- notifications outbox (V5); nothing here stores a phone number.

create schema if not exists communication;

-- ---------------------------------------------------------------- calendar

-- A holiday, event, exam, PTM or other entry on one day or a range of days, optionally at a time. The audience says who
-- sees it: the whole school, the families of some classes (staff always see every entry), or staff only.
create table communication.calendar_entry (
    id               uuid          primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    academic_year_id uuid,
    kind             varchar(10)   not null,
    title            varchar(200)  not null,
    description      varchar(2000),
    starts_on        date          not null,
    ends_on          date          not null,
    start_time       time,
    end_time         time,
    audience         varchar(10)   not null,
    -- Remind the audience this many days before the entry starts (in the app, and by SMS or WhatsApp if chosen).
    reminder_days    integer,
    remind_sms       boolean       not null default false,
    remind_whatsapp  boolean       not null default false,
    reminder_sent_at timestamptz,
    created_by_id    uuid,
    created_by_name  varchar(200),
    updated_by_name  varchar(200),
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint ck_calendar_entry_kind check (kind in ('HOLIDAY', 'EVENT', 'EXAM', 'PTM', 'OTHER')),
    constraint ck_calendar_entry_audience check (audience in ('SCHOOL', 'CLASSES', 'STAFF')),
    constraint ck_calendar_entry_dates check (ends_on >= starts_on and ends_on - starts_on <= 120),
    constraint ck_calendar_entry_times check ((start_time is not null or end_time is null)
        and (end_time is null or ends_on > starts_on or end_time > start_time)),
    constraint ck_calendar_entry_reminder check (reminder_days is null or reminder_days between 1 and 30),
    constraint ck_calendar_entry_reminder_channels check (reminder_days is not null
        or (not remind_sms and not remind_whatsapp)),
    constraint uq_calendar_entry_tenant_id unique (tenant_id, id),
    constraint fk_calendar_entry_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id) on delete set null (academic_year_id),
    constraint fk_calendar_entry_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_calendar_entry_dates on communication.calendar_entry (tenant_id, starts_on, ends_on);
create index ix_calendar_entry_year on communication.calendar_entry (academic_year_id);
create index ix_calendar_entry_created_by on communication.calendar_entry (created_by_id);
create index ix_calendar_entry_reminder_due on communication.calendar_entry (starts_on)
    where reminder_days is not null and reminder_sent_at is null;

-- The classes whose families see an entry with audience CLASSES.
create table communication.calendar_entry_class (
    id         uuid        primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    entry_id   uuid        not null,
    class_id   uuid        not null,
    created_at timestamptz not null,
    constraint uq_calendar_entry_class unique (entry_id, class_id),
    constraint fk_calendar_entry_class_entry foreign key (tenant_id, entry_id)
        references communication.calendar_entry (tenant_id, id) on delete cascade,
    constraint fk_calendar_entry_class_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id) on delete cascade
);
create index ix_calendar_entry_class_class on communication.calendar_entry_class (class_id);
create index ix_calendar_entry_class_tenant on communication.calendar_entry_class (tenant_id);

-- ---------------------------------------------------------------- circulars

-- A circular moves DRAFT -> (PENDING_APPROVAL ->) SCHEDULED or SENT, and a sent one can be WITHDRAWN. A rejected
-- circular goes back to DRAFT with the reviewer's note. Recipients are worked out when it is sent. The body is plain
-- text with line breaks; it is never stored or shown as HTML.
create table communication.circular (
    id                uuid          primary key,
    tenant_id         uuid          not null references platform.tenant (id),
    title             varchar(200)  not null,
    body              text          not null,
    category          varchar(20)   not null,
    status            varchar(20)   not null,
    -- STAFF: written by a person; CALENDAR: a reminder of a calendar entry.
    source            varchar(10)   not null default 'STAFF',
    calendar_entry_id uuid,
    whole_school      boolean       not null default false,
    audience_label    varchar(500)  not null,
    send_sms          boolean       not null default false,
    send_whatsapp     boolean       not null default false,
    send_email        boolean       not null default false,
    scheduled_at      timestamptz,
    created_by_id     uuid,
    created_by_name   varchar(200),
    submitted_at      timestamptz,
    reviewed_by_id    uuid,
    reviewed_by_name  varchar(200),
    reviewed_at       timestamptz,
    review_outcome    varchar(10),
    review_note       varchar(1000),
    sent_at           timestamptz,
    sent_by_name      varchar(200),
    -- What sending produced, kept with the circular so the numbers do not change when people come and go.
    in_app_count      integer       not null default 0,
    staff_count       integer       not null default 0,
    parent_count      integer       not null default 0,
    student_count     integer       not null default 0,
    sms_count         integer       not null default 0,
    whatsapp_count    integer       not null default 0,
    email_count       integer       not null default 0,
    withdrawn_at      timestamptz,
    withdrawn_by_id   uuid,
    withdrawn_by_name varchar(200),
    withdraw_reason   varchar(500),
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    version           bigint        not null default 0,
    constraint ck_circular_category check (category in ('GENERAL', 'ACADEMIC', 'EVENT', 'HOLIDAY', 'FEES', 'URGENT')),
    constraint ck_circular_status check (status in ('DRAFT', 'PENDING_APPROVAL', 'SCHEDULED', 'SENT', 'WITHDRAWN')),
    constraint ck_circular_source check (source in ('STAFF', 'CALENDAR')),
    constraint ck_circular_body check (char_length(body) between 1 and 5000),
    constraint ck_circular_review check (review_outcome is null or review_outcome in ('APPROVED', 'REJECTED')),
    constraint ck_circular_scheduled check (status <> 'SCHEDULED' or scheduled_at is not null),
    constraint ck_circular_sent check (status not in ('SENT', 'WITHDRAWN') or sent_at is not null),
    constraint ck_circular_withdrawn check (status <> 'WITHDRAWN'
        or (withdrawn_at is not null and withdraw_reason is not null)),
    constraint uq_circular_tenant_id unique (tenant_id, id),
    constraint fk_circular_calendar_entry foreign key (tenant_id, calendar_entry_id)
        references communication.calendar_entry (tenant_id, id) on delete set null (calendar_entry_id),
    constraint fk_circular_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id),
    constraint fk_circular_reviewed_by foreign key (tenant_id, reviewed_by_id)
        references identity.user_account (tenant_id, id) on delete set null (reviewed_by_id),
    constraint fk_circular_withdrawn_by foreign key (tenant_id, withdrawn_by_id)
        references identity.user_account (tenant_id, id) on delete set null (withdrawn_by_id)
);
create index ix_circular_status on communication.circular (tenant_id, status, updated_at desc);
create index ix_circular_due on communication.circular (scheduled_at) where status = 'SCHEDULED';
create index ix_circular_created_by on communication.circular (created_by_id);
create index ix_circular_reviewed_by on communication.circular (reviewed_by_id);
create index ix_circular_withdrawn_by on communication.circular (withdrawn_by_id);
create index ix_circular_calendar_entry on communication.circular (calendar_entry_id);

-- Who a circular is addressed to, as chosen: a class, a section, or a role (a staff role, PARENT or STUDENT). The whole
-- school is the whole_school flag on the circular.
create table communication.circular_target (
    id          uuid        primary key,
    tenant_id   uuid        not null references platform.tenant (id),
    circular_id uuid        not null,
    class_id    uuid,
    section_id  uuid,
    role_code   varchar(40),
    created_at  timestamptz not null,
    constraint ck_circular_target_one check (num_nonnulls(class_id, section_id, role_code) = 1),
    constraint fk_circular_target_circular foreign key (tenant_id, circular_id)
        references communication.circular (tenant_id, id) on delete cascade,
    constraint fk_circular_target_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id) on delete cascade,
    constraint fk_circular_target_section foreign key (tenant_id, section_id)
        references academics.section (tenant_id, id) on delete cascade
);
create index ix_circular_target_circular on communication.circular_target (circular_id);
create index ix_circular_target_class on communication.circular_target (class_id);
create index ix_circular_target_section on communication.circular_target (section_id);
create index ix_circular_target_tenant on communication.circular_target (tenant_id);

-- The people with a sign-in who see a sent circular on their notice board, and when they first read it.
create table communication.circular_recipient (
    id              uuid        primary key,
    tenant_id       uuid        not null references platform.tenant (id),
    circular_id     uuid        not null,
    user_account_id uuid        not null,
    kind            varchar(10) not null,
    read_at         timestamptz,
    created_at      timestamptz not null,
    constraint ck_circular_recipient_kind check (kind in ('STAFF', 'PARENT', 'STUDENT')),
    constraint uq_circular_recipient unique (circular_id, user_account_id),
    constraint fk_circular_recipient_circular foreign key (tenant_id, circular_id)
        references communication.circular (tenant_id, id) on delete cascade,
    constraint fk_circular_recipient_user foreign key (tenant_id, user_account_id)
        references identity.user_account (tenant_id, id) on delete cascade
);
create index ix_circular_recipient_user on communication.circular_recipient (tenant_id, user_account_id, read_at);

-- ---------------------------------------------------------------- settings

-- Per-school communication settings. A school without a row uses the defaults below.
create table communication.settings (
    id                              uuid        primary key,
    tenant_id                       uuid        not null references platform.tenant (id),
    teacher_circulars_need_approval boolean     not null default true,
    enquiry_ack_enabled             boolean     not null default true,
    enquiry_ack_channel             varchar(20) not null default 'SMS',
    created_at                      timestamptz not null,
    updated_at                      timestamptz not null,
    version                         bigint      not null default 0,
    constraint uq_communication_settings_tenant unique (tenant_id),
    constraint ck_communication_settings_channel check (enquiry_ack_channel in ('SMS', 'WHATSAPP_SMS'))
);

-- ---------------------------------------------------------------- scheduled work across schools

-- The scheduler runs without a school selected, so row-level security hides every row from it. These functions tell it
-- only which schools have work due; it then does the work as each school in turn.
create function communication.tenants_with_due_circulars(p_now timestamptz, p_limit integer)
    returns table (tenant_id uuid)
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select c.tenant_id
    from communication.circular c
    where c.status = 'SCHEDULED' and c.scheduled_at <= p_now
    group by c.tenant_id
    order by min(c.scheduled_at)
    limit p_limit
$$;

create function communication.tenants_with_due_reminders(p_today date, p_limit integer)
    returns table (tenant_id uuid)
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select e.tenant_id
    from communication.calendar_entry e
    where e.reminder_days is not null and e.reminder_sent_at is null
      and e.starts_on - e.reminder_days <= p_today and e.starts_on >= p_today
    group by e.tenant_id
    order by min(e.starts_on)
    limit p_limit
$$;

-- ---------------------------------------------------------------- row-level security

alter table communication.calendar_entry enable row level security;
create policy tenant_isolation on communication.calendar_entry
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table communication.calendar_entry_class enable row level security;
create policy tenant_isolation on communication.calendar_entry_class
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table communication.circular enable row level security;
create policy tenant_isolation on communication.circular
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table communication.circular_target enable row level security;
create policy tenant_isolation on communication.circular_target
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table communication.circular_recipient enable row level security;
create policy tenant_isolation on communication.circular_recipient
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table communication.settings enable row level security;
create policy tenant_isolation on communication.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema communication to ${appRole};
grant select, insert, update, delete on communication.calendar_entry, communication.calendar_entry_class,
    communication.circular, communication.circular_target, communication.circular_recipient,
    communication.settings to ${appRole};
revoke all on function communication.tenants_with_due_circulars(timestamptz, integer) from public;
revoke all on function communication.tenants_with_due_reminders(date, integer) from public;
grant execute on function communication.tenants_with_due_circulars(timestamptz, integer),
    communication.tenants_with_due_reminders(date, integer) to ${appRole};

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.
update identity.role
set permissions = array_append(permissions, 'notices.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER', 'ACCOUNTANT', 'FRONT_OFFICE', 'PARENT', 'STUDENT')
  and not ('notices.read' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'notices.approve')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('notices.approve' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'calendar.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('calendar.manage' = any (permissions));
