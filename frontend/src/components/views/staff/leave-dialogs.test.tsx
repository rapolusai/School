import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { LeaveBalance, LeavePreview, LeaveType, StaffLeaveRequest } from "@/lib/types";
import { renderAs } from "@/test/render";
import {
  ApplyLeaveDialog,
  EMPTY_LEAVE,
  EMPTY_LEAVE_TYPE,
  isHalfStep,
  validateBalance,
  validateLeave,
  validateLeaveType,
} from "./leave-dialogs";

const { preview, apply } = vi.hoisted(() => ({ preview: vi.fn(), apply: vi.fn() }));

vi.mock("@/lib/staff-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/staff-api")>();
  return { ...actual, leaveApi: { ...actual.leaveApi, preview, apply } };
});

const CL: LeaveType = {
  id: "cl",
  name: "Casual leave",
  code: "CL",
  yearlyQuota: 12,
  carryForwardCap: 0,
  halfDayAllowed: true,
  lossOfPay: false,
  active: true,
  inUse: true,
};
const EL: LeaveType = { ...CL, id: "el", name: "Earned leave", code: "EL", yearlyQuota: 15, halfDayAllowed: false };

const CL_BALANCE: LeaveBalance = {
  leaveTypeId: "cl",
  leaveTypeName: "Casual leave",
  code: "CL",
  lossOfPay: false,
  halfDayAllowed: true,
  active: true,
  opening: 0,
  accrued: 12,
  taken: 2,
  pending: 1,
  available: 10,
  setByHand: false,
};

const YEAR = { id: "y1", name: "2026-27", startsOn: "2026-04-01", endsOn: "2027-03-31", current: true };

function previewOf(overrides: Partial<LeavePreview> = {}): LeavePreview {
  return {
    year: YEAR,
    fromDate: "2026-10-12",
    toDate: "2026-10-18",
    halfDay: false,
    workingDays: ["2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-17"],
    nonWorkingDays: 1,
    days: 6,
    balance: CL_BALANCE,
    availableAfter: 3,
    enough: true,
    overlaps: false,
    ...overrides,
  };
}

beforeEach(() => {
  preview.mockImplementation(async (body: { fromDate: string; toDate: string; halfDay: boolean }) =>
    previewOf({ fromDate: body.fromDate, toDate: body.toDate, halfDay: body.halfDay }),
  );
});

describe("validateLeave", () => {
  const valid = { leaveTypeId: "cl", fromDate: "2026-10-12", toDate: "2026-10-13", halfDay: false, reason: "Family function" };

  it("accepts a complete request", () => {
    expect(validateLeave(valid, [CL, EL])).toEqual({});
  });

  it("asks for the type, dates and reason", () => {
    expect(validateLeave(EMPTY_LEAVE, [CL])).toEqual({
      leaveTypeId: "validation.required",
      fromDate: "validation.required",
      toDate: "validation.required",
      reason: "validation.required",
    });
  });

  it("refuses an end before the start and ranges over 200 days", () => {
    expect(validateLeave({ ...valid, toDate: "2026-10-11" }, [CL]).toDate).toBe("leave.v.toBeforeFrom");
    expect(validateLeave({ ...valid, toDate: "2027-05-01" }, [CL]).toDate).toBe("leave.v.tooLong");
  });

  it("allows a half day only on one date and for types that allow it", () => {
    expect(validateLeave({ ...valid, halfDay: true }, [CL]).halfDay).toBe("leave.v.halfDaySingle");
    expect(validateLeave({ ...valid, toDate: valid.fromDate, halfDay: true }, [CL])).toEqual({});
    expect(validateLeave({ ...valid, leaveTypeId: "el", toDate: valid.fromDate, halfDay: true }, [EL]).halfDay).toBe(
      "leave.v.halfDayType",
    );
  });
});

describe("leave type and balance checks", () => {
  it("takes whole or half days only", () => {
    expect(isHalfStep("12", 0, 366)).toBe(true);
    expect(isHalfStep("1.5", 0, 366)).toBe(true);
    expect(isHalfStep("1.25", 0, 366)).toBe(false);
    expect(isHalfStep("-1", 0, 366)).toBe(false);
    expect(isHalfStep("400", 0, 366)).toBe(false);
    expect(isHalfStep("abc", 0, 366)).toBe(false);
  });

  it("validates a leave type like the API", () => {
    expect(validateLeaveType({ ...EMPTY_LEAVE_TYPE, name: "Study leave", code: "STL", yearlyQuota: "5" })).toEqual({});
    expect(validateLeaveType({ ...EMPTY_LEAVE_TYPE, name: "Study leave", code: "S L" }).code).toBe("leave.v.code");
    expect(validateLeaveType({ ...EMPTY_LEAVE_TYPE, name: "X", code: "X", yearlyQuota: "2.3" }).yearlyQuota).toBe("leave.v.days");
    // Loss of pay has no allowance to check.
    expect(validateLeaveType({ ...EMPTY_LEAVE_TYPE, name: "LOP", code: "LOP", lossOfPay: true, yearlyQuota: "x" })).toEqual({});
    expect(validateBalance({ opening: "3.5", accrued: "12" })).toEqual({});
    expect(validateBalance({ opening: "1000", accrued: "" })).toEqual({ opening: "leave.v.balance", accrued: "leave.v.balance" });
  });
});

describe("ApplyLeaveDialog", () => {
  function renderDialog(onApplied = vi.fn()) {
    renderAs(
      ["leave.request"],
      <ApplyLeaveDialog open types={[CL, EL]} balances={[CL_BALANCE]} onClose={vi.fn()} onApplied={onApplied} />,
      ["TEACHER"],
    );
    return screen.getByRole("dialog", { name: "Apply for leave" });
  }

  it("shows what is missing instead of sending an incomplete request", async () => {
    const user = userEvent.setup();
    const dialog = renderDialog();
    await user.click(within(dialog).getByRole("button", { name: "Apply" }));
    expect(apply).not.toHaveBeenCalled();
    expect(within(dialog).getAllByText("Fill in this field.")).toHaveLength(4);
    expect(within(dialog).getByLabelText("Leave type")).toHaveFocus();
  });

  it("shows the working days and the balance after, then applies", async () => {
    const request = { id: "r1", status: "PENDING", approverName: "Meena Iyer" } as StaffLeaveRequest;
    apply.mockResolvedValue(request);
    const onApplied = vi.fn();
    const user = userEvent.setup();
    const dialog = renderDialog(onApplied);
    // The type list shows what is left after requests still waiting.
    expect(within(dialog).getByRole("option", { name: "Casual leave · 9 left" })).toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText("Leave type"), "cl");
    await user.type(within(dialog).getByLabelText("From"), "2026-10-12");
    await user.clear(within(dialog).getByLabelText("To"));
    await user.type(within(dialog).getByLabelText("To"), "2026-10-18");

    const box = await within(dialog).findByTestId("leave-preview");
    expect(box).toHaveTextContent("6 days of leave");
    expect(box).toHaveTextContent("(1 Sunday or off day not counted)");
    expect(within(box).getByTestId("leave-preview-balance")).toHaveTextContent(
      "Balance now 10, waiting for approval 1, after this 3.",
    );
    expect(preview).toHaveBeenLastCalledWith({ leaveTypeId: "cl", fromDate: "2026-10-12", toDate: "2026-10-18", halfDay: false });

    await user.type(within(dialog).getByLabelText("Reason"), "Sister's wedding");
    await user.click(within(dialog).getByRole("button", { name: "Apply" }));
    await waitFor(() => expect(apply).toHaveBeenCalledTimes(1));
    expect(apply).toHaveBeenCalledWith({
      leaveTypeId: "cl",
      fromDate: "2026-10-12",
      toDate: "2026-10-18",
      halfDay: false,
      reason: "Sister's wedding",
    });
    expect(onApplied).toHaveBeenCalledWith(request);
  });

  it("warns when the balance is not enough and shows the API's refusal on the field", async () => {
    preview.mockImplementation(async (body: { fromDate: string; toDate: string }) =>
      previewOf({ fromDate: body.fromDate, toDate: body.toDate, availableAfter: -2, enough: false }),
    );
    apply.mockRejectedValue(
      new ApiError({
        status: 409,
        title: "Not enough leave",
        detail: "Not enough Casual leave: 6 days asked, 4 left.",
        errors: { leaveTypeId: "Not enough Casual leave: 6 days asked, 4 left." },
      }),
    );
    const user = userEvent.setup();
    const dialog = renderDialog();
    await user.selectOptions(within(dialog).getByLabelText("Leave type"), "cl");
    await user.type(within(dialog).getByLabelText("From"), "2026-10-12");
    await user.clear(within(dialog).getByLabelText("To"));
    await user.type(within(dialog).getByLabelText("To"), "2026-10-18");
    expect(await within(dialog).findByText("Not enough balance for this request.")).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("Reason"), "Trip");
    await user.click(within(dialog).getByRole("button", { name: "Apply" }));
    expect(await within(dialog).findByText("Not enough Casual leave: 6 days asked, 4 left.", { selector: ".field-error" })).toBeInTheDocument();
  });

  it("offers a half day only for types that allow it", async () => {
    const user = userEvent.setup();
    const dialog = renderDialog();
    await user.selectOptions(within(dialog).getByLabelText("Leave type"), "el");
    expect(within(dialog).queryByLabelText("Half day only")).not.toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText("Leave type"), "cl");
    expect(within(dialog).getByLabelText("Half day only")).toBeInTheDocument();
  });
});
