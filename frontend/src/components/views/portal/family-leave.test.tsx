import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { Child, ChildLeave, FamilyLeave } from "@/lib/types";
import { renderAs } from "@/test/render";
import { EMPTY_CHILD_LEAVE, FamilyLeaveView, validateChildLeave } from "./family-leave";

const m = vi.hoisted(() => ({
  myChildren: vi.fn(),
  childLeave: vi.fn(),
  applyLeave: vi.fn(),
  cancelLeave: vi.fn(),
  myLeave: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren: m.myChildren } };
});
vi.mock("@/lib/portal-api", () => ({
  portalApi: { childLeave: m.childLeave, applyLeave: m.applyLeave, cancelLeave: m.cancelLeave, myLeave: m.myLeave },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/family/leave",
}));

const PARENT = ["dashboard.view", "child.view", "notices.read"];
const LIMITS = { earliest: "2026-09-09", latest: "2027-03-31" };

const ARJUN: Child = {
  id: "st1",
  fullName: "Arjun Sharma",
  admissionNo: "AKS/2026/001",
  status: "ACTIVE",
  className: "Class 5",
  sectionName: "A",
  rollNo: 1,
  classTeacherName: "Ravi Kumar",
  academicYearName: "2026-27",
};
const DIYA: Child = { ...ARJUN, id: "st2", fullName: "Diya Sharma", className: "Class 2" };

function request(overrides: Partial<ChildLeave> = {}): ChildLeave {
  return {
    id: "l1",
    studentId: "st1",
    studentName: "Arjun Sharma",
    admissionNo: "AKS/2026/001",
    rollNo: 1,
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    fromDate: "2026-10-12",
    toDate: "2026-10-13",
    halfDay: false,
    schoolDays: 2,
    reason: "Family wedding",
    status: "APPROVED",
    requestedByName: "Anitha Sharma",
    createdAt: "2026-10-08T04:00:00Z",
    decidedByName: "Ravi Kumar",
    decidedAt: "2026-10-08T06:00:00Z",
    decisionComment: "Enjoy the wedding",
    cancelledByName: null,
    cancelledAt: null,
    canCancel: true,
    canDecide: false,
    ...overrides,
  };
}

function family(studentId: string, requests: ChildLeave[], canApply = true): FamilyLeave {
  return {
    studentId,
    studentName: studentId === "st1" ? "Arjun Sharma" : "Diya Sharma",
    sectionLabel: "Class 5 A",
    classTeacherName: "Ravi Kumar",
    today: "2026-10-09",
    earliest: LIMITS.earliest,
    latest: LIMITS.latest,
    canApply,
    requests,
  };
}

beforeEach(() => {
  window.localStorage.clear();
  m.myChildren.mockResolvedValue([ARJUN, DIYA]);
  m.childLeave.mockImplementation((id: string) => Promise.resolve(family(id, id === "st1" ? [request()] : [])));
  m.applyLeave.mockImplementation((studentId: string, body: { fromDate: string; toDate: string; halfDay: boolean; reason: string }) =>
    Promise.resolve(request({ id: "l2", status: "PENDING", decidedByName: null, decidedAt: null, decisionComment: null, ...body, studentId })),
  );
  m.cancelLeave.mockResolvedValue(request({ status: "CANCELLED", canCancel: false }));
  m.myLeave.mockResolvedValue(family("st1", [request({ canCancel: false })], false));
});

afterEach(() => window.localStorage.clear());

describe("validateChildLeave", () => {
  const valid = { fromDate: "2026-10-12", toDate: "2026-10-13", halfDay: false, reason: "Family wedding" };

  it("accepts a complete note", () => {
    expect(validateChildLeave(valid, LIMITS)).toEqual({});
    expect(validateChildLeave({ ...valid, toDate: valid.fromDate, halfDay: true }, LIMITS)).toEqual({});
  });

  it("mirrors the API's checks", () => {
    expect(validateChildLeave(EMPTY_CHILD_LEAVE, LIMITS)).toEqual({
      fromDate: "validation.required",
      toDate: "validation.required",
      reason: "portal.leave.v.reason",
    });
    expect(validateChildLeave({ ...valid, toDate: "2026-10-11" }, LIMITS).toDate).toBe("leave.v.toBeforeFrom");
    expect(validateChildLeave({ ...valid, halfDay: true }, LIMITS).halfDay).toBe("leave.v.halfDaySingle");
    expect(validateChildLeave({ ...valid, toDate: "2026-11-12" }, LIMITS).toDate).toBe("portal.leave.v.tooLong");
    expect(validateChildLeave({ ...valid, toDate: "2026-11-11" }, LIMITS).toDate).toBeUndefined();
    expect(validateChildLeave({ ...valid, fromDate: "2026-09-08" }, LIMITS).fromDate).toBe("portal.leave.v.tooEarly");
    expect(validateChildLeave({ ...valid, toDate: "2027-04-01" }, LIMITS).toDate).toBe("portal.leave.v.tooLate");
    expect(validateChildLeave({ ...valid, reason: "   " }, LIMITS).reason).toBe("portal.leave.v.reason");
    expect(validateChildLeave({ ...valid, reason: "x".repeat(501) }, LIMITS).reason).toBe("validation.tooLong");
  });
});

describe("FamilyLeaveView for a parent", () => {
  it("lists the child's requests with the teacher's decision", async () => {
    renderAs(PARENT, <FamilyLeaveView />, ["PARENT"]);
    const row = await screen.findByTestId("family-leave-row");
    expect(row).toHaveTextContent("12 Oct 2026 – 13 Oct 2026");
    expect(row).toHaveTextContent("Approved");
    expect(row).toHaveTextContent("2 school days");
    expect(row).toHaveTextContent("Approved by Ravi Kumar: “Enjoy the wedding”");
    expect(screen.getByText(/Ravi Kumar, sees your request/)).toBeInTheDocument();
  });

  it("applies for a half day and shows it", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyLeaveView />, ["PARENT"]);
    await user.click(await screen.findByRole("button", { name: "Apply for leave" }));
    const dialog = await screen.findByRole("dialog", { name: "Leave for Arjun Sharma" });
    const form = within(dialog);

    // Nothing filled in: the form explains and sends nothing.
    await user.click(form.getByRole("button", { name: "Send request" }));
    expect(await form.findByText("Say why your child will be away.")).toBeInTheDocument();
    expect(m.applyLeave).not.toHaveBeenCalled();

    await user.type(form.getByLabelText("From"), "2026-10-14");
    await user.click(form.getByLabelText("Half day only"));
    expect(form.getByLabelText("To")).toHaveValue("2026-10-14");
    await user.type(form.getByLabelText("Reason"), "  Dentist at 1 pm  ");
    m.childLeave.mockResolvedValueOnce(family("st1", [request({ id: "l2", status: "PENDING", halfDay: true, fromDate: "2026-10-14", toDate: "2026-10-14" }), request()]));
    await user.click(form.getByRole("button", { name: "Send request" }));

    await waitFor(() =>
      expect(m.applyLeave).toHaveBeenCalledWith("st1", {
        fromDate: "2026-10-14",
        toDate: "2026-10-14",
        halfDay: true,
        reason: "Dentist at 1 pm",
      }),
    );
    expect(await screen.findByText("Leave request sent to the class teacher.")).toBeInTheDocument();
    expect(await screen.findByText("14 Oct 2026 (half day)")).toBeInTheDocument();
  });

  it("shows the API's refusal on the field it is about", async () => {
    const user = userEvent.setup();
    m.applyLeave.mockRejectedValue(
      new ApiError({
        status: 409,
        title: "Overlapping request",
        detail: "There is already a pending request for 2026-10-12.",
        errors: { fromDate: "There is already a pending request for 2026-10-12." },
      }),
    );
    renderAs(PARENT, <FamilyLeaveView />, ["PARENT"]);
    await user.click(await screen.findByRole("button", { name: "Apply for leave" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("From"), "2026-10-12");
    await user.type(within(dialog).getByLabelText("Reason"), "Fever");
    await user.click(within(dialog).getByRole("button", { name: "Send request" }));
    expect(await within(dialog).findAllByText("There is already a pending request for 2026-10-12.")).not.toHaveLength(0);
  });

  it("withdraws a request after confirming", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyLeaveView />, ["PARENT"]);
    await user.click(await screen.findByRole("button", { name: "Withdraw request" }));
    const dialog = await screen.findByRole("dialog", { name: "Withdraw this request?" });
    await user.click(within(dialog).getByRole("button", { name: "Withdraw request" }));
    await waitFor(() => expect(m.cancelLeave).toHaveBeenCalledWith("st1", "l1"));
    expect(await screen.findByText("Request withdrawn.")).toBeInTheDocument();
  });

  it("follows the child chosen in the switcher", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyLeaveView />, ["PARENT"]);
    await screen.findByTestId("family-leave-row");
    await user.click(screen.getByRole("radio", { name: "Diya Sharma" }));
    expect(await screen.findByTestId("family-leave-empty")).toBeInTheDocument();
    expect(m.childLeave).toHaveBeenCalledWith("st2");
  });

  it("does not offer leave for a child who is not in a class", async () => {
    m.childLeave.mockResolvedValue(family("st1", [], false));
    renderAs(PARENT, <FamilyLeaveView />, ["PARENT"]);
    expect(await screen.findByText(/not in a class this year/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Apply for leave" })).not.toBeInTheDocument();
  });
});

describe("FamilyLeaveView for a student", () => {
  it("is read only", async () => {
    renderAs(["dashboard.view"], <FamilyLeaveView />, ["STUDENT"]);
    expect(await screen.findByTestId("family-leave-row")).toHaveTextContent("Family wedding");
    expect(screen.getByText(/Your parent applies for your leave/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Apply for leave" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Withdraw request" })).not.toBeInTheDocument();
    expect(m.myChildren).not.toHaveBeenCalled();
  });
});
