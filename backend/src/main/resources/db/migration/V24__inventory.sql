-- Phase 3: inventory. Assets, stock, vendors and purchase orders: an item master with categories and locations, a
-- fixed-asset register with tags, custodians, transfers, disposal and physical verification rounds, consumable stock
-- with a movement ledger (receipts, issues, returns, write-offs, stock-take adjustments), and procurement from a
-- requisition through RFQ, purchase order with GST per line, approvals within limits, partial receipts and the
-- vendor's invoice. Vendors are the accounts module's (accounts.vendor); nothing here writes to accounts.
--
-- Every table carries tenant_id, is protected by the same tenant_isolation policy as V2/V3, and links to other school
-- tables through composite (tenant_id, id) foreign keys. Money is in paise (bigint), GST rates in basis points.

create schema if not exists inventory;

-- ---------------------------------------------------------------- approvers and masters

-- Who approves requisitions and purchase orders and up to what amount. A person with purchase.approve who is not
-- listed has no limit.
create table inventory.purchase_approver (
    id          uuid primary key,
    tenant_id   uuid         not null references platform.tenant (id),
    user_id     uuid         not null,
    user_name   varchar(200) not null,
    limit_paise bigint,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    constraint uq_inventory_purchase_approver_tenant_id unique (tenant_id, id),
    constraint uq_inventory_purchase_approver_user unique (tenant_id, user_id),
    constraint ck_inventory_purchase_approver_limit check (limit_paise is null or limit_paise > 0),
    constraint fk_inventory_purchase_approver_user foreign key (tenant_id, user_id)
        references identity.user_account (tenant_id, id) on delete cascade
);
create index ix_inventory_purchase_approver_user on inventory.purchase_approver (user_id);

-- Item and asset categories; depreciation_rate_bp is the straight-line rate per year in basis points (1500 = 15%).
create table inventory.category (
    id                   uuid primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    name                 varchar(80)  not null,
    depreciation_rate_bp integer      not null default 0,
    active               boolean      not null default true,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint uq_inventory_category_tenant_id unique (tenant_id, id),
    constraint ck_inventory_category_rate check (depreciation_rate_bp between 0 and 10000)
);
create unique index uq_inventory_category_name on inventory.category (tenant_id, lower(name));

create table inventory.location (
    id         uuid primary key,
    tenant_id  uuid         not null references platform.tenant (id),
    name       varchar(80)  not null,
    kind       varchar(20)  not null,
    active     boolean      not null default true,
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    version    bigint       not null default 0,
    constraint uq_inventory_location_tenant_id unique (tenant_id, id),
    constraint ck_inventory_location_kind check (kind in ('STORE', 'LAB', 'LIBRARY', 'OFFICE', 'CLASSROOM_BLOCK',
        'OTHER'))
);
create unique index uq_inventory_location_name on inventory.location (tenant_id, lower(name));

-- The item master. Current stock is never stored: it is the sum of the item's movements.
create table inventory.item (
    id                  uuid primary key,
    tenant_id           uuid         not null references platform.tenant (id),
    name                varchar(120) not null,
    category_id         uuid         not null,
    unit                varchar(20)  not null,
    kind                varchar(10)  not null,
    reorder_level       integer      not null default 0,
    preferred_vendor_id uuid,
    active              boolean      not null default true,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    version             bigint       not null default 0,
    constraint uq_inventory_item_tenant_id unique (tenant_id, id),
    constraint ck_inventory_item_kind check (kind in ('CONSUMABLE', 'ASSET')),
    constraint ck_inventory_item_reorder check (reorder_level >= 0),
    constraint fk_inventory_item_category foreign key (tenant_id, category_id)
        references inventory.category (tenant_id, id),
    constraint fk_inventory_item_vendor foreign key (tenant_id, preferred_vendor_id)
        references accounts.vendor (tenant_id, id) on delete set null (preferred_vendor_id)
);
create unique index uq_inventory_item_name on inventory.item (tenant_id, lower(name));
create index ix_inventory_item_category on inventory.item (category_id);
create index ix_inventory_item_vendor on inventory.item (preferred_vendor_id);

-- Numbers: requisitions REQ/2026-27/000001, RFQs, purchase orders, goods receipts, and asset tags (AST-000001, not
-- per year, written with financial_year '-').
create table inventory.number_counter (
    tenant_id      uuid        not null references platform.tenant (id),
    kind           varchar(3)  not null,
    financial_year varchar(7)  not null,
    last_seq       integer     not null default 0,
    updated_at     timestamptz not null,
    primary key (tenant_id, kind, financial_year),
    constraint ck_inventory_number_counter_kind check (kind in ('REQ', 'RFQ', 'PO', 'GRN', 'AST')),
    constraint ck_inventory_number_counter_year check (financial_year = '-' or financial_year ~ '^[0-9]{4}-[0-9]{2}$')
);

-- ---------------------------------------------------------------- procurement

-- A purchase requisition. The department head (if the department has one) approves first, then an approver with
-- purchase.approve within their limit; escalated_above_paise works as in accounts.expense.
create table inventory.requisition (
    id                    uuid primary key,
    tenant_id             uuid         not null references platform.tenant (id),
    number                varchar(30),
    status                varchar(15)  not null,
    department_id         uuid,
    department_name       varchar(100),
    requested_by_id       uuid,
    requested_by_name     varchar(200) not null,
    needed_by             date,
    purpose               varchar(500) not null,
    estimated_paise       bigint       not null default 0,
    head_user_id          uuid,
    head_name             varchar(200),
    escalated_above_paise bigint,
    rejection_reason      varchar(500),
    submitted_at          timestamptz,
    decided_at            timestamptz,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0,
    constraint uq_inventory_requisition_tenant_id unique (tenant_id, id),
    constraint ck_inventory_requisition_status check (status in ('DRAFT', 'SUBMITTED', 'HEAD_APPROVED', 'APPROVED',
        'REJECTED', 'ORDERED')),
    constraint ck_inventory_requisition_estimate check (estimated_paise >= 0),
    constraint fk_inventory_requisition_department foreign key (tenant_id, department_id)
        references staff.department (tenant_id, id) on delete set null (department_id),
    constraint fk_inventory_requisition_requester foreign key (tenant_id, requested_by_id)
        references identity.user_account (tenant_id, id) on delete set null (requested_by_id),
    constraint fk_inventory_requisition_head foreign key (tenant_id, head_user_id)
        references identity.user_account (tenant_id, id) on delete set null (head_user_id)
);
create unique index uq_inventory_requisition_number on inventory.requisition (tenant_id, number)
    where number is not null;
create index ix_inventory_requisition_status on inventory.requisition (tenant_id, status);
create index ix_inventory_requisition_requester on inventory.requisition (requested_by_id);
create index ix_inventory_requisition_department on inventory.requisition (department_id);
create index ix_inventory_requisition_head on inventory.requisition (head_user_id);

create table inventory.requisition_line (
    id                   uuid primary key,
    tenant_id            uuid         not null references platform.tenant (id),
    requisition_id       uuid         not null,
    line_no              integer      not null,
    item_id              uuid,
    description          varchar(200) not null,
    quantity             integer      not null,
    unit                 varchar(20)  not null,
    estimated_unit_paise bigint       not null default 0,
    created_at           timestamptz  not null,
    constraint uq_inventory_requisition_line_tenant_id unique (tenant_id, id),
    constraint ck_inventory_requisition_line_qty check (quantity > 0),
    constraint ck_inventory_requisition_line_paise check (estimated_unit_paise >= 0),
    constraint fk_inventory_requisition_line_req foreign key (tenant_id, requisition_id)
        references inventory.requisition (tenant_id, id) on delete cascade,
    constraint fk_inventory_requisition_line_item foreign key (tenant_id, item_id)
        references inventory.item (tenant_id, id) on delete set null (item_id)
);
create index ix_inventory_requisition_line_req on inventory.requisition_line (requisition_id);
create index ix_inventory_requisition_line_item on inventory.requisition_line (item_id);

-- A request for quotation to up to three vendors; quotes are recorded by hand (no email goes out).
create table inventory.rfq (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    number            varchar(30)  not null,
    requisition_id    uuid,
    title             varchar(200) not null,
    status            varchar(10)  not null,
    selected_quote_id uuid,
    selection_reason  varchar(500),
    created_by_name   varchar(200) not null,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    constraint uq_inventory_rfq_tenant_id unique (tenant_id, id),
    constraint uq_inventory_rfq_number unique (tenant_id, number),
    constraint ck_inventory_rfq_status check (status in ('OPEN', 'SELECTED', 'CANCELLED')),
    constraint fk_inventory_rfq_req foreign key (tenant_id, requisition_id)
        references inventory.requisition (tenant_id, id) on delete set null (requisition_id)
);
create index ix_inventory_rfq_req on inventory.rfq (requisition_id);

create table inventory.quote (
    id            uuid primary key,
    tenant_id     uuid         not null references platform.tenant (id),
    rfq_id        uuid         not null,
    vendor_id     uuid         not null,
    vendor_name   varchar(120) not null,
    amount_paise  bigint       not null,
    delivery_days integer,
    valid_until   date,
    notes         varchar(500),
    created_at    timestamptz  not null,
    constraint uq_inventory_quote_tenant_id unique (tenant_id, id),
    constraint uq_inventory_quote_vendor unique (tenant_id, rfq_id, vendor_id),
    constraint ck_inventory_quote_amount check (amount_paise > 0),
    constraint fk_inventory_quote_rfq foreign key (tenant_id, rfq_id)
        references inventory.rfq (tenant_id, id) on delete cascade,
    constraint fk_inventory_quote_vendor foreign key (tenant_id, vendor_id)
        references accounts.vendor (tenant_id, id) on delete cascade
);
create index ix_inventory_quote_rfq on inventory.quote (rfq_id);
create index ix_inventory_quote_vendor on inventory.quote (vendor_id);

alter table inventory.rfq add constraint fk_inventory_rfq_selected foreign key (tenant_id, selected_quote_id)
    references inventory.quote (tenant_id, id) on delete set null (selected_quote_id);
create index ix_inventory_rfq_selected on inventory.rfq (selected_quote_id);

-- A purchase order. Amounts are in paise: subtotal before GST, tax, and total. Once SUBMITTED the lines are frozen;
-- receipts move it to PARTIAL and then RECEIVED.
create table inventory.purchase_order (
    id                    uuid primary key,
    tenant_id             uuid         not null references platform.tenant (id),
    number                varchar(30)  not null,
    financial_year        varchar(7)   not null,
    status                varchar(10)  not null,
    vendor_id             uuid         not null,
    vendor_name           varchar(120) not null,
    requisition_id        uuid,
    rfq_id                uuid,
    ordered_on            date         not null,
    expected_on           date,
    terms                 varchar(1000),
    subtotal_paise        bigint       not null default 0,
    tax_paise             bigint       not null default 0,
    total_paise           bigint       not null default 0,
    escalated_above_paise bigint,
    rejection_reason      varchar(500),
    invoice_number        varchar(60),
    invoice_date          date,
    invoice_paise         bigint,
    created_by_id         uuid,
    created_by_name       varchar(200) not null,
    submitted_at          timestamptz,
    approved_at           timestamptz,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0,
    constraint uq_inventory_purchase_order_tenant_id unique (tenant_id, id),
    constraint uq_inventory_purchase_order_number unique (tenant_id, number),
    constraint ck_inventory_purchase_order_status check (status in ('DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL',
        'RECEIVED', 'REJECTED', 'CANCELLED')),
    constraint ck_inventory_purchase_order_amounts check (subtotal_paise >= 0 and tax_paise >= 0
        and total_paise >= 0),
    constraint fk_inventory_purchase_order_vendor foreign key (tenant_id, vendor_id)
        references accounts.vendor (tenant_id, id),
    constraint fk_inventory_purchase_order_req foreign key (tenant_id, requisition_id)
        references inventory.requisition (tenant_id, id) on delete set null (requisition_id),
    constraint fk_inventory_purchase_order_rfq foreign key (tenant_id, rfq_id)
        references inventory.rfq (tenant_id, id) on delete set null (rfq_id),
    constraint fk_inventory_purchase_order_creator foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_inventory_purchase_order_status on inventory.purchase_order (tenant_id, status);
create index ix_inventory_purchase_order_vendor on inventory.purchase_order (vendor_id);
create index ix_inventory_purchase_order_req on inventory.purchase_order (requisition_id);
create index ix_inventory_purchase_order_rfq on inventory.purchase_order (rfq_id);
create index ix_inventory_purchase_order_creator on inventory.purchase_order (created_by_id);

create table inventory.purchase_order_line (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    purchase_order_id uuid         not null,
    line_no           integer      not null,
    item_id           uuid,
    description       varchar(200) not null,
    quantity          integer      not null,
    unit              varchar(20)  not null,
    unit_paise        bigint       not null,
    gst_rate_bp       integer      not null default 0,
    amount_paise      bigint       not null,
    tax_paise         bigint       not null,
    received_qty      integer      not null default 0,
    created_at        timestamptz  not null,
    constraint uq_inventory_purchase_order_line_tenant_id unique (tenant_id, id),
    constraint ck_inventory_purchase_order_line_qty check (quantity > 0 and received_qty >= 0),
    constraint ck_inventory_purchase_order_line_gst check (gst_rate_bp between 0 and 10000),
    constraint ck_inventory_purchase_order_line_paise check (unit_paise >= 0 and amount_paise >= 0
        and tax_paise >= 0),
    constraint fk_inventory_purchase_order_line_po foreign key (tenant_id, purchase_order_id)
        references inventory.purchase_order (tenant_id, id) on delete cascade,
    constraint fk_inventory_purchase_order_line_item foreign key (tenant_id, item_id)
        references inventory.item (tenant_id, id) on delete set null (item_id)
);
create index ix_inventory_purchase_order_line_po on inventory.purchase_order_line (purchase_order_id);
create index ix_inventory_purchase_order_line_item on inventory.purchase_order_line (item_id);

-- Every decision on a requisition or purchase order, append-only. stage: HEAD (the department head) or PURCHASE
-- (purchase.approve within the limit).
create table inventory.purchase_approval (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    requisition_id    uuid,
    purchase_order_id uuid,
    stage             varchar(10)  not null,
    action            varchar(10)  not null,
    approver_id       uuid,
    approver_name     varchar(200) not null,
    limit_paise       bigint,
    note              varchar(500),
    created_at        timestamptz  not null,
    constraint uq_inventory_purchase_approval_tenant_id unique (tenant_id, id),
    constraint ck_inventory_purchase_approval_stage check (stage in ('HEAD', 'PURCHASE')),
    constraint ck_inventory_purchase_approval_action check (action in ('APPROVED', 'ESCALATED', 'REJECTED')),
    constraint fk_inventory_purchase_approval_req foreign key (tenant_id, requisition_id)
        references inventory.requisition (tenant_id, id) on delete cascade,
    constraint fk_inventory_purchase_approval_po foreign key (tenant_id, purchase_order_id)
        references inventory.purchase_order (tenant_id, id) on delete cascade,
    constraint fk_inventory_purchase_approval_approver foreign key (tenant_id, approver_id)
        references identity.user_account (tenant_id, id) on delete set null (approver_id)
);
create index ix_inventory_purchase_approval_req on inventory.purchase_approval (requisition_id);
create index ix_inventory_purchase_approval_po on inventory.purchase_approval (purchase_order_id);
create index ix_inventory_purchase_approval_approver on inventory.purchase_approval (approver_id);

-- ---------------------------------------------------------------- stock

-- A stock take of a location (or the whole school): expected quantities frozen at the start, counts entered, and
-- the variance posted as ADJUSTMENT movements when it is closed.
create table inventory.stock_take (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    location_id     uuid,
    status          varchar(10)  not null,
    started_on      date         not null,
    closed_at       timestamptz,
    notes           varchar(500),
    created_by_name varchar(200) not null,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint uq_inventory_stock_take_tenant_id unique (tenant_id, id),
    constraint ck_inventory_stock_take_status check (status in ('OPEN', 'CLOSED')),
    constraint fk_inventory_stock_take_location foreign key (tenant_id, location_id)
        references inventory.location (tenant_id, id) on delete set null (location_id)
);
create index ix_inventory_stock_take_location on inventory.stock_take (location_id);

create table inventory.stock_take_line (
    id            uuid primary key,
    tenant_id     uuid        not null references platform.tenant (id),
    stock_take_id uuid        not null,
    item_id       uuid        not null,
    expected_qty  integer     not null,
    counted_qty   integer,
    created_at    timestamptz not null,
    constraint uq_inventory_stock_take_line_tenant_id unique (tenant_id, id),
    constraint uq_inventory_stock_take_line_item unique (tenant_id, stock_take_id, item_id),
    constraint fk_inventory_stock_take_line_take foreign key (tenant_id, stock_take_id)
        references inventory.stock_take (tenant_id, id) on delete cascade,
    constraint fk_inventory_stock_take_line_item foreign key (tenant_id, item_id)
        references inventory.item (tenant_id, id) on delete cascade
);
create index ix_inventory_stock_take_line_take on inventory.stock_take_line (stock_take_id);
create index ix_inventory_stock_take_line_item on inventory.stock_take_line (item_id);

-- A receipt of goods against a purchase order (one order may have several).
create table inventory.goods_receipt (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    number            varchar(30)  not null,
    purchase_order_id uuid         not null,
    received_on       date         not null,
    location_id       uuid,
    invoice_number    varchar(60),
    notes             varchar(500),
    total_paise       bigint       not null default 0,
    received_by_id    uuid,
    received_by_name  varchar(200) not null,
    created_at        timestamptz  not null,
    constraint uq_inventory_goods_receipt_tenant_id unique (tenant_id, id),
    constraint uq_inventory_goods_receipt_number unique (tenant_id, number),
    constraint fk_inventory_goods_receipt_po foreign key (tenant_id, purchase_order_id)
        references inventory.purchase_order (tenant_id, id) on delete cascade,
    constraint fk_inventory_goods_receipt_location foreign key (tenant_id, location_id)
        references inventory.location (tenant_id, id) on delete set null (location_id),
    constraint fk_inventory_goods_receipt_receiver foreign key (tenant_id, received_by_id)
        references identity.user_account (tenant_id, id) on delete set null (received_by_id)
);
create index ix_inventory_goods_receipt_po on inventory.goods_receipt (purchase_order_id);
create index ix_inventory_goods_receipt_location on inventory.goods_receipt (location_id);
create index ix_inventory_goods_receipt_receiver on inventory.goods_receipt (received_by_id);

create table inventory.goods_receipt_line (
    id               uuid primary key,
    tenant_id        uuid        not null references platform.tenant (id),
    goods_receipt_id uuid        not null,
    po_line_id       uuid        not null,
    item_id          uuid,
    quantity         integer     not null,
    unit_paise       bigint      not null,
    created_at       timestamptz not null,
    constraint uq_inventory_goods_receipt_line_tenant_id unique (tenant_id, id),
    constraint ck_inventory_goods_receipt_line_qty check (quantity > 0),
    constraint fk_inventory_goods_receipt_line_grn foreign key (tenant_id, goods_receipt_id)
        references inventory.goods_receipt (tenant_id, id) on delete cascade,
    constraint fk_inventory_goods_receipt_line_po_line foreign key (tenant_id, po_line_id)
        references inventory.purchase_order_line (tenant_id, id) on delete cascade,
    constraint fk_inventory_goods_receipt_line_item foreign key (tenant_id, item_id)
        references inventory.item (tenant_id, id) on delete set null (item_id)
);
create index ix_inventory_goods_receipt_line_grn on inventory.goods_receipt_line (goods_receipt_id);
create index ix_inventory_goods_receipt_line_po_line on inventory.goods_receipt_line (po_line_id);
create index ix_inventory_goods_receipt_line_item on inventory.goods_receipt_line (item_id);

-- The stock ledger. quantity is always positive; direction +1 adds to stock (RECEIPT, RETURN, a surplus
-- ADJUSTMENT) and -1 takes from it (ISSUE, WRITE_OFF, a shortage ADJUSTMENT).
create table inventory.stock_movement (
    id                uuid primary key,
    tenant_id         uuid         not null references platform.tenant (id),
    item_id           uuid         not null,
    location_id       uuid,
    kind              varchar(12)  not null,
    direction         integer      not null,
    quantity          integer      not null,
    unit_cost_paise   bigint,
    moved_on          date         not null,
    department_id     uuid,
    department_name   varchar(100),
    issued_to_id      uuid,
    issued_to_name    varchar(200),
    purpose           varchar(500),
    purchase_order_id uuid,
    goods_receipt_id  uuid,
    stock_take_id     uuid,
    created_by_id     uuid,
    created_by_name   varchar(200) not null,
    created_at        timestamptz  not null,
    constraint uq_inventory_stock_movement_tenant_id unique (tenant_id, id),
    constraint ck_inventory_stock_movement_kind check (kind in ('RECEIPT', 'ISSUE', 'RETURN', 'WRITE_OFF',
        'ADJUSTMENT')),
    constraint ck_inventory_stock_movement_direction check (direction in (-1, 1)),
    constraint ck_inventory_stock_movement_qty check (quantity > 0),
    constraint ck_inventory_stock_movement_cost check (unit_cost_paise is null or unit_cost_paise >= 0),
    constraint fk_inventory_stock_movement_item foreign key (tenant_id, item_id)
        references inventory.item (tenant_id, id),
    constraint fk_inventory_stock_movement_location foreign key (tenant_id, location_id)
        references inventory.location (tenant_id, id) on delete set null (location_id),
    constraint fk_inventory_stock_movement_department foreign key (tenant_id, department_id)
        references staff.department (tenant_id, id) on delete set null (department_id),
    constraint fk_inventory_stock_movement_person foreign key (tenant_id, issued_to_id)
        references identity.user_account (tenant_id, id) on delete set null (issued_to_id),
    constraint fk_inventory_stock_movement_po foreign key (tenant_id, purchase_order_id)
        references inventory.purchase_order (tenant_id, id) on delete set null (purchase_order_id),
    constraint fk_inventory_stock_movement_grn foreign key (tenant_id, goods_receipt_id)
        references inventory.goods_receipt (tenant_id, id) on delete set null (goods_receipt_id),
    constraint fk_inventory_stock_movement_take foreign key (tenant_id, stock_take_id)
        references inventory.stock_take (tenant_id, id) on delete set null (stock_take_id),
    constraint fk_inventory_stock_movement_creator foreign key (tenant_id, created_by_id)
        references identity.user_account (tenant_id, id) on delete set null (created_by_id)
);
create index ix_inventory_stock_movement_item on inventory.stock_movement (item_id, moved_on);
create index ix_inventory_stock_movement_location on inventory.stock_movement (location_id);
create index ix_inventory_stock_movement_department on inventory.stock_movement (department_id);
create index ix_inventory_stock_movement_person on inventory.stock_movement (issued_to_id);
create index ix_inventory_stock_movement_po on inventory.stock_movement (purchase_order_id);
create index ix_inventory_stock_movement_grn on inventory.stock_movement (goods_receipt_id);
create index ix_inventory_stock_movement_take on inventory.stock_movement (stock_take_id);
create index ix_inventory_stock_movement_creator on inventory.stock_movement (created_by_id);

-- ---------------------------------------------------------------- fixed assets

-- The asset register. Depreciation is computed from cost, purchase date and the category's rate, never stored.
create table inventory.asset (
    id                    uuid primary key,
    tenant_id             uuid         not null references platform.tenant (id),
    asset_tag             varchar(20)  not null,
    name                  varchar(120) not null,
    category_id           uuid         not null,
    item_id               uuid,
    location_id           uuid,
    custodian_id          uuid,
    custodian_name        varchar(200),
    purchase_date         date         not null,
    cost_paise            bigint       not null,
    vendor_id             uuid,
    serial_no             varchar(80),
    condition             varchar(10)  not null,
    warranty_expires_on   date,
    status                varchar(20)  not null,
    disposal_reason       varchar(500),
    disposal_requested_by varchar(200),
    disposed_on           date,
    disposed_by_name      varchar(200),
    notes                 varchar(500),
    last_verified_on      date,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0,
    constraint uq_inventory_asset_tenant_id unique (tenant_id, id),
    constraint uq_inventory_asset_tag unique (tenant_id, asset_tag),
    constraint ck_inventory_asset_cost check (cost_paise >= 0),
    constraint ck_inventory_asset_condition check (condition in ('GOOD', 'FAIR', 'POOR', 'DAMAGED')),
    constraint ck_inventory_asset_status check (status in ('IN_USE', 'DISPOSAL_REQUESTED', 'DISPOSED')),
    constraint fk_inventory_asset_category foreign key (tenant_id, category_id)
        references inventory.category (tenant_id, id),
    constraint fk_inventory_asset_item foreign key (tenant_id, item_id)
        references inventory.item (tenant_id, id) on delete set null (item_id),
    constraint fk_inventory_asset_location foreign key (tenant_id, location_id)
        references inventory.location (tenant_id, id) on delete set null (location_id),
    constraint fk_inventory_asset_custodian foreign key (tenant_id, custodian_id)
        references identity.user_account (tenant_id, id) on delete set null (custodian_id),
    constraint fk_inventory_asset_vendor foreign key (tenant_id, vendor_id)
        references accounts.vendor (tenant_id, id) on delete set null (vendor_id)
);
create index ix_inventory_asset_category on inventory.asset (category_id);
create index ix_inventory_asset_item on inventory.asset (item_id);
create index ix_inventory_asset_location on inventory.asset (location_id);
create index ix_inventory_asset_custodian on inventory.asset (custodian_id);
create index ix_inventory_asset_vendor on inventory.asset (vendor_id);

create table inventory.asset_transfer (
    id                  uuid primary key,
    tenant_id           uuid         not null references platform.tenant (id),
    asset_id            uuid         not null,
    from_location_id    uuid,
    from_location_name  varchar(80),
    to_location_id      uuid,
    to_location_name    varchar(80),
    from_custodian_id   uuid,
    from_custodian_name varchar(200),
    to_custodian_id     uuid,
    to_custodian_name   varchar(200),
    reason              varchar(500),
    moved_on            date         not null,
    created_by_name     varchar(200) not null,
    created_at          timestamptz  not null,
    constraint uq_inventory_asset_transfer_tenant_id unique (tenant_id, id),
    constraint fk_inventory_asset_transfer_asset foreign key (tenant_id, asset_id)
        references inventory.asset (tenant_id, id) on delete cascade,
    constraint fk_inventory_asset_transfer_from foreign key (tenant_id, from_location_id)
        references inventory.location (tenant_id, id) on delete set null (from_location_id),
    constraint fk_inventory_asset_transfer_to foreign key (tenant_id, to_location_id)
        references inventory.location (tenant_id, id) on delete set null (to_location_id),
    constraint fk_inventory_asset_transfer_from_person foreign key (tenant_id, from_custodian_id)
        references identity.user_account (tenant_id, id) on delete set null (from_custodian_id),
    constraint fk_inventory_asset_transfer_to_person foreign key (tenant_id, to_custodian_id)
        references identity.user_account (tenant_id, id) on delete set null (to_custodian_id)
);
create index ix_inventory_asset_transfer_asset on inventory.asset_transfer (asset_id);
create index ix_inventory_asset_transfer_from on inventory.asset_transfer (from_location_id);
create index ix_inventory_asset_transfer_to on inventory.asset_transfer (to_location_id);
create index ix_inventory_asset_transfer_from_person on inventory.asset_transfer (from_custodian_id);
create index ix_inventory_asset_transfer_to_person on inventory.asset_transfer (to_custodian_id);

-- A physical verification round: every asset in use (of a location, or all) gets a line; each is scanned or ticked
-- FOUND, MISSING or DAMAGED.
create table inventory.verification_round (
    id              uuid primary key,
    tenant_id       uuid         not null references platform.tenant (id),
    name            varchar(120) not null,
    location_id     uuid,
    status          varchar(10)  not null,
    started_on      date         not null,
    closed_at       timestamptz,
    created_by_name varchar(200) not null,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint uq_inventory_verification_round_tenant_id unique (tenant_id, id),
    constraint ck_inventory_verification_round_status check (status in ('OPEN', 'CLOSED')),
    constraint fk_inventory_verification_round_location foreign key (tenant_id, location_id)
        references inventory.location (tenant_id, id) on delete set null (location_id)
);
create index ix_inventory_verification_round_location on inventory.verification_round (location_id);

create table inventory.verification_item (
    id         uuid primary key,
    tenant_id  uuid        not null references platform.tenant (id),
    round_id   uuid        not null,
    asset_id   uuid        not null,
    result     varchar(10),
    note       varchar(300),
    checked_at timestamptz,
    created_at timestamptz not null,
    constraint uq_inventory_verification_item_tenant_id unique (tenant_id, id),
    constraint uq_inventory_verification_item_asset unique (tenant_id, round_id, asset_id),
    constraint ck_inventory_verification_item_result check (result is null or result in ('FOUND', 'MISSING',
        'DAMAGED')),
    constraint fk_inventory_verification_item_round foreign key (tenant_id, round_id)
        references inventory.verification_round (tenant_id, id) on delete cascade,
    constraint fk_inventory_verification_item_asset foreign key (tenant_id, asset_id)
        references inventory.asset (tenant_id, id) on delete cascade
);
create index ix_inventory_verification_item_round on inventory.verification_item (round_id);
create index ix_inventory_verification_item_asset on inventory.verification_item (asset_id);

-- ---------------------------------------------------------------- row-level security

alter table inventory.purchase_approver enable row level security;
create policy tenant_isolation on inventory.purchase_approver
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.category enable row level security;
create policy tenant_isolation on inventory.category
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.location enable row level security;
create policy tenant_isolation on inventory.location
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.item enable row level security;
create policy tenant_isolation on inventory.item
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.number_counter enable row level security;
create policy tenant_isolation on inventory.number_counter
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.requisition enable row level security;
create policy tenant_isolation on inventory.requisition
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.requisition_line enable row level security;
create policy tenant_isolation on inventory.requisition_line
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.rfq enable row level security;
create policy tenant_isolation on inventory.rfq
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.quote enable row level security;
create policy tenant_isolation on inventory.quote
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.purchase_order enable row level security;
create policy tenant_isolation on inventory.purchase_order
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.purchase_order_line enable row level security;
create policy tenant_isolation on inventory.purchase_order_line
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.purchase_approval enable row level security;
create policy tenant_isolation on inventory.purchase_approval
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.stock_take enable row level security;
create policy tenant_isolation on inventory.stock_take
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.stock_take_line enable row level security;
create policy tenant_isolation on inventory.stock_take_line
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.goods_receipt enable row level security;
create policy tenant_isolation on inventory.goods_receipt
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.goods_receipt_line enable row level security;
create policy tenant_isolation on inventory.goods_receipt_line
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.stock_movement enable row level security;
create policy tenant_isolation on inventory.stock_movement
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.asset enable row level security;
create policy tenant_isolation on inventory.asset
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.asset_transfer enable row level security;
create policy tenant_isolation on inventory.asset_transfer
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.verification_round enable row level security;
create policy tenant_isolation on inventory.verification_round
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

alter table inventory.verification_item enable row level security;
create policy tenant_isolation on inventory.verification_item
    using (tenant_id = platform.current_tenant_id())
    with check (tenant_id = platform.current_tenant_id());

grant usage on schema inventory to ${appRole};
grant select, insert, update, delete on inventory.purchase_approver, inventory.category, inventory.location,
    inventory.item, inventory.number_counter, inventory.requisition, inventory.requisition_line,
    inventory.rfq, inventory.quote, inventory.purchase_order, inventory.purchase_order_line,
    inventory.purchase_approval, inventory.stock_take, inventory.stock_take_line, inventory.goods_receipt,
    inventory.goods_receipt_line, inventory.stock_movement, inventory.asset, inventory.asset_transfer,
    inventory.verification_round, inventory.verification_item to ${appRole};

-- ---------------------------------------------------------------- the Storekeeper role and the inventory permissions

-- New schools get the Storekeeper role and these permissions from RoleCatalog. Existing schools keep their stored
-- roles, so the built-in Storekeeper role is added to every school that has none, and the new codes are appended to
-- the matching built-in roles, once. Runs as the owner, which row-level security does not restrict. Idempotent.
insert into identity.role (id, tenant_id, code, name, permissions, system_role, created_at)
select gen_random_uuid(), t.id, 'STOREKEEPER', 'Storekeeper',
       array['dashboard.view', 'leave.request', 'notices.read', 'timetable.read', 'inventory.read',
             'inventory.manage'],
       true, now()
from platform.tenant t
where not exists (select 1 from identity.role r where r.tenant_id = t.id and r.code = 'STOREKEEPER');

-- inventory.read: items, stock, assets, the inventory reports, and raising a requisition for one's department
-- (teachers too).
update identity.role
set permissions = array_append(permissions, 'inventory.read')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER', 'ACCOUNTANT', 'LIBRARIAN', 'STOREKEEPER')
  and not ('inventory.read' = any (permissions));

-- inventory.manage: the storekeeper's work (School Admin and Storekeeper).
update identity.role
set permissions = array_append(permissions, 'inventory.manage')
where system_role
  and code in ('SCHOOL_ADMIN', 'STOREKEEPER')
  and not ('inventory.manage' = any (permissions));

-- purchase.approve: approving requisitions and purchase orders within a limit (Principal, School Admin).
update identity.role
set permissions = array_append(permissions, 'purchase.approve')
where system_role
  and code in ('SCHOOL_ADMIN', 'PRINCIPAL')
  and not ('purchase.approve' = any (permissions));
