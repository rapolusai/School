import { afterEach, describe, expect, it, vi } from "vitest";
import {
  dismissInstallHint,
  INSTALL_HINT_KEY,
  installHintDismissed,
  registerServiceWorker,
  shouldRegisterServiceWorker,
} from "./pwa";

const PROD = { production: true, supported: true, secureContext: true };

describe("service worker registration", () => {
  it("registers only in a production build of a secure browser that supports it", () => {
    expect(shouldRegisterServiceWorker(PROD)).toBe(true);
    expect(shouldRegisterServiceWorker({ ...PROD, production: false })).toBe(false);
    expect(shouldRegisterServiceWorker({ ...PROD, supported: false })).toBe(false);
    expect(shouldRegisterServiceWorker({ ...PROD, secureContext: false })).toBe(false);
  });

  it("calls the browser in production and never in development", async () => {
    const register = vi.fn().mockResolvedValue({});
    expect(await registerServiceWorker({ ...PROD, production: false }, register)).toBe(false);
    expect(register).not.toHaveBeenCalled();
    expect(await registerServiceWorker(PROD, register)).toBe(true);
    expect(register).toHaveBeenCalledTimes(1);
  });

  it("never throws when the browser refuses", async () => {
    const register = vi.fn().mockRejectedValue(new Error("SecurityError"));
    await expect(registerServiceWorker(PROD, register)).resolves.toBe(false);
  });
});

describe("install hint", () => {
  afterEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it("remembers that it was dismissed on this device", () => {
    expect(installHintDismissed()).toBe(false);
    dismissInstallHint();
    expect(window.localStorage.getItem(INSTALL_HINT_KEY)).toBe("1");
    expect(installHintDismissed()).toBe(true);
  });

  it("copes with blocked storage", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => dismissInstallHint()).not.toThrow();
    expect(installHintDismissed()).toBe(false);
  });
});
