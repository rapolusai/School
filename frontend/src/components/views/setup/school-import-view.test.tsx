import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { problemCount, workbookProblem, type SchoolImportResult, type SchoolImportSheet } from "@/lib/school-import-api";
import { ADMIN_PERMISSIONS, renderAs } from "@/test/render";
import { canImportSchool, SchoolImportCard, SchoolImportView } from "./school-import-view";

const { upload, downloadTemplate, downloadSample } = vi.hoisted(() => ({
  upload: vi.fn(),
  downloadTemplate: vi.fn(),
  downloadSample: vi.fn(),
}));

vi.mock("@/lib/school-import-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/school-import-api")>();
  return { ...actual, schoolImportApi: { upload, downloadTemplate, downloadSample } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/setup/import",
}));

const XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const IMPORTER = ["dashboard.view", "students.manage", "staff.manage"];

function sheet(overrides: Partial<SchoolImportSheet>): SchoolImportSheet {
  return {
    name: "Class 5 - A",
    kind: "STUDENTS",
    className: "Class 5",
    sectionName: "A",
    section: "EXISTS",
    rows: 2,
    validRows: 2,
    invalidRows: 0,
    problems: [],
    problemsNotShown: 0,
    ignoredColumns: [],
    ...overrides,
  };
}

function result(overrides: Partial<SchoolImportResult>): SchoolImportResult {
  return {
    dryRun: true,
    committed: false,
    createMissing: false,
    ready: true,
    studentRows: 2,
    staffRows: 1,
    validRows: 3,
    invalidRows: 0,
    sheetProblems: 0,
    studentsCreated: 0,
    staffCreated: 0,
    toCreate: { classes: [], sections: [], departments: [] },
    sheets: [
      sheet({ name: "Staff", kind: "STAFF", className: null, sectionName: null, section: null, rows: 1, validRows: 1 }),
      sheet({}),
    ],
    ...overrides,
  };
}

function workbook(name = "school.xlsx", size = 100) {
  return new File([new Uint8Array(size)], name, { type: XLSX });
}

async function pick(user: ReturnType<typeof userEvent.setup>, file: File) {
  await user.upload(screen.getByLabelText("Excel file (.xlsx)"), file);
}

beforeEach(() => {
  upload.mockReset();
  downloadTemplate.mockReset();
  downloadSample.mockReset();
});

describe("school import helpers", () => {
  it("judges files by name and size", () => {
    expect(workbookProblem({ name: "school.xlsx", size: 10 })).toBeNull();
    expect(workbookProblem({ name: "SCHOOL.XLSX", size: 10 })).toBeNull();
    expect(workbookProblem({ name: "school.xls", size: 10 })).toBe("type");
    expect(workbookProblem({ name: "school.csv", size: 10 })).toBe("type");
    expect(workbookProblem({ name: "school.xlsx", size: 0 })).toBe("empty");
    expect(workbookProblem({ name: "school.xlsx", size: 5 * 1024 * 1024 + 1 })).toBe("size");
    expect(problemCount(result({ invalidRows: 3, sheetProblems: 2 }))).toBe(5);
  });

  it("needs both students.manage and staff.manage", () => {
    const me = (permissions: string[]) => ({
      id: "u", name: "n", email: "e", roles: [], permissions, platformAdmin: false, tenant: null,
    });
    expect(canImportSchool(me(IMPORTER))).toBe(true);
    expect(canImportSchool(me(["students.manage"]))).toBe(false);
    expect(canImportSchool(me(["staff.manage"]))).toBe(false);
  });
});

describe("SchoolImportCard", () => {
  it("offers the template, the sample and the upload page", async () => {
    const user = userEvent.setup();
    downloadTemplate.mockResolvedValue("school-import-template.xlsx");
    downloadSample.mockRejectedValue(new ApiError({ status: 403, title: "Forbidden" }));
    renderAs(ADMIN_PERMISSIONS, <SchoolImportCard />);
    const card = screen.getByTestId("school-import-card");
    expect(within(card).getByRole("heading", { name: "Bring your existing students and staff" })).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: "Upload file" })).toHaveAttribute("href", "/app/setup/import");

    await user.click(within(card).getByRole("button", { name: "Download template" }));
    expect(downloadTemplate).toHaveBeenCalledTimes(1);
    await user.click(within(card).getByRole("button", { name: "Download sample" }));
    expect(downloadSample).toHaveBeenCalledTimes(1);
    expect(await within(card).findByRole("alert")).toBeInTheDocument();
  });
});

describe("SchoolImportView", () => {
  it("explains who may import when a permission is missing", () => {
    renderAs(["students.manage"], <SchoolImportView />);
    expect(screen.getByText(/needs permission to manage both/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Excel file (.xlsx)")).not.toBeInTheDocument();
  });

  it("asks for a file and refuses the wrong kind without calling the API", async () => {
    const user = userEvent.setup({ applyAccept: false });
    renderAs(IMPORTER, <SchoolImportView />);
    await user.click(screen.getByRole("button", { name: "Check file" }));
    expect(screen.getByText("Choose an Excel (.xlsx) file.")).toBeInTheDocument();

    await pick(user, new File(["a,b"], "students.csv", { type: "text/csv" }));
    expect(screen.getByText(/Save other formats as Excel Workbook first/)).toBeInTheDocument();
    await pick(user, workbook("big.xlsx", 5 * 1024 * 1024 + 1));
    expect(screen.getByText("The file is larger than 5 MB.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Check file" }));
    expect(upload).not.toHaveBeenCalled();
    // Only School setup managers may let the import add classes and sections.
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("lists every problem by sheet and row and offers no import", async () => {
    const user = userEvent.setup();
    upload.mockResolvedValue(
      result({
        ready: false,
        validRows: 1,
        invalidRows: 2,
        sheetProblems: 1,
        sheets: [
          sheet({
            name: "Staff",
            kind: "STAFF",
            className: null,
            sectionName: null,
            section: null,
            rows: 1,
            validRows: 0,
            invalidRows: 1,
            problems: [{ row: 2, column: "Email", message: "Someone in this school already uses a@b.test." }],
          }),
          sheet({
            validRows: 1,
            invalidRows: 1,
            problems: [{ row: 3, column: "Guardian mobile", message: "Enter a 10-digit Indian mobile number." }],
            problemsNotShown: 4,
            ignoredColumns: ["Aadhaar"],
          }),
          sheet({
            name: "Class 9 - C",
            className: "Class 9",
            sectionName: "C",
            section: "MISSING",
            rows: 0,
            validRows: 0,
            problems: [{ row: null, column: null, message: "Class 9 - C is not set up in School setup." }],
          }),
        ],
      }),
    );
    renderAs(IMPORTER, <SchoolImportView />);
    await pick(user, workbook());
    await user.click(screen.getByRole("button", { name: "Check file" }));

    expect(upload).toHaveBeenCalledWith(expect.any(File), true, false);
    const report = await screen.findByTestId("school-import-report");
    expect(within(report).getByRole("status")).toHaveTextContent("3 problems need fixing");
    const table = within(report).getByTestId("school-import-sheets");
    expect(within(table).getByText("Not set up")).toBeInTheDocument();
    expect(within(table).getAllByRole("row")).toHaveLength(4);
    expect(within(report).getByText("Row 2 · Email")).toBeInTheDocument();
    expect(within(report).getByText("Row 3 · Guardian mobile")).toBeInTheDocument();
    expect(within(report).getByText("Whole sheet")).toBeInTheDocument();
    expect(within(report).getByText("…and 4 more problems in this sheet.")).toBeInTheDocument();
    expect(within(report).getByText("These columns are not used and were ignored: Aadhaar")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Import \d/ })).not.toBeInTheDocument();
  });

  it("imports after a clean check, creating missing sections when asked", async () => {
    const user = userEvent.setup();
    upload
      .mockResolvedValueOnce(
        result({
          createMissing: true,
          toCreate: { classes: ["Class 9"], sections: ["Class 9 - A"], departments: ["Science"] },
        }),
      )
      .mockResolvedValueOnce(result({ dryRun: false, committed: true, studentsCreated: 2, staffCreated: 1 }));
    renderAs([...IMPORTER, "settings.manage"], <SchoolImportView />);
    await pick(user, workbook());
    await user.click(screen.getByRole("checkbox", { name: /Add classes, sections and departments/ }));
    await user.click(screen.getByRole("button", { name: "Check file" }));
    expect(upload).toHaveBeenLastCalledWith(expect.any(File), true, true);
    expect(await screen.findByText("Everything is ready to import: 2 students and 1 staff.")).toBeInTheDocument();
    const toCreate = screen.getByTestId("school-import-to-create");
    expect(toCreate).toHaveTextContent("Classes: Class 9");
    expect(toCreate).toHaveTextContent("Departments: Science");

    await user.click(screen.getByRole("button", { name: "Import 2 students and 1 staff" }));
    expect(upload).toHaveBeenLastCalledWith(expect.any(File), false, true);
    const done = await screen.findByTestId("school-import-done");
    expect(within(done).getByRole("heading", { name: "Imported 2 students and 1 staff" })).toBeInTheDocument();
    expect(done).toHaveTextContent("created without a password");
    expect(within(done).getByRole("link", { name: "View students" })).toHaveAttribute("href", "/app/students");
    expect(within(done).getByRole("link", { name: "View staff" })).toHaveAttribute("href", "/app/staff");
  });

  it("shows the server's file problem next to the file", async () => {
    const user = userEvent.setup();
    upload.mockRejectedValue(
      new ApiError({
        status: 400,
        title: "Check the form",
        detail: "Some fields need attention.",
        errors: { file: "The file is damaged or is not an Excel (.xlsx) file." },
      }),
    );
    renderAs(IMPORTER, <SchoolImportView />);
    await pick(user, workbook());
    await user.click(screen.getByRole("button", { name: "Check file" }));
    expect(await screen.findByText("The file is damaged or is not an Excel (.xlsx) file.")).toBeInTheDocument();
    expect(screen.queryByTestId("school-import-report")).not.toBeInTheDocument();
  });

  it("checks again when the import is refused, so the new problem is shown", async () => {
    const user = userEvent.setup();
    upload
      .mockResolvedValueOnce(result({}))
      .mockRejectedValueOnce(new ApiError({ status: 400, title: "Check the file", detail: "1 problem needs fixing. Nothing was imported." }))
      .mockResolvedValueOnce(
        result({
          ready: false,
          invalidRows: 1,
          validRows: 2,
          sheets: [sheet({ invalidRows: 1, validRows: 1, problems: [{ row: 2, column: "Section", message: "Class 5 A is full (40 places)." }] })],
        }),
      );
    renderAs(IMPORTER, <SchoolImportView />);
    await pick(user, workbook());
    await user.click(screen.getByRole("button", { name: "Check file" }));
    await user.click(await screen.findByRole("button", { name: "Import 2 students and 1 staff" }));

    expect(await screen.findByText("1 problem needs fixing. Nothing was imported.")).toBeInTheDocument();
    expect(await screen.findByText("Class 5 A is full (40 places).")).toBeInTheDocument();
    expect(upload).toHaveBeenCalledTimes(3);
    expect(upload).toHaveBeenLastCalledWith(expect.any(File), true, false);
  });
});
