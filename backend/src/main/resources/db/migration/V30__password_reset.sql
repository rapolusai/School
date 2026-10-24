-- "Forgot password": a one-time 6-digit code sent to the person's email. Only a hash of the code is kept; a code
-- lasts 15 minutes and allows 5 tries, and a new request replaces any code still open for the same person.

create table identity.password_reset (
    id           uuid primary key,
    tenant_id    uuid         not null references platform.tenant (id),
    user_id      uuid         not null,
    code_hash    varchar(64)  not null,
    expires_at   timestamptz  not null,
    attempts     integer      not null default 0,
    used_at      timestamptz,
    replaced_at  timestamptz,
    created_at   timestamptz  not null,
    constraint ck_password_reset_attempts check (attempts >= 0),
    constraint fk_password_reset_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id) on delete cascade
);
create index ix_password_reset_user on identity.password_reset (tenant_id, user_id, created_at desc);

alter table identity.password_reset enable row level security;
create policy tenant_isolation on identity.password_reset
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

grant select, insert, update on identity.password_reset to ${appRole};
