-- Phase 2, messages and PTM: parent-teacher conversations (one per student, parent and member of staff) with text
-- messages, attachments through the files module, read receipts, reports to the Principal and oversight; the school's
-- staff availability ("quiet hours" for teachers) and teachers' "away" notes; and parent-teacher meetings (PTM) with
-- slots per teacher, bookings by parents, attendance and notes. No phone number or email address is stored here: the
-- people in a conversation are kept as ids and the names shown come from the students and identity modules.
-- Every table carries tenant_id and the same tenant_isolation policy as V2/V3, and every link between school tables is
-- a composite (tenant_id, id) foreign key. Also adds the chat.use, chat.oversee and ptm.manage permissions.

create schema if not exists chat;

-- ---------------------------------------------------------------- settings (one row per school, made on first use)

create table chat.settings (
    id                    uuid primary key,
    tenant_id             uuid        not null references platform.tenant (id),
    -- The days staff answer messages, as a bit set: Monday = 1, Tuesday = 2, ... Sunday = 64 (63 = Monday to Saturday).
    available_days        integer     not null default 63,
    available_from        time        not null default '08:00',
    available_to          time        not null default '18:00',
    -- Tell the other side of a conversation about a new message through the notifications outbox (simulated).
    notify_new_messages   boolean     not null default true,
    -- PTM reminders to parents the day before and an hour before their slot. Off until the school switches them on.
    ptm_reminders_enabled boolean     not null default false,
    created_at            timestamptz not null,
    updated_at            timestamptz not null,
    version               bigint      not null default 0,
    constraint uq_chat_settings_tenant unique (tenant_id),
    constraint ck_chat_settings_days check (available_days between 1 and 127),
    constraint ck_chat_settings_hours check (available_to > available_from)
);

-- A member of staff who is away (leave, training): parents see the note and the day they are back, and notifications
-- about new messages wait until then. One row per person; removed when they are back or clear it.
create table chat.staff_away (
    id         uuid primary key,
    tenant_id  uuid         not null references platform.tenant (id),
    user_id    uuid         not null,
    returns_on date         not null,
    note       varchar(300),
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    version    bigint       not null default 0,
    constraint uq_staff_away_user unique (tenant_id, user_id),
    constraint fk_staff_away_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id) on delete cascade
);

-- ---------------------------------------------------------------- conversations and messages

-- One conversation per student, parent (their sign-in) and member of staff. It becomes read-only when the Principal
-- closes it, and (worked out when it is read) when the student or the member of staff leaves the school or the parent
-- is no longer linked to the student.
create table chat.conversation (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    student_id      uuid         not null,
    parent_user_id  uuid         not null,
    staff_user_id   uuid         not null,
    started_by      varchar(10)  not null,
    status          varchar(10)  not null,
    closed_at       timestamptz,
    closed_by_name  varchar(200),
    close_reason    varchar(500),
    -- Set when the student's data is erased (privacy module): message texts and attachments are gone.
    erased_at       timestamptz,
    last_message_at timestamptz,
    message_count   integer      not null default 0,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint uq_conversation_tenant_id unique (tenant_id, id),
    constraint uq_conversation_people unique (tenant_id, student_id, parent_user_id, staff_user_id),
    constraint fk_conversation_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_conversation_parent foreign key (tenant_id, parent_user_id)
        references identity.user_account (tenant_id, id),
    constraint fk_conversation_staff foreign key (tenant_id, staff_user_id)
        references identity.user_account (tenant_id, id),
    constraint ck_conversation_started_by check (started_by in ('PARENT', 'STAFF')),
    constraint ck_conversation_status check (status in ('OPEN', 'CLOSED')),
    constraint ck_conversation_closed check ((status = 'CLOSED') = (closed_at is not null)),
    constraint ck_conversation_people check (parent_user_id <> staff_user_id),
    constraint ck_conversation_count check (message_count >= 0)
);
create index ix_conversation_staff on chat.conversation (tenant_id, staff_user_id, last_message_at desc);
create index ix_conversation_parent on chat.conversation (tenant_id, parent_user_id, last_message_at desc);
create index ix_conversation_student on chat.conversation (student_id);
create index ix_conversation_parent_user on chat.conversation (parent_user_id);
create index ix_conversation_staff_user on chat.conversation (staff_user_id);

-- A message is never edited. The sender can delete it for both sides within 10 minutes: the placeholder stays and the
-- original text and attachments are kept for oversight. read_at is when the other side first saw it.
create table chat.message (
    id               uuid primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    conversation_id  uuid          not null,
    sender_user_id   uuid,
    sender_side      varchar(10)   not null,
    sender_name      varchar(200)  not null,
    body             varchar(2000),
    attachment_count integer       not null default 0,
    read_at          timestamptz,
    deleted_at       timestamptz,
    erased_at        timestamptz,
    created_at       timestamptz   not null,
    -- Moves whenever the message changes (read, deleted, erased), so an open chat can ask only for what changed.
    updated_at       timestamptz   not null,
    constraint uq_message_tenant_id unique (tenant_id, id),
    constraint fk_message_conversation foreign key (tenant_id, conversation_id)
        references chat.conversation (tenant_id, id),
    constraint fk_message_sender foreign key (tenant_id, sender_user_id)
        references identity.user_account (tenant_id, id) on delete set null (sender_user_id),
    constraint ck_message_side check (sender_side in ('PARENT', 'STAFF')),
    constraint ck_message_body check (body is null or char_length(body) between 1 and 2000),
    constraint ck_message_attachments check (attachment_count between 0 and 3),
    constraint ck_message_content check (body is not null or attachment_count > 0 or erased_at is not null)
);
create index ix_message_conversation on chat.message (conversation_id, created_at);
create index ix_message_changed on chat.message (conversation_id, updated_at);
create index ix_message_unread on chat.message (conversation_id) where read_at is null and deleted_at is null;
create index ix_message_sender on chat.message (tenant_id, sender_user_id, created_at);
create index ix_message_sender_user on chat.message (sender_user_id);

-- A parent or a teacher flags a conversation for the Principal (chat.oversee), who reviews it.
create table chat.report (
    id               uuid primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    conversation_id  uuid          not null,
    reported_by_id   uuid,
    reported_by_name varchar(200)  not null,
    reporter_side    varchar(10)   not null,
    reason           varchar(500)  not null,
    status           varchar(10)   not null,
    reviewed_at      timestamptz,
    reviewed_by_id   uuid,
    reviewed_by_name varchar(200),
    review_note      varchar(500),
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint uq_report_tenant_id unique (tenant_id, id),
    constraint fk_report_conversation foreign key (tenant_id, conversation_id)
        references chat.conversation (tenant_id, id),
    constraint fk_report_by foreign key (tenant_id, reported_by_id)
        references identity.user_account (tenant_id, id) on delete set null (reported_by_id),
    constraint fk_report_reviewed_by foreign key (tenant_id, reviewed_by_id)
        references identity.user_account (tenant_id, id) on delete set null (reviewed_by_id),
    constraint ck_report_side check (reporter_side in ('PARENT', 'STAFF')),
    constraint ck_report_status check (status in ('OPEN', 'REVIEWED')),
    constraint ck_report_reviewed check ((status = 'REVIEWED') = (reviewed_at is not null))
);
create index ix_report_conversation on chat.report (conversation_id);
create index ix_report_open on chat.report (tenant_id, created_at) where status = 'OPEN';
create index ix_report_by on chat.report (reported_by_id);
create index ix_report_reviewed_by on chat.report (reviewed_by_id);

-- ---------------------------------------------------------------- parent-teacher meetings (PTM)

-- A meeting day for some sections: the hours, the slot length, breaks ("10:30-10:45,12:00-12:30"), in person (with a
-- room) or by video (a link the school types in). Parents can book, cancel and change until cutoff_hours before a slot.
create table chat.ptm_event (
    id                       uuid primary key,
    tenant_id                uuid          not null references platform.tenant (id),
    academic_year_id         uuid          not null,
    title                    varchar(150)  not null,
    meeting_date             date          not null,
    starts_at                time          not null,
    ends_at                  time          not null,
    slot_minutes             integer       not null,
    breaks                   varchar(200)  not null default '',
    mode                     varchar(10)   not null,
    location                 varchar(200),
    video_link               varchar(500),
    include_subject_teachers boolean       not null default false,
    cutoff_hours             integer       not null default 12,
    note                     varchar(1000),
    status                   varchar(10)   not null,
    cancelled_at             timestamptz,
    cancelled_by_name        varchar(200),
    cancel_reason            varchar(500),
    created_by_id            uuid,
    created_by_name          varchar(200),
    created_at               timestamptz   not null,
    updated_at               timestamptz   not null,
    version                  bigint        not null default 0,
    constraint uq_ptm_event_tenant_id unique (tenant_id, id),
    constraint fk_ptm_event_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_ptm_event_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id),
    constraint ck_ptm_event_hours check (ends_at > starts_at),
    constraint ck_ptm_event_slot check (slot_minutes between 5 and 60),
    constraint ck_ptm_event_breaks check (breaks ~ '^([0-2][0-9]:[0-5][0-9]-[0-2][0-9]:[0-5][0-9](,[0-2][0-9]:[0-5][0-9]-[0-2][0-9]:[0-5][0-9]){0,4})?$'),
    constraint ck_ptm_event_mode check (mode in ('IN_PERSON', 'VIDEO')),
    constraint ck_ptm_event_link check (video_link is null or video_link ~ '^https://'),
    constraint ck_ptm_event_video check (mode <> 'VIDEO' or video_link is not null),
    constraint ck_ptm_event_cutoff check (cutoff_hours between 0 and 72),
    constraint ck_ptm_event_status check (status in ('SCHEDULED', 'CANCELLED')),
    constraint ck_ptm_event_cancelled check ((status = 'CANCELLED') = (cancelled_at is not null))
);
create index ix_ptm_event_date on chat.ptm_event (tenant_id, meeting_date);
create index ix_ptm_event_year on chat.ptm_event (academic_year_id);
create index ix_ptm_event_created_by on chat.ptm_event (created_by_id);

create table chat.ptm_section (
    id         uuid primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    event_id   uuid        not null,
    section_id uuid        not null,
    created_at timestamptz not null,
    constraint uq_ptm_section unique (event_id, section_id),
    constraint fk_ptm_section_event foreign key (tenant_id, event_id)
        references chat.ptm_event (tenant_id, id) on delete cascade,
    constraint fk_ptm_section_section foreign key (tenant_id, section_id) references academics.section (tenant_id, id)
);
create index ix_ptm_section_section on chat.ptm_section (section_id);
create index ix_ptm_section_tenant on chat.ptm_section (tenant_id);

-- A teacher meeting parents at the event: the class teacher of a section, or a subject teacher of one. Their hours
-- start as the event's and can be narrowed; slots are made from them.
create table chat.ptm_teacher (
    id              uuid primary key,
    tenant_id       uuid          not null references platform.tenant (id),
    event_id        uuid          not null,
    teacher_user_id uuid          not null,
    kind            varchar(15)   not null,
    subjects        varchar(300),
    available_from  time          not null,
    available_to    time          not null,
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null,
    version         bigint        not null default 0,
    constraint uq_ptm_teacher_tenant_id unique (tenant_id, id),
    constraint uq_ptm_teacher unique (event_id, teacher_user_id),
    constraint fk_ptm_teacher_event foreign key (tenant_id, event_id)
        references chat.ptm_event (tenant_id, id) on delete cascade,
    constraint fk_ptm_teacher_user foreign key (tenant_id, teacher_user_id)
        references identity.user_account (tenant_id, id),
    constraint ck_ptm_teacher_kind check (kind in ('CLASS_TEACHER', 'SUBJECT_TEACHER')),
    constraint ck_ptm_teacher_hours check (available_to > available_from)
);
create index ix_ptm_teacher_user on chat.ptm_teacher (tenant_id, teacher_user_id);
create index ix_ptm_teacher_teacher on chat.ptm_teacher (teacher_user_id);

create table chat.ptm_slot (
    id              uuid primary key,
    tenant_id       uuid        not null references platform.tenant (id),
    event_id        uuid        not null,
    ptm_teacher_id  uuid        not null,
    teacher_user_id uuid        not null,
    starts_at       time        not null,
    ends_at         time        not null,
    -- The teacher (or leadership) took the slot out: nobody can book it.
    blocked         boolean     not null default false,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    version         bigint      not null default 0,
    constraint uq_ptm_slot_tenant_id unique (tenant_id, id),
    constraint uq_ptm_slot_time unique (ptm_teacher_id, starts_at),
    constraint fk_ptm_slot_event foreign key (tenant_id, event_id)
        references chat.ptm_event (tenant_id, id) on delete cascade,
    constraint fk_ptm_slot_teacher foreign key (tenant_id, ptm_teacher_id)
        references chat.ptm_teacher (tenant_id, id) on delete cascade,
    constraint fk_ptm_slot_user foreign key (tenant_id, teacher_user_id)
        references identity.user_account (tenant_id, id),
    constraint ck_ptm_slot_times check (ends_at > starts_at)
);
create index ix_ptm_slot_event on chat.ptm_slot (event_id, teacher_user_id, starts_at);
create index ix_ptm_slot_user on chat.ptm_slot (teacher_user_id);

-- A booked slot for one child. A slot holds at most one booking, and a child at most one with each teacher at an
-- event. Bookings made by the office for a family without the app have no parent sign-in.
create table chat.ptm_booking (
    id                uuid primary key,
    tenant_id         uuid          not null references platform.tenant (id),
    event_id          uuid          not null,
    slot_id           uuid          not null,
    teacher_user_id   uuid          not null,
    student_id        uuid          not null,
    parent_user_id    uuid,
    booked_by_side    varchar(10)   not null,
    booked_by_name    varchar(200)  not null,
    status            varchar(10)   not null,
    attendance        varchar(10),
    -- Seen only by staff.
    private_note      varchar(1000),
    -- Shown to the parent after the meeting.
    parent_note       varchar(1000),
    marked_at         timestamptz,
    marked_by_name    varchar(200),
    cancelled_at      timestamptz,
    cancelled_by_name varchar(200),
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    version           bigint        not null default 0,
    constraint uq_ptm_booking_tenant_id unique (tenant_id, id),
    constraint fk_ptm_booking_event foreign key (tenant_id, event_id) references chat.ptm_event (tenant_id, id),
    constraint fk_ptm_booking_slot foreign key (tenant_id, slot_id) references chat.ptm_slot (tenant_id, id),
    constraint fk_ptm_booking_teacher foreign key (tenant_id, teacher_user_id)
        references identity.user_account (tenant_id, id),
    constraint fk_ptm_booking_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_ptm_booking_parent foreign key (tenant_id, parent_user_id)
        references identity.user_account (tenant_id, id) on delete set null (parent_user_id),
    constraint ck_ptm_booking_side check (booked_by_side in ('PARENT', 'SCHOOL')),
    constraint ck_ptm_booking_status check (status in ('BOOKED', 'CANCELLED')),
    constraint ck_ptm_booking_cancelled check ((status = 'CANCELLED') = (cancelled_at is not null)),
    constraint ck_ptm_booking_attendance check (attendance is null or attendance in ('ATTENDED', 'NO_SHOW'))
);
create unique index uq_ptm_booking_slot on chat.ptm_booking (slot_id) where status = 'BOOKED';
create unique index uq_ptm_booking_child_teacher on chat.ptm_booking (event_id, teacher_user_id, student_id)
    where status = 'BOOKED';
create index ix_ptm_booking_event on chat.ptm_booking (tenant_id, event_id);
create index ix_ptm_booking_student on chat.ptm_booking (student_id);
create index ix_ptm_booking_parent on chat.ptm_booking (parent_user_id);
create index ix_ptm_booking_teacher on chat.ptm_booking (teacher_user_id);
create index ix_ptm_booking_slot on chat.ptm_booking (slot_id);

-- ---------------------------------------------------------------- row-level security

alter table chat.settings enable row level security;
create policy tenant_isolation on chat.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table chat.staff_away enable row level security;
create policy tenant_isolation on chat.staff_away
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table chat.conversation enable row level security;
create policy tenant_isolation on chat.conversation
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table chat.message enable row level security;
create policy tenant_isolation on chat.message
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table chat.report enable row level security;
create policy tenant_isolation on chat.report
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table chat.ptm_event enable row level security;
create policy tenant_isolation on chat.ptm_event
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table chat.ptm_section enable row level security;
create policy tenant_isolation on chat.ptm_section
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table chat.ptm_teacher enable row level security;
create policy tenant_isolation on chat.ptm_teacher
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table chat.ptm_slot enable row level security;
create policy tenant_isolation on chat.ptm_slot
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table chat.ptm_booking enable row level security;
create policy tenant_isolation on chat.ptm_booking
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema chat to ${appRole};
-- A cancelled PTM booking goes with its slot when the slot is removed (the meeting hours changed); a booked slot is
-- never removed.
grant select, insert, update, delete on chat.settings, chat.staff_away, chat.ptm_event, chat.ptm_section,
    chat.ptm_teacher, chat.ptm_slot, chat.ptm_booking to ${appRole};
-- Conversations, messages and reports are the record of what was said: never deleted (a deleted message is only
-- marked, and erasure clears the text in place).
grant select, insert, update on chat.conversation, chat.message, chat.report to ${appRole};

-- ---------------------------------------------------------------- the chat and PTM permissions

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so the new codes are appended to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.

-- chat.use: talk to parents (Teacher, Principal, School Admin; a school can grant it to other roles).
update identity.role
set permissions = array_append(permissions, 'chat.use')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER')
  and not ('chat.use' = any (permissions));

-- chat.oversee: read any conversation (audited), close and reopen conversations, review reports, staff availability.
update identity.role
set permissions = array_append(permissions, 'chat.oversee')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('chat.oversee' = any (permissions));

-- ptm.manage: set up parent-teacher meetings, book for families, everyone's schedules and the attendance summary.
update identity.role
set permissions = array_append(permissions, 'ptm.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('ptm.manage' = any (permissions));
