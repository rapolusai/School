-- Subscriptions and audit, completed:
--   * A cancellation keeps who asked and why (cancel_reason, cancelled_by_name): a School Admin from the Billing page,
--     or the Super Admin for the school. A school still on its free trial can cancel too: it is then suspended when
--     the trial ends instead of being chased as past due.
--   * audit.platform_event: the Super Admin's own trail across schools. Super Admin sign-ins (and failed ones) and
--     sign-outs, every change a Super Admin makes to a school, and every subscription, invoice and school status change
--     whoever made it (a School Admin, the renewal job, the payment gateway). Platform data like platform.tenant: no
--     row-level security, read only by the platform console. Append-only like audit.audit_event.
--   * The Super Admin can end a subscription at once ("End now"); the suspension reason gets room for its prefix.
--   * audit.audit_event gains an index for the school audit page's filter by action.

alter table billing.subscription add column cancel_reason varchar(500);
-- The Super Admin's "End now" keeps its reason after a short prefix.
alter table billing.subscription alter column suspension_reason type varchar(600);
alter table billing.subscription add column cancelled_by_name varchar(200);
alter table billing.subscription add constraint ck_subscription_cancel_reason check (
    cancel_at_period_end or (cancel_reason is null and cancelled_by_name is null));

create table audit.platform_event (
    id          uuid         not null,
    at          timestamptz  not null,
    actor_id    uuid,
    actor_name  varchar(200),
    platform_admin boolean   not null default false,
    action      varchar(80)  not null,
    tenant_id   uuid,
    entity_type varchar(80),
    entity_id   varchar(80),
    details     jsonb        not null default '{}'::jsonb,
    ip          varchar(64),
    primary key (at, id)
);

create index ix_platform_event_tenant on audit.platform_event (tenant_id, at desc);
create index ix_platform_event_action on audit.platform_event (action, at desc);

create index ix_audit_event_action on audit.audit_event (tenant_id, action, at desc);

grant select, insert on audit.platform_event to ${appRole};
