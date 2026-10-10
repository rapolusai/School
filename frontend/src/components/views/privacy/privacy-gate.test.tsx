import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { NEEDS_CONSENT, PARENT, parentPrivacy } from "@/test/privacy-fixtures";
import { renderAs, TEACHER_PERMISSIONS } from "@/test/render";
import { PrivacyGate } from "./privacy-gate";

const mocks = vi.hoisted(() => ({ mine: vi.fn(), accept: vi.fn(), pathname: { value: "/app/dashboard" } }));

vi.mock("@/lib/privacy-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/privacy-api")>();
  return { ...actual, privacyApi: { ...actual.privacyApi, mine: mocks.mine, accept: mocks.accept } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => mocks.pathname.value,
}));

const page = <p>The dashboard</p>;

beforeEach(() => {
  mocks.pathname.value = "/app/dashboard";
  mocks.mine.mockResolvedValue(parentPrivacy());
});

describe("PrivacyGate", () => {
  it("shows staff the page without asking anything", () => {
    renderAs(TEACHER_PERMISSIONS, <PrivacyGate>{page}</PrivacyGate>, ["TEACHER"]);
    expect(screen.getByText("The dashboard")).toBeInTheDocument();
    expect(mocks.mine).not.toHaveBeenCalled();
  });

  it("shows a parent who has accepted the page", async () => {
    renderAs(PARENT, <PrivacyGate>{page}</PrivacyGate>, ["PARENT"]);
    expect(await screen.findByText("The dashboard")).toBeInTheDocument();
    expect(screen.queryByTestId("privacy-gate")).not.toBeInTheDocument();
  });

  it("asks a parent to accept a new notice, with optional choices unticked, before the page", async () => {
    const user = userEvent.setup();
    mocks.mine.mockResolvedValue(NEEDS_CONSENT);
    mocks.accept.mockResolvedValue(parentPrivacy());
    renderAs(PARENT, <PrivacyGate>{page}</PrivacyGate>, ["PARENT"]);

    const gate = await screen.findByTestId("privacy-gate");
    expect(screen.queryByText("The dashboard")).not.toBeInTheDocument();
    expect(within(gate).getByRole("heading", { name: "The school has updated its privacy notice" })).toBeInTheDocument();
    expect(within(gate).getByText("What changed: Added WhatsApp messages.")).toBeInTheDocument();
    expect(within(gate).getByRole("heading", { name: "What we collect" })).toBeInTheDocument();
    const arjun = within(gate).getByTestId("gate-child-st1");
    const photos = within(arjun).getByRole("checkbox", { name: /Photos in school albums/ });
    expect(photos).not.toBeChecked();

    await user.click(within(gate).getByRole("button", { name: "Agree and continue" }));
    expect(await within(gate).findByText("Tick this box to continue to the app.")).toBeInTheDocument();
    expect(mocks.accept).not.toHaveBeenCalled();

    await user.click(within(gate).getByRole("checkbox", { name: /I have read the notice/ }));
    await user.click(within(within(gate).getByTestId("gate-child-st2")).getByRole("checkbox", { name: /WhatsApp/ }));
    await user.click(within(gate).getByRole("button", { name: "Agree and continue" }));

    await waitFor(() =>
      expect(mocks.accept).toHaveBeenCalledWith({
        noticeVersion: 2,
        acceptEssential: true,
        choices: [
          { studentId: "st1", photos: false, whatsapp: false },
          { studentId: "st2", photos: false, whatsapp: true },
        ],
      }),
    );
    expect(await screen.findByText("The dashboard")).toBeInTheDocument();
  });

  it("asks again when the notice changed while the parent was reading", async () => {
    const user = userEvent.setup();
    mocks.mine.mockResolvedValue(NEEDS_CONSENT);
    mocks.accept.mockRejectedValue(new ApiError({ status: 409, title: "Notice changed" }));
    renderAs(PARENT, <PrivacyGate>{page}</PrivacyGate>, ["PARENT"]);

    const gate = await screen.findByTestId("privacy-gate");
    await user.click(within(gate).getByRole("checkbox", { name: /I have read the notice/ }));
    await user.click(within(gate).getByRole("button", { name: "Agree and continue" }));
    await waitFor(() => expect(mocks.mine).toHaveBeenCalledTimes(2));
  });

  it("lets a parent reach their privacy page without accepting", async () => {
    mocks.pathname.value = "/app/my-privacy/requests/r1";
    mocks.mine.mockResolvedValue(NEEDS_CONSENT);
    renderAs(PARENT, <PrivacyGate>{page}</PrivacyGate>, ["PARENT"]);
    expect(screen.getByText("The dashboard")).toBeInTheDocument();
    await waitFor(() => expect(mocks.mine).toHaveBeenCalled());
    expect(screen.queryByTestId("privacy-gate")).not.toBeInTheDocument();
  });

  it("shows the page when the check itself fails", async () => {
    mocks.mine.mockRejectedValue(new ApiError({ status: 500, title: "Server error" }));
    renderAs(PARENT, <PrivacyGate>{page}</PrivacyGate>, ["PARENT"]);
    expect(await screen.findByText("The dashboard")).toBeInTheDocument();
  });
});
