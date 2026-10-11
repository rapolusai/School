import { describe, expect, it } from "vitest";
import { translate, type Translate } from "@/lib/i18n";
import { timeOfDay, transportReportPaths } from "@/lib/transport-api";
import { allocationDates, validateAllocation } from "./allocations-view";
import { validateCrew } from "./crew-view";
import { stopRequests, validateStops, type StopRow } from "./route-detail-view";
import { validateRoute } from "./routes-view";
import { validateZone } from "./settings-view";
import { documentStateText, flagTone } from "./transport-shared";
import { validateVehicle } from "./vehicles-view";

const t: Translate = (key, vars) => translate("en", key, vars);

const row = (overrides: Partial<StopRow> = {}): StopRow => ({
  key: "k",
  id: null,
  name: "Gate Stop",
  landmark: "",
  pickupTime: "07:10",
  dropTime: "",
  latitude: "",
  longitude: "",
  feeZoneId: "",
  students: 0,
  ...overrides,
});

describe("transport form checks (the API's rules)", () => {
  it("checks a vehicle's registration, seats and dates", () => {
    const ok = {
      registrationNo: "ts09  ub 4410",
      type: "BUS" as const,
      seats: "40",
      gpsDeviceId: "",
      insuranceExpiry: "2027-03-31",
      fitnessExpiry: "",
      permitExpiry: "",
      pollutionExpiry: "",
      status: "ACTIVE" as const,
    };
    expect(validateVehicle(ok)).toEqual({});
    expect(validateVehicle({ ...ok, registrationNo: "T9", seats: "0", fitnessExpiry: "31/03/2027" })).toEqual({
      registrationNo: "transport.v.registration",
      seats: "transport.v.seats",
      fitnessExpiry: "validation.date",
    });
    expect(validateVehicle({ ...ok, registrationNo: " ", seats: "101" })).toMatchObject({
      registrationNo: "validation.required",
      seats: "transport.v.seats",
    });
  });

  it("needs a licence and its expiry for a driver only", () => {
    const driver = { userId: "u1", kind: "DRIVER" as const, licenceNo: "", licenceExpiry: "", policeVerifiedOn: "" };
    expect(validateCrew(driver)).toEqual({ licenceNo: "validation.required", licenceExpiry: "validation.required" });
    expect(validateCrew({ ...driver, kind: "ATTENDANT" })).toEqual({});
    expect(validateCrew({ ...driver, licenceNo: "TS0920190012345", licenceExpiry: "2030-01-01" })).toEqual({});
    expect(validateCrew({ ...driver, userId: "", licenceNo: "AB#1", licenceExpiry: "2030-01-01" })).toEqual({
      userId: "transport.v.staff",
      licenceNo: "transport.v.licence",
    });
    // Editing keeps the member of staff.
    expect(validateCrew({ ...driver, userId: "", kind: "ATTENDANT" }, false)).toEqual({});
  });

  it("needs a route name and at least one trip", () => {
    const route = { name: "Route 1", vehicleId: "", driverId: "", attendantId: "", runsMorning: true, runsAfternoon: false, active: true };
    expect(validateRoute(route)).toEqual({});
    expect(validateRoute({ ...route, name: "", runsMorning: false })).toEqual({
      name: "validation.required",
      runsMorning: "transport.v.trips",
    });
  });

  it("checks each stop by its place in the list, with both coordinates or neither", () => {
    expect(validateStops([row(), row({ name: "Market Stop", latitude: "17.42", longitude: "78.44" })])).toEqual({});
    expect(
      validateStops([row({ name: "", pickupTime: "7.10" }), row({ latitude: "17.42" }), row({ latitude: "95", longitude: "78" })]),
    ).toEqual({
      "stops[0].name": "validation.required",
      "stops[0].pickupTime": "transport.v.time",
      "stops[1].longitude": "transport.v.longitude",
      "stops[2].latitude": "transport.v.latitude",
    });
  });

  it("sends the stops in their order, keeping existing ids", () => {
    expect(
      stopRequests([
        row({ id: "st1", name: " Gate Stop ", landmark: " Post office ", latitude: "17.41", longitude: "78.44", feeZoneId: "z1" }),
        row({ name: "Lake Stop", pickupTime: "", dropTime: "15:20" }),
      ]),
    ).toEqual([
      { id: "st1", name: "Gate Stop", landmark: "Post office", pickupTime: "07:10", dropTime: null, latitude: 17.41, longitude: 78.44, feeZoneId: "z1" },
      { id: null, name: "Lake Stop", landmark: null, pickupTime: null, dropTime: "15:20", latitude: null, longitude: null, feeZoneId: null },
    ]);
  });

  it("checks an allocation's dates", () => {
    const a = { studentId: "s1", routeId: "r1", stopId: "st1", startDate: "2026-10-12", endDate: "", overCapacityReason: "" };
    expect(validateAllocation(a)).toEqual({});
    expect(validateAllocation({ ...a, studentId: "", stopId: "", endDate: "2026-10-01" })).toEqual({
      studentId: "transport.v.student",
      stopId: "validation.choose",
      endDate: "transport.v.endBeforeStart",
    });
    // A class allocation has no single student.
    expect(validateAllocation({ ...a, studentId: undefined }, false)).toEqual({});
  });

  it("checks a fee zone's distances and fee", () => {
    expect(validateZone({ name: "Zone A", minKm: "0", maxKm: "5", annualFee: "12,000" })).toEqual({});
    expect(validateZone({ name: "Zone C", minKm: "10", maxKm: "", annualFee: "20000" })).toEqual({});
    expect(validateZone({ name: "", minKm: "5", maxKm: "5", annualFee: "abc" })).toEqual({
      name: "validation.required",
      maxKm: "transport.v.maxKm",
      annualFeePaise: "transport.v.fee",
    });
  });
});

describe("transport words", () => {
  it("describes a document's state with the days left", () => {
    expect(documentStateText(t, "EXPIRING", 12)).toBe("Expires in 12 days");
    expect(documentStateText(t, "EXPIRING", 1)).toBe("Expires in 1 day");
    expect(documentStateText(t, "EXPIRING", 0)).toBe("Expires today");
    expect(documentStateText(t, "EXPIRED", -3)).toBe("Expired 3 days ago");
    expect(documentStateText(t, "MISSING", null)).toBe("No date on record");
    expect(documentStateText(t, "VALID", 200)).toBe("Valid");
    expect(flagTone("FITNESS_INVALID")).toBe("bad");
    expect(flagTone("DOCUMENT_EXPIRING")).toBe("warn");
  });

  it("writes an allocation's dates", () => {
    const base = { startDate: "2026-06-01", endDate: null, status: "ACTIVE" as const, current: true };
    expect(allocationDates(t, base, "en-IN")).toBe("Since 1 Jun 2026");
    expect(allocationDates(t, { ...base, current: false, startDate: "2026-11-01" }, "en-IN")).toBe("From 1 Nov 2026");
    expect(allocationDates(t, { ...base, endDate: "2026-09-30" }, "en-IN")).toBe("1 Jun 2026 to 30 Sept 2026");
    expect(allocationDates(t, { ...base, status: "ENDED", endDate: "2026-09-30" }, "en-IN")).toBe("Ended 30 Sept 2026");
  });

  it("builds the report paths with only the filters set, and reads times of day", () => {
    expect(transportReportPaths.boardingCsv({ date: "2026-10-12" })).toBe("/api/transport/reports/boarding.csv?date=2026-10-12");
    expect(transportReportPaths.documents({ withinDays: 0 })).toBe("/api/transport/reports/documents?withinDays=0");
    expect(transportReportPaths.fees({})).toBe("/api/transport/reports/fees");
    expect(timeOfDay("07:10:00")).toBe("07:10");
    expect(timeOfDay(null)).toBe("");
  });
});
