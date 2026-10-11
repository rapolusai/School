import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { lessonsPaths, lessonsQuery } from "@/lib/lessons-api";
import type { CoverageDetail, CoverageQuery } from "@/lib/types";
import { coverageReport, lessonsOptions } from "@/test/lessons-fixtures";
import { renderAs } from "@/test/render";
import { SyllabusCoverageReportView } from "./coverage-report";

const m = vi.hoisted(() => ({ options: vi.fn(), coverage: vi.fn(), coverageDetail: vi.fn(), downloadXlsx: vi.fn() }));

vi.mock("@/lib/lessons-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/lessons-api")>();
  return {
    ...actual,
    lessonsApi: { ...actual.lessonsApi, options: m.options, coverage: m.coverage, coverageDetail: m.coverageDetail },
  };
});

vi.mock("@/lib/reports-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/reports-api")>();
  return { ...actual, downloadXlsx: m.downloadXlsx };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/reports/syllabus-coverage",
}));

const PRINCIPAL = ["dashboard.view", "lessons.read", "lessons.approve"];

const DETAIL: CoverageDetail = {
  summary: coverageReport().rows[0],
  units: [
    {
      id: "u1",
      title: "Numbers",
      topics: [
        {
          id: "t1",
          title: "Place value",
          plannedPeriods: 4,
          targetMonth: "2026-07",
          taught: true,
          taughtOn: "2026-08-14",
          teacherName: "Ravi Kumar",
          overdue: false,
          plans: 1,
        },
        {
          id: "t2",
          title: "Fractions",
          plannedPeriods: 6,
          targetMonth: "2026-09",
          taught: false,
          taughtOn: null,
          teacherName: null,
          overdue: true,
          plans: 0,
        },
        {
          id: "t3",
          title: "Decimals",
          plannedPeriods: 6,
          targetMonth: "2026-12",
          taught: false,
          taughtOn: null,
          teacherName: null,
          overdue: false,
          plans: 0,
        },
      ],
    },
  ],
};

describe("coverage query", () => {
  it("sends only the filters that are set", () => {
    expect(lessonsQuery({ yearId: undefined, classId: "c5", sectionId: "", behindOnly: false })).toBe("?classId=c5");
    expect(lessonsQuery({})).toBe("");
    const query: CoverageQuery = { classId: "c5", subjectId: "math", behindOnly: true };
    expect(lessonsPaths.coverageCsv(query)).toBe("/api/lessons/coverage.csv?classId=c5&subjectId=math&behindOnly=true");
  });
});

describe("SyllabusCoverageReportView", () => {
  beforeEach(() => {
    for (const mock of Object.values(m)) mock.mockReset();
    m.options.mockResolvedValue(lessonsOptions({ wholeSchool: true, canApprove: true }));
    m.coverage.mockImplementation(async (query: CoverageQuery) =>
      coverageReport(
        query.behindOnly ? { rows: coverageReport().rows.filter((r) => r.behind), sections: 1, averagePercent: 25 } : {},
      ),
    );
    m.coverageDetail.mockResolvedValue(DETAIL);
  });

  it("shows each section's coverage against what is expected by now, flagging the ones behind", async () => {
    renderAs(PRINCIPAL, <SyllabusCoverageReportView />, ["PRINCIPAL"]);
    const table = await screen.findByTestId("coverage-table");
    // The page heading and the print header's.
    expect(screen.getAllByRole("heading", { level: 1, name: "Syllabus coverage" })).toHaveLength(2);
    expect(screen.getByTestId("coverage-average")).toHaveTextContent("37.5%");
    expect(screen.getByTestId("coverage-behind")).toHaveTextContent("1");

    const rows = within(table).getAllByTestId("coverage-row");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveAttribute("data-behind", "true");
    expect(within(rows[0]).getByRole("rowheader")).toHaveTextContent("Class 5 A");
    expect(rows[0]).toHaveTextContent("Ravi Kumar");
    expect(rows[0]).toHaveTextContent("1 / 4");
    expect(rows[0]).toHaveTextContent("25%");
    expect(rows[0]).toHaveTextContent("75%");
    expect(within(rows[0]).getByText("2 topics overdue")).toBeInTheDocument();
    expect(rows[1]).not.toHaveAttribute("data-behind");
    expect(within(rows[1]).queryByText(/overdue/)).not.toBeInTheDocument();

    // The phone cards carry the same rows with a short behind flag.
    const cards = screen.getByTestId("coverage-cards");
    expect(within(cards).getAllByRole("listitem")).toHaveLength(2);
    expect(within(cards).getAllByText("Behind")).toHaveLength(1);
    expect(within(cards).getByText("1 of 4 topics · expected 75% · Ravi Kumar")).toBeInTheDocument();
  });

  it("filters by class, section, subject and behind schedule, and downloads the CSV for the filters", async () => {
    m.downloadXlsx.mockResolvedValue("syllabus-coverage.csv");
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <SyllabusCoverageReportView />, ["PRINCIPAL"]);
    await screen.findByTestId("coverage-table");
    expect(m.coverage).toHaveBeenLastCalledWith({ behindOnly: false });
    expect(screen.getByLabelText("Section")).toBeDisabled();

    await user.selectOptions(await screen.findByLabelText("Class"), "c5");
    await user.selectOptions(screen.getByLabelText("Section"), "s5a");
    await user.selectOptions(screen.getByLabelText("Subject"), "math");
    await user.click(screen.getByRole("checkbox", { name: "Behind schedule only" }));
    await waitFor(() =>
      expect(m.coverage).toHaveBeenLastCalledWith({ classId: "c5", sectionId: "s5a", subjectId: "math", behindOnly: true }),
    );
    await waitFor(() => expect(screen.getAllByTestId("coverage-row")).toHaveLength(1));
    expect(screen.getByTestId("report-print-head")).toHaveTextContent(
      "2026-27 · Class 5 · Class 5 A · Mathematics · Behind schedule only",
    );

    await user.click(screen.getByRole("button", { name: "CSV" }));
    expect(m.downloadXlsx).toHaveBeenCalledWith(
      "/api/lessons/coverage.csv?classId=c5&subjectId=math&sectionId=s5a&behindOnly=true",
      "syllabus-coverage.csv",
    );
  });

  it("opens a section's topics: taught, overdue and still to come", async () => {
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <SyllabusCoverageReportView />, ["PRINCIPAL"]);
    await screen.findByTestId("coverage-table");
    const toggle = screen.getByRole("button", { name: "Topics of Class 5 A Mathematics" });
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(m.coverageDetail).toHaveBeenCalledWith("s5a", "math", "y1");

    const topics = (await screen.findAllByTestId("coverage-topics"))[0];
    expect(within(topics).getByText("Numbers")).toBeInTheDocument();
    expect(within(topics).getByLabelText("Taught")).toBeInTheDocument();
    expect(within(topics).getByText("Taught on 14 Aug 2026")).toBeInTheDocument();
    expect(within(topics).getByText("Fractions")).toHaveClass("text-warn");
    expect(within(topics).getByText("Due by Sept 2026")).toBeInTheDocument();
    expect(within(topics).getByText("By Dec 2026")).toBeInTheDocument();
  });

  it("says when nothing is behind or there is no syllabus yet", async () => {
    const user = userEvent.setup();
    m.coverage.mockResolvedValue(coverageReport({ rows: [], sections: 0, behind: 0, averagePercent: null }));
    renderAs(PRINCIPAL, <SyllabusCoverageReportView />, ["PRINCIPAL"]);
    expect(await screen.findByText("No syllabus coverage to show. Add syllabi and mark lessons as taught.")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Behind schedule only" }));
    expect(await screen.findByText("No section is behind schedule.")).toBeInTheDocument();
  });

  it("asks for an academic year first", async () => {
    m.coverage.mockResolvedValue(coverageReport({ yearId: null, yearName: null, rows: [] }));
    renderAs(PRINCIPAL, <SyllabusCoverageReportView />, ["PRINCIPAL"]);
    expect(await screen.findByText("Set up the current academic year first.")).toBeInTheDocument();
  });
});
