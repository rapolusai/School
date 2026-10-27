-- Phase 3, subscriptions: a School Admin changes the school's plan from its Billing page.
--   * An upgrade takes effect at once and is billed for the rest of the current period on an invoice of its own
--     (kind UPGRADE, previous_plan set): the per-student price difference, prorated by day, plus GST. A period's own
--     invoice stays kind PERIOD, and "one live invoice per school and period" now counts PERIOD invoices only.
--   * A downgrade is scheduled for the next renewal (pending_plan) and can be withdrawn until then.
--   * A cancellation (cancel_at_period_end) stops the renewal: at the end of the period the school is suspended
--     instead of renewed. It can be withdrawn until then.

alter table billing.invoice add column kind varchar(10) not null default 'PERIOD';
alter table billing.invoice add column previous_plan varchar(20);
alter table billing.invoice add constraint ck_invoice_kind check (
    (kind = 'PERIOD' and previous_plan is null)
    or (kind = 'UPGRADE' and previous_plan in ('STARTER', 'GROWTH', 'ENTERPRISE') and previous_plan <> plan));

drop index billing.uq_invoice_period;
create unique index uq_invoice_period on billing.invoice (tenant_id, period_start)
    where status <> 'CANCELLED' and kind = 'PERIOD';

alter table billing.subscription add column pending_plan varchar(20);
alter table billing.subscription add column pending_plan_requested_at timestamptz;
alter table billing.subscription add column cancel_at_period_end boolean not null default false;
alter table billing.subscription add column cancel_requested_at timestamptz;
alter table billing.subscription add constraint ck_subscription_pending_plan check (
    (pending_plan is null and pending_plan_requested_at is null)
    or (pending_plan in ('STARTER', 'GROWTH', 'ENTERPRISE') and pending_plan_requested_at is not null));
alter table billing.subscription add constraint ck_subscription_cancel check (
    cancel_at_period_end = (cancel_requested_at is not null));
