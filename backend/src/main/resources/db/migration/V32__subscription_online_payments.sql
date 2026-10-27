-- Phase 3, subscriptions: a School Admin pays an open subscription invoice online, through Akshara's own payment
-- gateway account (akshara.billing.payments; never a school's fee gateway). Like fees.payment_order / gateway_event
-- (V6), but the money is Akshara's and the payment lands on billing.invoice_payment.
--
-- Both new tables are documents of one school: tenant_id, the tenant_isolation policy, composite (tenant_id, id) keys.

-- ---------------------------------------------------------------- online payments recorded on invoices

-- Online payments are recorded by the gateway (the browser's verified checkout or the gateway's signed webhook),
-- never typed in by the Super Admin; their reference is the gateway's payment id, unique, so one gateway payment can
-- never be recorded twice.
alter table billing.invoice_payment drop constraint ck_invoice_payment_mode;
alter table billing.invoice_payment add constraint ck_invoice_payment_mode
    check (mode in ('BANK_TRANSFER', 'UPI', 'CHEQUE', 'CARD', 'ONLINE'));
create unique index uq_invoice_payment_online on billing.invoice_payment (reference) where mode = 'ONLINE';
alter table billing.invoice_payment add constraint uq_invoice_payment_tenant_id unique (tenant_id, id);

-- ---------------------------------------------------------------- orders at the gateway

-- An online payment started for the whole balance of one invoice. PAID exactly once, by whichever comes first of the
-- browser's verification and the gateway's webhook (both lock the row). invoice_payment_id is the payment recorded;
-- it is null on a PAID order only when the invoice was no longer due by then (paid by hand or cancelled meanwhile):
-- that money is refunded at the gateway, and failure_reason says so.
create table billing.payment_order (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    invoice_id         uuid         not null,
    gateway            varchar(20)  not null,
    gateway_order_id   varchar(64)  not null,
    amount_paise       bigint       not null,
    currency           varchar(3)   not null default 'INR',
    status             varchar(10)  not null,
    gateway_payment_id varchar(64),
    invoice_payment_id uuid,
    failure_reason     varchar(200),
    created_by         uuid,
    created_by_name    varchar(200) not null,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    constraint ck_billing_payment_order_amount check (amount_paise > 0),
    constraint ck_billing_payment_order_status check (status in ('CREATED', 'PAID', 'FAILED')),
    constraint ck_billing_payment_order_paid check (status <> 'PAID' or gateway_payment_id is not null),
    constraint uq_billing_payment_order_tenant_id unique (tenant_id, id),
    constraint fk_billing_payment_order_invoice foreign key (tenant_id, invoice_id)
        references billing.invoice (tenant_id, id),
    constraint fk_billing_payment_order_payment foreign key (tenant_id, invoice_payment_id)
        references billing.invoice_payment (tenant_id, id),
    constraint fk_billing_payment_order_created_by foreign key (tenant_id, created_by)
        references identity.user_account (tenant_id, id) on delete set null (created_by)
);
-- Gateway order ids are unique at the gateway; webhooks find the school through this index.
create unique index uq_billing_payment_order_gateway on billing.payment_order (gateway, gateway_order_id);
create index ix_billing_payment_order_invoice on billing.payment_order (tenant_id, invoice_id);
create index ix_billing_payment_order_payment on billing.payment_order (invoice_payment_id);

-- Every verified webhook delivery, so a replayed event is recognised and never records a payment twice.
create table billing.gateway_event (
    id                 uuid primary key,
    tenant_id          uuid         not null references platform.tenant (id),
    gateway            varchar(20)  not null,
    event_id           varchar(100) not null,
    event_type         varchar(60)  not null,
    payment_order_id   uuid         not null,
    gateway_payment_id varchar(64),
    outcome            varchar(20)  not null,
    received_at        timestamptz  not null,
    constraint ck_billing_gateway_event_outcome
        check (outcome in ('RECORDED', 'ALREADY_RECORDED', 'FAILED', 'IGNORED')),
    constraint uq_billing_gateway_event unique (tenant_id, gateway, event_id),
    constraint fk_billing_gateway_event_order foreign key (tenant_id, payment_order_id)
        references billing.payment_order (tenant_id, id)
);
create index ix_billing_gateway_event_order on billing.gateway_event (payment_order_id);

-- ---------------------------------------------------------------- row-level security

alter table billing.payment_order enable row level security;
create policy tenant_isolation on billing.payment_order
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table billing.gateway_event enable row level security;
create policy tenant_isolation on billing.gateway_event
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

-- A gateway's webhook arrives without a signed-in user, so no school is selected yet. After the signature is verified,
-- the API finds the school of the order through this function, which reveals nothing but that one id.
create function billing.payment_order_tenant(p_gateway text, p_gateway_order_id text)
    returns uuid
    language sql stable security definer
    set search_path = pg_catalog
as $$
    select o.tenant_id from billing.payment_order o
    where o.gateway = p_gateway and o.gateway_order_id = p_gateway_order_id
$$;

-- ---------------------------------------------------------------- grants for the runtime role (no ownership)

grant select, insert, update on billing.payment_order to ${appRole};
grant select, insert on billing.gateway_event to ${appRole};
revoke all on function billing.payment_order_tenant(text, text) from public;
grant execute on function billing.payment_order_tenant(text, text) to ${appRole};
