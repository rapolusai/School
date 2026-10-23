import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({ usePathname: () => "/app/govreport/rte", useRouter: () => ({ push: vi.fn() }) }));

import { validateIntake } from "./rte-view";

const values = (closeOn: string) => ({
  entryClassId: "c1",
  totalSeats: "40",
  rteSeats: "10",
  stateCode: "TS",
  incomeLimit: "100000",
  closeOn,
});

describe("validateIntake", () => {
  it("refuses an applications-close date before today", () => {
    expect(validateIntake(values("2001-01-01"), "2026-10-23").closeOn).toBe("govreport.v.closeOnPast");
    expect(validateIntake(values("2026-10-22"), "2026-10-23").closeOn).toBe("govreport.v.closeOnPast");
  });

  it("accepts today, a later date or no date", () => {
    expect(validateIntake(values("2026-10-23"), "2026-10-23")).toEqual({});
    expect(validateIntake(values("2027-03-31"), "2026-10-23")).toEqual({});
    expect(validateIntake(values(""), "2026-10-23")).toEqual({});
  });
});
