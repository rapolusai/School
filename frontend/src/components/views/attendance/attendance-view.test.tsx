import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { RegisterView, SaveRegisterResult, SectionDay, SectionsForDay } from "@/lib/types";
import { renderAs } from "@/test/render";
import { countStatuses, formatPercent, presentPercent } from "./attendance-shared";
import { AttendanceView, defaultSection } from "./attendance-view";

const { sections, register, saveRegister } = vi.hoisted(() => ({
  sections: vi.fn(),
  register: vi.fn(),
  saveRegister: vi.fn(),
}));

vi.mock("@/lib/attendance-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/attendance-api")>();
  return { ...actual, attendanceApi: { ...actual.attendanceApi, sections, register, saveRegister } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/attendance",
}));

const TEACHER = ["dashboard.view", "students.read", "attendance.mark", "attendance.read", "academics.read"];

const NONE = { present: 0, absent: 0, late: 0, halfDay: 0, leave: 0 };

function section(overrides: Partial<SectionDay> = {}): SectionDay {
  return {
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
    ...overrides,
  };
}

function day(list: SectionDay[], date: string): SectionsForDay {
  return {
    date,
    today: date,
    academicYear: { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31" },
    canMark: true,
    sections: list,
  };
}

function registerView(overrides: Partial<RegisterView> = {}): RegisterView {
  return {
    sectionId: "s5a",
    classId: "c5",
    className: "Class 5",
    sectionName: "A",
    label: "Class 5 A",
    date: "2026-10-09",
    academicYearName: "2026-27",
    marked: false,
    markedByName: null,
    markedAt: null,
    updatedByName: null,
    updatedAt: null,
    canEdit: true,
    counts: NONE,
    unmarked: 3,
    presentPercent: null,
    entries: [
      { studentId: "st1", fullName: "Asha Rao", admissionNo: "A-1", rollNo: 1, inSection: true, status: null },
      { studentId: "st2", fullName: "Bala Iyer", admissionNo: "A-2", rollNo: 2, inSection: true, status: null },
      { studentId: "st3", fullName: "Chitra Das", admissionNo: "A-3", rollNo: 3, inSection: true, status: null },
    ],
    ...overrides,
  };
}

beforeEach(() => {
  sections.mockImplementation((date: string) => Promise.resolve(day([section()], date)));
  register.mockImplementation((sectionId: string, date: string) =>
    Promise.resolve(registerView({ sectionId, date })),
  );
  saveRegister.mockImplementation((sectionId: string, date: string, body: { entries: { status: string }[] }) => {
    const statuses = body.entries.map((e) => e.status) as RegisterView["entries"][number]["status"][];
    const result: SaveRegisterResult = {
      register: registerView({
        sectionId,
        date,
        marked: true,
        markedByName: "Ravi Kumar",
        markedAt: "2026-10-09T03:40:00Z",
        unmarked: 0,
        entries: registerView().entries.map((e, i) => ({ ...e, status: statuses[i] ?? null })),
      }),
      firstSave: true,
      changed: 3,
      alertsQueued: statuses.filter((s) => s === "ABSENT").length,
      alertsCancelled: 0,
    };
    return Promise.resolve(result);
  });
});

describe("AttendanceView", () => {
  it("marks everyone present, takes one toggle and saves after confirming", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <AttendanceView />, ["TEACHER"]);

    expect(await screen.findByRole("heading", { name: "Class 5 A" })).toBeInTheDocument();
    const counts = screen.getByTestId("attendance-counts");
    expect(within(counts).getByText("Not marked yet").nextSibling).toHaveTextContent("3");

    await user.click(screen.getByRole("button", { name: "Mark all present" }));
    expect(within(counts).getByText("Present").nextSibling).toHaveTextContent("3");
    expect(within(counts).getByText("Not marked yet").nextSibling).toHaveTextContent("0");

    const bala = screen.getByRole("group", { name: "Bala Iyer" });
    await user.click(within(bala).getByRole("button", { name: "Absent" }));
    expect(within(bala).getByRole("button", { name: "Absent" })).toHaveAttribute("aria-pressed", "true");
    expect(within(bala).getByRole("button", { name: "Present" })).toHaveAttribute("aria-pressed", "false");
    expect(within(counts).getByText("Present").nextSibling).toHaveTextContent("2");
    expect(within(counts).getByText("Absent").nextSibling).toHaveTextContent("1");

    await user.click(screen.getByTestId("attendance-save"));
    const dialog = await screen.findByRole("dialog", { name: "Save attendance for Class 5 A?" });
    expect(dialog).toHaveTextContent("2 present, 1 absent");
    expect(saveRegister).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "Save attendance" }));

    await waitFor(() => expect(saveRegister).toHaveBeenCalledTimes(1));
    const [sectionId, , body] = saveRegister.mock.calls[0];
    expect(sectionId).toBe("s5a");
    expect(body).toEqual({
      entries: [
        { studentId: "st1", status: "PRESENT" },
        { studentId: "st2", status: "ABSENT" },
        { studentId: "st3", status: "PRESENT" },
      ],
    });
    expect(await screen.findByText("Attendance saved for Class 5 A. 1 absence alert queued.")).toBeInTheDocument();
    expect(screen.getByTestId("register-meta")).toHaveTextContent("Marked by Ravi Kumar");
    // Saved and unchanged: nothing to save until something changes.
    expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled();
  });

  it("will not save until every student has a mark", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <AttendanceView />, ["TEACHER"]);
    const asha = await screen.findByRole("group", { name: "Asha Rao" });
    await user.click(within(asha).getByRole("button", { name: "Late" }));
    await user.click(screen.getByTestId("attendance-save"));
    expect(await screen.findByRole("alert")).toHaveTextContent("2 students are not marked yet.");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(saveRegister).not.toHaveBeenCalled();
  });

  it("shows who marked a register and lets it be edited", async () => {
    const user = userEvent.setup();
    register.mockImplementation((sectionId: string, date: string) =>
      Promise.resolve(
        registerView({
          sectionId,
          date,
          marked: true,
          markedByName: "Ravi Kumar",
          markedAt: "2026-10-09T03:40:00Z",
          updatedByName: "Lakshmi Iyer",
          updatedAt: "2026-10-09T05:00:00Z",
          unmarked: 0,
          entries: registerView().entries.map((e) => ({ ...e, status: "PRESENT" as const })),
        }),
      ),
    );
    sections.mockImplementation((date: string) => Promise.resolve(day([section({ marked: true })], date)));
    renderAs(TEACHER, <AttendanceView />, ["TEACHER"]);

    const meta = await screen.findByTestId("register-meta");
    expect(meta).toHaveTextContent("Marked by Ravi Kumar");
    expect(meta).toHaveTextContent("last changed by Lakshmi Iyer");
    const save = screen.getByRole("button", { name: "Save changes" });
    expect(save).toBeDisabled();
    await user.click(within(screen.getByRole("group", { name: "Chitra Das" })).getByRole("button", { name: "Absent" }));
    expect(save).toBeEnabled();
  });

  it("offers sections grouped by class and picks the first one still to mark", async () => {
    const list = [
      section({ sectionId: "s1a", classId: "c1", className: "Class 1", label: "Class 1 A", marked: true }),
      section({ sectionId: "s5a" }),
      section({ sectionId: "s5b", sectionName: "B", label: "Class 5 B" }),
    ];
    sections.mockImplementation((date: string) => Promise.resolve(day(list, date)));
    renderAs([...TEACHER, "attendance.manage"], <AttendanceView />);
    const picker = await screen.findByRole("combobox", { name: "Class and section" });
    expect(within(picker).getAllByRole("group").map((g) => g.getAttribute("label"))).toEqual(["Class 1", "Class 5"]);
    expect(picker).toHaveValue("s5a");
    expect(screen.getByTestId("sections-marked")).toHaveTextContent("1 of 3 sections marked");
    expect(defaultSection(list)).toBe("s5a");
    await waitFor(() => expect(register).toHaveBeenCalledWith("s5a", expect.any(String)));
  });

  it("tells a teacher without a section what to do", async () => {
    sections.mockImplementation((date: string) => Promise.resolve(day([], date)));
    renderAs(TEACHER, <AttendanceView />, ["TEACHER"]);
    expect(await screen.findByTestId("attendance-no-sections")).toHaveTextContent(
      "You are not the class teacher of any section.",
    );
  });
});

describe("attendance maths", () => {
  it("counts present and late as a day and a half day as half", () => {
    const counts = countStatuses(["PRESENT", "ABSENT", "LATE", "HALF_DAY", null]);
    expect(counts).toEqual({ present: 1, absent: 1, late: 1, halfDay: 1, leave: 0 });
    expect(presentPercent(counts)).toBe(62.5);
    expect(presentPercent(countStatuses(["PRESENT", "PRESENT", "ABSENT"]))).toBe(66.7);
    expect(presentPercent(countStatuses(["LEAVE"]))).toBe(0);
    expect(presentPercent(countStatuses([]))).toBeNull();
    expect(formatPercent(62.5)).toBe("62.5%");
    expect(formatPercent(100)).toBe("100%");
    expect(formatPercent(null)).toBe("—");
  });
});
