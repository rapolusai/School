-- Phase 1: the parent and student app. Parents apply for their child's absence (an absence note); the child's class
-- teacher, or anyone with attendance.manage, approves or rejects it. Approved leave pre-fills the register as leave and
-- stops absence alerts for those days. Only what the note needs is stored: the dates, an optional half day and the
-- parent's reason. Like every school-owned table it carries tenant_id, is protected by the same tenant_isolation policy
-- as V2/V3, and links to other school tables through composite (tenant_id, id) foreign keys.

create table attendance.leave_request (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    student_id        uuid         not null,
    -- The section the student was in when the parent applied: its class teacher decides the request.
    section_id        uuid         not null,
    academic_year_id  uuid         not null,
    from_date         date         not null,
    to_date           date         not null,
    half_day          boolean      not null default false,
    reason            varchar(500) not null,
    status            varchar(10)  not null,
    -- Names are kept as well, so the history still reads correctly after a sign-in is removed.
    requested_by_id   uuid,
    requested_by_name varchar(200),
    decided_by_id     uuid,
    decided_by_name   varchar(200),
    decided_at        timestamptz,
    decision_comment  varchar(500),
    cancelled_by_id   uuid,
    cancelled_by_name varchar(200),
    cancelled_at      timestamptz,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint ck_child_leave_status check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    constraint ck_child_leave_dates check (to_date >= from_date),
    constraint ck_child_leave_half_day check (not half_day or from_date = to_date),
    -- Decided requests have a decision time and waiting ones none; approved leave cancelled later keeps its decision.
    constraint ck_child_leave_decided check ((status not in ('APPROVED', 'REJECTED') or decided_at is not null)
        and (status <> 'PENDING' or decided_at is null)),
    constraint ck_child_leave_cancelled check ((status = 'CANCELLED') = (cancelled_at is not null)),
    constraint uq_child_leave_tenant_id unique (tenant_id, id),
    constraint fk_child_leave_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id) on delete cascade,
    constraint fk_child_leave_section foreign key (tenant_id, section_id) references academics.section (tenant_id, id),
    constraint fk_child_leave_year foreign key (tenant_id, academic_year_id)
        references academics.academic_year (tenant_id, id),
    constraint fk_child_leave_requested_by foreign key (tenant_id, requested_by_id)
        references identity.user_account (tenant_id, id) on delete set null (requested_by_id),
    constraint fk_child_leave_decided_by foreign key (tenant_id, decided_by_id)
        references identity.user_account (tenant_id, id) on delete set null (decided_by_id),
    constraint fk_child_leave_cancelled_by foreign key (tenant_id, cancelled_by_id)
        references identity.user_account (tenant_id, id) on delete set null (cancelled_by_id)
);
create index ix_child_leave_student on attendance.leave_request (tenant_id, student_id, from_date);
create index ix_child_leave_section on attendance.leave_request (tenant_id, section_id, from_date);
create index ix_child_leave_pending on attendance.leave_request (tenant_id, section_id) where status = 'PENDING';
create index ix_child_leave_year on attendance.leave_request (academic_year_id);
create index ix_child_leave_requested_by on attendance.leave_request (requested_by_id);
create index ix_child_leave_decided_by on attendance.leave_request (decided_by_id);
create index ix_child_leave_cancelled_by on attendance.leave_request (cancelled_by_id);

-- ---------------------------------------------------------------- row-level security

alter table attendance.leave_request enable row level security;
create policy tenant_isolation on attendance.leave_request
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant select, insert, update, delete on attendance.leave_request to ${appRole};

-- No new permissions: parents apply with child.view, class teachers decide with attendance.mark for their own
-- sections, and attendance.manage decides for every section.
