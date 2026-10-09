import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FeeStructure, StructureResult } from "@/lib/types";
import { ACCOUNTANT, HEADS, PRINCIPAL_FEES } from "@/test/fees-fixtures";
import { renderAs } from "@/test/render";
import {
  addMonths,
  defaultInstalmentLabel,
  splitEvenly,
  spreadDueDates,
  StructureEditor,
  structureValues,
  toStructureRequest,
  validateStructure,
  type StructureValues,
} from "./structure-editor";

const { listHeads, getStructure, createStructure, updateStructure, publishStructure } = vi.hoisted(() => ({
  listHeads: vi.fn(),
  getStructure: vi.fn(),
  createStructure: vi.fn(),
  updateStructure: vi.fn(),
  publishStructure: vi.fn(),
}));

vi.mock("@/lib/fees-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/fees-api")>();
  return {
    ...actual,
    feesApi: { ...actual.feesApi, listHeads, getStructure, createStructure, updateStructure, publishStructure },
  };
});

const QUARTERS = ["2026-06-10", "2026-09-10", "2026-12-10", "2027-03-10"];

function values(overrides: Partial<StructureValues> = {}): StructureValues {
  return {
    amounts: { "h-tuition": "40000", "h-annual": "6000" },
    instalments: QUARTERS.map((dueDate) => ({ label: "", dueDate })),
    custom: false,
    shares: [],
    ...overrides,
  };
}

const SAVED: FeeStructure = {
  id: "fs1",
  academicYearId: "y1",
  academicYearName: "2026-27",
  classId: "c5",
  className: "Class 5",
  status: "DRAFT",
  publishedAt: null,
  heads: [
    { headId: "h-tuition", name: "Tuition fee", kind: "TUITION", oneTime: false, amountPaise: 40_000_00 },
    { headId: "h-annual", name: "Annual charges", kind: "ANNUAL", oneTime: false, amountPaise: 6_000_00 },
  ],
  instalments: QUARTERS.map((dueDate, i) => ({
    id: `i${i + 1}`,
    seq: i + 1,
    label: `Quarter ${i + 1}`,
    dueDate,
    amountPaise: 11_500_00,
    shares: [
      { headId: "h-tuition", amountPaise: 10_000_00 },
      { headId: "h-annual", amountPaise: 1_500_00 },
    ],
  })),
  totalPaise: 46_000_00,
  studentsWithDues: 0,
};

beforeEach(() => {
  listHeads.mockResolvedValue(HEADS);
});

describe("structure maths", () => {
  it("splits evenly with the last instalment taking the rounding", () => {
    expect(splitEvenly(10_000_01, 4)).toEqual([2_500_00, 2_500_00, 2_500_00, 2_500_01]);
    expect(splitEvenly(100, 3)).toEqual([33, 33, 34]);
    expect(splitEvenly(5, 1)).toEqual([5]);
  });

  it("names instalments like the API and spreads due dates over the year", () => {
    expect(defaultInstalmentLabel(1, 0)).toBe("Annual");
    expect(defaultInstalmentLabel(2, 1)).toBe("Term 2");
    expect(defaultInstalmentLabel(4, 3)).toBe("Quarter 4");
    expect(defaultInstalmentLabel(12, 0)).toBe("Instalment 1");
    expect(spreadDueDates("2026-06-01", 4)).toEqual(QUARTERS);
    expect(addMonths("2027-01-31", 1)).toBe("2027-02-28");
  });
});

describe("validateStructure", () => {
  it("accepts a plain quarterly structure and sends no shares", () => {
    expect(validateStructure(values(), HEADS)).toEqual([]);
    const request = toStructureRequest(values(), HEADS, "y1", "c5");
    expect(request.heads).toEqual([
      { headId: "h-tuition", amountPaise: 40_000_00 },
      { headId: "h-annual", amountPaise: 6_000_00 },
    ]);
    expect(request.instalments.map((i) => i.shares)).toEqual([null, null, null, null]);
    expect(request.instalments[0]).toEqual({ label: null, dueDate: "2026-06-10", shares: null });
  });

  it("needs at least one head with an amount", () => {
    expect(validateStructure(values({ amounts: {} }), HEADS)).toEqual([{ field: "heads", key: "fees.v.noHeads" }]);
    expect(validateStructure(values({ amounts: { "h-tuition": "12.345" } }), HEADS)).toEqual([
      { field: "amount.h-tuition", key: "fees.v.amount" },
    ]);
  });

  it("needs each instalment due after the one before it", () => {
    const instalments = values().instalments.map((i, index) => (index === 2 ? { ...i, dueDate: "2026-09-10" } : i));
    expect(validateStructure(values({ instalments }), HEADS)).toEqual([
      { field: "instalments[2].dueDate", key: "fees.v.dueOrder" },
    ]);
    expect(validateStructure(values({ instalments: [] }), HEADS)).toEqual([
      { field: "instalments", key: "fees.v.instalmentCount" },
    ]);
  });

  it("needs typed shares to add up to each head exactly", () => {
    const shares = QUARTERS.map(() => ({ "h-tuition": "10000", "h-annual": "1500" }));
    expect(validateStructure(values({ custom: true, shares }), HEADS)).toEqual([]);
    shares[0] = { "h-tuition": "12000", "h-annual": "1500" };
    expect(validateStructure(values({ custom: true, shares }), HEADS)).toEqual([
      {
        field: "sum.h-tuition",
        key: "fees.v.sharesMismatch",
        vars: { head: "Tuition fee", sum: "₹42,000", amount: "₹40,000" },
      },
    ]);
  });

  it("reads a saved even split back as an even split", () => {
    const loaded = structureValues(SAVED, "2026-06-01");
    expect(loaded.custom).toBe(false);
    expect(loaded.amounts).toEqual({ "h-tuition": "40000", "h-annual": "6000" });
    expect(loaded.instalments[0]).toEqual({ label: "", dueDate: "2026-06-10" });
  });
});

describe("StructureEditor", () => {
  const editor = (permissions: string[], structureId: string | null = null) =>
    renderAs(
      permissions,
      <StructureEditor
        academicYearId="y1"
        yearName="2026-27"
        yearStartsOn="2026-06-01"
        classId="c5"
        className="Class 5"
        structureId={structureId}
        canManage={permissions.includes("fees.manage")}
        onBack={vi.fn()}
        onChanged={vi.fn()}
      />,
      ["ACCOUNTANT"],
    );

  it("checks the plan like the API, then saves a new draft", async () => {
    const user = userEvent.setup();
    const result: StructureResult = { structure: SAVED, dues: { studentsCreated: 0, studentsUpdated: 0, paidCellsKept: 0 } };
    createStructure.mockResolvedValue(result);
    editor(ACCOUNTANT);

    // Nothing entered yet.
    await user.click(await screen.findByRole("button", { name: "Save draft" }));
    expect(await screen.findByText("Enter an amount for at least one fee head.")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Tuition fee"), "40000");
    await user.type(screen.getByLabelText("Annual charges"), "6000");
    expect(screen.getByTestId("structure-total")).toHaveTextContent("Total per student: ₹46,000");
    expect(screen.getAllByText("₹11,500")).toHaveLength(4);

    // A due date before the previous one is refused.
    const second = screen.getByLabelText("Due date of instalment 2");
    await user.clear(second);
    await user.type(second, "2026-05-01");
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findByText("Each instalment must be due after the one before it.")).toBeInTheDocument();
    expect(createStructure).not.toHaveBeenCalled();

    await user.clear(second);
    await user.type(second, "2026-09-10");
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(createStructure).toHaveBeenCalledTimes(1));
    const body = createStructure.mock.calls[0][0];
    expect(body.academicYearId).toBe("y1");
    expect(body.classId).toBe("c5");
    expect(body.heads).toEqual([
      { headId: "h-tuition", amountPaise: 40_000_00 },
      { headId: "h-annual", amountPaise: 6_000_00 },
    ]);
    expect(body.instalments.map((i: { dueDate: string }) => i.dueDate)).toEqual(QUARTERS);
  });

  it("shows when typed shares do not add up", async () => {
    const user = userEvent.setup();
    getStructure.mockResolvedValue(SAVED);
    editor(ACCOUNTANT, "fs1");
    await user.click(await screen.findByRole("checkbox", { name: "Set amounts per instalment" }));
    const share = screen.getByLabelText("Tuition fee in instalment 1");
    expect(share).toHaveValue("10000");
    await user.clear(share);
    await user.type(share, "12000");
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    expect(
      await screen.findByText("Tuition fee: the instalments add up to ₹42,000, not ₹40,000."),
    ).toBeInTheDocument();
    expect(updateStructure).not.toHaveBeenCalled();
  });

  it("publishes a saved draft after asking", async () => {
    const user = userEvent.setup();
    getStructure.mockResolvedValue(SAVED);
    publishStructure.mockResolvedValue({
      structure: { ...SAVED, status: "PUBLISHED", publishedAt: "2026-10-09T06:00:00Z" },
      dues: { studentsCreated: 31, studentsUpdated: 0, paidCellsKept: 0 },
    });
    editor(ACCOUNTANT, "fs1");
    await user.click(await screen.findByRole("button", { name: "Publish" }));
    const dialog = await screen.findByRole("dialog", { name: "Publish Class 5 fees?" });
    await user.click(within(dialog).getByRole("button", { name: "Publish" }));
    await waitFor(() => expect(publishStructure).toHaveBeenCalledWith("fs1"));
  });

  it("is read-only without fees.manage", async () => {
    getStructure.mockResolvedValue(SAVED);
    editor(PRINCIPAL_FEES, "fs1");
    expect(await screen.findByLabelText("Tuition fee")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save draft" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Publish" })).not.toBeInTheDocument();
  });
});
