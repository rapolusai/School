import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { messageQueryString } from "@/lib/notifications-api";
import type { MessageDetail, MessagePage, MessageRow } from "@/lib/types";
import { renderAs } from "@/test/render";
import { MessagesView, toMessageQuery } from "./messages-view";

const { listMessages, getMessage } = vi.hoisted(() => ({ listMessages: vi.fn(), getMessage: vi.fn() }));

vi.mock("@/lib/notifications-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/notifications-api")>();
  return { ...actual, notificationsApi: { ...actual.notificationsApi, listMessages, getMessage } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/messages",
}));

const ADMIN = ["dashboard.view", "messages.read"];

function message(overrides: Partial<MessageRow> = {}): MessageRow {
  return {
    id: "m1",
    createdAt: "2026-10-09T03:41:00Z",
    channel: "WHATSAPP",
    recipient: "98765•••01",
    recipientName: "Anitha Sharma",
    templateKey: "attendance.absence",
    status: "SIMULATED",
    attempts: 1,
    sentAt: "2026-10-09T03:42:00Z",
    nextAttemptAt: null,
    lastError: null,
    relatedType: "student",
    relatedId: "st1",
    relatedLabel: "Arjun Sharma (Class 5 A)",
    ...overrides,
  };
}

const PAGE: MessagePage = {
  items: [
    message(),
    message({
      id: "m2",
      channel: "SMS",
      recipient: "98765•••02",
      recipientName: "Uma Iyer",
      status: "SKIPPED",
      sentAt: null,
      lastError: "Marked present before the alert was sent",
      relatedId: "st2",
      relatedLabel: "Bala Iyer (Class 5 A)",
    }),
  ],
  page: 0,
  size: 25,
  total: 2,
};

const DETAIL: MessageDetail = {
  ...message(),
  language: "en",
  body: "Dear Anitha Sharma, Arjun Sharma (Class 5 A) was marked absent at Akshara Demo School on 08 Oct 2026. Please contact the school if this is unexpected.",
  fallbackChannel: "SMS",
};

beforeEach(() => {
  listMessages.mockResolvedValue(PAGE);
  getMessage.mockResolvedValue(DETAIL);
});

describe("MessagesView", () => {
  it("lists messages with masked numbers and their status", async () => {
    renderAs(ADMIN, <MessagesView />);
    const table = await screen.findByTestId("messages-table");
    expect(within(table).getByText("98765•••01")).toBeInTheDocument();
    // The student label shows in its cell and names the row's View button.
    expect(within(table).getByText("Arjun Sharma (Class 5 A)")).toBeInTheDocument();
    expect(within(table).getByRole("button", { name: "View Arjun Sharma (Class 5 A)" })).toBeInTheDocument();
    expect(within(table).getByText("Simulated")).toBeInTheDocument();
    expect(within(table).getByText("Skipped")).toBeInTheDocument();
    expect(within(table).getByText("Marked present before the alert was sent")).toBeInTheDocument();
    expect(within(table).getAllByText("Absence alert")).toHaveLength(2);
    // Full numbers never reach the page.
    expect(document.body.textContent).not.toMatch(/9876500001/);
    expect(screen.getByText(/Messages are simulated/)).toBeInTheDocument();
    expect(screen.getByText("2 messages")).toBeInTheDocument();
  });

  it("filters by status and channel", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, <MessagesView />);
    await screen.findByTestId("messages-table");
    await user.selectOptions(screen.getByRole("combobox", { name: "Status" }), "SIMULATED");
    await waitFor(() => expect(listMessages).toHaveBeenLastCalledWith(expect.objectContaining({ status: "SIMULATED" })));
    await user.selectOptions(screen.getByRole("combobox", { name: "Channel" }), "SMS");
    await waitFor(() =>
      expect(listMessages).toHaveBeenLastCalledWith(
        expect.objectContaining({ status: "SIMULATED", channel: "SMS", page: 0, size: 25 }),
      ),
    );
  });

  it("opens a message to show the text that was sent", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, <MessagesView />);
    const table = await screen.findByTestId("messages-table");
    await user.click(within(table).getAllByRole("button", { name: /View/ })[0]);
    const dialog = await screen.findByRole("dialog", { name: "Message" });
    expect(getMessage).toHaveBeenCalledWith("m1");
    expect(await within(dialog).findByTestId("message-detail")).toHaveTextContent(
      "was marked absent at Akshara Demo School on 08 Oct 2026",
    );
    expect(dialog).toHaveTextContent("SMS if that fails");
    expect(dialog).toHaveTextContent("98765•••01");
  });

  it("explains an empty log", async () => {
    listMessages.mockResolvedValue({ items: [], page: 0, size: 25, total: 0 });
    renderAs(ADMIN, <MessagesView />);
    expect(await screen.findByTestId("messages-empty")).toHaveTextContent("No messages yet.");
  });
});

describe("message queries", () => {
  it("sends only the filters that are set", () => {
    const query = toMessageQuery({ from: "2026-10-01", to: "", channel: "", status: "FAILED" }, " arjun ", 2);
    expect(query).toEqual({
      from: "2026-10-01",
      to: undefined,
      channel: undefined,
      status: "FAILED",
      q: "arjun",
      page: 2,
      size: 25,
    });
    expect(messageQueryString(query)).toBe("?from=2026-10-01&status=FAILED&q=arjun&page=2&size=25");
    expect(messageQueryString({})).toBe("");
  });
});
