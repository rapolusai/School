import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Child, FamilyPickup, GatePassView } from "@/lib/types";
import { renderAs } from "@/test/render";
import { FamilyPickupView, personProblems } from "./family-pickup";

const m = vi.hoisted(() => ({
  myChildren: vi.fn(),
  childPickup: vi.fn(),
  requestPickup: vi.fn(),
  cancelPickup: vi.fn(),
  addPerson: vi.fn(),
  removePerson: vi.fn(),
  personPhoto: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren: m.myChildren } };
});
vi.mock("@/lib/frontoffice-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/frontoffice-api")>();
  return { ...actual, frontOfficeApi: { ...actual.frontOfficeApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/family/pickup",
}));

const PARENT = ["dashboard.view", "child.view", "notices.read"];

const ARJUN: Child = {
  id: "st1",
  fullName: "Arjun Sharma",
  admissionNo: "AKS/2026/001",
  status: "ACTIVE",
  className: "Class 5",
  sectionName: "A",
  rollNo: 1,
  classTeacherName: "Ravi Kumar",
  academicYearName: "2026-27",
};

function pass(overrides: Partial<GatePassView> = {}): GatePassView {
  return {
    id: "p1",
    studentId: "st1",
    studentName: "Arjun Sharma",
    admissionNo: "AKS/2026/001",
    className: "Class 5 A",
    source: "PARENT_APP",
    requestedByName: "Anitha Sharma",
    pickupAt: "2026-10-12T08:30:00Z",
    reason: "Dentist",
    collectorKind: "SELF",
    collectorName: null,
    collectorPhoneMasked: "",
    status: "REQUESTED",
    otpExpiresAt: null,
    attemptsLeft: 5,
    guardianName: "Anitha Sharma",
    guardianPhoneMasked: "98765•••01",
    approvedByName: null,
    approvedAt: null,
    decisionNote: null,
    releasedByName: null,
    releasedAt: null,
    collectedByName: null,
    createdAt: "2026-10-12T06:00:00Z",
    canCancel: true,
    canDecide: false,
    canRelease: false,
    ...overrides,
  };
}

function pickup(overrides: Partial<FamilyPickup> = {}): FamilyPickup {
  return { studentId: "st1", studentName: "Arjun Sharma", className: "Class 5 A", canRequest: true, passes: [], persons: [], ...overrides };
}

beforeEach(() => {
  vi.clearAllMocks();
  window.localStorage.clear();
  m.myChildren.mockResolvedValue([ARJUN]);
});

describe("personProblems", () => {
  it("wants a name, a relation and a 10 digit phone", () => {
    expect(personProblems({ name: "Kamla Devi", relation: "Grandmother", phone: "9876500009" })).toEqual({});
    expect(personProblems({ name: "", relation: " ", phone: "98" })).toEqual({
      name: "frontoffice.v.name",
      relation: "frontoffice.v.relation",
      phone: "frontoffice.v.phone",
    });
  });
});

describe("FamilyPickupView", () => {
  it("sends an early pickup request for today with the collector's details", async () => {
    m.childPickup.mockResolvedValueOnce(pickup()).mockResolvedValueOnce(pickup({ canRequest: false, passes: [pass()] }));
    m.requestPickup.mockResolvedValue(pass());
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyPickupView />, ["PARENT"]);
    expect(await screen.findByTestId("pickup-none")).toBeInTheDocument();
    await user.click(screen.getByTestId("pickup-request"));
    const form = within(screen.getByTestId("pickup-form"));

    await user.click(form.getByTestId("pickup-save"));
    expect(form.getByText("Give the reason.")).toBeInTheDocument();
    expect(m.requestPickup).not.toHaveBeenCalled();

    await user.type(form.getByLabelText(/^Reason/), " Dentist ");
    await user.selectOptions(form.getByLabelText(/Who will collect/), "OTHER");
    await user.type(form.getByLabelText(/Collector's name/), "Kamla Devi");
    await user.type(form.getByLabelText(/Collector's mobile/), "9876500009");
    await user.click(form.getByTestId("pickup-save"));
    await waitFor(() => expect(m.requestPickup).toHaveBeenCalledTimes(1));
    const [studentId, body] = m.requestPickup.mock.calls[0];
    expect(studentId).toBe("st1");
    expect(body).toMatchObject({ reason: "Dentist", collectorKind: "OTHER", collectorName: "Kamla Devi", collectorPhone: "9876500009" });
    expect(new Date(body.pickupAt).toDateString()).toBe(new Date().toDateString());
    expect(await screen.findByText("Request sent to the office.")).toBeInTheDocument();
    const row = await screen.findByTestId("family-pass");
    expect(row).toHaveTextContent("Waiting for approval");
    expect(screen.queryByTestId("pickup-request")).not.toBeInTheDocument();
  });

  it("shows the approved pass with the OTP notice but never the OTP, and lets the parent cancel", async () => {
    m.childPickup.mockResolvedValue(pickup({ canRequest: false, passes: [pass({ status: "APPROVED", otpExpiresAt: "2026-10-12T12:30:00Z" })] }));
    m.cancelPickup.mockResolvedValue(pass({ status: "CANCELLED", canCancel: false }));
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyPickupView />, ["PARENT"]);
    const row = await screen.findByTestId("family-pass");
    expect(row).toHaveAttribute("data-status", "APPROVED");
    expect(within(row).getByTestId("pass-otp-hint")).toHaveTextContent(/OTP sent to your phone/);
    expect(row.textContent).not.toMatch(/\b[0-9]{6}\b/);
    await user.click(within(row).getByTestId("pass-cancel"));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel request" }));
    await waitFor(() => expect(m.cancelPickup).toHaveBeenCalledWith("st1", "p1"));
  });

  it("keeps the pickup persons list with masked phones and adds one", async () => {
    m.childPickup.mockResolvedValue(
      pickup({ persons: [{ id: "pp1", studentId: "st1", studentName: null, name: "Kamla Devi", relation: "Grandmother", phoneMasked: "98765•••09", photoFileId: null, addedByName: "Anitha Sharma", createdAt: "2026-10-01T00:00:00Z" }] }),
    );
    m.addPerson.mockResolvedValue({ id: "pp2" });
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyPickupView />, ["PARENT"]);
    const person = await screen.findByTestId("pickup-person");
    expect(person).toHaveTextContent("Kamla Devi");
    expect(person).toHaveTextContent("Grandmother · 98765•••09");
    expect(within(person).getByTestId("person-photo")).toBeInTheDocument();

    await user.click(screen.getByTestId("person-add"));
    const form = within(screen.getByTestId("person-form"));
    await user.type(form.getByLabelText(/^Name/), "Rajesh Sharma");
    await user.type(form.getByLabelText(/^Relation/), "Uncle");
    await user.type(form.getByLabelText(/Mobile number/), "9876500010");
    await user.click(form.getByTestId("person-save"));
    await waitFor(() => expect(m.addPerson).toHaveBeenCalledWith("st1", { name: "Rajesh Sharma", relation: "Uncle", phone: "9876500010" }));
    expect(await screen.findByText("Pickup person saved.")).toBeInTheDocument();
  });
});
