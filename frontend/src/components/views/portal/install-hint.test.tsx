import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ServiceWorkerRegistration } from "@/components/pwa/service-worker-registration";
import { INSTALL_HINT_KEY } from "@/lib/pwa";
import { renderAs } from "@/test/render";
import { InstallHint } from "./install-hint";

/** jsdom has no matchMedia: answer the phone-width and installed-app queries. */
function screenLike({ phone, installed }: { phone: boolean; installed: boolean }) {
  window.matchMedia = vi.fn((query: string) => ({
    matches: query.includes("max-width") ? phone : query.includes("standalone") ? installed : false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

afterEach(() => {
  window.localStorage.clear();
  // @ts-expect-error -- jsdom has no matchMedia; remove the stub again.
  delete window.matchMedia;
});

describe("InstallHint", () => {
  it("tells a phone user how to add the app, until dismissed", async () => {
    const user = userEvent.setup();
    screenLike({ phone: true, installed: false });
    renderAs(["dashboard.view"], <InstallHint />, ["PARENT"]);
    expect(screen.getByText("Add Akshara to your home screen")).toBeInTheDocument();
    expect(screen.getByText(/Add to Home screen/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Hide this tip" }));
    expect(screen.queryByTestId("install-hint")).not.toBeInTheDocument();
    expect(window.localStorage.getItem(INSTALL_HINT_KEY)).toBe("1");
  });

  it("offers the browser's own install prompt when there is one", async () => {
    const user = userEvent.setup();
    screenLike({ phone: true, installed: false });
    renderAs(["dashboard.view"], <InstallHint />, ["PARENT"]);
    const prompt = vi.fn().mockResolvedValue(undefined);
    const event = Object.assign(new Event("beforeinstallprompt", { cancelable: true }), {
      prompt,
      userChoice: Promise.resolve({ outcome: "accepted" }),
    });
    act(() => {
      window.dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(true);
    await user.click(await screen.findByRole("button", { name: "Install app" }));
    expect(prompt).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.queryByTestId("install-hint")).not.toBeInTheDocument());
  });

  it("stays away on a laptop, once installed or once dismissed", () => {
    screenLike({ phone: false, installed: false });
    const { unmount } = renderAs(["dashboard.view"], <InstallHint />, ["PARENT"]);
    expect(screen.queryByTestId("install-hint")).not.toBeInTheDocument();
    unmount();
    screenLike({ phone: true, installed: true });
    const second = renderAs(["dashboard.view"], <InstallHint />, ["PARENT"]);
    expect(screen.queryByTestId("install-hint")).not.toBeInTheDocument();
    second.unmount();
    screenLike({ phone: true, installed: false });
    window.localStorage.setItem(INSTALL_HINT_KEY, "1");
    renderAs(["dashboard.view"], <InstallHint />, ["PARENT"]);
    expect(screen.queryByTestId("install-hint")).not.toBeInTheDocument();
  });
});

describe("ServiceWorkerRegistration", () => {
  it("does not register the worker outside a production build", () => {
    const register = vi.fn().mockResolvedValue({});
    Object.defineProperty(navigator, "serviceWorker", { value: { register }, configurable: true });
    render(<ServiceWorkerRegistration />);
    expect(register).not.toHaveBeenCalled();
    // @ts-expect-error -- remove the stub again.
    delete navigator.serviceWorker;
  });
});
