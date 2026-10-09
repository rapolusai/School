import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { importTemplate } from "@/lib/csv";
import type { ImportResult } from "@/lib/types";
import { ADMIN_PERMISSIONS, renderAs } from "@/test/render";
import { MAX_FILE_BYTES, StudentImportView } from "./student-import-view";

const { importStudents } = vi.hoisted(() => ({ importStudents: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, importStudents } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/students/import",
}));

const CSV = importTemplate();

function result(overrides: Partial<ImportResult>): ImportResult {
  return {
    dryRun: true,
    committed: false,
    totalRows: 1,
    validRows: 1,
    invalidRows: 0,
    created: 0,
    errors: [],
    ignoredColumns: [],
    ...overrides,
  };
}

function csvFile(text: string, name = "students.csv") {
  return new File([text], name, { type: "text/csv" });
}

async function pick(user: ReturnType<typeof userEvent.setup>, file: File) {
  await user.upload(screen.getByLabelText("CSV file"), file);
}

beforeEach(() => {
  importStudents.mockReset();
});

describe("StudentImportView", () => {
  it("asks for a file before checking", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN_PERMISSIONS, <StudentImportView />);
    await user.click(screen.getByRole("button", { name: "Check file" }));
    expect(screen.getByText("Choose a CSV file.")).toBeInTheDocument();
    expect(importStudents).not.toHaveBeenCalled();
  });

  it("refuses files that are too large or have no rows, without calling the API", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN_PERMISSIONS, <StudentImportView />);
    await pick(user, csvFile("x".repeat(MAX_FILE_BYTES + 1)));
    expect(screen.getByText("The file is larger than 2 MB.")).toBeInTheDocument();

    await pick(user, csvFile("admission_no,first_name\n"));
    expect(await screen.findByText("The file has no student rows below the header.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Check file" }));
    expect(importStudents).not.toHaveBeenCalled();
  });

  it("lists every problem from the dry run and offers no import", async () => {
    const user = userEvent.setup();
    importStudents.mockResolvedValue(
      result({
        totalRows: 2,
        validRows: 1,
        invalidRows: 1,
        errors: [{ row: 3, column: "guardian_phone", message: "Enter a 10-digit Indian mobile number." }],
        ignoredColumns: ["aadhaar"],
      }),
    );
    renderAs(ADMIN_PERMISSIONS, <StudentImportView />);
    await pick(user, csvFile(`${CSV}A2,Diya,,2017-01-01,F,2026-06-01,Class 2,A,,Anitha,MOTHER,12345,,\r\n`));
    expect(screen.getByText("students.csv: 2 student rows")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Check file" }));

    expect(importStudents).toHaveBeenCalledWith(expect.stringContaining("admission_no"), true);
    const report = await screen.findByTestId("import-report");
    expect(within(report).getByText(/1 of 2 rows need fixing/)).toBeInTheDocument();
    expect(within(report).getByText("These columns are not used and were ignored: aadhaar")).toBeInTheDocument();
    const errors = within(report).getByTestId("import-errors");
    expect(within(errors).getByText("guardian_phone")).toBeInTheDocument();
    expect(within(report).getByText("Row 3 · guardian_phone")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Import \d/ })).not.toBeInTheDocument();
  });

  it("imports after a clean dry run", async () => {
    const user = userEvent.setup();
    importStudents
      .mockResolvedValueOnce(result({}))
      .mockResolvedValueOnce(result({ dryRun: false, committed: true, created: 1 }));
    renderAs(ADMIN_PERMISSIONS, <StudentImportView />);
    await pick(user, csvFile(CSV));
    await user.click(screen.getByRole("button", { name: "Check file" }));
    expect(await screen.findByText("The row is ready to import.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Import 1 student" }));
    expect(importStudents).toHaveBeenLastCalledWith(CSV, false);
    const done = await screen.findByTestId("import-done");
    expect(within(done).getByRole("heading", { name: "1 student imported" })).toBeInTheDocument();
    expect(within(done).getByRole("link", { name: "View students" })).toHaveAttribute("href", "/app/students");
  });

  it("checks again when the import is refused, so the new problem is shown", async () => {
    const user = userEvent.setup();
    importStudents
      .mockResolvedValueOnce(result({}))
      .mockRejectedValueOnce(new ApiError({ status: 400, title: "Invalid", detail: "Nothing was imported." }))
      .mockResolvedValueOnce(
        result({ validRows: 0, invalidRows: 1, errors: [{ row: 2, column: "section", message: "Class 5 A is full." }] }),
      );
    renderAs(ADMIN_PERMISSIONS, <StudentImportView />);
    await pick(user, csvFile(CSV));
    await user.click(screen.getByRole("button", { name: "Check file" }));
    await user.click(await screen.findByRole("button", { name: "Import 1 student" }));

    expect(await screen.findByText("Nothing was imported.")).toBeInTheDocument();
    expect(await screen.findAllByText("Class 5 A is full.")).not.toHaveLength(0);
    expect(importStudents).toHaveBeenCalledTimes(3);
    expect(importStudents).toHaveBeenLastCalledWith(CSV, true);
  });
});
