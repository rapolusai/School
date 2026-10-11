import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { MarkRow, MarkSheet } from "@/lib/types";
import { renderAs } from "@/test/render";
import { parseDecimal } from "./exams-shared";
import {
  entryProblems,
  entryTotal,
  isComplete,
  MarksGridView,
  missingStudents,
  partsOf,
  reasonProblem,
  toEntries,
  type EntryText,
} from "./marks-grid";

const m = vi.hoisted(() => ({ sheet: vi.fn(), saveSheet: vi.fn(), submitSheet: vi.fn(), unlockSheet: vi.fn() }));

vi.mock("@/lib/exams-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/exams-api")>();
  return { ...actual, examsApi: { ...actual.examsApi, ...m } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/exams/marks/p1/s5a",
}));

const TEACHER = ["dashboard.view", "exams.manage"];
const PRINCIPAL = ["dashboard.view", "exams.read", "exams.publish"];

function student(overrides: Partial<MarkRow>): MarkRow {
  return {
    studentId: "st1",
    fullName: "Aarav Rao",
    admissionNo: "AKS/2026/001",
    rollNo: 1,
    inSection: true,
    theory: null,
    practical: null,
    internal: null,
    code: null,
    total: null,
    percent: null,
    grade: null,
    ...overrides,
  };
}

function sheet(overrides: Partial<MarkSheet> = {}): MarkSheet {
  return {
    sheetId: "ms1",
    version: 3,
    examId: "e1",
    examName: "Half-yearly",
    termName: "Term 1",
    examClassId: "ec1",
    classId: "c5",
    className: "Class 5",
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    paperId: "p1",
    subjectId: "sub1",
    subjectName: "English",
    maxTheory: 80,
    maxPractical: 0,
    maxInternal: 20,
    maxTotal: 100,
    paperDate: "2026-09-14",
    status: "DRAFT",
    updatedByName: "Ravi Kumar",
    updatedAt: "2026-09-20T05:00:00Z",
    submittedByName: null,
    submittedAt: null,
    unlockedByName: null,
    unlockedAt: null,
    unlockReason: null,
    canEdit: true,
    canUnlock: false,
    resultsPublished: false,
    entered: 1,
    students: [
      student({ theory: 70, internal: 18, total: 88, percent: 88, grade: "A2" }),
      student({ studentId: "st2", fullName: "Diya Menon", admissionNo: "AKS/2026/002", rollNo: 2 }),
      student({ studentId: "st3", fullName: "Kabir Singh", admissionNo: "AKS/2026/003", rollNo: 3 }),
    ],
    ...overrides,
  };
}

const LIMITS = { maxTheory: 80, maxPractical: 0, maxInternal: 20 };
const text = (theory: string, internal: string, code: EntryText["code"] = null): EntryText => ({
  theory,
  practical: "",
  internal,
  code,
});

beforeEach(() => {
  m.sheet.mockResolvedValue(sheet());
  m.saveSheet.mockImplementation((_p: string, _s: string, body: { version: number }) =>
    Promise.resolve(sheet({ version: body.version + 1, entered: 2 })),
  );
  m.submitSheet.mockResolvedValue(
    sheet({ version: 5, status: "LOCKED", submittedByName: "Ravi Kumar", submittedAt: "2026-09-21T05:00:00Z" }),
  );
});

describe("marks helpers", () => {
  it("reads typed marks: two decimals at most, within each part's maximum", () => {
    expect(parseDecimal("")).toBeNull();
    expect(parseDecimal(" 37.5 ")).toBe(37.5);
    expect(parseDecimal("37.555")).toBeNaN();
    expect(parseDecimal("-1")).toBeNaN();
    expect(partsOf(LIMITS)).toEqual(["theory", "internal"]);
    expect(entryProblems(text("81", "x"), LIMITS)).toEqual({ theory: "exams.v.markMax", internal: "exams.v.mark" });
    expect(entryProblems(text("81", "x", "AB"), LIMITS)).toEqual({});
    expect(entryProblems(text("80", ""), LIMITS)).toEqual({});
  });

  it("counts a line as entered with every part or a code, and totals it", () => {
    expect(isComplete(text("70", ""), LIMITS)).toBe(false);
    expect(isComplete(text("70", "18.5"), LIMITS)).toBe(true);
    expect(isComplete(text("", "", "ML"), LIMITS)).toBe(true);
    expect(entryTotal(text("70", "18.5"), LIMITS)).toBe(88.5);
    expect(entryTotal(text("70", ""), LIMITS)).toBeNull();
    expect(entryTotal(text("", "", "AB"), LIMITS)).toBeNull();
  });

  it("sends every line, blanks as null and a code without marks", () => {
    expect(toEntries(LIMITS, { st1: text("70", "18"), st2: text("", ""), st3: { ...text("5", "5"), code: "EX" } })).toEqual([
      { studentId: "st1", theory: 70, practical: null, internal: 18, code: null },
      { studentId: "st2", theory: null, practical: null, internal: null, code: null },
      { studentId: "st3", theory: null, practical: null, internal: null, code: "EX" },
    ]);
  });

  it("lists who still needs marks, leaving out students who left the section", () => {
    const s = sheet();
    s.students[2] = { ...s.students[2], inSection: false };
    const missing = missingStudents(s, { st1: text("70", "18"), st2: text("60", ""), st3: text("", "") });
    expect(missing.map((row) => row.fullName)).toEqual(["Diya Menon"]);
  });

  it("needs a reason to unlock", () => {
    expect(reasonProblem("  ")).toBe("exams.v.reason");
    expect(reasonProblem("x".repeat(501))).toBe("exams.v.reasonLong");
    expect(reasonProblem("Wrong marks typed for Diya")).toBeNull();
  });
});

describe("MarksGridView", () => {
  it("saves a draft with the version it was opened at", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <MarksGridView paperId="p1" sectionId="s5a" />, ["TEACHER"]);
    const table = await screen.findByTestId("marks-table");
    expect(within(table).getAllByTestId("marks-row")).toHaveLength(3);
    expect(screen.getByTestId("marks-entered")).toHaveTextContent("1 of 3 entered");
    expect(screen.getByRole("button", { name: "Save draft" })).toBeDisabled();

    await user.type(screen.getByRole("textbox", { name: "Theory marks for Diya Menon, out of 80" }), "65");
    await user.type(screen.getByRole("textbox", { name: "Internal marks for Diya Menon, out of 20" }), "15.5");
    expect(screen.getByTestId("marks-entered")).toHaveTextContent("2 of 3 entered");
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save draft" }));

    await waitFor(() => expect(m.saveSheet).toHaveBeenCalledTimes(1));
    const [paperId, sectionId, body] = m.saveSheet.mock.calls[0];
    expect([paperId, sectionId, body.version]).toEqual(["p1", "s5a", 3]);
    expect(body.entries).toContainEqual({ studentId: "st2", theory: 65, practical: null, internal: 15.5, code: null });
    expect(body.entries).toContainEqual({ studentId: "st3", theory: null, practical: null, internal: null, code: null });
    expect(await screen.findByText("Marks saved: 2 of 3 entered")).toBeInTheDocument();
  });

  it("will not submit with a wrong mark or a student without marks", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <MarksGridView paperId="p1" sectionId="s5a" />, ["TEACHER"]);
    const theory = await screen.findByRole("textbox", { name: "Theory marks for Aarav Rao, out of 80" });
    await user.clear(theory);
    await user.type(theory, "85");
    expect(theory).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("At most 80.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Submit and lock" }));
    expect(screen.getByText("Some marks are not right. Fix the ones marked in red.")).toBeInTheDocument();

    await user.clear(theory);
    await user.type(theory, "75");
    await user.click(screen.getByRole("button", { name: "Submit and lock" }));
    expect(
      screen.getByText(/2 students have no marks yet \(Diya Menon, Kabir Singh\)/),
    ).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(m.saveSheet).not.toHaveBeenCalled();
    expect(m.submitSheet).not.toHaveBeenCalled();
  });

  it("marks a student absent, saves and locks with the new version", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <MarksGridView paperId="p1" sectionId="s5a" />, ["TEACHER"]);
    await user.type(await screen.findByRole("textbox", { name: "Theory marks for Diya Menon, out of 80" }), "65");
    await user.type(screen.getByRole("textbox", { name: "Internal marks for Diya Menon, out of 20" }), "15");
    const codes = screen.getByRole("group", { name: "Absent, medical or exempt for Kabir Singh" });
    await user.click(within(codes).getByRole("button", { name: "AB" }));
    expect(within(codes).getByRole("button", { name: "AB" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("textbox", { name: "Theory marks for Kabir Singh, out of 80" })).toHaveValue("");

    await user.click(screen.getByRole("button", { name: "Submit and lock" }));
    const dialog = await screen.findByRole("dialog", { name: "Submit and lock these marks?" });
    await user.click(within(dialog).getByRole("button", { name: "Submit and lock" }));

    await waitFor(() => expect(m.submitSheet).toHaveBeenCalledWith("p1", "s5a", 4));
    expect(m.saveSheet.mock.calls[0][2].entries).toContainEqual({
      studentId: "st3",
      theory: null,
      practical: null,
      internal: null,
      code: "AB",
    });
    expect(await screen.findByTestId("marks-locked")).toHaveTextContent("Submitted by Ravi Kumar");
    expect(screen.getByText("Marks submitted and locked")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save draft" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Theory marks for Diya Menon, out of 80" })).toBeDisabled();
  });

  it("explains when someone else saved first and reloads the sheet", async () => {
    const user = userEvent.setup();
    m.saveSheet.mockRejectedValue(
      new ApiError({
        status: 409,
        title: "Conflict",
        detail: "Someone else saved these marks after you opened them.",
        errors: { version: "stale" },
      }),
    );
    renderAs(TEACHER, <MarksGridView paperId="p1" sectionId="s5a" />, ["TEACHER"]);
    await user.type(await screen.findByRole("textbox", { name: "Theory marks for Kabir Singh, out of 80" }), "40");
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Someone else saved these marks after you opened them.");
    await user.click(within(alert).getByRole("button", { name: "Reload" }));
    await waitFor(() => expect(m.sheet).toHaveBeenCalledTimes(2));
  });

  it("lets the principal unlock locked marks with a reason", async () => {
    const user = userEvent.setup();
    m.sheet.mockResolvedValue(
      sheet({ status: "LOCKED", canEdit: false, canUnlock: true, submittedByName: "Ravi Kumar", submittedAt: "2026-09-21T05:00:00Z" }),
    );
    m.unlockSheet.mockResolvedValue(
      sheet({ version: 6, unlockedByName: "Lakshmi Iyer", unlockedAt: "2026-09-22T05:00:00Z", unlockReason: "Wrong marks typed", canEdit: false }),
    );
    renderAs(PRINCIPAL, <MarksGridView paperId="p1" sectionId="s5a" />, ["PRINCIPAL"]);
    const locked = await screen.findByTestId("marks-locked");
    expect(screen.getByRole("textbox", { name: "Theory marks for Aarav Rao, out of 80" })).toBeDisabled();
    await user.click(within(locked).getByRole("button", { name: "Unlock" }));
    const dialog = await screen.findByRole("dialog", { name: "Unlock these marks?" });
    await user.click(within(dialog).getByRole("button", { name: "Unlock" }));
    expect(within(dialog).getByText("Say why.")).toBeInTheDocument();
    expect(m.unlockSheet).not.toHaveBeenCalled();

    await user.type(within(dialog).getByRole("textbox", { name: "Reason" }), "Wrong marks typed");
    await user.click(within(dialog).getByRole("button", { name: "Unlock" }));
    await waitFor(() => expect(m.unlockSheet).toHaveBeenCalledWith("p1", "s5a", "Wrong marks typed"));
    expect(await screen.findByText(/Unlocked by Lakshmi Iyer .*: Wrong marks typed/)).toBeInTheDocument();
    expect(screen.queryByTestId("marks-locked")).not.toBeInTheDocument();
  });

  it("tells a teacher to ask when the marks are locked", async () => {
    m.sheet.mockResolvedValue(sheet({ status: "LOCKED", submittedByName: "Ravi Kumar", submittedAt: "2026-09-21T05:00:00Z" }));
    renderAs(TEACHER, <MarksGridView paperId="p1" sectionId="s5a" />, ["TEACHER"]);
    const locked = await screen.findByTestId("marks-locked");
    expect(locked).toHaveTextContent("Ask the Principal or School Admin");
    expect(within(locked).queryByRole("button", { name: "Unlock" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Submit and lock" })).not.toBeInTheDocument();
  });
});
