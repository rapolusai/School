import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { libraryReportPaths } from "@/lib/library-api";
import type { LoanView } from "@/lib/types";
import { renderAs } from "@/test/render";
import { LibraryOverdueReportView, LibraryPopularReportView } from "./library-reports";

const m = vi.hoisted(() => ({ overdue: vi.fn(), popular: vi.fn(), listClasses: vi.fn() }));

vi.mock("@/lib/library-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/library-api")>();
  return { ...actual, libraryApi: { ...actual.libraryApi, overdue: m.overdue, popular: m.popular } };
});
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listClasses: m.listClasses } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/reports/library-overdue",
}));

const LIBRARIAN = ["dashboard.view", "library.read", "library.manage", "academics.read"];

const ARJUN_LOAN: LoanView = {
  id: "l1",
  copyId: "c1",
  accessionNo: "LIB00001",
  titleId: "t1",
  title: "The Jungle Book",
  authors: "Rudyard Kipling",
  member: { type: "STUDENT", id: "st1", name: "Arjun Sharma", code: "AKS/2026/001", detail: "Class 5 A", active: true },
  issuedOn: "2026-09-11",
  dueOn: "2026-09-25",
  renewals: 0,
  renewalsLeft: 1,
  status: "ISSUED",
  returnedOn: null,
  overdue: true,
  daysOverdue: 14,
  fineSoFarPaise: 1400,
  issuedByName: "Kavitha Rao",
  returnedByName: null,
};

beforeEach(() => {
  m.listClasses.mockResolvedValue([{ id: "cls5", name: "Class 5", sections: [] }]);
});

afterEach(() => vi.clearAllMocks());

describe("library reports", () => {
  it("lists overdue books with the fine so far, filtered by member type and class, with a CSV download", async () => {
    const user = userEvent.setup();
    m.overdue.mockResolvedValue({ asOf: "2026-10-11", rows: [ARJUN_LOAN], totalFinePaise: 1400 });
    renderAs(LIBRARIAN, <LibraryOverdueReportView />, ["LIBRARIAN"]);

    const table = await screen.findByTestId("library-overdue-table");
    const row = within(table).getByRole("row", { name: /Arjun Sharma/ });
    expect(row).toHaveTextContent("Class 5 A");
    expect(row).toHaveTextContent("LIB00001");
    expect(row).toHaveTextContent("25 Sept 2026");
    expect(row).toHaveTextContent("14");
    expect(row).toHaveTextContent("₹14");
    expect(screen.getByTestId("library-overdue-count")).toHaveTextContent("1");
    expect(screen.getByTestId("report-print-head")).toHaveTextContent("As of 11 Oct 2026");

    await user.selectOptions(screen.getByRole("combobox", { name: "Members" }), "STUDENT");
    await user.selectOptions(await screen.findByRole("combobox", { name: "Class" }), "cls5");
    await waitFor(() => expect(m.overdue).toHaveBeenLastCalledWith({ memberType: "STUDENT", classId: "cls5" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Members" }), "STAFF");
    expect(screen.queryByRole("combobox", { name: "Class" })).not.toBeInTheDocument();
    await waitFor(() => expect(m.overdue).toHaveBeenLastCalledWith({ memberType: "STAFF", classId: undefined }));
    expect(libraryReportPaths.overdueCsv({ memberType: "STUDENT", classId: "cls5" })).toBe(
      "/api/library/reports/overdue.csv?memberType=STUDENT&classId=cls5",
    );
  });

  it("ranks the most borrowed titles and checks the date range", async () => {
    const user = userEvent.setup();
    m.popular.mockImplementation((p: { from: string; to: string }) =>
      Promise.resolve({ ...p, rows: [{ titleId: "t1", title: "Matilda", authors: "Roald Dahl", loans: 5, copies: 2 }] }),
    );
    renderAs(LIBRARIAN, <LibraryPopularReportView />, ["LIBRARIAN"]);
    const table = await screen.findByTestId("library-popular-table");
    const row = within(table).getByRole("row", { name: /Matilda/ });
    expect(within(row).getByRole("rowheader")).toHaveTextContent("MatildaRoald Dahl");
    // Rank, then times borrowed and copies.
    expect(within(row).getAllByRole("cell").map((c) => c.textContent)).toEqual(["1", "5", "2"]);

    const from = screen.getByLabelText("From");
    await user.clear(from);
    await user.type(from, "2030-01-01");
    expect(await screen.findByText("The end date must not be before the start date.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Download Excel" })).toBeDisabled();
  });
});
