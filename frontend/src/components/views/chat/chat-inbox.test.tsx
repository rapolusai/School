import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { ChatClient } from "@/lib/chat-api";
import { translate, type Translate } from "@/lib/i18n";
import type { ChatMessage, ConversationDetail, ConversationRow } from "@/lib/types";
import { renderAs } from "@/test/render";
import { ChatInbox, messageProblem, previewText, sortConversations } from "./chat-inbox";
import { availabilityNotice, daysText, hhmm, mergeMessages } from "./chat-shared";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/chat",
}));

const t: Translate = (key, vars) => translate("en", key, vars);

const NOW = new Date();
const iso = (minutesAgo: number) => new Date(NOW.getTime() - minutesAgo * 60_000).toISOString();

const message = (overrides: Partial<ChatMessage> = {}): ChatMessage => ({
  id: "m1",
  side: "PARENT",
  mine: true,
  senderName: "Lata Rao",
  body: "Hello Sir, how is Asha doing in Maths?",
  attachments: [],
  deleted: false,
  erased: false,
  createdAt: iso(30),
  readAt: iso(20),
  deletableUntil: iso(20),
  canDelete: false,
  ...overrides,
});

const row = (overrides: Partial<ConversationRow> = {}): ConversationRow => ({
  id: "c1",
  student: { id: "s1", name: "Asha Rao", classLabel: "Class 5 A" },
  withName: "Ravi Kumar",
  withDetail: "Class teacher",
  unread: 1,
  last: { text: "She is doing well.", mine: false, deleted: false, attachments: 0, at: iso(5) },
  lastMessageAt: iso(5),
  readOnly: false,
  readOnlyReason: null,
  away: false,
  ...overrides,
});

const detail = (overrides: Partial<ConversationDetail> = {}): ConversationDetail => ({
  id: "c1",
  mySide: "PARENT",
  student: { id: "s1", name: "Asha Rao", classLabel: "Class 5 A" },
  parentName: "Lata Rao",
  staffName: "Ravi Kumar",
  staffDetail: "Class teacher",
  readOnly: false,
  readOnlyReason: null,
  closedByName: null,
  closeReason: null,
  closedAt: null,
  staffAvailability: {
    openNow: true,
    nextOpenAt: null,
    days: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"],
    from: "08:00:00",
    to: "18:00:00",
    awayUntil: null,
    awayNote: null,
  },
  messages: [
    message(),
    message({ id: "m2", side: "STAFF", mine: false, senderName: "Ravi Kumar", body: "She is doing well.", createdAt: iso(5), readAt: iso(1) }),
  ],
  hasOlder: false,
  reportedByMe: false,
  serverTime: NOW.toISOString(),
  ...overrides,
});

function fakeClient(overrides: Partial<ChatClient> = {}): ChatClient {
  return {
    list: vi.fn().mockResolvedValue([row()]),
    unread: vi.fn().mockResolvedValue({ unread: 1, conversations: 1 }),
    contacts: vi.fn().mockResolvedValue({ sections: [], sectionId: null, contacts: [] }),
    start: vi.fn().mockResolvedValue(detail()),
    get: vi.fn().mockResolvedValue(detail()),
    changes: vi.fn().mockResolvedValue({ id: "c1", readOnly: false, readOnlyReason: null, messages: [], serverTime: NOW.toISOString() }),
    send: vi.fn(),
    remove: vi.fn(),
    report: vi.fn(),
    ...overrides,
  };
}

const PARENT = ["dashboard.view", "child.view"];

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("ChatInbox", () => {
  it("lists conversations with the teacher, the child, the last message and the unread count", async () => {
    const client = fakeClient({
      list: vi.fn().mockResolvedValue([
        row({ id: "c2", withName: "Meera Das", withDetail: "Science", unread: 0, lastMessageAt: iso(60), last: { text: "Thank you", mine: true, deleted: false, attachments: 0, at: iso(60) } }),
        row(),
      ]),
    });
    renderAs(PARENT, <ChatInbox client={client} side="PARENT" cacheKey="test" />, ["PARENT"]);
    const rows = await screen.findAllByTestId("chat-conversation");
    expect(rows.map((r) => within(r).getByText(/Kumar|Das/).textContent)).toEqual(["Ravi Kumar", "Meera Das"]);
    expect(rows[0]).toHaveTextContent("Class teacher · Asha Rao");
    expect(rows[0]).toHaveTextContent("She is doing well.");
    expect(within(rows[0]).getByLabelText("1 unread message")).toHaveTextContent("1");
    expect(rows[1]).toHaveTextContent("You: Thank you");
    expect(screen.getByText("Choose a conversation to read it.")).toBeInTheDocument();
    expect(screen.queryByText(/98765|@/)).not.toBeInTheDocument();
  });

  it("opens a conversation with read receipts and sends a message", async () => {
    const user = userEvent.setup();
    const sent = message({ id: "m3", body: "Thank you, Sir.", createdAt: NOW.toISOString(), readAt: null, canDelete: true, deletableUntil: new Date(NOW.getTime() + 600_000).toISOString() });
    const client = fakeClient({ send: vi.fn().mockResolvedValue({ message: sent, staffAvailability: detail().staffAvailability }) });
    renderAs(PARENT, <ChatInbox client={client} side="PARENT" cacheKey="test" initialId="c1" />, ["PARENT"]);

    expect(await screen.findByTestId("chat-with")).toHaveTextContent("Ravi Kumar");
    const bubbles = screen.getAllByTestId("chat-message");
    expect(bubbles).toHaveLength(2);
    expect(within(bubbles[0]).getByTestId("chat-seen")).toHaveTextContent("Seen");
    expect(bubbles[1]).toHaveAttribute("data-mine", "false");
    expect(client.get).toHaveBeenCalledWith("c1");

    // Nothing to send yet: the box says so and the API is not called.
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Write a message or attach a file.");
    expect(client.send).not.toHaveBeenCalled();

    await user.type(screen.getByRole("textbox", { name: "Write a message" }), "Thank you, Sir.");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(client.send).toHaveBeenCalledWith("c1", "Thank you, Sir.", []));
    const after = await screen.findAllByTestId("chat-message");
    expect(after).toHaveLength(3);
    expect(after[2]).toHaveTextContent("Thank you, Sir.");
    expect(after[2]).toHaveTextContent("Sent");
    expect(screen.getByRole("textbox", { name: "Write a message" })).toHaveValue("");
  });

  it("tells a parent when the teacher is outside school hours or away", async () => {
    const client = fakeClient({
      get: vi.fn().mockResolvedValue(
        detail({
          staffAvailability: {
            openNow: false,
            nextOpenAt: "2026-10-12T02:30:00Z",
            days: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"],
            from: "08:00:00",
            to: "18:00:00",
            awayUntil: null,
            awayNote: null,
          },
        }),
      ),
    });
    renderAs(PARENT, <ChatInbox client={client} side="PARENT" cacheKey="test" initialId="c1" />, ["PARENT"]);
    const notice = await screen.findByTestId("chat-hours");
    expect(notice).toHaveTextContent("Teachers reply during school hours (Mon–Sat, 08:00–18:00).");
    expect(notice).toHaveTextContent("Your message reaches them from 12 Oct 2026, 8:00 am.");
  });

  it("shows the rate limit in words and keeps what was typed", async () => {
    const user = userEvent.setup();
    const client = fakeClient({
      send: vi.fn().mockRejectedValue(new ApiError({ status: 429, title: "Too Many Requests", errors: { body: "Too many messages." } })),
    });
    renderAs(PARENT, <ChatInbox client={client} side="PARENT" cacheKey="test" initialId="c1" />, ["PARENT"]);
    await user.type(await screen.findByRole("textbox", { name: "Write a message" }), "One more");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("You have sent many messages in the last hour. Wait a while and try again.")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Write a message" })).toHaveValue("One more");
  });

  it("deletes one's own message within ten minutes, leaving a placeholder", async () => {
    const user = userEvent.setup();
    const mine = message({ id: "m9", body: "Wrong child, sorry", createdAt: iso(2), readAt: null, canDelete: true, deletableUntil: new Date(NOW.getTime() + 480_000).toISOString() });
    const client = fakeClient({
      get: vi.fn().mockResolvedValue(detail({ messages: [mine] })),
      remove: vi.fn().mockResolvedValue({ ...mine, body: null, deleted: true, canDelete: false }),
    });
    renderAs(PARENT, <ChatInbox client={client} side="PARENT" cacheKey="test" initialId="c1" />, ["PARENT"]);
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete for both" }));
    await waitFor(() => expect(client.remove).toHaveBeenCalledWith("c1", "m9"));
    expect(await screen.findByText("Message deleted")).toBeInTheDocument();
    expect(screen.queryByText("Wrong child, sorry")).not.toBeInTheDocument();
  });

  it("shows a closed conversation as read-only without the message box", async () => {
    const client = fakeClient({
      get: vi.fn().mockResolvedValue(detail({ readOnly: true, readOnlyReason: "CLOSED", closeReason: "Resolved at school" })),
    });
    renderAs(PARENT, <ChatInbox client={client} side="PARENT" cacheKey="test" initialId="c1" />, ["PARENT"]);
    expect(await screen.findByTestId("chat-read-only")).toHaveTextContent(
      "The school closed this conversation. You can still read it. Reason: Resolved at school",
    );
    expect(screen.queryByRole("textbox", { name: "Write a message" })).not.toBeInTheDocument();
  });

  it("asks for new messages every 15 seconds while the tab is visible, and not while it is hidden", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const reply = message({ id: "m5", side: "STAFF", mine: false, body: "Please see me on Monday.", createdAt: new Date(NOW.getTime() + 1000).toISOString(), readAt: null });
    const client = fakeClient({
      changes: vi
        .fn()
        .mockResolvedValueOnce({ id: "c1", readOnly: false, readOnlyReason: null, messages: [reply], serverTime: "2026-10-11T10:00:15Z" })
        .mockResolvedValue({ id: "c1", readOnly: false, readOnlyReason: null, messages: [], serverTime: "2026-10-11T10:00:30Z" }),
    });
    renderAs(PARENT, <ChatInbox client={client} side="PARENT" cacheKey="test" initialId="c1" />, ["PARENT"]);
    await screen.findByTestId("chat-with");
    expect(client.changes).not.toHaveBeenCalled();

    await act(async () => {
      vi.advanceTimersByTime(15_000);
    });
    await waitFor(() => expect(client.changes).toHaveBeenCalledWith("c1", NOW.toISOString()));
    expect(await screen.findByText("Please see me on Monday.")).toBeInTheDocument();

    // Hidden tab: no more requests until it is visible again, then one at once.
    let state: DocumentVisibilityState = "hidden";
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
    try {
      act(() => {
        document.dispatchEvent(new Event("visibilitychange"));
      });
      const calls = vi.mocked(client.changes).mock.calls.length;
      await act(async () => {
        vi.advanceTimersByTime(60_000);
      });
      expect(client.changes).toHaveBeenCalledTimes(calls);
      state = "visible";
      await act(async () => {
        document.dispatchEvent(new Event("visibilitychange"));
      });
      await waitFor(() => expect(client.changes).toHaveBeenCalledTimes(calls + 1));
      expect(client.changes).toHaveBeenLastCalledWith("c1", "2026-10-11T10:00:15Z");
    } finally {
      // Back to jsdom's own (prototype) property.
      delete (document as { visibilityState?: DocumentVisibilityState }).visibilityState;
    }
  });

  it("starts a conversation with a teacher of the child", async () => {
    const user = userEvent.setup();
    const client = fakeClient({
      list: vi.fn().mockResolvedValue([]),
      contacts: vi.fn().mockResolvedValue({
        sections: [],
        sectionId: null,
        contacts: [
          { studentId: "s1", studentName: "Asha Rao", classLabel: "Class 5 A", personId: "t1", personName: "Ravi Kumar", detail: "Class teacher", conversationId: null, awayUntil: null },
          { studentId: "s1", studentName: "Asha Rao", classLabel: "Class 5 A", personId: "t2", personName: "Meera Das", detail: "Science", conversationId: null, awayUntil: "2026-10-20" },
        ],
      }),
    });
    renderAs(PARENT, <ChatInbox client={client} side="PARENT" cacheKey="test" />, ["PARENT"]);
    expect(await screen.findByTestId("chat-empty")).toHaveTextContent("No messages yet.");
    await user.click(screen.getByRole("button", { name: "New message" }));
    const contacts = await screen.findAllByTestId("chat-contact");
    expect(contacts[0]).toHaveTextContent("Class teacher · Asha Rao");
    expect(contacts[1]).toHaveTextContent("Away until 20 Oct 2026.");
    await user.click(within(contacts[0]).getByRole("button", { name: "Message Ravi Kumar about Asha Rao" }));
    await waitFor(() => expect(client.start).toHaveBeenCalledWith("s1", "t1"));
    expect(await screen.findByTestId("chat-with")).toHaveTextContent("Ravi Kumar");
  });
});

describe("chat helpers", () => {
  it("merges changed and new messages in time order", () => {
    const a = message({ id: "a", createdAt: "2026-10-11T10:00:00Z", readAt: null });
    const b = message({ id: "b", createdAt: "2026-10-11T10:05:00Z" });
    const readA = { ...a, readAt: "2026-10-11T10:06:00Z" };
    expect(mergeMessages([b, a], [readA]).map((m) => [m.id, m.readAt])).toEqual([
      ["a", "2026-10-11T10:06:00Z"],
      ["b", b.readAt],
    ]);
    expect(mergeMessages([a], [])).toEqual([a]);
  });

  it("previews the last message and sorts the newest first", () => {
    expect(previewText(t, row({ last: { text: null, mine: true, deleted: false, attachments: 2, at: iso(1) } }))).toBe("You: 2 attachments");
    expect(previewText(t, row({ last: { text: null, mine: false, deleted: true, attachments: 0, at: iso(1) } }))).toBe("Message deleted");
    expect(previewText(t, row({ last: null }))).toBe("No messages yet");
    const older = row({ id: "old", lastMessageAt: "2026-10-01T00:00:00Z" });
    const empty = row({ id: "new", lastMessageAt: null });
    expect(sortConversations([older, empty, row()]).map((r) => r.id)).toEqual(["c1", "old", "new"]);
  });

  it("checks a message before sending it", () => {
    expect(messageProblem("  ", [])).toBe("chat.v.empty");
    expect(messageProblem("", [new File(["x"], "a.txt")])).toBeNull();
    expect(messageProblem("x".repeat(2001), [])).toBe("chat.v.long");
    expect(messageProblem("Hello", [])).toBeNull();
  });

  it("describes the teacher's hours and away note", () => {
    expect(daysText(t, ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"])).toBe("Mon–Sat");
    expect(daysText(t, ["FRIDAY", "MONDAY", "WEDNESDAY"])).toBe("Mon, Wed, Fri");
    expect(hhmm("09:30:00")).toBe("09:30");
    const base = detail().staffAvailability;
    expect(availabilityNotice(t, base, "en-IN")).toBeNull();
    expect(availabilityNotice(t, base && { ...base, awayUntil: "2026-10-20", awayNote: "On leave." }, "en-IN")).toBe(
      "Away until 20 Oct 2026. On leave.",
    );
  });
});
