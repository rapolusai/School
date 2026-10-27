-- Phase 3, subscriptions: the daily renewal job (BillingRenewalJob). It raises renewal invoices, marks schools past due
-- when an invoice is unpaid after its due date or a trial has ended unpaid, and suspends them after a grace period.
-- Two dates on the school's subscription row (platform data, like the rest of billing.subscription):
--   * auto_past_due_since: the day the job marked the school past due. Only such schools are made active again
--     automatically once nothing is overdue; a school the Super Admin marked past due by hand stays as it is.
--   * grace_restarted_on: the day the Super Admin last reactivated the school. The grace period before an automatic
--     suspension counts from this day too, so a reactivated school is not suspended again the next morning.

alter table billing.subscription add column auto_past_due_since date;
alter table billing.subscription add column grace_restarted_on date;
