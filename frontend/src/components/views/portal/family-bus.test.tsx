import { screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { positionText } from "@/components/views/transport/transport-shared";
import { translate, type Translate } from "@/lib/i18n";
import type { FamilyTrip, MyTransport } from "@/lib/types";
import { renderAs } from "@/test/render";
import { eventText, FamilyBusView, stopsAwayText } from "./family-bus";

const m = vi.hoisted(() => ({ mine: vi.fn() }));

vi.mock("@/lib/transport-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/transport-api")>();
  return { ...actual, transportApi: { ...actual.transportApi, mine: m.mine } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/family/bus",
}));

const PARENT = ["dashboard.view", "child.view", "notices.read"];

const morning = (overrides: Partial<FamilyTrip> = {}): FamilyTrip => ({
  kind: "MORNING",
  status: "RUNNING",
  startedAt: "2026-10-12T01:30:00Z",
  endedAt: null,
  childStatus: "BOARDED",
  childMarkedAt: "2026-10-12T01:51:00Z",
  position: { at: "2026-10-12T01:58:00Z", minutesAgo: 2, stale: false, nearestStop: { id: "st2", name: "Market Stop" } },
  nextStop: { id: "st3", name: "Temple Stop" },
  stopsAway: null,
  delay: { minutes: 10, reason: "Traffic at the junction", at: "2026-10-12T01:55:00Z" },
  stops: [
    { id: "st1", seq: 1, name: "Gate Stop", time: "07:10:00", reached: true, next: false, mine: false },
    { id: "st2", seq: 2, name: "Market Stop", time: "07:20:00", reached: true, next: false, mine: true },
    { id: "st3", seq: 3, name: "Temple Stop", time: "07:30:00", reached: false, next: true, mine: false },
  ],
  events: [
    { kind: "BOARDED", at: "2026-10-12T01:51:00Z", stopName: "Market Stop", minutes: null, note: null },
    { kind: "DELAYED", at: "2026-10-12T01:55:00Z", stopName: null, minutes: 10, note: "Traffic at the junction" },
  ],
  ...overrides,
});

const FAMILY: MyTransport = {
  today: "2026-10-12",
  children: [
    {
      studentId: "s1",
      name: "Arjun Sharma",
      classLabel: "Class 5 A",
      allocation: {
        routeName: "Route 1",
        stopName: "Market Stop",
        landmark: "Near the post office",
        pickupTime: "07:20:00",
        dropTime: "15:00:00",
        startDate: "2026-06-01",
        endDate: null,
        vehicleRegistrationNo: "TS09 UB 4410",
        vehicleType: "BUS",
      },
      trips: [
        morning(),
        {
          kind: "AFTERNOON",
          status: "NOT_STARTED",
          startedAt: null,
          endedAt: null,
          childStatus: null,
          childMarkedAt: null,
          position: null,
          nextStop: null,
          stopsAway: null,
          delay: null,
          stops: [],
          events: [],
        },
      ],
    },
    { studentId: "s2", name: "Diya Sharma", classLabel: "Class 2 A", allocation: null, trips: [] },
  ],
};

afterEach(() => vi.clearAllMocks());

describe("FamilyBusView", () => {
  it("shows the child's route, stop and times, that they boarded, and where the bus is", async () => {
    m.mine.mockResolvedValue(FAMILY);
    renderAs(PARENT, <FamilyBusView />, ["PARENT"]);
    const [arjun, diya] = await screen.findAllByTestId("family-bus-child");
    expect(within(arjun).getByRole("heading", { level: 2, name: /Arjun Sharma\s*Class 5 A/ })).toBeInTheDocument();
    const route = within(arjun).getByTestId("family-bus-route");
    expect(route).toHaveTextContent("Route 1");
    expect(route).toHaveTextContent("Stop: Market Stop · Near the post office");
    expect(route).toHaveTextContent("Pickup 07:20 · Drop 15:00");
    expect(route).toHaveTextContent("Bus TS09 UB 4410");

    const trip = within(arjun).getByTestId("family-bus-trip-MORNING");
    expect(trip).toHaveTextContent("On the way");
    expect(within(trip).getByTestId("family-bus-child-status")).toHaveTextContent("Boarded");
    expect(trip).toHaveTextContent(/Boarded at 7:21\s?am/i);
    expect(within(trip).getByTestId("family-bus-position")).toHaveTextContent("Near Market Stop · 2 min ago");
    expect(within(trip).getByTestId("family-bus-position")).toHaveTextContent("Next stop: Temple Stop");
    expect(within(trip).getByTestId("family-bus-delay")).toHaveTextContent("Delayed 10 min: Traffic at the junction");
    // The stop list marks the child's stop; no map is drawn.
    expect(within(trip).getByRole("list", { name: "Stops" })).toHaveTextContent("Market StopYour stop");
    const events = within(trip).getByTestId("family-bus-events");
    expect(events).toHaveTextContent("Boarded at Market Stop");
    expect(events).toHaveTextContent("Delayed 10 min: Traffic at the junction");

    // The afternoon trip has not started: no status for the child yet.
    const afternoon = within(arjun).getByTestId("family-bus-trip-AFTERNOON");
    expect(afternoon).toHaveTextContent("Not started");
    expect(within(afternoon).queryByTestId("family-bus-child-status")).not.toBeInTheDocument();

    expect(within(diya).getByTestId("family-bus-no-route")).toHaveTextContent("Not on a school bus.");
  });

  it("says no recent position when the bus has not reported for 5 minutes, and how many stops away it is", async () => {
    m.mine.mockResolvedValue({
      ...FAMILY,
      children: [
        {
          ...FAMILY.children[0],
          trips: [
            morning({
              childStatus: null,
              childMarkedAt: null,
              delay: null,
              stopsAway: 2,
              position: { at: "2026-10-12T01:40:00Z", minutesAgo: 9, stale: true, nearestStop: { id: "st1", name: "Gate Stop" } },
              events: [],
            }),
          ],
        },
      ],
    });
    renderAs(PARENT, <FamilyBusView />, ["PARENT"]);
    const position = await screen.findByTestId("family-bus-position");
    expect(position).toHaveTextContent("No recent position");
    expect(position).toHaveTextContent("2 stops away");
    expect(screen.getByTestId("family-bus-child-status")).toHaveTextContent("Not marked");
  });

  it("says so when no child is linked, and shows errors with a retry", async () => {
    m.mine.mockResolvedValue({ today: "2026-10-12", children: [] });
    renderAs(PARENT, <FamilyBusView />, ["PARENT"]);
    expect(await screen.findByTestId("family-bus-none")).toHaveTextContent("No child is linked to you yet.");
  });

  it("shows the error with a retry when the bus cannot be loaded", async () => {
    const { ApiError } = await import("@/lib/api");
    m.mine.mockRejectedValue(new ApiError({ status: 500, title: "Server error" }));
    renderAs(PARENT, <FamilyBusView />, ["PARENT"]);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});

describe("bus lines", () => {
  const t: Translate = (key, vars) => translate("en", key, vars);

  it("counts stops only while the trip runs", () => {
    expect(stopsAwayText(t, { status: "RUNNING", stopsAway: 1 })).toBe("1 stop away");
    expect(stopsAwayText(t, { status: "RUNNING", stopsAway: 0 })).toBe("At your stop");
    expect(stopsAwayText(t, { status: "COMPLETED", stopsAway: 2 })).toBe("");
  });

  it("writes the day's events in words", () => {
    expect(eventText(t, { kind: "DROPPED", at: "", stopName: "Gate Stop", minutes: null, note: null })).toBe("Dropped at Gate Stop");
    expect(eventText(t, { kind: "ABSENT", at: "", stopName: null, minutes: null, note: null })).toBe("Absent");
    expect(eventText(t, { kind: "DELAYED", at: "", stopName: null, minutes: 5, note: "Rain" })).toBe("Delayed 5 min: Rain");
  });

  it("describes the bus's position, or its absence", () => {
    expect(positionText(t, null)).toBe("No recent position");
    expect(positionText(t, { stale: false, minutesAgo: 0, nearestStop: { id: "s", name: "Gate Stop" } })).toBe(
      "Near Gate Stop · Updated just now",
    );
    expect(positionText(t, { stale: false, minutesAgo: 3, nearestStop: null })).toBe("3 min ago");
    expect(positionText(t, { stale: true, minutesAgo: 6, nearestStop: { id: "s", name: "Gate Stop" } })).toBe("No recent position");
  });
});
