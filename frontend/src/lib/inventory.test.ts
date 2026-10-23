import { describe, expect, it } from "vitest";
import { depreciation, emptyLine, gstOn, lineTotals, orderTotals, outstanding, validateMovement, validateOrder, validateReceive, validateRequisition } from "./inventory";

describe("inventory arithmetic", () => {
  it("computes GST half up in paise from basis points, as the API does", () => {
    expect(gstOn(100_000, 1800)).toBe(18_000);
    expect(gstOn(1, 1800)).toBe(0);
    expect(gstOn(3, 1800)).toBe(1);
    expect(lineTotals(10, 4_500_00, 1200)).toEqual({ amountPaise: 45_000_00, taxPaise: 5_400_00, totalPaise: 50_400_00 });
    expect(orderTotals([{ quantity: 2, unitPaise: 100_00, gstRateBp: 500 }, { quantity: 1, unitPaise: 50_00, gstRateBp: 0 }])).toEqual({ amountPaise: 250_00, taxPaise: 10_00, totalPaise: 260_00 });
  });

  it("depreciates straight line per day and never below zero", () => {
    expect(depreciation(36_500_00, 1000, "2025-01-01", "2026-01-01")).toBe(3_650_00);
    expect(depreciation(36_500_00, 1000, "2025-01-01", "2024-12-31")).toBe(0);
    expect(depreciation(36_500_00, 0, "2025-01-01", "2026-01-01")).toBe(0);
    expect(depreciation(1_000_00, 10_000, "2010-01-01", "2026-01-01")).toBe(1_000_00);
  });

  it("knows what is still pending on an order", () => {
    expect(outstanding({ lines: [{ id: "l1", quantity: 10, receivedQty: 4 }, { id: "l2", quantity: 2, receivedQty: 2 }] } as never)).toEqual([
      { poLineId: "l1", pending: 6 },
      { poLineId: "l2", pending: 0 },
    ]);
  });
});

describe("inventory validation", () => {
  const today = "2026-10-22";

  it("refuses an issue of more than is in stock, or without a purpose", () => {
    const values = { itemId: "i1", locationId: "", quantity: "5", unitCost: "", movedOn: today, departmentId: "", issuedToId: "", purpose: "" };
    expect(validateMovement(values, "ISSUE", today, 3)).toEqual({ quantity: "inventory.v.notEnough", purpose: "inventory.v.purpose" });
    expect(validateMovement({ ...values, purpose: "Class 5" }, "ISSUE", today, 10)).toEqual({});
    expect(validateMovement({ ...values, movedOn: "2026-10-23", quantity: "0" }, "RECEIPT", today)).toEqual({ quantity: "inventory.v.quantity", movedOn: "inventory.v.futureDate" });
  });

  it("needs a purpose and complete lines on a requisition", () => {
    expect(validateRequisition({ departmentId: "", neededBy: "", purpose: "", lines: [] }, today)).toEqual({ purpose: "inventory.v.purpose", lines: "inventory.v.lines" });
    const line = { ...emptyLine(), description: "Chalk", unit: "box", quantity: "x" };
    expect(validateRequisition({ departmentId: "", neededBy: "2026-01-01", purpose: "Term", lines: [line] }, today)).toEqual({ neededBy: "inventory.v.neededByPast", "lines.0.quantity": "inventory.v.quantity" });
  });

  it("needs a vendor, a date and priced lines on an order", () => {
    const line = { ...emptyLine(), description: "Chalk", unit: "box", quantity: "10", unitPrice: "45" };
    expect(validateOrder({ vendorId: "", requisitionId: "", rfqId: "", orderedOn: today, expectedOn: "2026-10-01", terms: "", lines: [line] }, today)).toEqual({ vendorId: "inventory.v.vendor", expectedOn: "inventory.v.expectedBeforeOrder" });
    expect(validateOrder({ vendorId: "v1", requisitionId: "", rfqId: "", orderedOn: today, expectedOn: "", terms: "", lines: [{ ...line, unitPrice: "" }] }, today)).toEqual({ "lines.0.unitPrice": "inventory.v.amount" });
  });

  it("refuses a receipt above what is pending, before the order date, or of nothing", () => {
    const order = { orderedOn: "2026-10-10", lines: [{ id: "l1", quantity: 10, receivedQty: 4 }] } as never;
    expect(validateReceive({ receivedOn: today, locationId: "", invoiceNumber: "", notes: "", quantities: { l1: "7" } }, order, today)).toEqual({ "lines.l1": "inventory.v.overReceipt" });
    expect(validateReceive({ receivedOn: "2026-10-01", locationId: "", invoiceNumber: "", notes: "", quantities: { l1: "6" } }, order, today)).toEqual({ receivedOn: "inventory.v.receivedBeforeOrder" });
    expect(validateReceive({ receivedOn: today, locationId: "", invoiceNumber: "", notes: "", quantities: { l1: "" } }, order, today)).toEqual({ lines: "inventory.v.receiveSomething" });
    expect(validateReceive({ receivedOn: today, locationId: "", invoiceNumber: "", notes: "", quantities: { l1: "6" } }, order, today)).toEqual({});
  });
});
