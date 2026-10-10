import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ChildLeave, LeaveInbox, RegisterView, SectionsForDay } from "@/lib/types";
import { renderAs } from "@/test/render";
import { AttendanceView } from "../attendance/attendance-view";
import { LeaveInboxView, validateDecision } from "./leave-inbox";

const m = vi.hoisted(() => ({
  inbox: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  sections: vi.fn(),
  register: vi.fn(),
  saveRegister: vi.fn(),
}));

vi.mock("@/lib/portal-api", () => ({ portalApi: { inbox: m.inbox, approve: m.approve, reject: m.reject } }));
vi.mock("@/lib/attendance-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/attendance-api")>();
  return {
    ...actual,
    attendanceApi: { ...actual.attendanceApi, sections: m.sections, register: m.register, saveRegister: m.saveRegister },
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/attendance/leave-requests",
}));

const TEACHER = ["dashboard.view", "students.read", "attendance.mark", "attendance.read", "academics.read"];
const NONE = { present: 0, absent: 0, late: 0, halfDay: 0, leave: 0 };

function request(overrides: Partial<ChildLeave> = {}): ChildLeave {
  return {
    id: "l1",
    studentId: "st1",
    studentName: "Asha Rao",
    admissionNo: "A-1",
    rollNo: 1,
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    fromDate: "2026-10-12",
    toDate: "2026-10-13",
    halfDay: false,
    schoolDays: 2,
    reason: "Family wedding",
    status: "PENDING",
    requestedByName: "Lata Rao",
    createdAt: "2026-10-08T04:00:00Z",
    decidedByName: null,
    decidedAt: null,
    decisionComment: null,
    cancelledByName: null,
    cancelledAt: null,
    canCancel: false,
    canDecide: true,
    ...overrides,
  };
}

const INBOX: LeaveInbox = {
  pending: [request()],
  recent: [
    request({
      id: "l0",
      studentName: "Bala Iyer",
      status: "REJECTED",
      decidedByName: "Ravi Kumar",
      decidedAt: "2026-10-01T05:00:00Z",
      decisionComment: "Exams that week",
      canDecide: false,
    }),
  ],
  wholeSchool: false,
};

beforeEach(() => {
  m.inbox.mockResolvedValue(INBOX);
  m.approve.mockImplementation((id: string) =>
    Promise.resolve(request({ id, status: "APPROVED", decidedByName: "Ravi Kumar", decidedAt: "2026-10-09T05:00:00Z", canDecide: false })),
  );
  m.reject.mockImplementation((id: string, comment: string) =>
    Promise.resolve(request({ id, status: "REJECTED", decisionComment: comment, decidedByName: "Ravi Kumar", decidedAt: "2026-10-09T05:00:00Z", canDecide: false })),
  );
});

describe("validateDecision", () => {
  it("needs a reason to reject but not to approve", () => {
    expect(validateDecision("approve", "")).toEqual({});
    expect(validateDecision("reject", "  ")).toEqual({ comment: "portal.inbox.v.comment" });
    expect(validateDecision("reject", "Exams")).toEqual({});
    expect(validateDecision("approve", "x".repeat(501))).toEqual({ comment: "validation.tooLong" });
  });
});

describe("LeaveInboxView", () => {
  it("lists waiting and recent requests", async () => {
    renderAs(TEACHER, <LeaveInboxView />, ["TEACHER"]);
    const pending = await screen.findByTestId("inbox-pending");
    const asha = within(pending).getByRole("listitem", { name: "Asha Rao" });
    expect(asha).toHaveTextContent("12 Oct 2026 – 13 Oct 2026");
    expect(asha).toHaveTextContent("2 school days");
    expect(asha).toHaveTextContent("Family wedding");
    expect(asha).toHaveTextContent("Asked by Lata Rao");
    expect(screen.getByText("Students of your class")).toBeInTheDocument();
    const recent = screen.getByTestId("inbox-recent");
    expect(recent).toHaveTextContent("Rejected by Ravi Kumar: “Exams that week”");
    expect(within(recent).queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("approves with an optional comment", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <LeaveInboxView />, ["TEACHER"]);
    const asha = await screen.findByRole("listitem", { name: "Asha Rao" });
    await user.click(within(asha).getByRole("button", { name: "Approve" }));
    const dialog = await screen.findByRole("dialog", { name: "Approve this leave?" });
    expect(dialog).toHaveTextContent("no absence alert goes to the parent");
    await user.click(within(dialog).getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(m.approve).toHaveBeenCalledWith("l1", ""));
    expect(await screen.findByText("Leave approved for Asha Rao.")).toBeInTheDocument();
    expect(m.inbox).toHaveBeenCalledTimes(2);
  });

  it("asks why before rejecting", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <LeaveInboxView />, ["TEACHER"]);
    const asha = await screen.findByRole("listitem", { name: "Asha Rao" });
    await user.click(within(asha).getByRole("button", { name: "Reject" }));
    const dialog = await screen.findByRole("dialog", { name: "Reject this leave?" });
    await user.click(within(dialog).getByRole("button", { name: "Reject" }));
    expect(await within(dialog).findByText("Say why, so the parent knows.")).toBeInTheDocument();
    expect(m.reject).not.toHaveBeenCalled();
    await user.type(within(dialog).getByLabelText("Reason for the parent"), "Exams that week");
    await user.click(within(dialog).getByRole("button", { name: "Reject" }));
    await waitFor(() => expect(m.reject).toHaveBeenCalledWith("l1", "Exams that week"));
  });

  it("says when nothing is waiting", async () => {
    m.inbox.mockResolvedValue({ pending: [], recent: [], wholeSchool: true });
    renderAs(TEACHER, <LeaveInboxView />, ["TEACHER"]);
    expect(await screen.findByTestId("inbox-empty")).toHaveTextContent("No leave requests are waiting.");
    expect(screen.getByText("Every class")).toBeInTheDocument();
  });
});

/* ------------------------------------------------------------------ the register */

function sectionsFor(date: string): SectionsForDay {
  return {
    date,
    today: date,
    academicYear: { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31" },
    canMark: true,
    sections: [
      {
        sectionId: "s5a",
        classId: "c5",
        className: "Class 5",
        sectionName: "A",
        label: "Class 5 A",
        classTeacherName: "Ravi Kumar",
        students: 3,
        marked: false,
        markedByName: null,
        markedAt: null,
        counts: NONE,
        presentPercent: null,
        canMark: true,
      },
    ],
  };
}

function registerFor(date: string, marked = false): RegisterView {
  return {
    sectionId: "s5a",
    classId: "c5",
    className: "Class 5",
    sectionName: "A",
    label: "Class 5 A",
    date,
    academicYearName: "2026-27",
    marked,
    markedByName: marked ? "Ravi Kumar" : null,
    markedAt: marked ? "2026-10-09T03:40:00Z" : null,
    updatedByName: null,
    updatedAt: null,
    canEdit: true,
    counts: NONE,
    unmarked: marked ? 0 : 3,
    presentPercent: null,
    entries: [
      {
        studentId: "st1",
        fullName: "Asha Rao",
        admissionNo: "A-1",
        rollNo: 1,
        inSection: true,
        status: marked ? "ABSENT" : null,
        approvedLeave: { requestId: "l1", halfDay: false, prefill: "LEAVE" },
      },
      {
        studentId: "st2",
        fullName: "Bala Iyer",
        admissionNo: "A-2",
        rollNo: 2,
        inSection: true,
        status: marked ? "PRESENT" : null,
        approvedLeave: { requestId: "l2", halfDay: true, prefill: "HALF_DAY" },
      },
      { studentId: "st3", fullName: "Chitra Das", admissionNo: "A-3", rollNo: 3, inSection: true, status: marked ? "PRESENT" : null },
    ],
  };
}

describe("the register with approved child leave", () => {
  beforeEach(() => {
    m.sections.mockImplementation((date: string) => Promise.resolve(sectionsFor(date)));
    m.register.mockImplementation((_s: string, date: string) => Promise.resolve(registerFor(date)));
  });

  it("starts an unmarked register from approved leave, which the teacher can change", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <AttendanceView />, ["TEACHER"]);
    const asha = await screen.findByRole("group", { name: "Asha Rao" });
    expect(within(asha).getByRole("button", { name: "Leave (excused)" })).toHaveAttribute("aria-pressed", "true");
    const bala = screen.getByRole("group", { name: "Bala Iyer" });
    expect(within(bala).getByRole("button", { name: "Half day" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("Roll 1 · On approved leave")).toBeInTheDocument();
    expect(screen.getByText("Roll 2 · Approved half day leave")).toBeInTheDocument();
    const counts = screen.getByTestId("attendance-counts");
    expect(within(counts).getByText("Not marked yet").nextSibling).toHaveTextContent("1");

    // "Mark all present" keeps the approved leave.
    await user.click(screen.getByRole("button", { name: "Mark all present" }));
    expect(within(asha).getByRole("button", { name: "Leave (excused)" })).toHaveAttribute("aria-pressed", "true");
    expect(within(counts).getByText("Present").nextSibling).toHaveTextContent("1");

    // The teacher can still mark someone on leave as present.
    await user.click(within(asha).getByRole("button", { name: "Present" }));
    expect(within(asha).getByRole("button", { name: "Present" })).toHaveAttribute("aria-pressed", "true");
  });

  it("does not change a register that is already marked", async () => {
    m.register.mockImplementation((_s: string, date: string) => Promise.resolve(registerFor(date, true)));
    renderAs(TEACHER, <AttendanceView />, ["TEACHER"]);
    const asha = await screen.findByRole("group", { name: "Asha Rao" });
    expect(within(asha).getByRole("button", { name: "Absent" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("Roll 1 · On approved leave")).toBeInTheDocument();
  });

  it("links to the leave requests with how many wait", async () => {
    renderAs(TEACHER, <AttendanceView />, ["TEACHER"]);
    const link = await screen.findByTestId("leave-requests-link");
    expect(link).toHaveAttribute("href", "/app/attendance/leave-requests");
    await waitFor(() => expect(link).toHaveTextContent("Leave requests1"));
  });
});
