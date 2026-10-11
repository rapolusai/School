import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { StockCheckDetail } from "@/lib/types";
import { renderAs } from "@/test/render";
import { StockCheckView } from "./stock-view";

const m = vi.hoisted(() => ({ stockCheck: vi.fn(), scan: vi.fn(), closeStockCheck: vi.fn() }));

vi.mock("@/lib/library-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/library-api")>();
  return { ...actual, libraryApi: { ...actual.libraryApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/library/stock/sc1",
}));

const LIBRARIAN = ["dashboard.view", "library.read", "library.manage"];

function detail(seen: number, missing: string[]): StockCheckDetail {
  return {
    check: {
      id: "sc1",
      status: "OPEN",
      note: "Term 1",
      startedByName: "Kavitha Rao",
      startedAt: "2026-10-11T04:30:00Z",
      closedByName: null,
      closedAt: null,
      seen,
      expected: 3,
      missing: missing.length,
    },
    missing: missing.map((acc, i) => ({
      copyId: `c${i}`,
      accessionNo: acc,
      titleId: "t1",
      title: "Matilda",
      callNumber: "823 DAH",
      shelfLocation: "Shelf B1",
      status: "AVAILABLE",
    })),
    latest: [],
  };
}

afterEach(() => vi.clearAllMocks());

describe("StockCheckView", () => {
  it("scans copies one after another with Enter and lists the ones not seen yet", async () => {
    const user = userEvent.setup();
    m.stockCheck.mockResolvedValueOnce(detail(0, ["LIB00001", "LIB00002", "LIB00003"])).mockResolvedValue(detail(1, ["LIB00002", "LIB00003"]));
    m.scan.mockResolvedValue({
      accessionNo: "LIB00001",
      title: "Matilda",
      status: "ISSUED",
      alreadySeen: false,
      warning: "ISSUED",
      seen: 1,
      expected: 3,
    });
    renderAs(LIBRARIAN, <StockCheckView id="sc1" />, ["LIBRARIAN"]);

    expect(await screen.findByTestId("stock-progress")).toHaveTextContent("0 of 3 seen");
    expect(screen.getByRole("heading", { name: "3 copies not seen" })).toBeInTheDocument();
    const field = within(screen.getByTestId("stock-scan")).getByLabelText("Scan a copy");
    expect(field).toHaveFocus();

    await user.type(field, "lib00001{Enter}");
    await waitFor(() => expect(m.scan).toHaveBeenCalledWith("sc1", "LIB00001"));
    // A copy the catalogue says is lent, found on the shelf, is pointed out.
    expect(await screen.findByTestId("stock-last")).toHaveTextContent("LIB00001 Matilda · the catalogue says: Lent");
    expect(await screen.findByRole("heading", { name: "2 copies not seen" })).toBeInTheDocument();
    expect(screen.getByTestId("stock-progress")).toHaveTextContent("1 of 3 seen");
    expect(field).toHaveValue("");
    expect(field).toHaveFocus();
  });
});
