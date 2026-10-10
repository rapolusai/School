/*
 * The installable app (PWA): a hand-written service worker (public/sw.js) that caches only the
 * static app shell, never API responses or anyone's data. See docs/adr/0005-installable-parent-app.md.
 */

export const SERVICE_WORKER_URL = "/sw.js";

/** Browser storage key: the install hint was dismissed on this device. */
export const INSTALL_HINT_KEY = "akshara.installHint.dismissed";

/**
 * Register the service worker only in production builds, in a secure context (HTTPS or localhost)
 * of a browser that supports it. In development it would cache stale bundles. Pure.
 */
export function shouldRegisterServiceWorker(env: {
  production: boolean;
  supported: boolean;
  secureContext: boolean;
}): boolean {
  return env.production && env.supported && env.secureContext;
}

/** Registers the worker when allowed. Never throws: an app without it simply is not installable. */
export async function registerServiceWorker(
  env: { production: boolean; supported: boolean; secureContext: boolean },
  register: () => Promise<unknown>,
): Promise<boolean> {
  if (!shouldRegisterServiceWorker(env)) return false;
  try {
    await register();
    return true;
  } catch {
    return false;
  }
}

/** True when the app already runs installed (from the home screen). */
export function isStandalone(): boolean {
  try {
    return (
      window.matchMedia?.("(display-mode: standalone)").matches === true ||
      (navigator as Navigator & { standalone?: boolean }).standalone === true
    );
  } catch {
    return false;
  }
}

/** Phones only: the hint is not worth the space on a laptop. */
export function isPhoneWidth(): boolean {
  try {
    return window.matchMedia?.("(max-width: 640px)").matches === true;
  } catch {
    return false;
  }
}

export function installHintDismissed(): boolean {
  try {
    return window.localStorage.getItem(INSTALL_HINT_KEY) === "1";
  } catch {
    return false;
  }
}

export function dismissInstallHint(): void {
  try {
    window.localStorage.setItem(INSTALL_HINT_KEY, "1");
  } catch {
    // Blocked storage: the hint comes back next visit, which is harmless.
  }
}
