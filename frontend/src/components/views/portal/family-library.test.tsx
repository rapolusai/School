import { screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { translate, type Translate } from "@/lib/i18n";
import type { FamilyLoan, MyLibrary } from "@/lib/types";
import { renderAs } from "@/test/render";
import { dueLine, FamilyLibraryView } from "./family-library";

const m = vi.hoisted(() => ({ mine: vi.fn() }));

vi.mock("@/lib/library-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/library-api")>();
  return { ...actual, libraryApi: { ...actual.libraryApi, mine: m.mine } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/family/library",
}));

const PARENT = ["dashboard.view", "child.view", "notices.read"];

const loan = (overrides: Partial<FamilyLoan> = {}): FamilyLoan => ({
  title: "The Jungle Book",
  authors: "Rudyard Kipling",
  accessionNo: "LIB00001",
  issuedOn: "2026-09-11",
  dueOn: "2026-09-25",
  overdue: true,
  daysOverdue: 14,
  renewals: 0,
  fineSoFarPaise: 1400,
  ...overrides,
});

const FAMILY: MyLibrary = {
  members: [
    {
      type: "STUDENT",
      id: "st1",
      name: "Arjun Sharma",
      className: "Class 5 A",
      loans: [loan()],
      holds: [{ title: "Matilda", status: "READY", position: 1, holdUntil: "2026-10-14" }],
      fines: [
        { title: "Wings of Fire", kind: "OVERDUE", amountPaise: 300, status: "PAID", createdAt: "2026-08-01T05:00:00Z", receiptNo: "LIBF/2026-27/000003" },
      ],
      finesDuePaise: 0,
    },
    { type: "STUDENT", id: "st2", name: "Diya Sharma", className: "Class 2 A", loans: [], holds: [], fines: [], finesDuePaise: 0 },
  ],
};

afterEach(() => vi.clearAllMocks());

describe("FamilyLibraryView", () => {
  it("shows each child's books with the due date, how late and the fine so far", async () => {
    m.mine.mockResolvedValue(FAMILY);
    renderAs(PARENT, <FamilyLibraryView />, ["PARENT"]);
    const [arjun, diya] = await screen.findAllByTestId("family-library-member");
    expect(within(arjun).getByRole("heading", { name: /Arjun Sharma\s*Class 5 A/ })).toBeInTheDocument();
    const book = within(arjun).getByTestId("family-library-loan");
    expect(book).toHaveTextContent("The Jungle Book");
    expect(book).toHaveTextContent("Borrowed 11 Sept 2026");
    expect(book).toHaveTextContent("14 days late");
    expect(book).toHaveTextContent("Was due 25 Sept 2026");
    expect(book).toHaveTextContent("Fine so far ₹14");
    expect(within(arjun).getByText("Ready to collect")).toBeInTheDocument();
    expect(within(arjun).getByText("Collect by 14 Oct 2026")).toBeInTheDocument();
    expect(within(arjun).getByTestId("family-library-fines")).toHaveTextContent("LIBF/2026-27/000003");
    expect(within(diya).getByTestId("family-library-empty")).toHaveTextContent("No library books at home.");
  });

  it("says so when nobody is linked", async () => {
    m.mine.mockResolvedValue({ members: [] });
    renderAs(PARENT, <FamilyLibraryView />, ["PARENT"]);
    expect(await screen.findByText("No library account is linked to you yet.")).toBeInTheDocument();
  });
});

describe("dueLine", () => {
  const t: Translate = (key, vars) => translate("en", key, vars);
  it("reads the due date, today or how late", () => {
    expect(dueLine(loan({ overdue: false, daysOverdue: 0, dueOn: "2026-10-20" }), "2026-10-11", t, "en-IN")).toBe("Due 20 Oct 2026");
    expect(dueLine(loan({ overdue: false, daysOverdue: 0, dueOn: "2026-10-11" }), "2026-10-11", t, "en-IN")).toBe("Due today");
    expect(dueLine(loan({ daysOverdue: 1 }), "2026-10-11", t, "en-IN")).toBe("1 day late");
  });
});
