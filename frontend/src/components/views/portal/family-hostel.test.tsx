import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Child, FamilyHostel, HostelPassView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { renderAs } from "@/test/render";
import { FamilyHostelCard, FamilyHostelView, openFirst } from "./family-hostel";

const m = vi.hoisted(() => ({
  myChildren: vi.fn(),
  childHostel: vi.fn(),
  childPasses: vi.fn(),
  requestPass: vi.fn(),
  cancelPass: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren: m.myChildren } };
});
vi.mock("@/lib/hostel-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/hostel-api")>();
  return { ...actual, hostelApi: { ...actual.hostelApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/family/hostel",
  useSearchParams: () => new URLSearchParams(),
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

function pass(overrides: Partial<HostelPassView> = {}): HostelPassView {
  return {
    id: "p1",
    studentId: "st1",
    studentName: "Arjun Sharma",
    admissionNo: "AKS/2026/001",
    className: "Class 5 A",
    hostelId: "h1",
    hostelName: "Boys' Hostel",
    kind: "OUTING",
    source: "PARENT_APP",
    requestedByName: "Lata Sharma",
    fromDate: "2026-10-24",
    toDate: "2026-10-24",
    returnBy: "2026-10-24T12:30:00Z",
    reason: "Family function",
    status: "REQUESTED",
    decidedByName: null,
    decidedAt: null,
    decisionNote: null,
    outAt: null,
    outByName: null,
    inAt: null,
    inByName: null,
    late: false,
    createdAt: "2026-10-22T05:00:00Z",
    canCancel: true,
    canDecide: false,
    canMarkOut: false,
    canMarkIn: false,
    ...overrides,
  };
}

function boarder(overrides: Partial<FamilyHostel> = {}): FamilyHostel {
  return {
    studentId: "st1",
    studentName: "Arjun Sharma",
    className: "Class 5 A",
    boarder: true,
    hostelName: "Boys' Hostel",
    blockName: "A",
    roomNumber: "101",
    bedLabel: "A",
    wardenName: "Vikram Pillai",
    nextPass: pass(),
    lastRollCallNight: "2026-10-21",
    lastRollCallStatus: "PRESENT",
    canRequest: true,
    passes: [pass()],
    ...overrides,
  };
}

const DAY_SCHOLAR = boarder({ boarder: false, hostelName: null, blockName: null, roomNumber: null, bedLabel: null, wardenName: null, nextPass: null, lastRollCallNight: null, lastRollCallStatus: null, canRequest: false, passes: [] });

function Card() {
  const data = useApiData("hostel:family:st1", () => m.childHostel("st1") as Promise<FamilyHostel>);
  return <FamilyHostelCard data={data} href="/app/family/hostel?child=st1" />;
}

describe("openFirst", () => {
  it("puts the passes still to come before the finished ones", () => {
    const rows = openFirst([pass({ id: "a", status: "RETURNED" }), pass({ id: "b", status: "APPROVED" }), pass({ id: "c", status: "REJECTED" }), pass({ id: "d", status: "OUT" })]);
    expect(rows.map((p) => p.id)).toEqual(["b", "d", "a", "c"]);
  });
});

describe("FamilyHostelCard", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows a boarder's bed, warden, next pass and last roll call", async () => {
    m.childHostel.mockResolvedValue(boarder());
    renderAs(PARENT, <Card />);
    const card = await screen.findByTestId("family-hostel-card");
    expect(within(card).getByTestId("family-hostel-bed")).toHaveTextContent("Boys' Hostel · Room 101 / A");
    expect(card).toHaveTextContent("Warden: Vikram Pillai");
    expect(within(card).getByTestId("family-hostel-next")).toHaveTextContent("Outing · 24 Oct 2026");
    expect(within(card).getByTestId("family-hostel-roll-call")).toHaveTextContent("Roll call on 21 Oct 2026");
    expect(within(card).getByTestId("family-hostel-roll-call")).toHaveTextContent("Present");
    expect(within(card).getByTestId("family-hostel-open")).toHaveAttribute("href", "/app/family/hostel?child=st1");
  });

  it("renders nothing for a day scholar", async () => {
    m.childHostel.mockResolvedValue(DAY_SCHOLAR);
    renderAs(PARENT, <Card />);
    await waitFor(() => expect(m.childHostel).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByTestId("family-hostel-card")).not.toBeInTheDocument());
  });
});

describe("FamilyHostelView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    m.myChildren.mockResolvedValue([ARJUN]);
    m.childHostel.mockResolvedValue(boarder());
    m.childPasses.mockResolvedValue([pass()]);
    m.requestPass.mockResolvedValue(pass({ id: "p2" }));
    m.cancelPass.mockResolvedValue(pass({ status: "CANCELLED", canCancel: false }));
  });

  it("asks for an outing once the form is complete", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyHostelView />);
    expect((await screen.findAllByTestId("family-hostel-pass"))[0]).toHaveAttribute("data-status", "REQUESTED");
    await user.click(screen.getByTestId("hostel-pass-request"));
    const form = await screen.findByTestId("hostel-pass-form");
    await user.click(within(form).getByTestId("hostel-pass-save"));
    expect(m.requestPass).not.toHaveBeenCalled();
    expect(within(form).getByText("Give the reason.")).toBeInTheDocument();
    await user.type(within(form).getByLabelText(/Reason/), "Cousin's wedding");
    await user.click(within(form).getByTestId("hostel-pass-save"));
    await waitFor(() => expect(m.requestPass).toHaveBeenCalledWith("st1", expect.objectContaining({ kind: "OUTING", toDate: null, reason: "Cousin's wedding" })));
    expect(await screen.findByText("Pass requested; the warden will decide.")).toBeInTheDocument();
    await waitFor(() => expect(m.childPasses).toHaveBeenCalledTimes(2));
  });

  it("needs an end date for home leave", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyHostelView />);
    await user.click(await screen.findByTestId("hostel-pass-request"));
    const form = await screen.findByTestId("hostel-pass-form");
    await user.selectOptions(within(form).getByLabelText(/^Pass/), "HOME_LEAVE");
    await user.type(within(form).getByLabelText(/Reason/), "Diwali");
    await user.click(within(form).getByTestId("hostel-pass-save"));
    expect(m.requestPass).not.toHaveBeenCalled();
    expect(within(form).getByText("Give the end date.")).toBeInTheDocument();
  });

  it("cancels a pending request after confirming", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyHostelView />);
    await user.click(await screen.findByTestId("hostel-pass-cancel"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Cancel request" }));
    await waitFor(() => expect(m.cancelPass).toHaveBeenCalledWith("st1", "p1"));
  });

  it("tells a day scholar's parent there is nothing here", async () => {
    m.childHostel.mockResolvedValue(DAY_SCHOLAR);
    renderAs(PARENT, <FamilyHostelView />);
    expect(await screen.findByTestId("family-hostel-none")).toHaveTextContent("Arjun Sharma does not stay in the hostel.");
    expect(screen.queryByTestId("hostel-pass-request")).not.toBeInTheDocument();
  });
});
