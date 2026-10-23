import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ItemLedger, ItemView, Lookups, OrderView, RoundView } from "@/lib/types";
import { renderAs } from "@/test/render";
import { ledgerCsv, ItemLedgerView } from "./item-ledger-view";
import { OrderFormDialog } from "./order-form";
import { VerificationRoundView } from "./verification-view";

const api = vi.hoisted(() => ({
  ledger: vi.fn(),
  lookups: vi.fn(),
  items: vi.fn(),
  issue: vi.fn(),
  requisitions: vi.fn(),
  rfqs: vi.fn(),
  createOrder: vi.fn(),
  round: vi.fn(),
  verify: vi.fn(),
}));

vi.mock("@/lib/inventory-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/inventory-api")>();
  return { ...actual, inventoryApi: { ...actual.inventoryApi, ...api } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/inventory/items/i1",
}));

const KEEPER = ["dashboard.view", "inventory.read", "inventory.manage"];

const item: ItemView = { id: "i1", name: "Chalk box", categoryId: "c1", categoryName: "Stationery", unit: "box", kind: "CONSUMABLE", reorderLevel: 5, preferredVendorId: null, preferredVendorName: null, active: true, stock: 3, low: true };

const ledger: ItemLedger = {
  item,
  movements: [
    { id: "m2", itemId: "i1", itemName: "Chalk box", unit: "box", kind: "ISSUE", direction: -1, quantity: 7, unitCostPaise: null, movedOn: "2026-10-12", locationId: null, locationName: null, departmentId: "d1", departmentName: "Primary", issuedToId: null, issuedToName: null, purpose: "Class 3", purchaseOrderId: null, goodsReceiptId: null, stockTakeId: null, createdByName: "Mohan Das", balance: 3 },
    { id: "m1", itemId: "i1", itemName: "Chalk box", unit: "box", kind: "RECEIPT", direction: 1, quantity: 10, unitCostPaise: 4500, movedOn: "2026-10-01", locationId: "l1", locationName: "Main store", departmentId: null, departmentName: null, issuedToId: null, issuedToName: null, purpose: null, purchaseOrderId: null, goodsReceiptId: null, stockTakeId: null, createdByName: "Mohan Das", balance: 10 },
  ],
};

const lookups: Lookups = {
  categories: [{ id: "c1", name: "Stationery", depreciationRateBp: 0, active: true, inUse: true }],
  locations: [{ id: "l1", name: "Main store", kind: "STORE", active: true, inUse: true }],
  vendors: [{ id: "v1", name: "Vijaya Stationers", active: true }],
  departments: [{ id: "d1", name: "Primary" }],
  people: [{ id: "p1", name: "Ravi Kumar", departmentName: "Primary" }],
};

beforeEach(() => {
  vi.clearAllMocks();
  api.ledger.mockResolvedValue(ledger);
  api.lookups.mockResolvedValue(lookups);
  api.items.mockResolvedValue([item]);
  api.requisitions.mockResolvedValue([]);
  api.rfqs.mockResolvedValue([]);
});

describe("ItemLedgerView", () => {
  it("shows the stock, the running balance and the low-stock flag, and issues stock with a purpose", async () => {
    const user = userEvent.setup();
    api.issue.mockResolvedValue({ ...ledger.movements[0], id: "m3" });
    renderAs(KEEPER, <ItemLedgerView id="i1" />);
    expect(await screen.findByTestId("ledger-stock")).toHaveTextContent("3 box · Low stock");
    const rows = within(screen.getByTestId("ledger-table")).getAllByTestId("ledger-row");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Class 3");
    expect(rows[1]).toHaveTextContent("10");

    await user.click(screen.getByTestId("ledger-issue"));
    const form = await screen.findByTestId("movement-form-ISSUE");
    await user.type(within(form).getByLabelText("Quantity"), "5");
    await user.click(within(form).getByRole("button", { name: "Save" }));
    expect(await within(form).findByText("Not that much in stock.")).toBeInTheDocument();
    await user.clear(within(form).getByLabelText("Quantity"));
    await user.type(within(form).getByLabelText("Quantity"), "2");
    await user.selectOptions(within(form).getByLabelText("Department"), "d1");
    await user.type(within(form).getByLabelText("Purpose"), "Class 4 board work");
    await user.click(within(form).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.issue).toHaveBeenCalledWith({ itemId: "i1", locationId: null, quantity: 2, movedOn: expect.any(String), departmentId: "d1", issuedToId: null, purpose: "Class 4 board work" }));
  });

  it("exports the ledger as CSV with a BOM", () => {
    const csv = ledgerCsv(ledger);
    expect(csv.startsWith("﻿Date,Kind,In,Out,Balance")).toBe(true);
    expect(csv).toContain("2026-10-12,ISSUE,,7,3,,Primary,,Class 3,Mohan Das");
  });
});

describe("OrderFormDialog", () => {
  it("refuses an empty order, then totals GST per line and sends it for approval", async () => {
    const user = userEvent.setup();
    const onSaved = vi.fn();
    api.createOrder.mockImplementation(async (body, submit) => ({ id: "po1", number: "PO/2026-27/000001", status: submit ? "SUBMITTED" : "DRAFT", lines: [], ...body }) as OrderView);
    renderAs(KEEPER, <OrderFormDialog order={null} onClose={vi.fn()} onSaved={onSaved} />);
    const form = await screen.findByTestId("order-form");
    await within(form).findByRole("option", { name: "Vijaya Stationers" });
    await user.click(within(form).getByTestId("order-submit"));
    expect(await within(form).findByText("Choose a vendor.")).toBeInTheDocument();
    expect(within(form).getByText("Describe the line (up to 200 characters).")).toBeInTheDocument();
    expect(api.createOrder).not.toHaveBeenCalled();

    await user.selectOptions(within(form).getByLabelText("Vendor"), "v1");
    await user.selectOptions(within(form).getByLabelText("Item"), "i1");
    expect(within(form).getByLabelText("Description")).toHaveValue("Chalk box");
    await user.clear(within(form).getByLabelText("Quantity"));
    await user.type(within(form).getByLabelText("Quantity"), "10");
    await user.type(within(form).getByLabelText("Unit price"), "100");
    expect(within(form).getByTestId("order-totals")).toHaveTextContent("₹1,180");
    await user.click(within(form).getByTestId("order-submit"));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(api.createOrder).toHaveBeenCalledWith(
      expect.objectContaining({ vendorId: "v1", lines: [{ itemId: "i1", description: "Chalk box", quantity: 10, unit: "box", unitPaise: 10_000, gstRateBp: 1800 }] }),
      true,
    );
  });
});

describe("VerificationRoundView", () => {
  const round = (checked: number, result: "FOUND" | null): RoundView => ({
    id: "r1",
    name: "Term 2 check",
    locationId: null,
    locationName: null,
    status: "OPEN",
    startedOn: "2026-10-20",
    closedAt: null,
    createdByName: "Mohan Das",
    assets: 2,
    checked,
    found: checked,
    missing: 0,
    damaged: 0,
    items: [
      { assetId: "a1", assetTag: "AST-000001", assetName: "Projector", locationName: "Lab", custodianName: "Anjali", result, note: null, checkedAt: null },
      { assetId: "a2", assetTag: "AST-000002", assetName: "Printer", locationName: "Office", custodianName: null, result: null, note: null, checkedAt: null },
    ],
  });

  it("marks a scanned tag found and takes it off the pending list", async () => {
    const user = userEvent.setup();
    api.round.mockResolvedValueOnce(round(0, null)).mockResolvedValue(round(1, "FOUND"));
    api.verify.mockResolvedValue(round(1, "FOUND"));
    renderAs(KEEPER, <VerificationRoundView id="r1" />);
    expect(await screen.findByTestId("round-checked")).toHaveTextContent("0 / 2");
    expect(screen.getAllByTestId("round-item")).toHaveLength(2);
    const scan = screen.getByTestId("verify-scan");
    await user.type(within(scan).getByLabelText("Asset tag"), "ast-000001{Enter}");
    await waitFor(() => expect(api.verify).toHaveBeenCalledWith("r1", { assetTag: "AST-000001", result: "FOUND", note: null }));
    expect(await screen.findByTestId("verify-last")).toHaveTextContent("AST-000001");
    await waitFor(() => expect(screen.getByTestId("round-checked")).toHaveTextContent("1 / 2"));
    expect(screen.getAllByTestId("round-item")).toHaveLength(1);
  });
});
