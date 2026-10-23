import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { BedView, HostelView, OccupancyBoard } from "@/lib/types";
import { renderAs } from "@/test/render";
import { allotProblems, freeFirst, OccupancyView, vacateProblems } from "./occupancy-view";

const m = vi.hoisted(() => ({
  hostels: vi.fn(),
  occupancy: vi.fn(),
  students: vi.fn(),
  allot: vi.fn(),
  vacate: vi.fn(),
  move: vi.fn(),
}));

vi.mock("@/lib/hostel-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/hostel-api")>();
  return { ...actual, hostelApi: { ...actual.hostelApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/hostel/occupancy",
  useSearchParams: () => new URLSearchParams(),
}));

const ADMIN = ["dashboard.view", "hostel.read", "hostel.manage"];
const WARDEN = ["dashboard.view", "hostel.warden"];

function hostel(overrides: Partial<HostelView> = {}): HostelView {
  return {
    id: "h1",
    name: "Boys' Hostel",
    type: "BOYS",
    wardenUserId: "w1",
    wardenName: "Vikram Pillai",
    address: null,
    active: true,
    blocks: 1,
    rooms: 1,
    beds: 2,
    occupied: 1,
    occupancyPercent: 50,
    canManage: true,
    canWarden: true,
    ...overrides,
  };
}

const TAKEN: BedView = { id: "b1", label: "A", allotmentId: "al1", studentId: "st1", studentName: "Arjun Sharma", className: "Class 5 A", since: "2026-06-01" };
const FREE: BedView = { id: "b2", label: "B", allotmentId: null, studentId: null, studentName: null, className: null, since: null };

function board(): OccupancyBoard {
  return {
    hostelId: "h1",
    hostelName: "Boys' Hostel",
    type: "BOYS",
    beds: 2,
    occupied: 1,
    free: 1,
    occupancyPercent: 50,
    blocks: [{ id: "bl1", hostelId: "h1", name: "A", floors: 2, rooms: 1 }],
    rooms: [
      {
        id: "r1",
        blockId: "bl1",
        blockName: "A",
        floor: 1,
        number: "101",
        capacity: 2,
        type: "DOUBLE",
        monthlyFeePaise: 250000,
        active: true,
        beds: [TAKEN, FREE],
        freeBeds: 1,
        openComplaints: 1,
      },
    ],
  };
}

describe("allotProblems", () => {
  it("needs a student, a date within range and the guardian's consent", () => {
    expect(allotProblems({ studentId: "", studentLabel: "", fromDate: "", guardianConsent: false }, "2026-10-22")).toEqual({
      studentId: "hostel.v.student",
      fromDate: "hostel.v.fromDate",
      guardianConsent: "hostel.v.consent",
    });
    expect(allotProblems({ studentId: "st1", studentLabel: "Arjun", fromDate: "2027-06-01", guardianConsent: true }, "2026-10-22")).toEqual({
      fromDate: "hostel.v.fromDateRange",
    });
    expect(allotProblems({ studentId: "st1", studentLabel: "Arjun", fromDate: "2026-10-22", guardianConsent: true }, "2026-10-22")).toEqual({});
  });
});

describe("vacateProblems", () => {
  it("needs a reason and a date", () => {
    expect(vacateProblems({ reason: " ", toDate: "" })).toEqual({ reason: "hostel.v.reason", toDate: "hostel.v.toDate" });
    expect(vacateProblems({ reason: "Left school", toDate: "2026-10-22" })).toEqual({});
  });
});

describe("freeFirst", () => {
  it("lists free beds before taken ones without losing any", () => {
    expect(freeFirst([TAKEN, FREE]).map((b) => b.label)).toEqual(["B", "A"]);
  });
});

describe("OccupancyView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    m.hostels.mockResolvedValue([hostel()]);
    m.occupancy.mockResolvedValue(board());
    m.students.mockResolvedValue([{ id: "st2", fullName: "Bala Iyer", admissionNo: "AKS/2026/002", className: "Class 5 A" }]);
    m.allot.mockResolvedValue({});
    m.vacate.mockResolvedValue({});
  });

  it("shows the room cards with their free beds, the boarder in each taken bed and open complaints", async () => {
    renderAs(ADMIN, <OccupancyView />);
    const boardEl = await screen.findByTestId("occupancy-board");
    const card = within(boardEl).getByTestId("room-card");
    expect(card).toHaveAttribute("data-room", "101");
    expect(card).toHaveAttribute("data-free", "1");
    const beds = within(card).getAllByTestId("bed");
    expect(beds.map((b) => b.getAttribute("data-bed"))).toEqual(["B", "A"]);
    expect(beds[1]).toHaveAttribute("data-taken", "yes");
    expect(within(beds[1]).getByText("Arjun Sharma")).toBeInTheDocument();
    expect(within(card).getByTestId("room-complaints")).toBeInTheDocument();
    expect(screen.getByTestId("occupancy-occupied")).toHaveTextContent("1");
    expect(screen.getByTestId("occupancy-free")).toHaveTextContent("1");
    expect(m.occupancy).toHaveBeenCalledWith("h1");
  });

  it("allots a free bed to a searched student once the guardian has consented", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, <OccupancyView />);
    await user.click(await screen.findByTestId("bed-allot"));
    const form = await screen.findByTestId("allot-form");
    await user.type(within(form).getByTestId("student-search"), "Ba");
    await user.click(await within(screen.getByTestId("student-matches")).findByText(/Bala Iyer/));
    await user.click(within(form).getByTestId("allot-save"));
    expect(m.allot).not.toHaveBeenCalled();
    expect(within(form).getByText("The guardian's consent is needed.")).toBeInTheDocument();
    await user.click(within(form).getByTestId("allot-consent"));
    await user.click(within(form).getByTestId("allot-save"));
    await waitFor(() => expect(m.allot).toHaveBeenCalledWith(expect.objectContaining({ studentId: "st2", bedId: "b2", guardianConsent: true })));
    await waitFor(() => expect(m.occupancy).toHaveBeenCalledTimes(2));
  });

  it("vacates a taken bed with a reason", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, <OccupancyView />);
    await user.click(await screen.findByTestId("bed-vacate"));
    const form = await screen.findByTestId("vacate-form");
    await user.type(within(form).getByLabelText(/Reason for vacating/), "Left school");
    await user.click(within(form).getByTestId("vacate-save"));
    await waitFor(() => expect(m.vacate).toHaveBeenCalledWith("al1", expect.objectContaining({ reason: "Left school" })));
  });

  it("gives a warden the board without the allot, move and vacate buttons", async () => {
    m.hostels.mockResolvedValue([hostel({ canManage: false })]);
    renderAs(WARDEN, <OccupancyView />);
    await screen.findByTestId("occupancy-board");
    expect(screen.queryByTestId("bed-allot")).not.toBeInTheDocument();
    expect(screen.queryByTestId("bed-vacate")).not.toBeInTheDocument();
    expect(screen.queryByTestId("bed-move")).not.toBeInTheDocument();
  });

  it("says so when the hostel has no rooms yet", async () => {
    m.occupancy.mockResolvedValue({ ...board(), rooms: [], beds: 0, occupied: 0, free: 0, occupancyPercent: 0 });
    renderAs(ADMIN, <OccupancyView />);
    expect(await screen.findByTestId("board-empty")).toBeInTheDocument();
  });
});
