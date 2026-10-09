-- Phase 1, slice 1: school setup. Academic years, classes, sections, subjects and the school's contact profile.
-- Every school-owned table carries tenant_id and is protected by row-level security, exactly like V1.

create schema if not exists academics;

-- Defense in depth: child rows reference their parents by (tenant_id, id), so the database itself refuses a link
-- between two schools even if application code passed a foreign id. Foreign key checks are not filtered by
-- row-level security, so this composite key is what closes that gap.
alter table identity.user_account add constraint uq_user_account_tenant_id unique (tenant_id, id);

-- ---------------------------------------------------------------- school profile (platform data, not RLS-secured)

alter table platform.tenant
    add column address       varchar(500),
    add column phone         varchar(20),
    add column contact_email varchar(254),
    add column udise_code    varchar(11),
    add constraint ck_tenant_udise_code check (udise_code is null or udise_code ~ '^[0-9]{11}$');

-- ---------------------------------------------------------------- academic years

create table academics.academic_year (
    id         uuid primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    name       varchar(20) not null,
    starts_on  date        not null,
    ends_on    date        not null,
    is_current boolean     not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null default 0,
    constraint ck_academic_year_dates check (ends_on > starts_on),
    constraint uq_academic_year_tenant_id unique (tenant_id, id)
);
create unique index uq_academic_year_name on academics.academic_year (tenant_id, lower(name));
-- At most one current year per school.
create unique index uq_academic_year_current on academics.academic_year (tenant_id) where is_current;

-- ---------------------------------------------------------------- classes, sections, subjects

create table academics.school_class (
    id            uuid primary key,
    tenant_id     uuid        not null references platform.tenant (id),
    name          varchar(40) not null,
    display_order integer     not null,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    version       bigint      not null default 0,
    constraint uq_school_class_tenant_id unique (tenant_id, id)
);
create unique index uq_school_class_name on academics.school_class (tenant_id, lower(name));

create table academics.section (
    id               uuid primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    class_id         uuid        not null,
    name             varchar(20) not null,
    capacity         integer,
    class_teacher_id uuid,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null default 0,
    constraint ck_section_capacity check (capacity is null or capacity between 1 and 500),
    constraint uq_section_tenant_id unique (tenant_id, id),
    constraint fk_section_class foreign key (tenant_id, class_id) references academics.school_class (tenant_id, id),
    constraint fk_section_class_teacher foreign key (tenant_id, class_teacher_id)
        references identity.user_account (tenant_id, id) on delete set null (class_teacher_id)
);
create unique index uq_section_name on academics.section (class_id, lower(name));
create index ix_section_tenant on academics.section (tenant_id);
create index ix_section_class_teacher on academics.section (class_teacher_id);

create table academics.subject (
    id         uuid primary key,
    tenant_id  uuid         not null references platform.tenant (id),
    name       varchar(100) not null,
    code       varchar(20),
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    version    bigint       not null default 0,
    constraint uq_subject_tenant_id unique (tenant_id, id)
);
create unique index uq_subject_name on academics.subject (tenant_id, lower(name));
create unique index uq_subject_code on academics.subject (tenant_id, lower(code)) where code is not null;

-- Which subjects a class studies. Carries tenant_id so the same simple policy applies.
create table academics.class_subject (
    id         uuid primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    class_id   uuid        not null,
    subject_id uuid        not null,
    created_at timestamptz not null,
    constraint uq_class_subject unique (class_id, subject_id),
    constraint fk_class_subject_class foreign key (tenant_id, class_id)
        references academics.school_class (tenant_id, id) on delete cascade,
    constraint fk_class_subject_subject foreign key (tenant_id, subject_id) references academics.subject (tenant_id, id)
);
create index ix_class_subject_subject on academics.class_subject (subject_id);
create index ix_class_subject_tenant on academics.class_subject (tenant_id);

-- ---------------------------------------------------------------- row-level security

alter table academics.academic_year enable row level security;
create policy tenant_isolation on academics.academic_year
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table academics.school_class enable row level security;
create policy tenant_isolation on academics.school_class
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table academics.section enable row level security;
create policy tenant_isolation on academics.section
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table academics.subject enable row level security;
create policy tenant_isolation on academics.subject
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table academics.class_subject enable row level security;
create policy tenant_isolation on academics.class_subject
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant usage on schema academics to ${appRole};
grant select, insert, update, delete on academics.academic_year, academics.school_class, academics.section,
    academics.subject, academics.class_subject to ${appRole};

-- ---------------------------------------------------------------- new permissions for schools that already exist

-- New schools get these from RoleCatalog. Existing schools keep their stored roles, so append the new codes to the
-- matching built-in roles once. Runs as the owner, which row-level security does not restrict. Idempotent.
update identity.role
set permissions = array_append(permissions, 'academics.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER', 'ACCOUNTANT', 'FRONT_OFFICE')
  and not ('academics.read' = any (permissions));

update identity.role
set permissions = array_append(permissions, 'students.manage')
where system_role
  and code = 'FRONT_OFFICE'
  and not ('students.manage' = any (permissions));
