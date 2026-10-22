import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { GatePassView, GateScreen } from "@/lib/types";
import { renderAs } from "@/test/render";
import { GateView, otpProblem, WRONG_OTP_TYPE } from "./gate-view";

const m = vi.hoisted(() => ({
  gate: vi.fn(),
  release: vi.fn(),
  pickupPersons: vi.fn(),
  staff: vi.fn(),
}));

vi.mock("@/lib/frontoffice-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/frontoffice-api")>();
  return { ...actual, frontOfficeApi: { ...actual.frontOfficeApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/frontoffice/gate",
}));

const GUARD = ["dashboard.view", "gate.pass"];

function pass(overrides: Partial<GatePassView> = {}): GatePassView {
  return {
    id: "p1",
    studentId: "st1",
    studentName: "Asha Rao",
    admissionNo: "AKS/2026/001",
    className: "Class 5 A",
    source: "PARENT_APP",
    requestedByName: "Lata Rao",
    pickupAt: "2026-10-12T08:30:00Z",
    reason: "Dentist",
    collectorKind: "OTHER",
    collectorName: "Kamla Devi",
    collectorPhoneMasked: "98765•••09",
    status: "APPROVED",
    otpExpiresAt: "2026-10-12T12:30:00Z",
    attemptsLeft: 5,
    guardianName: "Lata Rao",
    guardianPhoneMasked: "98765•••01",
    approvedByName: "Olivia Office",
    approvedAt: "2026-10-12T07:00:00Z",
    decisionNote: null,
    releasedByName: null,
    releasedAt: null,
    collectedByName: null,
    createdAt: "2026-10-12T06:00:00Z",
    canCancel: false,
    canDecide: false,
    canRelease: true,
    ...overrides,
  };
}

const SCREEN: GateScreen = { date: "2026-10-12", passes: [pass()], inside: [], expected: [] };

beforeEach(() => {
  vi.clearAllMocks();
  m.gate.mockResolvedValue(SCREEN);
  m.staff.mockResolvedValue([]);
  m.pickupPersons.mockResolvedValue({
    studentId: "st1",
    studentName: "Asha Rao",
    className: "Class 5 A",
    persons: [{ id: "pp1", studentId: "st1", studentName: "Asha Rao", name: "Kamla Devi", relation: "Grandmother", phoneMasked: "98765•••09", photoFileId: null, addedByName: "Lata Rao", createdAt: "2026-10-01T00:00:00Z" }],
  });
});

describe("otpProblem", () => {
  it("wants six digits, spaces allowed", () => {
    expect(otpProblem("123456")).toBe(false);
    expect(otpProblem("123 456")).toBe(false);
    expect(otpProblem("12345")).toBe(true);
    expect(otpProblem("12345a")).toBe(true);
    expect(otpProblem("")).toBe(true);
  });
});

describe("GateView", () => {
  it("shows the approved pass with the collector and the family's pickup persons", async () => {
    const user = userEvent.setup();
    renderAs(GUARD, <GateView />, ["SECURITY"]);
    const row = await screen.findByTestId("gate-pass");
    expect(row).toHaveTextContent("Asha Rao");
    expect(row).toHaveTextContent("Kamla Devi · 98765•••09");
    expect(row).toHaveTextContent("5 tries left");
    expect(within(row).queryByText(/[0-9]{6}/)).not.toBeInTheDocument();
    await user.click(within(row).getByRole("button", { name: "Who may collect this child" }));
    const persons = await screen.findByTestId("gate-persons");
    expect(persons).toHaveTextContent("Kamla Devi");
    expect(persons).toHaveTextContent("Grandmother · 98765•••09");
    expect(m.pickupPersons).toHaveBeenCalledWith("st1");
  });

  it("refuses a short OTP without calling the API, then releases with a good one", async () => {
    m.release.mockResolvedValue(pass({ status: "RELEASED", canRelease: false }));
    const user = userEvent.setup();
    renderAs(GUARD, <GateView />, ["SECURITY"]);
    const form = within(await screen.findByTestId("release-form"));
    expect(form.getByLabelText(/Taken by/)).toHaveValue("Kamla Devi");

    await user.type(form.getByLabelText(/OTP from the parent/), "1234");
    await user.click(form.getByTestId("release-button"));
    expect(form.getByText("Type the 6 digit OTP.")).toBeInTheDocument();
    expect(m.release).not.toHaveBeenCalled();

    await user.clear(form.getByLabelText(/OTP from the parent/));
    await user.type(form.getByLabelText(/OTP from the parent/), "482 913");
    await user.click(form.getByTestId("release-button"));
    await waitFor(() => expect(m.release).toHaveBeenCalledWith("p1", { otp: "482913", collectedByName: "Kamla Devi" }));
    expect(await screen.findByText("Asha Rao released.")).toBeInTheDocument();
    expect(m.gate).toHaveBeenCalledTimes(2);
  });

  it("shows the API's wrong-OTP message on the field and the tries left", async () => {
    m.release.mockRejectedValue(new ApiError({ status: 409, title: "Wrong OTP", detail: "Wrong OTP; 4 tries left.", type: WRONG_OTP_TYPE }));
    const user = userEvent.setup();
    renderAs(GUARD, <GateView />, ["SECURITY"]);
    const form = within(await screen.findByTestId("release-form"));
    await user.type(form.getByLabelText(/OTP from the parent/), "000000");
    await user.click(form.getByTestId("release-button"));
    expect(await form.findByText("Wrong OTP; 4 tries left.")).toBeInTheDocument();
    expect(form.getByLabelText(/OTP from the parent/)).toHaveAttribute("aria-invalid", "true");
  });

  it("tells the guard when nothing is due and shows visitors inside", async () => {
    m.gate.mockResolvedValue({
      ...SCREEN,
      passes: [],
      inside: [
        {
          id: "v1",
          name: "Ravi Courier",
          phoneMasked: "98765•••02",
          phone: null,
          purpose: "DELIVERY",
          purposeNote: null,
          toMeetUserId: null,
          toMeetName: null,
          peopleCount: 1,
          idType: "NONE",
          badgeNo: "B-12",
          photoFileId: null,
          status: "IN",
          expectedOn: null,
          checkedInAt: "2026-10-12T04:30:00Z",
          checkedOutAt: null,
          addedByName: null,
          checkedInByName: "Sam Security",
          checkedOutByName: null,
          blocked: null,
        },
      ],
    });
    renderAs(GUARD, <GateView />, ["SECURITY"]);
    expect(await screen.findByText("No approved passes right now.")).toBeInTheDocument();
    const inside = screen.getByTestId("gate-inside");
    expect(within(inside).getByText("Ravi Courier")).toBeInTheDocument();
    expect(within(inside).getByTestId("visitor-check-out")).toBeInTheDocument();
  });
});
