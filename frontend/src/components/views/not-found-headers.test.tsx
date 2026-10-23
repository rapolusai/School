import { act, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { setLang } from "@/lib/i18n";
import { renderAs } from "@/test/render";
import { ExpenseView } from "./accounts/expense-view";
import { OversightDetailView } from "./chat/oversight-views";
import { PtmEventView } from "./chat/ptm-views";
import { TitleDetailView } from "./library/title-detail-view";
import { StockCheckView } from "./library/stock-view";

const m = vi.hoisted(() => ({
  title: vi.fn(),
  stockCheck: vi.fn(),
  expense: vi.fn(),
  event: vi.fn(),
  myEvent: vi.fn(),
  oversightRead: vi.fn(),
}));

vi.mock("@/lib/library-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/library-api")>();
  return { ...actual, libraryApi: { ...actual.libraryApi, title: m.title, stockCheck: m.stockCheck } };
});
vi.mock("@/lib/accounts-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/accounts-api")>();
  return { ...actual, accountsApi: { ...actual.accountsApi, expense: m.expense } };
});
vi.mock("@/lib/chat-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/chat-api")>();
  return {
    ...actual,
    ptmApi: { ...actual.ptmApi, event: m.event, myEvent: m.myEvent },
    chatApi: { ...actual.chatApi, oversightRead: m.oversightRead },
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app",
}));

const notFound = (what: string) => new ApiError({ status: 404, title: "Not found", detail: `${what} was not found.` });

afterEach(() => {
  vi.clearAllMocks();
  act(() => setLang("en"));
});

describe("detail pages for a record that does not exist", () => {
  it("library title: says not found instead of loading forever", async () => {
    m.title.mockRejectedValue(notFound("Title"));
    renderAs(["dashboard.view", "library.read", "library.manage"], <TitleDetailView id="t404" />, ["LIBRARIAN"]);
    expect(await screen.findByRole("heading", { level: 1, name: "Not found" })).toBeInTheDocument();
    expect(screen.queryByText("Loading…")).not.toBeInTheDocument();
  });

  it("library stock check: says not found", async () => {
    m.stockCheck.mockRejectedValue(notFound("Stock check"));
    renderAs(["dashboard.view", "library.read", "library.manage"], <StockCheckView id="s404" />, ["LIBRARIAN"]);
    expect(await screen.findByRole("heading", { level: 1, name: "Not found" })).toBeInTheDocument();
  });

  it("parent-teacher meeting: says not found for leadership and for a teacher", async () => {
    m.event.mockRejectedValue(notFound("Meeting"));
    const view = renderAs(["dashboard.view", "ptm.manage"], <PtmEventView id="e404" />, ["PRINCIPAL"]);
    expect(await screen.findByRole("heading", { level: 1, name: "Not found" })).toBeInTheDocument();
    expect(screen.getByText(/This record was not found/)).toBeInTheDocument();
    view.unmount();

    m.myEvent.mockRejectedValue(notFound("Meeting"));
    renderAs(["dashboard.view", "chat.use"], <PtmEventView id="e404" />, ["TEACHER"]);
    expect(await screen.findByRole("heading", { level: 1, name: "Not found" })).toBeInTheDocument();
  });

  it("chat oversight: says not found", async () => {
    m.oversightRead.mockRejectedValue(notFound("Conversation"));
    renderAs(["dashboard.view", "chat.oversee"], <OversightDetailView id="c404" />, ["PRINCIPAL"]);
    expect(await screen.findByRole("heading", { level: 1, name: "Not found" })).toBeInTheDocument();
  });

  it("expense: no Draft badge for an expense that does not exist", async () => {
    m.expense.mockRejectedValue(notFound("Expense"));
    renderAs(["dashboard.view", "accounts.read", "accounts.manage"], <ExpenseView id="x404" />, ["ACCOUNTANT"]);
    expect(await screen.findByRole("heading", { level: 1, name: "Not found" })).toBeInTheDocument();
    expect(screen.queryByText("Draft")).not.toBeInTheDocument();
  });

  it("shows the not-found heading and message in Telugu", async () => {
    act(() => setLang("te"));
    m.title.mockRejectedValue(notFound("Title"));
    renderAs(["dashboard.view", "library.read"], <TitleDetailView id="t404" />, ["LIBRARIAN"]);
    expect(await screen.findByRole("heading", { level: 1, name: "కనబడలేదు" })).toBeInTheDocument();
    expect(screen.queryByText(/was not found/)).not.toBeInTheDocument();
  });
});
