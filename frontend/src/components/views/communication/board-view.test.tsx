import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { BoardPage } from "@/lib/types";
import { boardItem, entry } from "@/test/communication-fixtures";
import { renderAs } from "@/test/render";
import { BoardView } from "./board-view";
import { NoticesCard, UpcomingCard } from "./dashboard-cards";

const { page, item, markRead, markAllRead, upcoming } = vi.hoisted(() => ({
  page: vi.fn(),
  item: vi.fn(),
  markRead: vi.fn(),
  markAllRead: vi.fn(),
  upcoming: vi.fn(),
}));

vi.mock("@/lib/communication-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/communication-api")>();
  return {
    ...actual,
    boardApi: { ...actual.boardApi, page, item, markRead, markAllRead },
    calendarApi: { ...actual.calendarApi, upcoming },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/board",
}));

const PARENT = ["dashboard.view", "child.view", "notices.read"];
const TEACHER = ["dashboard.view", "notices.send", "notices.read"];

const URGENT = boardItem();
const FEES = boardItem({
  id: "b2",
  title: "Fee reminder",
  body: "Second term fees are due by 15 October.",
  category: "FEES",
  pinned: false,
  read: true,
  readAt: "2026-10-08T14:00:00Z",
});

const PAGE: BoardPage = { items: [URGENT, FEES], page: 0, size: 20, total: 2, unread: 1 };

beforeEach(() => {
  page.mockResolvedValue(PAGE);
  markRead.mockResolvedValue({ ...URGENT, read: true, readAt: "2026-10-09T05:00:00Z" });
  markAllRead.mockResolvedValue({ marked: 1 });
});

describe("BoardView", () => {
  it("shows the pinned urgent notice first and marks a notice read when it is opened", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <BoardView />, ["PARENT"]);
    const list = await screen.findByTestId("board-list");
    const rows = within(list).getAllByTestId("notice-row");
    expect(rows[0]).toHaveTextContent("School closed tomorrow");
    expect(rows[0]).toHaveTextContent("Pinned");
    expect(rows[0]).toHaveTextContent("Urgent");
    expect(rows[0]).toHaveAttribute("data-unread", "true");
    expect(rows[1]).not.toHaveAttribute("data-unread");
    expect(screen.getByText("1 unread")).toBeInTheDocument();
    // Families cannot write circulars.
    expect(screen.queryByRole("link", { name: "New circular" })).not.toBeInTheDocument();

    await user.click(within(rows[0]).getByRole("button", { name: /School closed tomorrow/ }));
    const dialog = await screen.findByRole("dialog", { name: "School closed tomorrow" });
    expect(within(dialog).getByTestId("notice-reader")).toHaveTextContent("Because of heavy rain");
    await waitFor(() => expect(markRead).toHaveBeenCalledWith("b1"));
    await waitFor(() => expect(page).toHaveBeenCalledTimes(2));

    // A notice already read is not marked again.
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(within(rows[1]).getByRole("button", { name: "Fee reminder" }));
    await screen.findByRole("dialog", { name: "Fee reminder" });
    expect(markRead).toHaveBeenCalledTimes(1);
  });

  it("filters to unread notices and marks everything read", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <BoardView />, ["TEACHER"]);
    await screen.findByTestId("board-list");
    expect(screen.getByRole("link", { name: "New circular" })).toHaveAttribute("href", "/app/notices/new");

    await user.click(screen.getByRole("radio", { name: "Unread" }));
    await waitFor(() => expect(page).toHaveBeenLastCalledWith({ page: 0, size: 20, unreadOnly: true }));

    await user.click(screen.getByRole("button", { name: "Mark all as read" }));
    expect(markAllRead).toHaveBeenCalled();
    expect(await screen.findByText("1 notice marked as read.")).toBeInTheDocument();
  });

  it("explains an empty board", async () => {
    page.mockResolvedValue({ items: [], page: 0, size: 20, total: 0, unread: 0 });
    renderAs(PARENT, <BoardView />, ["PARENT"]);
    expect(await screen.findByTestId("board-empty")).toHaveTextContent("No notices yet.");
  });

  it("opens a notice linked from the dashboard", async () => {
    item.mockResolvedValue(FEES);
    renderAs(PARENT, <BoardView initialOpenId="b2" />, ["PARENT"]);
    expect(await screen.findByRole("dialog", { name: "Fee reminder" })).toBeInTheDocument();
    expect(item).toHaveBeenCalledWith("b2");
  });
});

describe("dashboard cards", () => {
  it("lists the latest unread notices and opens them in place", async () => {
    page.mockResolvedValue({ items: [URGENT], page: 0, size: 3, total: 1, unread: 1 });
    const user = userEvent.setup();
    renderAs(PARENT, <NoticesCard />, ["PARENT"]);
    const card = await screen.findByTestId("notices-card");
    await within(card).findByRole("button", { name: "School closed tomorrow" });
    expect(page).toHaveBeenCalledWith({ size: 3, unreadOnly: true });
    expect(within(card).getByText("1 unread")).toBeInTheDocument();
    await user.click(within(card).getByRole("button", { name: "School closed tomorrow" }));
    await waitFor(() => expect(markRead).toHaveBeenCalledWith("b1"));
    expect(within(card).getByRole("link", { name: "Open the notice board" })).toHaveAttribute("href", "/app/board");
  });

  it("says when everything has been read", async () => {
    page.mockResolvedValue({ items: [], page: 0, size: 3, total: 0, unread: 0 });
    renderAs(PARENT, <NoticesCard />, ["PARENT"]);
    expect(await screen.findByText("You're all caught up.")).toBeInTheDocument();
  });

  it("shows the next calendar entries", async () => {
    upcoming.mockResolvedValue([
      entry(),
      entry({
        id: "e2",
        kind: "PTM",
        title: "Parent-teacher meeting",
        startsOn: "2026-10-24",
        endsOn: "2026-10-24",
        startTime: "09:00",
        endTime: "12:30",
        audience: "CLASSES",
        classes: [{ id: "c5", name: "Class 5" }],
      }),
    ]);
    renderAs(PARENT, <UpcomingCard />, ["PARENT"]);
    const card = await screen.findByTestId("upcoming-card");
    await within(card).findByText("Parent-teacher meeting");
    expect(upcoming).toHaveBeenCalledWith(5);
    expect(card).toHaveTextContent("Holiday · 2 Oct 2026 · Whole school");
    expect(card).toHaveTextContent("24 Oct 2026, 9:00 am – 12:30 pm · Classes: Class 5");
  });
});
