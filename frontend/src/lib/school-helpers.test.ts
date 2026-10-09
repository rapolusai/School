import { describe, expect, it } from "vitest";
import { studentQueryString } from "./api";
import { countCsvRows, csvCell, csvLine, IMPORT_COLUMNS, importTemplate } from "./csv";
import { classLabel, formatPhone, formatPlainDate, todayInIndia } from "./format";
import {
  isPlainDate,
  isValidAdmissionNo,
  isValidIndianMobile,
  isValidSchoolPhone,
  isWholeNumberInRange,
  normalizeIndianMobile,
  suggestYearName,
} from "./validation";

describe("Indian mobile numbers (same rule as the API)", () => {
  it("accepts 10 digits starting 6-9, with optional +91, 91 or 0 and spaces or hyphens", () => {
    for (const ok of ["9876500001", "+91 98765 00001", "91-98765-00001", "098765 00001", "6000000000"]) {
      expect(isValidIndianMobile(ok), ok).toBe(true);
    }
    for (const bad of ["5876500001", "98765", "98765000011", "+1 98765 00001", "phone", ""]) {
      expect(isValidIndianMobile(bad), bad).toBe(false);
    }
  });

  it("normalises to the ten digits the API stores", () => {
    expect(normalizeIndianMobile("+91 98765-00001")).toBe("9876500001");
    expect(normalizeIndianMobile("098765 00001")).toBe("9876500001");
    expect(normalizeIndianMobile("12345")).toBeNull();
  });
});

describe("student and school field rules", () => {
  it("checks admission numbers", () => {
    expect(isValidAdmissionNo("AKS/2026/001")).toBe(true);
    expect(isValidAdmissionNo("A-1.b_2")).toBe(true);
    expect(isValidAdmissionNo("/A")).toBe(false);
    expect(isValidAdmissionNo("A 1")).toBe(false);
    expect(isValidAdmissionNo("A".repeat(31))).toBe(false);
  });

  it("checks real calendar dates", () => {
    expect(isPlainDate("2026-06-01")).toBe(true);
    expect(isPlainDate("2025-02-29")).toBe(false);
    expect(isPlainDate("2024-02-29")).toBe(true);
    expect(isPlainDate("01-06-2026")).toBe(false);
  });

  it("checks whole numbers in a range", () => {
    expect(isWholeNumberInRange("40", 1, 500)).toBe(true);
    expect(isWholeNumberInRange("0", 1, 500)).toBe(false);
    expect(isWholeNumberInRange("4.5", 1, 500)).toBe(false);
    expect(isWholeNumberInRange("501", 1, 500)).toBe(false);
  });

  it("checks school phone numbers", () => {
    expect(isValidSchoolPhone("040 2345 6789")).toBe(true);
    expect(isValidSchoolPhone("+91 (40) 2345-6789")).toBe(true);
    expect(isValidSchoolPhone("12345")).toBe(false);
  });

  it("names an academic year from its start date", () => {
    expect(suggestYearName("2026-06-01")).toBe("2026-27");
    expect(suggestYearName("2099-04-01")).toBe("2099-00");
    expect(suggestYearName("")).toBe("");
  });
});

describe("formatting", () => {
  it("formats calendar dates without shifting the day", () => {
    expect(formatPlainDate("2026-06-01")).toBe("1 Jun 2026");
    expect(formatPlainDate("2027-03-31")).toBe("31 Mar 2027");
    expect(formatPlainDate(null)).toBe("");
    expect(formatPlainDate("not a date")).toBe("");
  });

  it("groups mobile numbers and joins class labels", () => {
    expect(formatPhone("9876500001")).toBe("98765 00001");
    expect(formatPhone("040 2345 6789")).toBe("040 2345 6789");
    expect(classLabel("Class 5", "A")).toBe("Class 5 A");
    expect(classLabel("Class 5", null)).toBe("Class 5");
  });

  it("gives today's date in India", () => {
    // 20:00 UTC on 9 Oct is already 10 Oct in India.
    expect(todayInIndia(new Date("2026-10-09T20:00:00Z"))).toBe("2026-10-10");
    expect(todayInIndia(new Date("2026-10-09T06:00:00Z"))).toBe("2026-10-09");
  });
});

describe("CSV helpers", () => {
  it("quotes only when needed", () => {
    expect(csvCell("plain")).toBe("plain");
    expect(csvCell("Kabir, Jr")).toBe('"Kabir, Jr"');
    expect(csvCell('say "hi"')).toBe('"say ""hi"""');
    expect(csvLine(["a", "b,c"])).toBe('a,"b,c"');
  });

  it("builds a template whose header matches the documented columns", () => {
    const [header, example] = importTemplate().trim().split("\r\n");
    expect(header.split(",").slice(0, IMPORT_COLUMNS.length)).toEqual([...IMPORT_COLUMNS]);
    expect(header.split(",")).toContain("roll_no");
    expect(example.split(",")).toHaveLength(header.split(",").length);
  });

  it("counts data rows, ignoring blank lines and quoted line breaks", () => {
    expect(countCsvRows("")).toBe(0);
    expect(countCsvRows("a,b\n")).toBe(0);
    expect(countCsvRows('﻿a,b\r\n1,2\r\n\r\n3,"x\ny"\r\n,,\n4,5')).toBe(3);
  });
});

describe("student list query", () => {
  it("sends only the filters that are set", () => {
    expect(studentQueryString({})).toBe("");
    expect(studentQueryString({ classId: "c1", q: "", page: 0, size: 25 })).toBe("?classId=c1&page=0&size=25");
    expect(studentQueryString({ q: "aks/2026" })).toBe("?q=aks%2F2026");
  });
});
