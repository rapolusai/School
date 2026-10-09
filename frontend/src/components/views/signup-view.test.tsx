import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/lib/auth";
import { SignupView, validateSignupStep } from "./signup-view";

const replace = vi.fn();
const { signup } = vi.hoisted(() => ({ signup: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn() }),
  usePathname: () => "/signup",
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, signup } };
});

function renderSignup(login = vi.fn()) {
  const value = {
    status: "anonymous",
    me: null,
    accessToken: null,
    login,
    platformLogin: vi.fn(),
    logout: vi.fn(),
    reloadMe: vi.fn(),
  } as AuthContextValue;
  render(
    <AuthContext.Provider value={value}>
      <SignupView />
    </AuthContext.Provider>,
  );
  return login;
}

beforeEach(() => {
  replace.mockReset();
  signup.mockReset();
});

describe("validateSignupStep", () => {
  const base = {
    schoolName: "Sunrise Public School",
    schoolCode: "sunrise-public-school",
    board: "CBSE" as const,
    city: "Pune",
    adminName: "Asha Rao",
    adminEmail: "asha@school.edu",
    password: "long-enough-1",
  };

  it("accepts complete steps", () => {
    expect(validateSignupStep(1, base)).toEqual({});
    expect(validateSignupStep(2, base)).toEqual({});
  });

  it("flags bad codes, emails and short passwords", () => {
    expect(validateSignupStep(1, { ...base, schoolCode: "1x" })).toEqual({
      schoolCode: "validation.schoolCode",
    });
    expect(validateSignupStep(2, { ...base, adminEmail: "nope", password: "short" })).toEqual({
      adminEmail: "validation.email",
      password: "validation.password",
    });
  });
});

describe("SignupView", () => {
  it("suggests a school code, walks three steps, creates the school and signs in", async () => {
    const user = userEvent.setup();
    signup.mockResolvedValue({ tenantId: "t1", schoolCode: "sunrise-public-school", trialEndsAt: "2026-10-23T00:00:00Z" });
    const login = renderSignup(vi.fn().mockResolvedValue({}));

    expect(screen.getByRole("heading", { name: "Tell us about your school" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("School name"), "Sunrise Public School");
    expect(screen.getByLabelText("School code")).toHaveValue("sunrise-public-school");
    await user.selectOptions(screen.getByLabelText("Board"), "ICSE");
    await user.type(screen.getByLabelText("City"), "Pune");
    await user.click(screen.getByRole("button", { name: "Continue" }));

    expect(screen.getByRole("heading", { name: "Create the admin account" })).toHaveFocus();
    await user.type(screen.getByLabelText("Your name"), "Asha Rao");
    await user.type(screen.getByLabelText("Work email"), "asha@school.edu");
    await user.type(screen.getByLabelText("Password"), "long-enough-1");
    await user.click(screen.getByRole("button", { name: "Continue" }));

    expect(screen.getByRole("heading", { name: "Review and create" })).toBeInTheDocument();
    expect(screen.getByText("sunrise-public-school")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create my school" }));

    expect(signup).toHaveBeenCalledWith({
      schoolName: "Sunrise Public School",
      schoolCode: "sunrise-public-school",
      board: "ICSE",
      city: "Pune",
      adminName: "Asha Rao",
      adminEmail: "asha@school.edu",
      password: "long-enough-1",
    });
    expect(login).toHaveBeenCalledWith("sunrise-public-school", "asha@school.edu", "long-enough-1");
    expect(replace).toHaveBeenCalledWith("/app/dashboard");
  });

  it("blocks Continue with inline errors", async () => {
    const user = userEvent.setup();
    renderSignup();
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(screen.getAllByText("Fill in this field.").length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: "Tell us about your school" })).toBeInTheDocument();
  });
});
