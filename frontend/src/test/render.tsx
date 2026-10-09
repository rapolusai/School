import { render } from "@testing-library/react";
import { vi } from "vitest";
import { ToastProvider } from "@/components/ui/toast";
import { AuthContext, type AuthContextValue } from "@/lib/auth";
import type { Me } from "@/lib/types";

/** Test-only: a signed-in school user with these permissions and roles. */
export function meWith(permissions: string[], roles: string[] = ["SCHOOL_ADMIN"]): Me {
  return {
    id: "u1",
    name: "Priya Nair",
    email: "admin@school.test",
    roles,
    permissions,
    platformAdmin: false,
    tenant: null,
  };
}

/** Renders `ui` inside the auth context and toast provider the app shell provides. */
export function renderAs(permissions: string[], ui: React.ReactNode, roles?: string[]) {
  const value = {
    status: "authenticated",
    me: meWith(permissions, roles),
    accessToken: "t",
    login: vi.fn(),
    platformLogin: vi.fn(),
    logout: vi.fn(),
    reloadMe: vi.fn(),
  } as AuthContextValue;
  return render(
    <AuthContext.Provider value={value}>
      <ToastProvider>{ui}</ToastProvider>
    </AuthContext.Provider>,
  );
}

export const ADMIN_PERMISSIONS = [
  "dashboard.view",
  "users.read",
  "users.manage",
  "roles.read",
  "audit.read",
  "settings.manage",
  "academics.read",
  "students.read",
  "students.manage",
  "child.view",
];

export const TEACHER_PERMISSIONS = ["dashboard.view", "students.read", "academics.read"];
