import { api, onSessionChange, refreshSession, setAccessToken, setSessionKind } from "../api";
import { applyAccountLang, hasDeviceLang, isLang, type Lang } from "../i18n";
import type { AuthResponse, Me } from "../types";

export type SessionState =
  | { status: "loading"; me: null; accessToken: null }
  | { status: "anonymous"; me: null; accessToken: null }
  | { status: "authenticated"; me: Me; accessToken: string };

const LOADING: SessionState = { status: "loading", me: null, accessToken: null };
const ANONYMOUS: SessionState = { status: "anonymous", me: null, accessToken: null };

let state: SessionState = LOADING;
const listeners = new Set<() => void>();

function setState(next: SessionState) {
  state = next;
  for (const listener of listeners) listener();
}

export function getSessionState(): SessionState {
  return state;
}

/** The server never knows the session: it always renders the loading state. */
export function getServerSessionState(): SessionState {
  return LOADING;
}

export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function applyAuth(auth: AuthResponse) {
  const signingIn = state.status !== "authenticated" || state.me.id !== auth.user.id;
  setAccessToken(auth.accessToken);
  setSessionKind(auth.user.platformAdmin ? "platform" : "school");
  setState({ status: "authenticated", me: auth.user, accessToken: auth.accessToken });
  if (signingIn) void loadAccountLanguage(auth.user);
}

/**
 * On sign-in, show the app in the language the person saved on their account, unless they already picked one on
 * this device (the device's choice wins). Failures are ignored: the app simply stays in the current language.
 */
async function loadAccountLanguage(me: Me): Promise<void> {
  if (me.platformAdmin || !me.tenant || hasDeviceLang()) return;
  try {
    const { language } = await api.myPreferences();
    if (isLang(language)) applyAccountLang(language);
  } catch {
    // Offline or not available: keep the current language.
  }
}

/** Saves a language the signed-in school user picked, so their other devices and messages follow it. */
export async function saveAccountLanguage(lang: Lang): Promise<void> {
  if (state.status !== "authenticated" || state.me.platformAdmin || !state.me.tenant) return;
  try {
    await api.saveMyPreferences({ language: lang });
  } catch {
    // The device keeps the choice; the account copy is a convenience.
  }
}

function clearAuth() {
  setAccessToken(null);
  setSessionKind(null);
  setState(ANONYMOUS);
}

// Silent refreshes (after a 401) update the user; a failed refresh ends the session.
onSessionChange((auth) => (auth ? applyAuth(auth) : clearAuth()));

let bootstrapped = false;

/**
 * Restore the session from the refresh cookie once per page load (a school user's or a Super Admin's, whichever the
 * browser holds). Safe to call repeatedly.
 */
export function bootstrapSession(): void {
  if (bootstrapped) return;
  bootstrapped = true;
  void refreshSession({ restore: true }).then((auth) => {
    if (!auth && state.status === "loading") clearAuth();
  });
}

export async function login(schoolCode: string, email: string, password: string): Promise<Me> {
  const auth = await api.login({ schoolCode: schoolCode.trim().toLowerCase(), email: email.trim(), password });
  applyAuth(auth);
  return auth.user;
}

export async function platformLogin(email: string, password: string): Promise<Me> {
  const auth = await api.platformLogin({ email: email.trim(), password });
  applyAuth(auth);
  return auth.user;
}

export async function logout(): Promise<void> {
  try {
    await api.logout();
  } catch {
    // Already signed out on the server, or offline: clear the local session regardless.
  }
  clearAuth();
}

/** Re-read /api/me (e.g. after the tenant changes) while keeping the current token. */
export async function reloadMe(): Promise<Me | null> {
  if (state.status !== "authenticated") return null;
  const me = await api.me();
  if (state.status === "authenticated") setState({ ...state, me });
  return me;
}

/** Test helper: reset module state between tests. */
export function __resetSessionForTests(): void {
  bootstrapped = false;
  setAccessToken(null);
  setSessionKind(null);
  state = LOADING;
}
