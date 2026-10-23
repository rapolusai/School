-- Phase 3: languages. Telugu joins English and Hindi.
--
-- 1. The school's default message language (notifications.settings.alert_language) may now be 'te'.
-- 2. Each person may choose their own language (preferences.user_language): the web app uses it on sign-in, and
--    messages to a parent go out in it instead of the school's default. One row per person; tenant-scoped with the
--    same tenant_isolation policy as V2/V3 and a composite (tenant_id, id) foreign key to the person's sign-in.

alter table notifications.settings drop constraint ck_settings_language;
alter table notifications.settings
    add constraint ck_settings_language check (alert_language in ('en', 'hi', 'te'));

create schema if not exists preferences;

create table preferences.user_language (
    id               uuid primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    user_account_id  uuid        not null,
    language         varchar(5)  not null,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null default 0,
    constraint uq_user_language_tenant_id unique (tenant_id, id),
    constraint uq_user_language_user unique (tenant_id, user_account_id),
    constraint ck_user_language_language check (language in ('en', 'hi', 'te')),
    constraint fk_user_language_user foreign key (tenant_id, user_account_id)
        references identity.user_account (tenant_id, id) on delete cascade
);
create index ix_user_language_user on preferences.user_language (user_account_id);

alter table preferences.user_language enable row level security;
create policy tenant_isolation on preferences.user_language
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

grant usage on schema preferences to ${appRole};
grant select, insert, update, delete on preferences.user_language to ${appRole};
