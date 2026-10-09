import { describe, expect, it } from "vitest";
import {
  daysUntil,
  firstName,
  formatDate,
  formatDateTime,
  formatINR,
  formatLongDate,
  hourInIndia,
  humanizeCode,
  initials,
} from "./format";

describe("formatINR", () => {
  it("uses Indian lakh grouping with the rupee sign", () => {
    expect(formatINR(184300)).toBe("₹1,84,300");
    expect(formatINR(6150000)).toBe("₹61,50,000");
    expect(formatINR(123456789)).toBe("₹12,34,56,789");
  });

  it("handles small, zero, fractional and negative amounts", () => {
    expect(formatINR(999)).toBe("₹999");
    expect(formatINR(0)).toBe("₹0");
    expect(formatINR(1234.56)).toBe("₹1,235");
    expect(formatINR(-3850)).toBe("-₹3,850");
  });
});

describe("formatDate", () => {
  it("formats ISO dates as day month year (en-IN)", () => {
    expect(formatDate("2026-10-09")).toBe("9 Oct 2026");
    expect(formatDate("2026-10-09T10:15:00Z")).toBe("9 Oct 2026");
  });

  it("uses India time for instants near midnight UTC", () => {
    // 20:00 UTC on 8 Oct is 01:30 IST on 9 Oct.
    expect(formatDate("2026-10-08T20:00:00Z")).toBe("9 Oct 2026");
  });

  it("returns an empty string for missing or invalid input", () => {
    expect(formatDate(null)).toBe("");
    expect(formatDate(undefined)).toBe("");
    expect(formatDate("not a date")).toBe("");
  });
});

describe("other date helpers", () => {
  it("formats date-times and long dates", () => {
    expect(formatDateTime("2026-10-09T06:02:00Z")).toBe("9 Oct 2026, 11:32 am");
    expect(formatLongDate("2026-10-09T06:00:00Z")).toBe("Friday, 9 October 2026");
  });

  it("counts whole days remaining, rounding up", () => {
    const now = new Date("2026-10-09T06:00:00Z");
    expect(daysUntil("2026-10-23T06:00:00Z", now)).toBe(14);
    expect(daysUntil("2026-10-09T18:00:00Z", now)).toBe(1);
    expect(daysUntil("2026-10-01T00:00:00Z", now)).toBeLessThan(0);
  });

  it("reads the hour in IST", () => {
    expect(hourInIndia(new Date("2026-10-09T03:00:00Z"))).toBe(8);
    expect(hourInIndia(new Date("2026-10-09T18:45:00Z"))).toBe(0);
  });
});

describe("name helpers", () => {
  it("derives initials, first names and readable codes", () => {
    expect(initials("Asha Rao Menon")).toBe("AR");
    expect(initials("  kiran ")).toBe("K");
    expect(firstName("Asha Rao")).toBe("Asha");
    expect(humanizeCode("SCHOOL_ADMIN")).toBe("School admin");
  });
});
