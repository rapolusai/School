import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { circular, estimate } from "@/test/communication-fixtures";
import { renderAs } from "@/test/render";
import { NoticeDetailView } from "./notice-detail-view";

const { get, approve, reject, estimateApi } = vi.hoisted(() => ({
  get: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  estimateApi: vi.fn(),
}));

vi.mock("@/lib/communication-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/communication-api")>();
  return { ...actual, noticesApi: { ...actual.noticesApi, get, approve, reject, estimate: estimateApi } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/notices/n1",
}));

const PRINCIPAL = ["dashboard.view", "notices.send", "notices.read", "notices.approve"];
const ACTIONS = { edit: false, delete: false, submit: false, approve: false, reject: false, cancel: false, withdraw: false };

const PENDING = circular({
  status: "PENDING_APPROVAL",
  submittedAt: "2026-10-09T04:05:00Z",
  actions: { ...ACTIONS, approve: true, reject: true, cancel: true },
});

beforeEach(() => {
  estimateApi.mockResolvedValue(estimate());
});

describe("NoticeDetailView", () => {
  it("lets a principal approve a circular waiting for approval, which sends it", async () => {
    get.mockResolvedValue(PENDING);
    approve.mockResolvedValue(
      circular({
        status: "SENT",
        sentAt: "2026-10-09T05:00:00Z",
        sentByName: "Meera Iyer",
        reviewOutcome: "APPROVED",
        reviewedByName: "Meera Iyer",
        reviewedAt: "2026-10-09T05:00:00Z",
        actions: { ...ACTIONS, withdraw: true },
        delivery: {
          inApp: 2,
          read: 0,
          readPercent: 0,
          staff: 0,
          parents: 2,
          students: 0,
          kinds: [{ kind: "PARENT", recipients: 2, read: 0 }],
          messages: [],
        },
      }),
    );
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <NoticeDetailView id="n1" />, ["PRINCIPAL"]);

    const status = await screen.findByTestId("notice-status");
    expect(status).toHaveTextContent("Waiting for approval");
    expect(screen.getByTestId("notice-audience")).toHaveTextContent("Class 5 A · Parents");
    expect(screen.getByTestId("notice-body")).toHaveTextContent("The science project is due on Friday.");
    // Before approving, the principal sees who it would reach.
    expect(await screen.findByTestId("estimate-people")).toHaveTextContent("2 parents");

    await user.click(within(status).getByRole("button", { name: "Approve and send" }));
    const dialog = await screen.findByRole("dialog", { name: "Approve this circular?" });
    await user.click(within(dialog).getByRole("button", { name: "Approve and send" }));

    await waitFor(() => expect(approve).toHaveBeenCalledWith("n1", ""));
    expect(await screen.findByText("Circular sent.")).toBeInTheDocument();
    expect(screen.getByTestId("notice-status")).toHaveTextContent("Sent on");
    expect(screen.getByTestId("delivery-read")).toHaveTextContent("0 of 2 read");
    expect(within(screen.getByTestId("notice-actions")).getByRole("button", { name: "Withdraw" })).toBeInTheDocument();
  });

  it("asks for a note before sending a circular back", async () => {
    get.mockResolvedValue(PENDING);
    reject.mockResolvedValue(
      circular({
        reviewOutcome: "REJECTED",
        reviewNote: "Add the date.",
        reviewedByName: "Meera Iyer",
        actions: { ...ACTIONS, edit: true, submit: true, delete: true },
      }),
    );
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <NoticeDetailView id="n1" />, ["PRINCIPAL"]);
    const status = await screen.findByTestId("notice-status");

    await user.click(within(status).getByRole("button", { name: "Send back" }));
    const dialog = await screen.findByRole("dialog", { name: "Send back to the author?" });
    await user.click(within(dialog).getByRole("button", { name: "Send back" }));
    expect(within(dialog).getByText("Fill in this field.")).toBeInTheDocument();
    expect(reject).not.toHaveBeenCalled();

    await user.type(within(dialog).getByLabelText("What should change"), "Add the date.");
    await user.click(within(dialog).getByRole("button", { name: "Send back" }));
    await waitFor(() => expect(reject).toHaveBeenCalledWith("n1", "Add the date."));
    expect(await screen.findByTestId("review-note")).toHaveTextContent("Sent back by Meera Iyer: Add the date.");
  });

  it("shows read receipts and messages for a sent circular", async () => {
    get.mockResolvedValue(
      circular({
        status: "SENT",
        sentAt: "2026-10-08T05:00:00Z",
        sentByName: "Meera Iyer",
        channels: ["SMS"],
        delivery: {
          inApp: 40,
          read: 12,
          readPercent: 30,
          staff: 0,
          parents: 38,
          students: 2,
          kinds: [
            { kind: "PARENT", recipients: 38, read: 11 },
            { kind: "STUDENT", recipients: 2, read: 1 },
          ],
          messages: [{ channel: "SMS", total: 36, byStatus: { SIMULATED: 35, FAILED: 1 } }],
        },
      }),
    );
    renderAs(PRINCIPAL, <NoticeDetailView id="n1" />, ["PRINCIPAL"]);
    const delivery = await screen.findByTestId("delivery");
    expect(delivery).toHaveTextContent("30%");
    expect(screen.getByTestId("delivery-read")).toHaveTextContent("12 of 40 read");
    expect(within(screen.getByTestId("delivery-messages")).getByText("Simulated 35")).toBeInTheDocument();
    expect(within(screen.getByTestId("delivery-messages")).getByText("Failed 1")).toBeInTheDocument();
    // A sent circular is not estimated again.
    expect(estimateApi).not.toHaveBeenCalled();
  });
});
