-- Phase 2, certificates and ID cards: the school's certificate header and signatory, one editable template per
-- certificate type, certificates (requested, prepared, signed and issued, cancelled, corrected as a new version), the
-- serial counters per type and academic year, the ID card register, and a per-school rate limit-free public lookup of a
-- certificate by its random verification token. Students and staff are referred to by id only; the names and details
-- printed on a certificate are copied into the certificate's fields when it is prepared, so a certificate still reads
-- correctly later. Every table carries tenant_id and the same tenant_isolation policy as V2/V3, and every link between
-- school tables is a composite (tenant_id, id) foreign key.

create schema if not exists certificates;

-- ---------------------------------------------------------------- settings (one row per school, made on first use)

create table certificates.settings (
    id                    uuid primary key,
    tenant_id             uuid         not null references platform.tenant (id),
    -- The printed header: the school's name and address as they should appear, the board affiliation number and a
    -- short text in place of a logo ("ADS").
    header_name           varchar(200),
    header_address        varchar(500),
    affiliation_no        varchar(60),
    logo_text             varchar(12),
    signatory_name        varchar(200),
    signatory_designation varchar(100),
    -- Warn (never block) when a student with fees due is given a transfer certificate.
    tc_dues_check         boolean      not null default true,
    -- Months an ID card stays valid from its issue, and the fee noted for a lost card (paise; 0 = none).
    card_valid_months     integer      not null default 12,
    lost_card_fee_paise   bigint       not null default 0,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0,
    constraint uq_certificates_settings_tenant unique (tenant_id),
    constraint ck_certificates_settings_months check (card_valid_months between 1 and 60),
    constraint ck_certificates_settings_fee check (lost_card_fee_paise between 0 and 10000000)
);

-- ---------------------------------------------------------------- templates (one per certificate type)

create table certificates.template (
    id            uuid primary key,
    tenant_id     uuid         not null references platform.tenant (id),
    cert_type     varchar(20)  not null,
    title         varchar(120) not null,
    -- The wording with placeholders such as {student}, {admissionNo}, {class}, {dob}, {father}, {mother}, {from},
    -- {to}, {conduct} and {reason}; rendered when the certificate is prepared and frozen when it is signed.
    body          text         not null,
    -- Serial numbers are the prefix, the academic year and a running number: TC/2026-27/0001.
    serial_prefix varchar(8)   not null,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0,
    constraint uq_template_type unique (tenant_id, cert_type),
    constraint ck_template_type check (cert_type in ('TRANSFER', 'BONAFIDE', 'CHARACTER', 'FEE', 'STUDY',
        'MIGRATION', 'CUSTOM', 'EXPERIENCE', 'SALARY')),
    constraint ck_template_prefix check (serial_prefix ~ '^[A-Z0-9-]{1,8}$')
);

-- One counter per type and academic year, moved under a row lock when a certificate is signed.
create table certificates.serial_counter (
    tenant_id     uuid        not null references platform.tenant (id),
    cert_type     varchar(20) not null,
    academic_year varchar(7)  not null,
    last_seq      integer     not null default 0,
    updated_at    timestamptz not null,
    primary key (tenant_id, cert_type, academic_year),
    constraint ck_serial_counter_year check (academic_year ~ '^[0-9]{4}-[0-9]{2}$')
);

-- ---------------------------------------------------------------- certificates

-- A certificate for a student (student_id) or a member of staff (staff_user_id), never both. REQUESTED by a parent
-- or the office, PREPARED with its fields filled in (editable), ISSUED once a signatory signs it (serial number,
-- frozen wording, verification token), or CANCELLED with a reason. A correction cancels the certificate and makes a
-- new version pointing back at it. Rows are never deleted.
create table certificates.certificate (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    cert_type         varchar(20)  not null,
    status            varchar(10)  not null,
    subject_type      varchar(10)  not null,
    student_id        uuid,
    staff_user_id     uuid,
    title             varchar(120) not null,
    purpose           varchar(300),
    -- The placeholder values, as a JSON object of strings, editable until the certificate is signed.
    fields            jsonb        not null default '{}'::jsonb,
    -- The wording as signed; null until then.
    body              text,
    serial_no         varchar(30),
    academic_year     varchar(7),
    version_no        integer      not null default 1,
    supersedes_id     uuid,
    verify_token      varchar(40),
    requested_via     varchar(10)  not null,
    requested_by_id   uuid,
    requested_by_name varchar(200),
    requested_at      timestamptz  not null,
    prepared_by_id    uuid,
    prepared_by_name  varchar(200),
    prepared_at       timestamptz,
    signed_by_id      uuid,
    signed_by_name    varchar(200),
    signed_at         timestamptz,
    issued_on         date,
    cancelled_by_id   uuid,
    cancelled_by_name varchar(200),
    cancelled_at      timestamptz,
    cancel_reason     varchar(500),
    -- Fees still due when a transfer certificate was prepared (a warning, never a block).
    dues_paise        bigint,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint uq_certificate_tenant_id unique (tenant_id, id),
    constraint fk_certificate_student foreign key (tenant_id, student_id)
        references students.student (tenant_id, id),
    constraint fk_certificate_staff foreign key (tenant_id, staff_user_id)
        references identity.user_account (tenant_id, id),
    constraint fk_certificate_supersedes foreign key (tenant_id, supersedes_id)
        references certificates.certificate (tenant_id, id),
    constraint fk_certificate_requested_by foreign key (tenant_id, requested_by_id)
        references identity.user_account (tenant_id, id) on delete set null (requested_by_id),
    constraint fk_certificate_prepared_by foreign key (tenant_id, prepared_by_id)
        references identity.user_account (tenant_id, id) on delete set null (prepared_by_id),
    constraint fk_certificate_signed_by foreign key (tenant_id, signed_by_id)
        references identity.user_account (tenant_id, id) on delete set null (signed_by_id),
    constraint fk_certificate_cancelled_by foreign key (tenant_id, cancelled_by_id)
        references identity.user_account (tenant_id, id) on delete set null (cancelled_by_id),
    constraint ck_certificate_type check (cert_type in ('TRANSFER', 'BONAFIDE', 'CHARACTER', 'FEE', 'STUDY',
        'MIGRATION', 'CUSTOM', 'EXPERIENCE', 'SALARY')),
    constraint ck_certificate_status check (status in ('REQUESTED', 'PREPARED', 'ISSUED', 'CANCELLED')),
    constraint ck_certificate_subject check ((subject_type = 'STUDENT' and student_id is not null
        and staff_user_id is null) or (subject_type = 'STAFF' and staff_user_id is not null and student_id is null)),
    constraint ck_certificate_via check (requested_via in ('OFFICE', 'FAMILY')),
    -- Signing gives the serial number, the token, the frozen wording and the issue date together.
    constraint ck_certificate_signed check ((signed_at is not null) = (serial_no is not null
        and verify_token is not null and body is not null and issued_on is not null)),
    -- ISSUED means signed; REQUESTED and PREPARED mean not yet; a CANCELLED certificate may be either.
    constraint ck_certificate_status_signed check (case status when 'ISSUED' then signed_at is not null
        when 'CANCELLED' then true else signed_at is null end),
    constraint ck_certificate_cancelled check ((status = 'CANCELLED') = (cancelled_at is not null
        and cancel_reason is not null)),
    constraint ck_certificate_version check (version_no >= 1 and (version_no = 1) = (supersedes_id is null))
);
create unique index uq_certificate_serial on certificates.certificate (tenant_id, serial_no) where serial_no is not null;
-- Tokens are random and looked up without a school selected (the public verification page), so unique everywhere.
create unique index uq_certificate_token on certificates.certificate (verify_token) where verify_token is not null;
create index ix_certificate_student on certificates.certificate (tenant_id, student_id) where student_id is not null;
create index ix_certificate_staff on certificates.certificate (tenant_id, staff_user_id) where staff_user_id is not null;
create index ix_certificate_status on certificates.certificate (tenant_id, status);
create index ix_certificate_issued_on on certificates.certificate (tenant_id, issued_on) where issued_on is not null;
create index ix_certificate_supersedes on certificates.certificate (supersedes_id);
create index ix_certificate_requested_by on certificates.certificate (requested_by_id);
create index ix_certificate_prepared_by on certificates.certificate (prepared_by_id);
create index ix_certificate_signed_by on certificates.certificate (signed_by_id);
create index ix_certificate_cancelled_by on certificates.certificate (cancelled_by_id);

-- The public verification page runs without a school selected, so row-level security hides every certificate from
-- it. This function tells it only which school a token belongs to; it then works as that school and shows the few
-- fields the page may show. Unknown tokens give null.
create function certificates.tenant_of_verify_token(p_token varchar)
    returns uuid
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select c.tenant_id from certificates.certificate c where c.verify_token = p_token
$$;

-- ---------------------------------------------------------------- ID cards (the card register)

-- One row per card printed for a student or a member of staff. A lost or damaged card is reissued: the old row is
-- CANCELLED with the reason and a new one made with the next issue number. Only what the card shows is kept here:
-- the blood group and emergency number given on the request (the student record is not changed) and the validity.
create table certificates.id_card (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    subject_type       varchar(10)  not null,
    student_id         uuid,
    staff_user_id      uuid,
    card_no            varchar(30)  not null,
    issue_no           integer      not null default 1,
    status             varchar(10)  not null,
    blood_group        varchar(3),
    emergency_phone    varchar(20),
    valid_from         date         not null,
    valid_until        date         not null,
    replaces_id        uuid,
    reissue_reason     varchar(300),
    -- A lost card's fee is noted here (the fees module has no public way to add a charge).
    lost_fee_paise     bigint,
    fee_note           varchar(300),
    issued_by_id       uuid,
    issued_by_name     varchar(200),
    cancelled_at       timestamptz,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    constraint uq_id_card_tenant_id unique (tenant_id, id),
    constraint fk_id_card_student foreign key (tenant_id, student_id) references students.student (tenant_id, id),
    constraint fk_id_card_staff foreign key (tenant_id, staff_user_id)
        references identity.user_account (tenant_id, id),
    constraint fk_id_card_replaces foreign key (tenant_id, replaces_id)
        references certificates.id_card (tenant_id, id),
    constraint fk_id_card_issued_by foreign key (tenant_id, issued_by_id)
        references identity.user_account (tenant_id, id) on delete set null (issued_by_id),
    constraint ck_id_card_subject check ((subject_type = 'STUDENT' and student_id is not null
        and staff_user_id is null) or (subject_type = 'STAFF' and staff_user_id is not null and student_id is null)),
    constraint ck_id_card_status check (status in ('ACTIVE', 'CANCELLED')),
    constraint ck_id_card_blood_group check (blood_group is null or blood_group in ('A+', 'A-', 'B+', 'B-', 'AB+',
        'AB-', 'O+', 'O-')),
    constraint ck_id_card_dates check (valid_until >= valid_from),
    constraint ck_id_card_issue_no check (issue_no >= 1 and (issue_no = 1) = (replaces_id is null)),
    constraint ck_id_card_cancelled check ((status = 'CANCELLED') = (cancelled_at is not null)),
    constraint ck_id_card_fee check (lost_fee_paise is null or lost_fee_paise between 0 and 10000000)
);
create unique index uq_id_card_no on certificates.id_card (tenant_id, card_no);
-- One active card per person.
create unique index uq_id_card_active_student on certificates.id_card (student_id)
    where status = 'ACTIVE' and student_id is not null;
create unique index uq_id_card_active_staff on certificates.id_card (staff_user_id)
    where status = 'ACTIVE' and staff_user_id is not null;
create index ix_id_card_student on certificates.id_card (tenant_id, student_id) where student_id is not null;
create index ix_id_card_staff on certificates.id_card (tenant_id, staff_user_id) where staff_user_id is not null;
create index ix_id_card_replaces on certificates.id_card (replaces_id);
create index ix_id_card_issued_by on certificates.id_card (issued_by_id);

-- ---------------------------------------------------------------- row-level security

alter table certificates.settings enable row level security;
create policy tenant_isolation on certificates.settings
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table certificates.template enable row level security;
create policy tenant_isolation on certificates.template
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table certificates.serial_counter enable row level security;
create policy tenant_isolation on certificates.serial_counter
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table certificates.certificate enable row level security;
create policy tenant_isolation on certificates.certificate
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table certificates.id_card enable row level security;
create policy tenant_isolation on certificates.id_card
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema certificates to ${appRole};
grant select, insert, update on certificates.settings, certificates.template, certificates.serial_counter
    to ${appRole};
-- Certificates and cards are the school's record of what it issued: never deleted.
grant select, insert, update on certificates.certificate, certificates.id_card to ${appRole};
revoke all on function certificates.tenant_of_verify_token(varchar) from public;
grant execute on function certificates.tenant_of_verify_token(varchar) to ${appRole};

-- ---------------------------------------------------------------- the certificate permissions

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.

-- certificates.read: the certificate register, templates and the ID card register.
update identity.role
set permissions = array_append(permissions, 'certificates.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'FRONT_OFFICE')
  and not ('certificates.read' = any (permissions));

-- certificates.issue: preparing certificates, templates, ID cards and corrections.
update identity.role
set permissions = array_append(permissions, 'certificates.issue')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'FRONT_OFFICE')
  and not ('certificates.issue' = any (permissions));

-- certificates.sign: signing a prepared certificate, which issues it.
update identity.role
set permissions = array_append(permissions, 'certificates.sign')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('certificates.sign' = any (permissions));
