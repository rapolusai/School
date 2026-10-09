import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { AuthContext, type AuthContextValue } from "@/lib/auth";
import type { Me } from "@/lib/types";
import { LoginView } from "./login-view";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/login",
}));

const TEACHER: Me = {
  id: "u1",
  name: "Fatima Shaikh",
  email: "fatima@school.edu",
  roles: ["TEACHER"],
  permissions: ["dashboard.view"],
  platformAdmin: false,
  tenant: null,
};

function renderLogin(overrides: Partial<AuthContextValue> = {}, next: string | null = null) {
  const value: AuthContextValue = {
    status: "anonymous",
    me: null,
    accessToken: null,
    login: vi.fn(),
    platformLogin: vi.fn(),
    logout: vi.fn(),
    reloadMe: vi.fn(),
    ...overrides,
  } as AuthContextValue;
  render(
    <AuthContext.Provider value={value}>
      <LoginView next={next} />
    </AuthContext.Provider>,
  );
  return value;
}

async function fillSchoolForm() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("School code"), "sunrise-public");
  await user.type(screen.getByLabelText("Email"), "fatima@school.edu");
  await user.type(screen.getByLabelText("Password"), "wrong-password");
  await user.click(screen.getByRole("button", { name: "Sign in" }));
  return user;
}

beforeEach(() => {
  replace.mockReset();
  window.localStorage.clear();
});

describe("LoginView", () => {
  it("shows the generic message on 401", async () => {
    const login = vi.fn().mockRejectedValue(new ApiError({ status: 401, title: "Unauthorized" }));
    renderLogin({ login });
    await fillSchoolForm();
    expect(login).toHaveBeenCalledWith("sunrise-public", "fatima@school.edu", "wrong-password");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Those details don't match. Check the school code, email and password.",
    );
  });

  it("asks the user to wait on 429", async () => {
    const login = vi.fn().mockRejectedValue(new ApiError({ status: 429, title: "Too Many Requests" }));
    renderLogin({ login });
    await fillSchoolForm();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Too many attempts. Wait a minute and try again.",
    );
  });

  it("goes to the landing page after a successful sign-in", async () => {
    const login = vi.fn().mockResolvedValue(TEACHER);
    renderLogin({ login });
    await fillSchoolForm();
    expect(replace).toHaveBeenCalledWith("/app/dashboard");
  });

  it("returns to a safe ?next= path", async () => {
    const login = vi.fn().mockResolvedValue(TEACHER);
    renderLogin({ login }, "/app/users");
    await fillSchoolForm();
    expect(replace).toHaveBeenCalledWith("/app/users");
  });

  it("switches to the Super Admin form, which has no school code", async () => {
    const platformLogin = vi
      .fn()
      .mockResolvedValue({ ...TEACHER, platformAdmin: true, permissions: ["platform.admin"] });
    renderLogin({ platformLogin });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Super Admin sign in" }));

    expect(screen.getByRole("heading", { name: "Platform sign in" })).toBeInTheDocument();
    expect(screen.queryByLabelText("School code")).not.toBeInTheDocument();

    await user.type(screen.getByLabelText("Email"), "kiran@akshara.app");
    await user.type(screen.getByLabelText("Password"), "platform-password");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(platformLogin).toHaveBeenCalledWith("kiran@akshara.app", "platform-password");
    expect(replace).toHaveBeenCalledWith("/app/platform/schools");
  });

  it("ignores an unsafe ?next= path", async () => {
    const login = vi.fn().mockResolvedValue(TEACHER);
    renderLogin({ login }, "https://evil.example/app");
    await fillSchoolForm();
    expect(replace).toHaveBeenCalledWith("/app/dashboard");
  });

  it("links to the free-trial signup", () => {
    renderLogin();
    expect(screen.getByRole("link", { name: "Start a 14-day free trial" })).toHaveAttribute(
      "href",
      "/signup",
    );
  });
});
