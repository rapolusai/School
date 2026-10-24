import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import { ForgotPasswordView } from "./forgot-password-view";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/forgot-password",
}));

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function askForCode(school = "sunrise-public") {
  const user = userEvent.setup();
  const field = screen.getByLabelText("School code");
  await user.clear(field);
  await user.type(field, school);
  await user.type(screen.getByLabelText("Email"), "fatima@school.edu ");
  await user.click(screen.getByRole("button", { name: "Email me a code" }));
  return user;
}

describe("ForgotPasswordView", () => {
  it("asks for a code and moves on with the same message whether or not the account exists", async () => {
    const request = vi.spyOn(api, "requestPasswordReset").mockResolvedValue({ message: "ok" });
    render(<ForgotPasswordView />);
    await askForCode("Sunrise-Public");

    expect(request).toHaveBeenCalledWith({ schoolCode: "sunrise-public", email: "fatima@school.edu" });
    expect(await screen.findByText(/a 6-digit code is on its way to fatima@school.edu/)).toBeInTheDocument();
    expect(screen.getByLabelText("6-digit code")).toBeInTheDocument();
  });

  it("checks the code and both passwords before sending", async () => {
    vi.spyOn(api, "requestPasswordReset").mockResolvedValue({ message: "ok" });
    const confirm = vi.spyOn(api, "confirmPasswordReset").mockResolvedValue(undefined);
    render(<ForgotPasswordView school="sunrise-public" />);
    const user = await askForCode();

    await user.type(await screen.findByLabelText("6-digit code"), "12ab");
    await user.type(screen.getByLabelText("New password"), "Brand-new-password-1");
    await user.type(screen.getByLabelText("Type the new password again"), "Brand-new-password-2");
    await user.click(screen.getByRole("button", { name: "Set password" }));

    expect(screen.getByText("Enter the 6 digits from the email.")).toBeInTheDocument();
    expect(screen.getByText("The two passwords don't match.")).toBeInTheDocument();
    expect(confirm).not.toHaveBeenCalled();
  });

  it("sets the password with the code and offers to sign in", async () => {
    vi.spyOn(api, "requestPasswordReset").mockResolvedValue({ message: "ok" });
    const confirm = vi.spyOn(api, "confirmPasswordReset").mockResolvedValue(undefined);
    render(<ForgotPasswordView />);
    const user = await askForCode();

    await user.type(await screen.findByLabelText("6-digit code"), " 042817 ");
    await user.type(screen.getByLabelText("New password"), "Brand-new-password-1");
    await user.type(screen.getByLabelText("Type the new password again"), "Brand-new-password-1");
    await user.click(screen.getByRole("button", { name: "Set password" }));

    expect(confirm).toHaveBeenCalledWith({
      schoolCode: "sunrise-public",
      email: "fatima@school.edu",
      code: "042817",
      newPassword: "Brand-new-password-1",
    });
    expect(await screen.findByRole("heading", { name: "Password set" })).toBeInTheDocument();
    expect(screen.getByTestId("reset-sign-in")).toHaveAttribute("href", "/login?school=sunrise-public");
  });

  it("explains a wrong or expired code", async () => {
    vi.spyOn(api, "requestPasswordReset").mockResolvedValue({ message: "ok" });
    vi.spyOn(api, "confirmPasswordReset").mockRejectedValue(
      new ApiError({ status: 400, title: "Bad request", errors: { code: "That code is wrong." } }),
    );
    render(<ForgotPasswordView />);
    const user = await askForCode();

    await user.type(await screen.findByLabelText("6-digit code"), "000000");
    await user.type(screen.getByLabelText("New password"), "Brand-new-password-1");
    await user.type(screen.getByLabelText("Type the new password again"), "Brand-new-password-1");
    await user.click(screen.getByRole("button", { name: "Set password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That code is wrong or has expired. Check the latest email, or ask for a new code.",
    );
  });

  it("asks the user to wait when there were too many requests", async () => {
    vi.spyOn(api, "requestPasswordReset").mockRejectedValue(new ApiError({ status: 429, title: "Too many" }));
    render(<ForgotPasswordView />);
    await askForCode();
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many attempts. Wait a minute and try again.");
  });
});
