import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import { AuthContext, type AuthContextValue } from "@/lib/auth";
import type { AuthResponse, Me } from "@/lib/types";
import { ChangePasswordView } from "./change-password-view";

const TEACHER = {
  id: "u1",
  name: "Fatima Shaikh",
  email: "fatima@school.edu",
  roles: ["TEACHER"],
  permissions: ["dashboard.view"],
  platformAdmin: false,
  tenant: { id: "t1", name: "Sunrise Public School", code: "sunrise-public" },
} as unknown as Me;

function renderView(me: Me = TEACHER) {
  const value = {
    status: "authenticated",
    me,
    accessToken: "token",
    login: vi.fn(),
    platformLogin: vi.fn(),
    logout: vi.fn(),
    reloadMe: vi.fn(),
  } as unknown as AuthContextValue;
  render(
    <AuthContext.Provider value={value}>
      <ChangePasswordView />
    </AuthContext.Provider>,
  );
}

async function fill(current: string, next: string, repeat = next) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Current password"), current);
  await user.type(screen.getByLabelText("New password"), next);
  await user.type(screen.getByLabelText("Type the new password again"), repeat);
  await user.click(screen.getByRole("button", { name: "Change password" }));
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ChangePasswordView", () => {
  it("changes the password and says other devices were signed out", async () => {
    const change = vi
      .spyOn(api, "changePassword")
      .mockResolvedValue({ accessToken: "new", expiresIn: 900, user: TEACHER } as AuthResponse);
    renderView();
    await fill("Old-password-123", "Brand-new-password-1");

    expect(change).toHaveBeenCalledWith({ currentPassword: "Old-password-123", newPassword: "Brand-new-password-1" });
    expect(await screen.findByTestId("password-changed")).toHaveTextContent(
      "Password changed. Other devices have been signed out.",
    );
  });

  it("checks a short or mistyped new password before sending", async () => {
    const change = vi.spyOn(api, "changePassword");
    renderView();
    await fill("Old-password-123", "short");
    expect(screen.getByText("Use at least 10 characters.", { selector: ".field-error" })).toBeInTheDocument();
    expect(change).not.toHaveBeenCalled();
  });

  it("marks a wrong current password on its field", async () => {
    vi.spyOn(api, "changePassword").mockRejectedValue(
      new ApiError({ status: 400, title: "Bad request", errors: { currentPassword: "wrong" } }),
    );
    renderView();
    await fill("Wrong-password-1", "Brand-new-password-1");
    expect(await screen.findByText("That isn't your current password.")).toBeInTheDocument();
  });

  it("explains that Super Admins change their password elsewhere", () => {
    renderView({ ...TEACHER, platformAdmin: true, tenant: null } as Me);
    expect(screen.getByText("Super Admin passwords are changed by the platform team, not here.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Current password")).not.toBeInTheDocument();
  });
});
