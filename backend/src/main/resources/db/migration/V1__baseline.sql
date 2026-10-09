-- Phase 0 baseline: schools (tenants), platform admins, users, roles, refresh tokens and the audit trail.
-- Every school-owned table carries tenant_id and is protected by row-level security (RLS).
-- The runtime role ${appRole} is never the owner of these tables, so RLS always applies to it.

do $$
begin
    if not exists (select 1 from pg_roles where rolname = '${appRole}') then
        execute 'create role ${appRole} nologin nosuperuser nobypassrls';
    end if;
end
$$;

create schema if not exists platform;
create schema if not exists identity;
create schema if not exists audit;

-- The school the current connection works for, set by the API on every borrowed connection.
create function platform.current_tenant_id() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

-- ---------------------------------------------------------------- platform

create table platform.tenant (
    id            uuid primary key,
    code          varchar(40)  not null,
    name          varchar(200) not null,
    board         varchar(20)  not null,
    city          varchar(100),
    plan          varchar(20)  not null,
    status        varchar(20)  not null,
    trial_ends_at timestamptz,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0,
    constraint uq_tenant_code unique (code),
    constraint ck_tenant_code check (code ~ '^[a-z][a-z0-9-]{2,39}$'),
    constraint ck_tenant_status check (status in ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED')),
    constraint ck_tenant_plan check (plan in ('STARTER', 'GROWTH', 'ENTERPRISE'))
);

create table platform.platform_admin (
    id            uuid primary key,
    email         varchar(254) not null,
    name          varchar(200) not null,
    password_hash varchar(255) not null,
    status        varchar(20)  not null,
    last_login_at timestamptz,
    created_at    timestamptz  not null
);
create unique index uq_platform_admin_email on platform.platform_admin (lower(email));

-- ---------------------------------------------------------------- identity

create table identity.role (
    id          uuid primary key,
    tenant_id   uuid         not null references platform.tenant (id),
    code        varchar(40)  not null,
    name        varchar(100) not null,
    permissions text[]       not null default '{}',
    system_role boolean      not null default true,
    created_at  timestamptz  not null,
    constraint uq_role_code unique (tenant_id, code)
);

create table identity.user_account (
    id            uuid primary key,
    tenant_id     uuid         not null references platform.tenant (id),
    email         varchar(254) not null,
    name          varchar(200) not null,
    password_hash varchar(255) not null,
    status        varchar(20)  not null,
    last_login_at timestamptz,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0,
    constraint ck_user_status check (status in ('ACTIVE', 'DISABLED'))
);
create unique index uq_user_email on identity.user_account (tenant_id, lower(email));

create table identity.user_role (
    user_id uuid not null references identity.user_account (id) on delete cascade,
    role_id uuid not null references identity.role (id),
    primary key (user_id, role_id)
);
create index ix_user_role_role on identity.user_role (role_id);

create table identity.refresh_token (
    id         uuid primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    user_id    uuid        not null references identity.user_account (id) on delete cascade,
    family_id  uuid        not null,
    token_hash varchar(64) not null,
    expires_at timestamptz not null,
    created_at timestamptz not null,
    revoked_at timestamptz,
    replaced_by uuid,
    constraint uq_refresh_token_hash unique (token_hash)
);
create index ix_refresh_token_family on identity.refresh_token (tenant_id, family_id);
create index ix_refresh_token_user on identity.refresh_token (user_id);

-- ---------------------------------------------------------------- audit (append-only, partitioned by time)

create table audit.audit_event (
    id          uuid         not null,
    tenant_id   uuid         not null,
    at          timestamptz  not null,
    actor_id    uuid,
    actor_name  varchar(200),
    action      varchar(80)  not null,
    entity_type varchar(80),
    entity_id   varchar(80),
    details     jsonb        not null default '{}'::jsonb,
    ip          varchar(64),
    primary key (tenant_id, at, id)
) partition by range (at);

create table audit.audit_event_default partition of audit.audit_event default;

-- ---------------------------------------------------------------- row-level security

alter table identity.role enable row level security;
create policy tenant_isolation on identity.role
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table identity.user_account enable row level security;
create policy tenant_isolation on identity.user_account
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- user_role has no tenant_id of its own: a link is visible only when its user is, and can only join a user and a
-- role that both belong to the current school.
alter table identity.user_role enable row level security;
create policy tenant_isolation on identity.user_role
    using (exists (select 1 from identity.user_account u where u.id = user_role.user_id))
    with check (exists (select 1 from identity.user_account u where u.id = user_role.user_id)
            and exists (select 1 from identity.role r where r.id = user_role.role_id));

alter table identity.refresh_token enable row level security;
create policy tenant_isolation on identity.refresh_token
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table audit.audit_event enable row level security;
create policy tenant_isolation on audit.audit_event
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- Platform admins see how many users each school has without reading any user rows.
create function platform.tenant_user_counts()
    returns table (tenant_id uuid, user_count bigint)
    language sql stable security definer
    set search_path = pg_catalog
as $$ select u.tenant_id, count(*) from identity.user_account u group by u.tenant_id $$;

-- ---------------------------------------------------------------- grants for the runtime role

grant usage on schema platform, identity, audit to ${appRole};
grant select, insert, update on platform.tenant, platform.platform_admin to ${appRole};
grant delete on platform.tenant to ${appRole};
grant select, insert, update, delete on identity.role, identity.user_account, identity.user_role,
    identity.refresh_token to ${appRole};
grant select, insert on audit.audit_event to ${appRole};
revoke all on function platform.tenant_user_counts() from public;
grant execute on function platform.current_tenant_id(), platform.tenant_user_counts() to ${appRole};
