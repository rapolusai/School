-- Phase 1, data protection (Digital Personal Data Protection Act, 2023): each school's versioned privacy notice and
-- grievance officer, parents' consent per child and purpose, data requests (access, correction, erasure, grievance)
-- with their timeline, and the data exports made for access requests. Every table carries tenant_id and the same
-- tenant_isolation policy as V1-V6, and every link between school tables is a composite (tenant_id, id) foreign key.

create schema if not exists privacy;

-- ---------------------------------------------------------------- grievance officer (one per school)

-- The person parents contact about their data. Shown on the privacy notice; a copy is kept on each published version.
create table privacy.grievance_officer (
    id         uuid primary key,
    tenant_id  uuid         not null references platform.tenant (id),
    name       varchar(200) not null,
    email      varchar(254) not null,
    phone      varchar(20)  not null,
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    version    bigint       not null default 0,
    constraint uq_grievance_officer_tenant unique (tenant_id)
);

-- ---------------------------------------------------------------- privacy notices (published versions never change)

create table privacy.privacy_notice (
    id                uuid primary key,
    tenant_id         uuid          not null references platform.tenant (id),
    version           integer       not null,
    body_en           varchar(20000) not null,
    body_hi           varchar(20000) not null,
    change_summary    varchar(500),
    grievance_name    varchar(200)  not null,
    grievance_email   varchar(254)  not null,
    grievance_phone   varchar(20)   not null,
    published_at      timestamptz   not null,
    published_by      uuid,
    published_by_name varchar(200),
    created_at        timestamptz   not null,
    constraint ck_privacy_notice_version check (version >= 1),
    constraint uq_privacy_notice_tenant_id unique (tenant_id, id),
    constraint uq_privacy_notice_version unique (tenant_id, version),
    constraint fk_privacy_notice_published_by foreign key (tenant_id, published_by)
        references identity.user_account (tenant_id, id) on delete set null (published_by)
);
create index ix_privacy_notice_published_by on privacy.privacy_notice (published_by);

-- ---------------------------------------------------------------- consent records (append-only history)

-- One row per decision: a parent (online) or the school on the parent's behalf (a signed paper form) gives, declines
-- or withdraws consent for one purpose for one child, against one notice version. A child's current consent for a
-- purpose is its latest row. Rows are never changed or deleted.
create table privacy.consent_record (
    id               uuid primary key,
    tenant_id        uuid         not null references platform.tenant (id),
    student_id       uuid         not null,
    purpose          varchar(12)  not null,
    action           varchar(10)  not null,
    method           varchar(10)  not null,
    notice_id        uuid         not null,
    notice_version   integer      not null,
    given_by_id      uuid,
    given_by_name    varchar(200) not null,
    recorded_by_id   uuid,
    recorded_by_name varchar(200),
    paper_reference  varchar(100),
    signed_on        date,
    at               timestamptz  not null,
    constraint ck_consent_record_purpose check (purpose in ('ESSENTIAL', 'PHOTOS', 'WHATSAPP')),
    constraint ck_consent_record_action check (action in ('GIVEN', 'DECLINED', 'WITHDRAWN')),
    constraint ck_consent_record_method check (method in ('ONLINE', 'PAPER')),
    constraint ck_consent_record_paper check (method = 'ONLINE' or signed_on is not null),
    constraint fk_consent_record_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_consent_record_notice foreign key (tenant_id, notice_id)
        references privacy.privacy_notice (tenant_id, id),
    constraint fk_consent_record_given_by foreign key (tenant_id, given_by_id)
        references identity.user_account (tenant_id, id) on delete set null (given_by_id),
    constraint fk_consent_record_recorded_by foreign key (tenant_id, recorded_by_id)
        references identity.user_account (tenant_id, id) on delete set null (recorded_by_id)
);
create index ix_consent_record_student on privacy.consent_record (tenant_id, student_id, purpose, at desc);
create index ix_consent_record_notice on privacy.consent_record (notice_id);
create index ix_consent_record_given_by on privacy.consent_record (given_by_id);
create index ix_consent_record_recorded_by on privacy.consent_record (recorded_by_id);

-- ---------------------------------------------------------------- data requests

create table privacy.data_request (
    id               uuid primary key,
    tenant_id        uuid          not null references platform.tenant (id),
    type             varchar(12)   not null,
    subject          varchar(6)    not null,
    student_id       uuid,
    requested_by_id  uuid,
    requester_name   varchar(200)  not null,
    details          varchar(2000) not null,
    status           varchar(12)   not null,
    -- The school's policy: an answer within 30 days of the request (a school day in India).
    due_on           date          not null,
    assigned_to_id   uuid,
    assigned_to_name varchar(200),
    resolution       varchar(10),
    closing_note     varchar(2000),
    closed_at        timestamptz,
    closed_by_id     uuid,
    closed_by_name   varchar(200),
    erased_at        timestamptz,
    last_activity_at timestamptz   not null,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint ck_data_request_type check (type in ('ACCESS', 'CORRECTION', 'ERASURE', 'GRIEVANCE')),
    constraint ck_data_request_subject check (subject in ('SELF', 'CHILD')),
    constraint ck_data_request_student check ((subject = 'CHILD') = (student_id is not null)),
    constraint ck_data_request_status check (status in ('SUBMITTED', 'IN_PROGRESS', 'CLOSED')),
    constraint ck_data_request_resolution check (resolution is null or resolution in ('COMPLETED', 'DECLINED')),
    constraint ck_data_request_closed check ((status = 'CLOSED') = (closed_at is not null and resolution is not null)),
    constraint ck_data_request_erased check (erased_at is null or (type = 'ERASURE' and subject = 'CHILD')),
    constraint uq_data_request_tenant_id unique (tenant_id, id),
    constraint fk_data_request_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_data_request_requested_by foreign key (tenant_id, requested_by_id)
        references identity.user_account (tenant_id, id) on delete set null (requested_by_id),
    constraint fk_data_request_assigned_to foreign key (tenant_id, assigned_to_id)
        references identity.user_account (tenant_id, id) on delete set null (assigned_to_id),
    constraint fk_data_request_closed_by foreign key (tenant_id, closed_by_id)
        references identity.user_account (tenant_id, id) on delete set null (closed_by_id)
);
create index ix_data_request_queue on privacy.data_request (tenant_id, status, due_on);
create index ix_data_request_requested_by on privacy.data_request (tenant_id, requested_by_id, created_at desc);
create index ix_data_request_student on privacy.data_request (student_id);
create index ix_data_request_assigned_to on privacy.data_request (assigned_to_id);
create index ix_data_request_closed_by on privacy.data_request (closed_by_id);

-- Every step of a request, in order: submitted, assigned, replies from either side, export ready and downloaded,
-- erased, closed.
-- Rows are never changed or deleted.
create table privacy.request_event (
    id         uuid primary key,
    tenant_id  uuid          not null references platform.tenant (id),
    request_id uuid          not null,
    kind       varchar(20)   not null,
    actor_id   uuid,
    actor_name varchar(200),
    body       varchar(2000),
    at         timestamptz   not null,
    constraint ck_request_event_kind check (kind in ('SUBMITTED', 'ASSIGNED', 'STAFF_REPLY', 'PARENT_REPLY',
        'EXPORT_READY', 'EXPORT_DOWNLOADED', 'ERASED', 'CLOSED')),
    constraint fk_request_event_request foreign key (tenant_id, request_id)
        references privacy.data_request (tenant_id, id),
    constraint fk_request_event_actor foreign key (tenant_id, actor_id)
        references identity.user_account (tenant_id, id) on delete set null (actor_id)
);
create index ix_request_event_request on privacy.request_event (request_id, at);
create index ix_request_event_tenant on privacy.request_event (tenant_id);
create index ix_request_event_actor on privacy.request_event (actor_id);

-- ---------------------------------------------------------------- data exports for access requests

-- The ZIP file is kept here until it expires (7 days), is replaced by a newer one, or the child's data is erased; the
-- row then stays as a record without the file.
create table privacy.data_export (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    request_id         uuid         not null,
    student_id         uuid,
    file_name          varchar(120) not null,
    content            bytea,
    size_bytes         bigint       not null,
    sha256             varchar(64)  not null,
    status             varchar(10)  not null,
    created_by_id      uuid,
    created_by_name    varchar(200) not null,
    created_at         timestamptz  not null,
    expires_at         timestamptz  not null,
    deleted_at         timestamptz,
    download_count     integer      not null default 0,
    last_downloaded_at timestamptz,
    version            bigint       not null default 0,
    constraint ck_data_export_status check (status in ('READY', 'EXPIRED', 'REPLACED', 'ERASED')),
    constraint ck_data_export_content check ((status = 'READY') = (content is not null)),
    constraint ck_data_export_expiry check (expires_at > created_at),
    constraint ck_data_export_size check (size_bytes >= 0 and download_count >= 0),
    constraint fk_data_export_request foreign key (tenant_id, request_id)
        references privacy.data_request (tenant_id, id),
    constraint fk_data_export_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_data_export_created_by foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_data_export_request on privacy.data_export (request_id, created_at desc);
create index ix_data_export_tenant on privacy.data_export (tenant_id);
create index ix_data_export_student on privacy.data_export (student_id);
create index ix_data_export_created_by on privacy.data_export (created_by_id);
create index ix_data_export_due on privacy.data_export (expires_at) where status = 'READY';

-- ---------------------------------------------------------------- row-level security

alter table privacy.grievance_officer enable row level security;
create policy tenant_isolation on privacy.grievance_officer
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table privacy.privacy_notice enable row level security;
create policy tenant_isolation on privacy.privacy_notice
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table privacy.consent_record enable row level security;
create policy tenant_isolation on privacy.consent_record
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table privacy.data_request enable row level security;
create policy tenant_isolation on privacy.data_request
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table privacy.request_event enable row level security;
create policy tenant_isolation on privacy.request_event
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table privacy.data_export enable row level security;
create policy tenant_isolation on privacy.data_export
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- The clean-up job runs without a school selected, so row-level security hides every export from it. This function
-- tells it only which schools have exports past their expiry; it then deletes them as each school in turn.
create function privacy.tenants_with_expired_exports(p_now timestamptz, p_limit integer)
    returns table (tenant_id uuid)
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select e.tenant_id
    from privacy.data_export e
    where e.status = 'READY' and e.expires_at <= p_now
    group by e.tenant_id
    order by min(e.expires_at)
    limit p_limit
$$;

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema privacy to ${appRole};
grant select, insert, update on privacy.grievance_officer, privacy.data_request, privacy.data_export to ${appRole};
-- Published notices, consent decisions and request timelines are a record: they can be added and read, never changed.
grant select, insert on privacy.privacy_notice, privacy.consent_record, privacy.request_event to ${appRole};
revoke all on function privacy.tenants_with_expired_exports(timestamptz, integer) from public;
grant execute on function privacy.tenants_with_expired_exports(timestamptz, integer) to ${appRole};

-- ---------------------------------------------------------------- new permission for schools that already exist

-- privacy.manage: the privacy notice, the grievance officer, paper consent, and the data request queue (exports and
-- erasure). New schools get it from RoleCatalog; existing schools' built-in roles get it here once. Runs as the owner.
-- Idempotent.
update identity.role
set permissions = array_append(permissions, 'privacy.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('privacy.manage' = any (permissions));
