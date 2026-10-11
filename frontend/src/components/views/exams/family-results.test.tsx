import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { Child } from "@/lib/types";
import { familyResults, reportCard, reportCards } from "@/test/exams-fixtures";
import { renderAs } from "@/test/render";
import { defaultPeriod, FamilyResultsView } from "./family-results";
import { cellText, ReportCardPages, scaleText } from "./report-card";

const m = vi.hoisted(() => ({
  myChildren: vi.fn(),
  childResults: vi.fn(),
  childCard: vi.fn(),
  myResults: vi.fn(),
  myCard: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren: m.myChildren } };
});
vi.mock("@/lib/exams-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/exams-api")>();
  return {
    ...actual,
    examsApi: {
      ...actual.examsApi,
      childResults: m.childResults,
      childCard: m.childCard,
      myResults: m.myResults,
      myCard: m.myCard,
    },
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/family/results",
}));

const PARENT = ["dashboard.view", "child.view"];
const STUDENT = ["dashboard.view"];

const ARJUN: Child = {
  id: "st1",
  fullName: "Arjun Sharma",
  admissionNo: "AKS/2025/036",
  status: "ACTIVE",
  className: "Class 5",
  sectionName: "A",
  rollNo: 2,
  classTeacherName: "Ravi Kumar",
  academicYearName: "2026-27",
};
const DIYA: Child = { ...ARJUN, id: "st2", fullName: "Diya Sharma", className: "Class 2", rollNo: 4 };

beforeEach(() => {
  window.localStorage.clear();
  m.myChildren.mockResolvedValue([ARJUN, DIYA]);
  m.childResults.mockImplementation((id: string) =>
    Promise.resolve(id === "st1" ? familyResults("st1") : familyResults("st2", { results: [], terms: [], upcoming: [] })),
  );
  m.childCard.mockResolvedValue(reportCards());
  m.myResults.mockResolvedValue(familyResults("st1"));
  m.myCard.mockResolvedValue(reportCards());
});

afterEach(() => {
  window.localStorage.clear();
});

describe("report card helpers", () => {
  it("shows marks, a code or a percentage in a cell", () => {
    expect(cellText({ columnId: "e1", obtained: 37.5, max: 40, percent: 93.75, grade: "A1", code: null })).toBe("37.5/40");
    expect(cellText({ columnId: "e1", obtained: null, max: 40, percent: null, grade: null, code: "AB" })).toBe("AB");
    expect(cellText({ columnId: "t1", obtained: null, max: null, percent: 88.25, grade: "A2", code: null })).toBe("88.25%");
    expect(cellText(undefined)).toBe("—");
  });

  it("lists the grading scale best first", () => {
    expect(scaleText(reportCards().scheme.bands)).toBe("A1 91%+ · A2 81%+ · D 33%+ · E 0%+");
  });

  it("opens the latest term with published results", () => {
    expect(defaultPeriod(familyResults())).toEqual({ termId: "t1" });
    const both = familyResults("st1", {
      terms: [
        { id: "t1", name: "Term 1", published: true },
        { id: "t2", name: "Term 2", published: true },
      ],
    });
    expect(defaultPeriod(both)).toEqual({ termId: "t2" });
    expect(defaultPeriod(familyResults("st1", { terms: [{ id: "t1", name: "Term 1", published: false }] }))).toBeNull();
  });
});

describe("ReportCardPages", () => {
  it("prints the scholastic table, result, attendance, co-scholastic areas and the holistic progress card", () => {
    renderAs(["exams.read"], <ReportCardPages cards={reportCards()} />);
    const card = screen.getByRole("article", { name: "Report card of Arjun Sharma" });
    expect(within(card).getByText("Report card: Term 1")).toBeInTheDocument();
    expect(within(card).getByRole("columnheader", { name: "Periodic Test 1 (20%)" })).toBeInTheDocument();
    const subjects = within(card).getByTestId("card-subjects");
    expect(within(subjects).getByRole("row", { name: /English/ })).toHaveTextContent("37/40");
    expect(within(subjects).getByRole("row", { name: /Mathematics/ })).toHaveTextContent("ML");
    expect(within(card).getByTestId("card-percent")).toHaveTextContent("92.5%");
    expect(card).toHaveTextContent("18 of 19 days (94.7%)");
    expect(card).toHaveTextContent("Grade point average10");
    expect(within(card).queryByText("Rank in section")).not.toBeInTheDocument();
    expect(within(card).getByTestId("card-co-scholastic")).toHaveTextContent("Art educationB");
    const hpc = within(card).getByTestId("card-hpc");
    expect(hpc).toHaveTextContent("Art and creativityProgressing");
    expect(hpc).toHaveTextContent("Parent's feedback");
    expect(hpc).not.toHaveTextContent("Child's own reflection");
    expect(card).toHaveTextContent("CBSE classes 1 to 8: A1 91%+ · A2 81%+ · D 33%+ · E 0%+. Pass mark 33% in each subject.");
  });

  it("says when nothing is published for the period and leaves out what the school turned off", () => {
    renderAs(
      ["dashboard.view"],
      <ReportCardPages
        cards={reportCards({
          columns: [],
          hpcEnabled: false,
          kind: "YEAR",
          termId: null,
          termName: null,
          cards: [reportCard({ subjects: [], coScholastic: null, remark: null })],
        })}
      />,
    );
    expect(screen.getByText("Annual report card")).toBeInTheDocument();
    expect(screen.getByText("No results have been published for this period yet.")).toBeInTheDocument();
    expect(screen.queryByTestId("card-hpc")).not.toBeInTheDocument();
    expect(screen.queryByTestId("card-co-scholastic")).not.toBeInTheDocument();
  });
});

describe("FamilyResultsView for a parent", () => {
  it("shows the published result, coming papers and the latest term's report card", async () => {
    renderAs(PARENT, <FamilyResultsView />, ["PARENT"]);
    const published = await screen.findByTestId("family-results");
    expect(within(published).getByText("Periodic Test 1")).toBeInTheDocument();
    expect(published).toHaveTextContent("Term 1 · 89.5% · A2");
    expect(within(published).getByText("Pass")).toBeInTheDocument();
    const upcoming = screen.getByTestId("family-date-sheet");
    expect(upcoming).toHaveTextContent("Half-Yearly Examination");
    expect(upcoming).toHaveTextContent("7 Dec 2026 · 09:00–11:30 · Room 12");
    expect(await screen.findByRole("article", { name: "Report card of Arjun Sharma" })).toBeInTheDocument();
    expect(m.childResults).toHaveBeenCalledWith("st1");
    expect(m.childCard).toHaveBeenCalledWith("st1", { termId: "t1" });
    // Only terms with published results can be chosen, besides the whole year.
    const period = screen.getByRole("combobox", { name: "Report card for" });
    expect(within(period).getAllByRole("option").map((o) => o.textContent)).toEqual(["Term 1", "The whole year"]);
  });

  it("opens the whole year's card", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyResultsView />, ["PARENT"]);
    const period = await screen.findByRole("combobox", { name: "Report card for" });
    m.childCard.mockResolvedValue(reportCards({ kind: "YEAR", termId: null, termName: null }));
    await user.selectOptions(period, "year");
    await waitFor(() => expect(m.childCard).toHaveBeenCalledWith("st1", { yearId: "y1" }));
    expect(await screen.findByText("Annual report card")).toBeInTheDocument();
  });

  it("switches child, and shows no card until something is published", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyResultsView />, ["PARENT"]);
    await screen.findByTestId("family-results");
    await user.click(screen.getByRole("radio", { name: "Diya Sharma" }));
    await waitFor(() => expect(m.childResults).toHaveBeenCalledWith("st2"));
    expect(await screen.findByText("No results have been published yet.")).toBeInTheDocument();
    expect(screen.getByText("No date sheet has been published for coming exams.")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Report card for" })).not.toBeInTheDocument();
    expect(m.childCard).not.toHaveBeenCalledWith("st2", expect.anything());
  });

  it("explains a child who is not linked", async () => {
    m.childResults.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(PARENT, <FamilyResultsView initialChildId="st1" />, ["PARENT"]);
    expect(await screen.findByText(/not linked/i)).toBeInTheDocument();
  });
});

describe("FamilyResultsView for a student", () => {
  it("shows their own results and card", async () => {
    renderAs(STUDENT, <FamilyResultsView />, ["STUDENT"]);
    expect(await screen.findByRole("article", { name: "Report card of Arjun Sharma" })).toBeInTheDocument();
    expect(m.myCard).toHaveBeenCalledWith({ termId: "t1" });
    expect(screen.queryByTestId("child-switcher")).not.toBeInTheDocument();
    expect(m.myChildren).not.toHaveBeenCalled();
  });
});
