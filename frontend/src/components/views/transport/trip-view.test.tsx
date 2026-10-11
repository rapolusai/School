import { act, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { POSITION_INTERVAL_MS } from "@/lib/transport-api";
import type { MyRoute, MyTrips, Trip, TripStudent } from "@/lib/types";
import { renderAs } from "@/test/render";
import { marksFor, runningTrip, TripScreen, validateDelay } from "./trip-view";

const m = vi.hoisted(() => ({
  myTrips: vi.fn(),
  todayTrip: vi.fn(),
  startTrip: vi.fn(),
  mark: vi.fn(),
  position: vi.fn(),
  delay: vi.fn(),
  endTrip: vi.fn(),
}));

vi.mock("@/lib/transport-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/transport-api")>();
  return { ...actual, transportApi: { ...actual.transportApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/transport/trip",
}));

const ATTENDANT = ["dashboard.view", "leave.request", "notices.read", "transport.trip"];

/** A stand-in for the browser's Geolocation API: answers with a fixed position, or with an error. */
const geo = {
  getCurrentPosition: vi.fn(),
  watchPosition: vi.fn(),
  clearWatch: vi.fn(),
};

function phoneAt(latitude: number, longitude: number) {
  geo.getCurrentPosition.mockImplementation((ok: PositionCallback) =>
    ok({
      coords: { latitude, longitude, accuracy: 12.4, speed: 5, altitude: null, altitudeAccuracy: null, heading: null },
      timestamp: Date.now(),
    } as unknown as GeolocationPosition),
  );
}

const route = (overrides: Partial<MyRoute> = {}): MyRoute => ({
  routeId: "r1",
  name: "Route 1",
  vehicleRegistrationNo: "TS09 UB 4410",
  role: "ATTENDANT",
  runsMorning: true,
  runsAfternoon: true,
  morning: null,
  afternoon: null,
  students: 2,
  ...overrides,
});

const MINE = (routes: MyRoute[]): MyTrips => ({ today: "2026-10-12", routes });

const student = (id: string, name: string, status: TripStudent["status"] = null): TripStudent => ({
  id,
  name,
  admissionNo: id === "s1" ? "TR-1" : "TR-2",
  classLabel: "Class 5 A",
  status,
  markedAt: status ? "2026-10-12T01:52:00Z" : null,
});

function trip(overrides: Partial<Trip> = {}, asha: TripStudent["status"] = null): Trip {
  return {
    id: "t1",
    route: { id: "r1", name: "Route 1" },
    date: "2026-10-12",
    kind: "MORNING",
    status: "RUNNING",
    vehicleRegistrationNo: "TS09 UB 4410",
    startedAt: "2026-10-12T01:30:00Z",
    startedByName: "Anita Attendant",
    endedAt: null,
    endedByName: null,
    delay: null,
    position: null,
    reachedStop: null,
    nextStop: { id: "st1", name: "Gate Stop" },
    counts: { total: 2, boarded: asha === "BOARDED" ? 1 : 0, dropped: 0, absent: 0, pending: asha ? 1 : 2 },
    stops: [
      {
        id: "st1",
        seq: 1,
        name: "Gate Stop",
        landmark: "Near the post office",
        time: "07:10:00",
        reached: asha === "BOARDED",
        next: asha !== "BOARDED",
        students: [student("s1", "Asha Rao", asha)],
      },
      {
        id: "st2",
        seq: 2,
        name: "Market Stop",
        landmark: null,
        time: "07:20:00",
        reached: false,
        next: asha === "BOARDED",
        students: [student("s2", "Bala Iyer")],
      },
    ],
    ...overrides,
  };
}

const NEAR_MARKET = {
  latitude: 17.42,
  longitude: 78.44,
  accuracyM: 12,
  at: "2026-10-12T01:50:00Z",
  source: "PHONE" as const,
  minutesAgo: 0,
  stale: false,
  nearestStop: { id: "st2", name: "Market Stop" },
  nearestMeters: 40,
};

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["setInterval", "clearInterval"] });
  Object.defineProperty(navigator, "geolocation", { value: geo, configurable: true });
  phoneAt(17.42, 78.44);
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("TripScreen", () => {
  it("starts the morning trip and shares the phone's location now and every 30 seconds", async () => {
    m.myTrips.mockResolvedValue(MINE([route()]));
    m.todayTrip.mockResolvedValue(undefined);
    m.startTrip.mockResolvedValue(trip());
    m.position.mockResolvedValue(trip({ position: NEAR_MARKET }));
    renderAs(ATTENDANT, <TripScreen />, ["BUS_ATTENDANT"]);

    const card = await screen.findByTestId("trip-route");
    expect(card).toHaveTextContent("Route 1");
    expect(card).toHaveTextContent("TS09 UB 4410");
    expect(card).toHaveTextContent("2 students · You are the attendant");
    fireEvent.click(within(card).getByTestId("trip-open-MORNING"));

    expect(await screen.findByTestId("trip-not-started")).toHaveTextContent("2 students travel on this route");
    expect(m.todayTrip).toHaveBeenCalledWith("r1", "MORNING");
    // Nothing is shared before the trip starts.
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Start Morning trip" }));

    expect(await screen.findByTestId("trip-panel")).toHaveTextContent("Running");
    expect(m.startTrip).toHaveBeenCalledWith("r1", "MORNING");
    // The first position goes at once, and the server's answer moves the bus to the nearest stop.
    expect(await screen.findByText("Near Market Stop · Updated just now")).toBeInTheDocument();
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
    expect(m.position).toHaveBeenCalledWith("t1", { latitude: 17.42, longitude: 78.44, accuracyM: 12, speedKmh: 18 });
    expect(screen.getByTestId("trip-location")).toHaveTextContent(/Sharing location · last sent/);
    expect(screen.getByTestId("route-line-bus")).toBeInTheDocument();

    // Then every 30 seconds while the screen is open.
    phoneAt(17.43, 78.44);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POSITION_INTERVAL_MS);
    });
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(2);
    expect(m.position).toHaveBeenLastCalledWith("t1", expect.objectContaining({ latitude: 17.43, longitude: 78.44 }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POSITION_INTERVAL_MS);
    });
    expect(m.position).toHaveBeenCalledTimes(3);
  });

  it("opens the running trip, marks a student boarded and stops sharing when the screen closes", async () => {
    m.myTrips.mockResolvedValue(MINE([route({ morning: { id: "t1", status: "RUNNING", startedAt: "2026-10-12T01:30:00Z", endedAt: null } })]));
    m.todayTrip.mockResolvedValue(trip());
    m.position.mockResolvedValue(trip());
    m.mark.mockResolvedValue(trip({}, "BOARDED"));
    const view = renderAs(ATTENDANT, <TripScreen />, ["BUS_ATTENDANT"]);

    const stops = await screen.findAllByTestId("trip-stop");
    expect(stops.map((s) => within(s).getByRole("heading").textContent)).toEqual(["1. Gate Stop", "2. Market Stop"]);
    expect(stops[0]).toHaveTextContent("Near the post office");
    expect(stops[0]).toHaveTextContent("Next");
    const asha = within(stops[0]).getByRole("group", { name: "Mark Asha Rao" });
    // The morning trip takes boarded or absent only.
    expect(within(asha).getAllByRole("button").map((b) => b.textContent)).toEqual(["Boarded", "Absent"]);
    expect(screen.getByTestId("trip-counts")).toHaveTextContent("To mark2");

    fireEvent.click(within(asha).getByRole("button", { name: "Boarded" }));
    expect(m.mark).toHaveBeenCalledWith("t1", "s1", "BOARDED");
    expect(await within(asha).findByRole("button", { name: "Boarded", pressed: true })).toBeInTheDocument();
    expect(screen.getByTestId("trip-counts")).toHaveTextContent("Boarded1");
    expect(screen.getAllByTestId("trip-stop")[0]).toHaveTextContent("Reached");
    expect(screen.getByTestId("trip-position")).toHaveTextContent("No recent position");
    expect(screen.getByTestId("trip-position")).toHaveTextContent("Next stop: Market Stop");

    // Closing the screen stops the location sharing.
    const calls = geo.getCurrentPosition.mock.calls.length;
    view.unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POSITION_INTERVAL_MS * 2);
    });
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(calls);
  });

  it("offers dropped on the afternoon trip and shows a stale position as no recent position", async () => {
    m.myTrips.mockResolvedValue(MINE([route({ afternoon: { id: "t2", status: "RUNNING", startedAt: "2026-10-12T09:30:00Z", endedAt: null } })]));
    const afternoon = trip({
      id: "t2",
      kind: "AFTERNOON",
      position: { ...NEAR_MARKET, minutesAgo: 7, stale: true },
    });
    m.todayTrip.mockResolvedValue(afternoon);
    m.position.mockResolvedValue(afternoon);
    renderAs(ATTENDANT, <TripScreen />, ["BUS_ATTENDANT"]);

    const stops = await screen.findAllByTestId("trip-stop");
    expect(m.todayTrip).toHaveBeenCalledWith("r1", "AFTERNOON");
    const asha = within(stops[0]).getByRole("group", { name: "Mark Asha Rao" });
    expect(within(asha).getAllByRole("button").map((b) => b.textContent)).toEqual(["Boarded", "Dropped", "Absent"]);
    expect(screen.getByTestId("trip-position")).toHaveTextContent("No recent position");
    expect(screen.queryByTestId("route-line-bus")).not.toBeInTheDocument();
  });

  it("says so when location is blocked, and keeps marking possible", async () => {
    geo.getCurrentPosition.mockImplementation((_ok: PositionCallback, fail: PositionErrorCallback) =>
      fail({ code: 1, message: "denied" } as GeolocationPositionError),
    );
    m.myTrips.mockResolvedValue(MINE([route({ morning: { id: "t1", status: "RUNNING", startedAt: "2026-10-12T01:30:00Z", endedAt: null } })]));
    m.todayTrip.mockResolvedValue(trip());
    renderAs(ATTENDANT, <TripScreen />, ["BUS_ATTENDANT"]);

    expect(await screen.findByText("Location is blocked. Allow location for this site in the browser settings.")).toBeInTheDocument();
    expect(m.position).not.toHaveBeenCalled();
    expect(screen.getAllByRole("button", { name: "Boarded" })).toHaveLength(2);
  });

  it("works without a location on a phone that has none", async () => {
    Object.defineProperty(navigator, "geolocation", { value: undefined, configurable: true });
    m.myTrips.mockResolvedValue(MINE([route({ morning: { id: "t1", status: "RUNNING", startedAt: "2026-10-12T01:30:00Z", endedAt: null } })]));
    m.todayTrip.mockResolvedValue(trip());
    renderAs(ATTENDANT, <TripScreen />, ["BUS_ATTENDANT"]);
    expect(await screen.findByText("This phone cannot share its location. Mark students as usual.")).toBeInTheDocument();
    expect(m.position).not.toHaveBeenCalled();
  });

  it("reports a delay with a reason and ends the trip", async () => {
    m.myTrips.mockResolvedValue(MINE([route({ morning: { id: "t1", status: "RUNNING", startedAt: "2026-10-12T01:30:00Z", endedAt: null } })]));
    m.todayTrip.mockResolvedValue(trip());
    m.position.mockResolvedValue(trip());
    m.delay.mockResolvedValue(trip({ delay: { minutes: 15, reason: "Traffic at the junction", at: "2026-10-12T01:55:00Z" } }));
    m.endTrip.mockResolvedValue(trip({ status: "COMPLETED", endedAt: "2026-10-12T02:30:00Z" }, "BOARDED"));
    renderAs(ATTENDANT, <TripScreen />, ["BUS_ATTENDANT"]);

    fireEvent.click(await screen.findByTestId("trip-report-delay"));
    const form = await screen.findByTestId("delay-form");
    fireEvent.change(within(form).getByLabelText("Minutes late"), { target: { value: "15" } });
    fireEvent.submit(form);
    expect(await within(form).findByText("Fill in this field.")).toBeInTheDocument();
    expect(m.delay).not.toHaveBeenCalled();
    fireEvent.change(within(form).getByLabelText("Reason"), { target: { value: " Traffic at the junction " } });
    fireEvent.submit(form);
    expect(await screen.findByTestId("trip-delay")).toHaveTextContent("Delayed 15 min: Traffic at the junction");
    expect(m.delay).toHaveBeenCalledWith("t1", { minutes: 15, reason: "Traffic at the junction" });

    fireEvent.click(screen.getByTestId("trip-end"));
    const confirm = await screen.findByRole("dialog", { name: "End this trip?" });
    expect(confirm).toHaveTextContent("2 students are not marked yet. End the trip anyway?");
    fireEvent.click(within(confirm).getByRole("button", { name: "End trip" }));
    expect(await screen.findByText("Completed")).toBeInTheDocument();
    expect(m.endTrip).toHaveBeenCalledWith("t1");
    // A completed trip shows the marks, with no buttons and no more location sharing.
    expect(screen.queryByRole("group", { name: "Mark Asha Rao" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("trip-location")).not.toBeInTheDocument();
  });

  it("tells crew without a route today what to do", async () => {
    m.myTrips.mockResolvedValue(MINE([]));
    renderAs(ATTENDANT, <TripScreen />, ["BUS_ATTENDANT"]);
    expect(await screen.findByTestId("trip-no-routes")).toHaveTextContent("You have no route to run today.");
  });

  it("shows the error with a retry when the trips cannot be loaded", async () => {
    const { ApiError } = await import("@/lib/api");
    m.myTrips.mockRejectedValueOnce(new ApiError({ status: 500, title: "Server error" }));
    renderAs(ATTENDANT, <TripScreen />, ["BUS_ATTENDANT"]);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    m.myTrips.mockResolvedValue(MINE([route()]));
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByTestId("trip-route")).toBeInTheDocument();
  });
});

describe("trip rules", () => {
  it("takes boarded or absent in the morning and dropped too after school", () => {
    expect(marksFor("MORNING")).toEqual(["BOARDED", "ABSENT"]);
    expect(marksFor("AFTERNOON")).toEqual(["BOARDED", "DROPPED", "ABSENT"]);
  });

  it("opens the trip already running", () => {
    const brief = { id: "t", status: "RUNNING" as const, startedAt: "2026-10-12T09:00:00Z", endedAt: null };
    expect(runningTrip([route(), route({ routeId: "r2", afternoon: brief })])).toEqual({ routeId: "r2", kind: "AFTERNOON" });
    expect(runningTrip([route({ morning: { ...brief, status: "COMPLETED" } })])).toBeNull();
  });

  it("checks a delay like the API", () => {
    expect(validateDelay({ minutes: "15", reason: "Traffic" })).toEqual({});
    expect(validateDelay({ minutes: "0", reason: "" })).toEqual({ minutes: "transport.v.delayMinutes", reason: "validation.required" });
    expect(validateDelay({ minutes: "601", reason: "x".repeat(301) })).toEqual({
      minutes: "transport.v.delayMinutes",
      reason: "validation.tooLong",
    });
  });
});
