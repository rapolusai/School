import { fireEvent, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { Allocation, RouteDetail, RouteSummary } from "@/lib/types";
import { renderAs } from "@/test/render";
import { AllocationsView, ROUTE_FULL_TYPE } from "./allocations-view";

const m = vi.hoisted(() => ({
  routes: vi.fn(),
  route: vi.fn(),
  allocations: vi.fn(),
  students: vi.fn(),
  allocate: vi.fn(),
  endAllocation: vi.fn(),
  listClasses: vi.fn(),
}));

vi.mock("@/lib/transport-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/transport-api")>();
  const { listClasses: _unused, ...transport } = m;
  void _unused;
  return { ...actual, transportApi: { ...actual.transportApi, ...transport } };
});
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listClasses: m.listClasses } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/transport/allocations",
}));

const MANAGER = ["dashboard.view", "academics.read", "transport.read", "transport.manage", "transport.trip"];

const ROUTE: RouteSummary = {
  id: "r1",
  name: "Route 1",
  active: true,
  runsMorning: true,
  runsAfternoon: true,
  vehicle: { id: "v1", registrationNo: "TS09 UB 4410", type: "BUS", seats: 1, status: "ACTIVE" },
  driver: null,
  attendant: null,
  stopCount: 1,
  allocated: 1,
  seats: 1,
  firstPickup: "07:10:00",
  flags: [],
};

const DETAIL: RouteDetail = {
  ...ROUTE,
  stops: [
    {
      id: "st1",
      seq: 1,
      name: "Gate Stop",
      landmark: null,
      pickupTime: "07:10:00",
      dropTime: "15:10:00",
      latitude: null,
      longitude: null,
      feeZone: { id: "z1", name: "Zone A", annualFeePaise: 1_200_000 },
      students: 1,
    },
  ],
};

const allocation = (overrides: Partial<Allocation> = {}): Allocation => ({
  id: "a1",
  student: { id: "s2", name: "Bala Iyer", admissionNo: "TR-2", classLabel: "Class 5 A" },
  route: { id: "r1", name: "Route 1" },
  stop: { id: "st1", name: "Gate Stop" },
  feeZone: { id: "z1", name: "Zone A", annualFeePaise: 1_200_000 },
  startDate: "2026-06-01",
  endDate: null,
  status: "ACTIVE",
  current: true,
  overCapacityReason: null,
  endReason: null,
  createdByName: "Mohan Manager",
  createdAt: "2026-06-01T04:00:00Z",
  endedByName: null,
  endedAt: null,
  ...overrides,
});

afterEach(() => vi.clearAllMocks());

describe("AllocationsView", () => {
  it("lists the students on the buses with their stop, zone and dates", async () => {
    m.routes.mockResolvedValue([ROUTE]);
    m.listClasses.mockResolvedValue([]);
    m.allocations.mockResolvedValue([allocation()]);
    renderAs(MANAGER, <AllocationsView initialRouteId="r1" />, ["TRANSPORT_MANAGER"]);
    const row = await screen.findByTestId("allocation-row");
    expect(row).toHaveTextContent("Bala Iyer");
    expect(row).toHaveTextContent("Route 1Gate Stop");
    expect(row).toHaveTextContent("₹12,000 a year");
    expect(row).toHaveTextContent("Since 1 Jun 2026");
    expect(m.allocations).toHaveBeenCalledWith({ routeId: "r1", classId: undefined, status: "ACTIVE", q: undefined });
  });

  it("asks for a reason when the bus is full and allocates with it", async () => {
    m.routes.mockResolvedValue([ROUTE]);
    m.route.mockResolvedValue(DETAIL);
    m.listClasses.mockResolvedValue([]);
    m.allocations.mockResolvedValue([]);
    m.students.mockResolvedValue([
      { id: "s1", name: "Asha Rao", admissionNo: "TR-1", classLabel: "Class 5 A", allocation: null },
      {
        id: "s2",
        name: "Bala Iyer",
        admissionNo: "TR-2",
        classLabel: "Class 5 A",
        allocation: { id: "a1", routeId: "r1", routeName: "Route 1", stopName: "Gate Stop", startDate: "2026-06-01", endDate: null },
      },
    ]);
    m.allocate
      .mockRejectedValueOnce(
        new ApiError({
          status: 409,
          title: "The bus is full",
          detail: "The bus on Route 1 is full: 1 of 1 seats are taken. Give a reason to allocate anyway.",
          errors: { overCapacityReason: "Give a reason to allocate beyond the 1 seats." },
          type: ROUTE_FULL_TYPE,
        }),
      )
      .mockResolvedValueOnce(allocation({ id: "a2", student: { id: "s1", name: "Asha Rao", admissionNo: "TR-1", classLabel: "Class 5 A" } }));
    renderAs(MANAGER, <AllocationsView />, ["TRANSPORT_MANAGER"]);
    expect(await screen.findByText("No students on the buses yet.")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("alloc-one"));
    const form = await screen.findByTestId("allocate-form");
    fireEvent.change(within(form).getByRole("searchbox"), { target: { value: "ra" } });
    const picker = await within(form).findByRole("list", { name: "Students" }, { timeout: 2000 });
    // A student already on a bus cannot be picked twice.
    expect(within(picker).getByRole("button", { name: /Bala Iyer/ })).toBeDisabled();
    expect(within(picker).getByRole("button", { name: /Bala Iyer/ })).toHaveTextContent("Already on Route 1 (Gate Stop)");
    fireEvent.click(within(picker).getByRole("button", { name: /Asha Rao/ }));
    expect(within(form).getByTestId("alloc-picked")).toHaveTextContent("Asha Rao");

    fireEvent.change(within(form).getByLabelText("Route"), { target: { value: "r1" } });
    expect(within(form).getByText("1 of 1 seats taken")).toBeInTheDocument();
    await within(form).findByRole("option", { name: /Gate Stop — Zone A/ });
    fireEvent.change(within(form).getByLabelText("Stop"), { target: { value: "st1" } });
    fireEvent.submit(form);

    expect(await within(form).findByText(/The bus on Route 1 is full/)).toBeInTheDocument();
    const reason = within(form).getByLabelText("Reason to go over capacity");
    expect(within(form).getByText("Give a reason to allocate beyond the 1 seats.")).toBeInTheDocument();
    fireEvent.change(reason, { target: { value: "Sibling already on this bus" } });
    fireEvent.click(within(form).getByRole("button", { name: "Put on the bus anyway" }));

    expect(await screen.findByText("Asha Rao is on Route 1.")).toBeInTheDocument();
    expect(m.allocate).toHaveBeenLastCalledWith({
      studentId: "s1",
      routeId: "r1",
      stopId: "st1",
      startDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
      endDate: null,
      overCapacityReason: "Sibling already on this bus",
    });
  });

  it("hides the changes from someone who may only read", async () => {
    m.routes.mockResolvedValue([ROUTE]);
    m.listClasses.mockResolvedValue([]);
    m.allocations.mockResolvedValue([allocation()]);
    renderAs(["dashboard.view", "transport.read"], <AllocationsView />, ["PRINCIPAL"]);
    await screen.findByTestId("allocation-row");
    expect(screen.queryByTestId("alloc-one")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "End" })).not.toBeInTheDocument();
  });
});
