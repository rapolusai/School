-- Production hardening, additive only:
-- 1. platform.rate_limit: sign-in and public-endpoint limits shared by every API instance (they were in memory).
-- 2. platform.platform_refresh_token: rotated refresh tokens for Super Admins, so a reload keeps them signed in.
-- 3. notifications.whatsapp_opt_out: parents' phones that must not get WhatsApp because WhatsApp consent was
--    withdrawn or declined for one of their children (privacy module, ConsentChanged).

-- ---------------------------------------------------------------- 1. shared rate limits

-- One row per limiter key in its current window. key_hash is the SHA-256 of the key (it contains a client IP and
-- possibly an email), so no plain personal data is kept. Not owned by any school: keys are per IP, email or device.
-- Rows whose window is over are deleted by the API every few hundred hits.
create table platform.rate_limit (
    key_hash     varchar(64) primary key,
    window_start timestamptz not null,
    expires_at   timestamptz not null,
    hits         integer     not null,
    constraint ck_rate_limit_hits check (hits >= 0),
    constraint ck_rate_limit_window check (expires_at > window_start)
);
create index ix_rate_limit_expires on platform.rate_limit (expires_at);

-- ---------------------------------------------------------------- 2. Super Admin sessions

-- Like identity.refresh_token, but for platform admins: no school, so no tenant_id and no row-level security (the
-- runtime role reads platform.platform_admin the same way). Only the SHA-256 of the cookie value is stored; every
-- rotation keeps the family id so reuse of an old token ends the whole chain.
create table platform.platform_refresh_token (
    id          uuid primary key,
    admin_id    uuid        not null references platform.platform_admin (id) on delete cascade,
    family_id   uuid        not null,
    token_hash  varchar(64) not null,
    expires_at  timestamptz not null,
    created_at  timestamptz not null,
    revoked_at  timestamptz,
    replaced_by uuid,
    constraint uq_platform_refresh_token_hash unique (token_hash)
);
create index ix_platform_refresh_token_family on platform.platform_refresh_token (family_id);
create index ix_platform_refresh_token_admin on platform.platform_refresh_token (admin_id);

-- ---------------------------------------------------------------- 3. WhatsApp opt-outs

-- One row per child and guardian phone (digits only, last 10 digits for Indian numbers). The dispatcher does not send
-- WhatsApp to a phone with any row here and sends the same text by SMS instead.
create table notifications.whatsapp_opt_out (
    id         uuid primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    student_id uuid        not null,
    address    varchar(20) not null,
    created_at timestamptz not null,
    constraint uq_whatsapp_opt_out unique (tenant_id, student_id, address)
);
create index ix_whatsapp_opt_out_address on notifications.whatsapp_opt_out (tenant_id, address);

alter table notifications.whatsapp_opt_out enable row level security;
create policy tenant_isolation on notifications.whatsapp_opt_out
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant select, insert, update, delete on platform.rate_limit to ${appRole};
grant select, insert, update on platform.platform_refresh_token to ${appRole};
grant select, insert, delete on notifications.whatsapp_opt_out to ${appRole};
