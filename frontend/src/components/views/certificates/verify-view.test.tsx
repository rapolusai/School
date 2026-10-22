import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { renderAs } from "@/test/render";
import { VerifyView } from "./verify-view";

const m = vi.hoisted(() => ({ verify: vi.fn() }));

vi.mock("@/lib/certificates-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/certificates-api")>();
  return { ...actual, certificatesApi: { ...actual.certificatesApi, verify: m.verify } };
});

afterEach(() => vi.clearAllMocks());

describe("VerifyView", () => {
  it("shows a genuine certificate with only the school, type, serial, first name and date", async () => {
    m.verify.mockResolvedValue({ schoolName: "Akshara Demo School", type: "BONAFIDE", title: "Bonafide Certificate", serialNo: "BC/2026-27/0001", firstName: "Arjun", issuedOn: "2026-10-02", valid: true });
    renderAs([], <VerifyView token="tok1" />);
    const result = await screen.findByTestId("verify-result");
    expect(result).toHaveAttribute("data-valid", "true");
    expect(screen.getByTestId("verify-status")).toHaveTextContent("Genuine certificate");
    expect(screen.getByTestId("verify-serial")).toHaveTextContent("BC/2026-27/0001");
    expect(result).toHaveTextContent("Arjun");
    expect(result).not.toHaveTextContent("Sharma");
    expect(m.verify).toHaveBeenCalledWith("tok1");
  });

  it("says when a certificate is cancelled", async () => {
    m.verify.mockResolvedValue({ schoolName: "Akshara Demo School", type: "TRANSFER", title: "Transfer Certificate", serialNo: "TC/2026-27/0001", firstName: "Laila", issuedOn: "2026-10-02", valid: false });
    renderAs([], <VerifyView token="tok2" />);
    expect(await screen.findByTestId("verify-status")).toHaveTextContent("Cancelled certificate");
    expect(screen.getByTestId("verify-result")).toHaveAttribute("data-valid", "false");
  });

  it("explains an unknown code and a rate limit", async () => {
    m.verify.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    const { unmount } = renderAs([], <VerifyView token="nope" />);
    expect(await screen.findByTestId("verify-unavailable")).toHaveTextContent("No such certificate");
    unmount();
    m.verify.mockRejectedValue(new ApiError({ status: 429, title: "Too many" }));
    renderAs([], <VerifyView token="again" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many checks");
  });
});
