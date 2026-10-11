import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ExamClassDetail, MarkSheetRow, MarksOverview } from "@/lib/types";
import { examPaper } from "@/test/exams-fixtures";
import { renderAs } from "@/test/render";
import { ExamClassView } from "./exam-class-view";

const m = vi.hoisted(() => ({
  examClass: vi.fn(),
  marks: vi.fn(),
  publishResults: vi.fn(),
  publishDateSheet: vi.fn(),
  listClasses: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listClasses: m.listClasses } };
});
vi.mock("@/lib/exams-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/exams-api")>();
  return {
    ...actual,
    examsApi: {
      ...actual.examsApi,
      examClass: m.examClass,
      marks: m.marks,
      publishResults: m.publishResults,
      publishDateSheet: m.publishDateSheet,
    },
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/exams/e1/c5",
}));

const PRINCIPAL = ["dashboard.view", "exams.read", "exams.setup", "exams.publish"];

function detail(overrides: Partial<ExamClassDetail> = {}): ExamClassDetail {
  return {
    examId: "e1",
    examName: "Half-yearly",
    termId: "t1",
    termName: "Term 1",
    academicYearId: "y1",
    academicYearName: "2026-27",
    examClassId: "ec1",
    classId: "c5",
    className: "Class 5",
    instructions: null,
    papers: [examPaper(), examPaper({ id: "p2", subjectId: "sub2", subjectName: "Mathematics", date: "2026-12-08" })],
    clashes: [],
    classSubjects: [
      { id: "sub1", name: "English", code: null },
      { id: "sub2", name: "Mathematics", code: null },
    ],
    dateSheetPublishedAt: null,
    dateSheetPublishedBy: null,
    resultsPublishedAt: null,
    resultsPublishedBy: null,
    canSetup: true,
    canPublish: true,
    ...overrides,
  };
}

function sheet(paperId: string, status: MarkSheetRow["status"]): MarkSheetRow {
  return {
    examClassId: "ec1",
    classId: "c5",
    className: "Class 5",
    paperId,
    subjectId: paperId === "p1" ? "sub1" : "sub2",
    subjectName: paperId === "p1" ? "English" : "Mathematics",
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    paperDate: "2026-12-07",
    status,
    students: 3,
    entered: status === "NOT_STARTED" ? 0 : 3,
    canEdit: true,
    updatedByName: "Ravi Kumar",
    updatedAt: "2026-12-09T05:00:00Z",
    resultsPublished: false,
  };
}

function overview(sheets: MarkSheetRow[]): MarksOverview {
  const year = { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31", current: true };
  return {
    year,
    years: [year],
    wholeSchool: true,
    exams: [
      {
        examId: "e1",
        examName: "Half-yearly",
        termName: "Term 1",
        total: sheets.length,
        locked: sheets.filter((s) => s.status === "LOCKED").length,
        draft: sheets.filter((s) => s.status === "DRAFT").length,
        notStarted: sheets.filter((s) => s.status === "NOT_STARTED").length,
        sheets,
      },
    ],
  };
}

beforeEach(() => {
  m.examClass.mockResolvedValue(detail());
  m.listClasses.mockResolvedValue([{ id: "c5", name: "Class 5", sections: [{ id: "s5a", name: "A" }] }]);
  m.marks.mockResolvedValue(overview([sheet("p1", "LOCKED"), sheet("p2", "DRAFT")]));
  m.publishResults.mockResolvedValue(
    detail({ resultsPublishedAt: "2026-12-12T05:00:00Z", resultsPublishedBy: "Lakshmi Iyer" }),
  );
});

describe("ExamClassView", () => {
  it("keeps publishing closed while a mark sheet is not locked", async () => {
    renderAs(PRINCIPAL, <ExamClassView examId="e1" classId="c5" />, ["PRINCIPAL"]);
    expect(await screen.findByText(/^1 of 2 mark sheets are not locked yet\./)).toHaveAttribute("data-testid", "results-pending");
    expect(screen.getByRole("button", { name: "Publish results" })).toBeDisabled();
    const sections = screen.getByTestId("exam-sections");
    expect(sections).toHaveTextContent("Class 5 A1 of 2 locked");
    expect(within(sections).getByRole("link", { name: /Report cards/ })).toHaveAttribute(
      "href",
      "/app/exams/report-cards?section=s5a&term=t1",
    );
    expect(screen.getByTestId("date-sheet-status")).toHaveTextContent("Not published yet: 2 of 2 papers have a date.");
  });

  it("publishes the results once every sheet is locked", async () => {
    const user = userEvent.setup();
    m.marks.mockResolvedValue(overview([sheet("p1", "LOCKED"), sheet("p2", "LOCKED")]));
    renderAs(PRINCIPAL, <ExamClassView examId="e1" classId="c5" />, ["PRINCIPAL"]);
    expect(await screen.findByText(/^Every mark sheet is locked\./)).toHaveAttribute("data-testid", "results-pending");
    await user.click(screen.getByRole("button", { name: "Publish results" }));
    const dialog = await screen.findByRole("dialog", { name: "Publish the results of Half-yearly for Class 5?" });
    expect(dialog).toHaveTextContent("parents get a message");
    await user.click(within(dialog).getByRole("button", { name: "Publish results" }));
    await waitFor(() => expect(m.publishResults).toHaveBeenCalledWith("e1", "c5"));
    expect(await screen.findByText("Results published. Parents will get a message.")).toBeInTheDocument();
    const status = screen.getByTestId("results-status");
    expect(status).toHaveTextContent("Results published");
    expect(status).toHaveTextContent("by Lakshmi Iyer");
    expect(within(status).getByRole("button", { name: "Withdraw results" })).toBeInTheDocument();
  });

  it("shows readers the state without the buttons", async () => {
    m.examClass.mockResolvedValue(detail({ canSetup: false, canPublish: false }));
    renderAs(["dashboard.view", "exams.read"], <ExamClassView examId="e1" classId="c5" />, ["TEACHER"]);
    await screen.findByTestId("results-pending");
    expect(screen.queryByRole("button", { name: "Publish results" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Publish date sheet" })).not.toBeInTheDocument();
  });
});
