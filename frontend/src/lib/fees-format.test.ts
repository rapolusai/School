import { describe, expect, it } from "vitest";
import { rangeProblem } from "@/components/views/fees/overview-view";
import { describeLateFee, toLateFeeRule, validateLateFee } from "@/components/views/fees/settings-view";
import { feesExports, feesQuery } from "./fees-api";
import { formatPaise, parseRupees, rupeesInput } from "./format";
import { translate, type Translate } from "./i18n";

const t: Translate = (key, vars) => translate("en", key, vars);

describe("formatPaise", () => {
  it("shows rupees with lakh and crore grouping, and paise only when there are some", () => {
    expect(formatPaise(1_84_300_00)).toBe("₹1,84,300");
    expect(formatPaise(1_84_300_50)).toBe("₹1,84,300.50");
    expect(formatPaise(1_23_45_678_00)).toBe("₹1,23,45,678");
    expect(formatPaise(5)).toBe("₹0.05");
    expect(formatPaise(0)).toBe("₹0");
    expect(formatPaise(-450_00)).toBe("-₹450");
  });
});

describe("parseRupees", () => {
  it("reads typed rupees as paise", () => {
    expect(parseRupees("184300")).toBe(1_84_300_00);
    expect(parseRupees("1,84,300")).toBe(1_84_300_00);
    expect(parseRupees("₹ 4500.5")).toBe(4_500_50);
    expect(parseRupees("450.05")).toBe(450_05);
    expect(parseRupees("12.")).toBe(12_00);
  });

  it("refuses anything that is not a non-negative amount with two decimals at most", () => {
    for (const text of ["", "abc", "-5", "1.234", "1e5", "12..5", "0x10"]) expect(parseRupees(text), text).toBeNull();
  });

  it("round-trips through the text an input starts with", () => {
    expect(rupeesInput(11_500_00)).toBe("11500");
    expect(rupeesInput(450_50)).toBe("450.50");
    expect(rupeesInput(5)).toBe("0.05");
    expect(parseRupees(rupeesInput(98_765_43))).toBe(98_765_43);
  });
});

describe("fees API helpers", () => {
  it("builds query strings from the filters that are set", () => {
    expect(feesQuery({ yearId: undefined, q: "", page: 0, size: 25 })).toBe("?page=0&size=25");
    expect(feesQuery({})).toBe("");
    expect(feesExports.tally("2026-04-01", "2027-03-31")).toBe("/api/fees/reports/tally.csv?from=2026-04-01&to=2027-03-31");
  });

  it("checks report ranges like the API", () => {
    expect(rangeProblem("2026-10-01", "2026-10-09")).toBeNull();
    expect(rangeProblem("2026-10-09", "2026-10-01")).toBe("fees.v.range");
    expect(rangeProblem("2024-01-01", "2026-10-09")).toBe("fees.v.rangeTooLong");
    expect(rangeProblem("2026-13-01", "2026-10-09")).toBe("validation.date");
  });
});

describe("late fee rule", () => {
  it("mirrors the API's checks", () => {
    const base = { graceDays: "7", flat: "", perDay: "", cap: "" };
    expect(validateLateFee({ mode: "NONE", ...base, graceDays: "" })).toEqual({});
    expect(validateLateFee({ mode: "FLAT", ...base })).toEqual({ flatPaise: "fees.v.amount" });
    expect(validateLateFee({ mode: "PER_DAY", ...base, perDay: "10", cap: "5" })).toEqual({
      capPaise: "fees.v.capBelowDay",
    });
    expect(validateLateFee({ mode: "PER_DAY", ...base, graceDays: "400", perDay: "10" })).toEqual({
      graceDays: "fees.v.graceDays",
    });
    expect(toLateFeeRule({ mode: "PER_DAY", ...base, perDay: "10", cap: "500" })).toEqual({
      mode: "PER_DAY",
      graceDays: 7,
      flatPaise: 0,
      perDayPaise: 10_00,
      capPaise: 500_00,
    });
  });

  it("describes the rule in one sentence", () => {
    expect(describeLateFee({ mode: "PER_DAY", graceDays: 7, flatPaise: 0, perDayPaise: 10_00, capPaise: 500_00 }, t)).toBe(
      "₹10 a day from 7 days after the due date, at most ₹500 per instalment.",
    );
    expect(describeLateFee({ mode: "NONE", graceDays: 0, flatPaise: 0, perDayPaise: 0, capPaise: 0 }, t)).toBe(
      "No late fee is charged.",
    );
  });
});
