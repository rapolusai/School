import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RequirePermission } from "@/components/access";
import { ToastProvider } from "@/components/ui/toast";
import { AuthContext, type AuthContextValue } from "@/lib/auth";
import type { Me, Role, UserSummary } from "@/lib/types";
import { UsersView } from "./users-view";

const { listUsers, listRoles, createUser } = vi.hoisted(() => ({
  listUsers: vi.fn(),
  listRoles: vi.fn(),
  createUser: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listUsers, listRoles, createUser } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/users",
}));

const USERS: UserSummary[] = [
  {
    id: "1",
    name: "Asha Rao",
    email: "asha@school.edu",
    roles: ["SCHOOL_ADMIN"],
    status: "ACTIVE",
    lastLoginAt: "2026-10-09T06:02:00Z",
    createdAt: "2026-10-01T00:00:00Z",
  },
  {
    id: "2",
    name: "Fatima Shaikh",
    email: "fatima@school.edu",
    roles: ["TEACHER"],
    status: "ACTIVE",
    lastLoginAt: null,
    createdAt: "2026-10-02T00:00:00Z",
  },
];

const ROLES: Role[] = [
  { code: "SCHOOL_ADMIN", name: "School Admin", permissions: ["users.manage"] },
  { code: "TEACHER", name: "Teacher", permissions: ["dashboard.view"] },
];

function me(permissions: string[]): Me {
  return {
    id: "1",
    name: "Asha Rao",
    email: "asha@school.edu",
    roles: ["SCHOOL_ADMIN"],
    permissions,
    platformAdmin: false,
    tenant: null,
  };
}

function renderWith(permissions: string[], ui: React.ReactNode = <UsersView />) {
  const value = {
    status: "authenticated",
    me: me(permissions),
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

beforeEach(() => {
  listUsers.mockResolvedValue(USERS);
  listRoles.mockResolvedValue(ROLES);
  createUser.mockReset();
});

describe("UsersView", () => {
  it("lists users with roles, status and last sign-in", async () => {
    renderWith(["users.read", "users.manage", "roles.read"]);
    const table = await screen.findByTestId("users-table");
    expect(within(table).getByText("Asha Rao")).toBeInTheDocument();
    expect(within(table).getByText("fatima@school.edu")).toBeInTheDocument();
    expect(within(table).getAllByText("Teacher").length).toBeGreaterThan(0);
    expect(within(table).getByText("Never")).toBeInTheDocument();
    expect(within(table).getByText("9 Oct 2026, 11:32 am")).toBeInTheDocument();
  });

  it("filters by search text", async () => {
    const user = userEvent.setup();
    renderWith(["users.read"]);
    await screen.findByTestId("users-table");
    await user.type(screen.getByRole("searchbox", { name: /search/i }), "fatima");
    expect(screen.queryByText("Asha Rao", { selector: "b" })).not.toBeInTheDocument();
    expect(screen.getByText("Showing 1 of 2")).toBeInTheDocument();
  });

  it("shows Add user only with users.manage", async () => {
    const { unmount } = renderWith(["users.read"]);
    await screen.findByTestId("users-table");
    expect(screen.queryByRole("button", { name: "Add user" })).not.toBeInTheDocument();
    unmount();

    renderWith(["users.read", "users.manage", "roles.read"]);
    await screen.findByTestId("users-table");
    expect(screen.getByRole("button", { name: "Add user" })).toBeInTheDocument();
  });

  it("adds a user with the chosen roles", async () => {
    const user = userEvent.setup();
    createUser.mockResolvedValue({ ...USERS[1], id: "3", name: "Ravi Kumar" });
    renderWith(["users.read", "users.manage", "roles.read"]);
    await screen.findByTestId("users-table");

    await user.click(screen.getByRole("button", { name: "Add user" }));
    const dialog = screen.getByRole("dialog", { name: "Add a user" });
    await user.type(within(dialog).getByLabelText("Full name"), "Ravi Kumar");
    await user.type(within(dialog).getByLabelText("Email"), "ravi@school.edu");
    await user.type(within(dialog).getByLabelText("Temporary password"), "temporary-123");
    await user.click(within(dialog).getByRole("checkbox", { name: "Teacher" }));
    await user.click(within(dialog).getByRole("button", { name: "Add user" }));

    expect(createUser).toHaveBeenCalledWith({
      name: "Ravi Kumar",
      email: "ravi@school.edu",
      password: "temporary-123",
      roles: ["TEACHER"],
    });
    expect(await screen.findByText("Ravi Kumar can now sign in.")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("validates the form before calling the API", async () => {
    const user = userEvent.setup();
    renderWith(["users.read", "users.manage", "roles.read"]);
    await screen.findByTestId("users-table");
    await user.click(screen.getByRole("button", { name: "Add user" }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Add user" }));
    expect(createUser).not.toHaveBeenCalled();
    expect(within(dialog).getByText("Use at least 10 characters.")).toBeInTheDocument();
    expect(within(dialog).getByText("Pick at least one role.")).toBeInTheDocument();
  });
});

describe("RequirePermission", () => {
  it("shows the access-denied state without the permission", () => {
    renderWith(
      ["dashboard.view"],
      <RequirePermission permission="users.read">
        <p>secret</p>
      </RequirePermission>,
    );
    expect(screen.getByRole("heading", { name: "You don't have access to this page" })).toBeInTheDocument();
    expect(screen.queryByText("secret")).not.toBeInTheDocument();
  });
});
