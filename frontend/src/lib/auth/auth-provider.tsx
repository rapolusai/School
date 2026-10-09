"use client";

import { createContext, useContext, useEffect, useMemo, useSyncExternalStore } from "react";
import type { Me } from "../types";
import {
  bootstrapSession,
  getServerSessionState,
  getSessionState,
  login,
  logout,
  platformLogin,
  reloadMe,
  subscribeSession,
  type SessionState,
} from "./session-store";

export type AuthContextValue = SessionState & {
  login: (schoolCode: string, email: string, password: string) => Promise<Me>;
  platformLogin: (email: string, password: string) => Promise<Me>;
  logout: () => Promise<void>;
  reloadMe: () => Promise<Me | null>;
};

export const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Holds the signed-in user (`me`) and access token. On first mount it calls
 * POST /api/auth/refresh to restore a session from the httpOnly refresh cookie.
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const session = useSyncExternalStore(subscribeSession, getSessionState, getServerSessionState);

  useEffect(() => {
    bootstrapSession();
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ ...session, login, platformLogin, logout, reloadMe }),
    [session],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}
