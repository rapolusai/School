import { api, onSessionChange, refreshSession, setAccessToken } from "../api";
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
  setAccessToken(auth.accessToken);
  setState({ status: "authenticated", me: auth.user, accessToken: auth.accessToken });
}

function clearAuth() {
  setAccessToken(null);
  setState(ANONYMOUS);
}

// Silent refreshes (after a 401) update the user; a failed refresh ends the session.
onSessionChange((auth) => (auth ? applyAuth(auth) : clearAuth()));

let bootstrapped = false;

/** Restore the session from the refresh cookie once per page load. Safe to call repeatedly. */
export function bootstrapSession(): void {
  if (bootstrapped) return;
  bootstrapped = true;
  void refreshSession().then((auth) => {
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
  state = LOADING;
}
