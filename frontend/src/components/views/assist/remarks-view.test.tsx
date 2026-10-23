import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AssistSettings, RemarkDraft, RemarkSheet } from "@/lib/types";
import { renderAs } from "@/test/render";
import { batches, RemarksView, studentsToDraft } from "./remarks-view";

const m = vi.hoisted(() => ({
  settings: vi.fn(),
  remarks: vi.fn(),
  draftRemarks: vi.fn(),
  acceptRemark: vi.fn(),
  editRemark: vi.fn(),
  structure: vi.fn(),
  listClasses: vi.fn(),
}));

vi.mock("@/lib/assist-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/assist-api")>();
  return {
    ...actual,
    assistApi: { ...actual.assistApi, settings: m.settings, remarks: m.remarks, draftRemarks: m.draftRemarks, acceptRemark: m.acceptRemark, editRemark: m.editRemark },
  };
});

vi.mock("@/lib/exams-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/exams-api")>();
  return { ...actual, examsApi: { ...actual.examsApi, structure: m.structure } };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listClasses: m.listClasses } };
});

vi.mock("next/navigation", () => ({ usePathname: () => "/app/assist", useRouter: () => ({ push: vi.fn() }) }));

const TEACHER = ["dashboard.view", "assist.use", "exams.manage", "academics.read"];

const SETTINGS: AssistSettings = {
  enabled: true,
  remarksEnabled: true,
  earlyWarningEnabled: true,
  papersEnabled: true,
  writingEnabled: true,
  monthlyLimit: 500,
  language: "en",
  weights: { attendance30: 25, attendance90: 15, marksTrend: 25, feeDues: 10, homework: 25, threshold: 30 },
  saved: true,
  updatedAt: "2026-10-01T04:00:00Z",
  provider: "simulated",
  simulated: true,
  usedThisMonth: 3,
  lastRunAt: null,
  lastRunStudents: null,
  lastRunListed: null,
};

function draft(id: string, studentId: string, name: string, status: RemarkDraft["status"] = "DRAFT"): RemarkDraft {
  return {
    id,
    studentId,
    studentName: name,
    subjectId: null,
    subjectName: null,
    tone: "ENCOURAGING",
    language: "en",
    provider: "simulated",
    simulated: true,
    draftText: `${name.split(" ")[0]} has done very well in Class 5 A this term.`,
    acceptedText: status === "ACCEPTED" ? "Accepted text." : null,
    status,
    inputs: { first: name.split(" ")[0], classLabel: "Class 5 A", tone: "ENCOURAGING", percent: "82.5", grade: "A2", attendance: "95", best: "Mathematics", weak: "English" },
    inputLines: ["Mathematics: 90% (A2)", "English: 75% (B1)"],
    createdByName: "Ravi Kumar",
    acceptedByName: null,
    acceptedAt: null,
    updatedAt: null,
    version: 0,
  };
}

function sheet(students: number, drafted = false): RemarkSheet {
  return {
    termId: "term1",
    termName: "Term 1",
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    canAccept: true,
    language: "en",
    subjects: [],
    students: Array.from({ length: students }, (_, i) => ({
      studentId: `st${i + 1}`,
      fullName: `Student${i + 1} Rao`,
      rollNo: i + 1,
      currentRemark: null,
      draft: drafted ? draft(`d${i + 1}`, `st${i + 1}`, `Student${i + 1} Rao`) : null,
      subjectDrafts: [],
    })),
  };
}

async function pick() {
  const user = userEvent.setup();
  await user.selectOptions(await screen.findByLabelText("Term"), "term1");
  await user.selectOptions(screen.getByLabelText("Class and section"), "s5a");
  return user;
}

describe("batches and studentsToDraft", () => {
  it("splits students into batches of five and skips accepted remarks", () => {
    expect(batches([1, 2, 3, 4, 5, 6, 7])).toEqual([[1, 2, 3, 4, 5], [6, 7]]);
    const s = sheet(3, true);
    s.students[1].draft = draft("d2", "st2", "Student2 Rao", "ACCEPTED");
    expect(studentsToDraft(s)).toEqual(["st1", "st3"]);
  });
});

describe("RemarksView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    m.settings.mockResolvedValue(SETTINGS);
    m.structure.mockResolvedValue({ year: null, years: [], terms: [{ id: "term1", name: "Term 1", startsOn: "2026-06-01", endsOn: "2026-12-31", weight: null, examWeightTotal: null, exams: [] }], termWeightTotal: null, classes: [], canSetup: false, canPublish: false });
    m.listClasses.mockResolvedValue([{ id: "c5", name: "Class 5", displayOrder: 5, subjects: [], sections: [{ id: "s5a", classId: "c5", className: "Class 5", name: "A", capacity: 40, classTeacher: null, studentCount: 7 }] }]);
  });

  it("asks for a term and section first and labels drafts as simulated", async () => {
    renderAs(TEACHER, <RemarksView />);
    expect(await screen.findByText("Choose a term and a section to see its students.")).toBeInTheDocument();
    expect(screen.getAllByTestId("simulated-label")[0]).toHaveTextContent("Simulated draft");
  });

  it("drafts the whole section in batches with progress", async () => {
    m.remarks.mockResolvedValue(sheet(7));
    let release: () => void = () => {};
    m.draftRemarks.mockImplementationOnce(() => new Promise((resolve) => (release = () => resolve([])))).mockResolvedValue([]);
    renderAs(TEACHER, <RemarksView />);
    const user = await pick();
    expect(await screen.findAllByTestId("remark-row")).toHaveLength(7);
    await user.click(screen.getByTestId("assist-draft-all"));
    expect(await screen.findByTestId("assist-progress")).toHaveTextContent("Drafted 0 of 7");
    release();
    await waitFor(() => expect(m.draftRemarks).toHaveBeenCalledTimes(2));
    expect(m.draftRemarks.mock.calls[0][0]).toEqual({ termId: "term1", sectionId: "s5a", tone: "ENCOURAGING", studentIds: ["st1", "st2", "st3", "st4", "st5"] });
    expect(m.draftRemarks.mock.calls[1][0].studentIds).toEqual(["st6", "st7"]);
    await waitFor(() => expect(screen.queryByTestId("assist-progress")).not.toBeInTheDocument());
  });

  it("shows what was used, and accepts the edited text", async () => {
    m.remarks.mockResolvedValue(sheet(1, true));
    m.acceptRemark.mockResolvedValue(draft("d1", "st1", "Student1 Rao", "ACCEPTED"));
    renderAs(TEACHER, <RemarksView />);
    const user = await pick();
    const row = await screen.findByTestId("remark-row");
    const used = within(row).getByTestId("used-inputs");
    expect(used).toHaveTextContent("First name");
    expect(used).toHaveTextContent("Mathematics: 90% (A2)");
    expect(used).toHaveTextContent("Never used: surname, Aadhaar, phone numbers");
    const box = within(row).getByLabelText("Remark for Student1 Rao");
    await user.clear(box);
    await user.type(box, "Works hard.");
    await user.click(within(row).getByRole("button", { name: "Accept" }));
    await waitFor(() => expect(m.acceptRemark).toHaveBeenCalledWith("d1", "Works hard."));
  });

  it("marks the remark box when the saved text is too long", async () => {
    const { ApiError } = await import("@/lib/api");
    m.remarks.mockResolvedValue(sheet(1, true));
    m.editRemark.mockRejectedValue(
      new ApiError({
        status: 400,
        title: "Check the form",
        detail: "Some fields need attention.",
        errors: { text: "size must be between 0 and 500" },
      }),
    );
    renderAs(TEACHER, <RemarksView />);
    const user = await pick();
    const row = await screen.findByTestId("remark-row");
    const box = within(row).getByLabelText("Remark for Student1 Rao");
    await user.type(box, " More.");
    await user.click(within(row).getByRole("button", { name: "Save edit" }));
    expect(await within(row).findByText("Use at most 500 characters.")).toBeInTheDocument();
    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(within(row).queryByText("Some fields need attention.")).not.toBeInTheDocument();
  });

  it("explains the monthly limit in plain words", async () => {
    const { ApiError } = await import("@/lib/api");
    m.remarks.mockResolvedValue(sheet(2));
    m.draftRemarks.mockRejectedValue(new ApiError({ status: 409, title: "Limit reached", detail: "x", type: "urn:akshara:assist:limit-reached" }));
    renderAs(TEACHER, <RemarksView />);
    const user = await pick();
    await screen.findAllByTestId("remark-row");
    await user.click(screen.getByTestId("assist-draft-all"));
    expect(await screen.findByText(/This month's draft limit has been reached/)).toBeInTheDocument();
  });

  it("shows when assistance is switched off", async () => {
    m.settings.mockResolvedValue({ ...SETTINGS, enabled: false });
    renderAs(TEACHER, <RemarksView />);
    expect(await screen.findByTestId("assist-off")).toHaveTextContent("AI assistance is switched off for this school.");
  });
});
