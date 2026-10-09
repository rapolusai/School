import { describe, expect, it } from "vitest";
import { isValidEmail, isValidSchoolCode, suggestSchoolCode } from "./validation";

describe("suggestSchoolCode", () => {
  it("slugifies the school name", () => {
    expect(suggestSchoolCode("Sunrise Public School")).toBe("sunrise-public-school");
    expect(suggestSchoolCode("St. Mary's Convent, Pune")).toBe("st-marys-convent-pune");
    expect(suggestSchoolCode("Delhi Public School & College")).toBe("delhi-public-school-and-college");
  });

  it("starts with a letter and drops accents", () => {
    expect(suggestSchoolCode("21st Century School")).toBe("st-century-school");
    expect(suggestSchoolCode("École Française")).toBe("ecole-francaise");
  });

  it("keeps codes within 3–40 characters", () => {
    const long = suggestSchoolCode(
      "The Very Long Name International Residential Senior Secondary School",
    );
    expect(long.length).toBeLessThanOrEqual(40);
    expect(isValidSchoolCode(long)).toBe(true);
    expect(suggestSchoolCode("AB")).toBe("ab-school");
  });

  it("returns empty when nothing usable is typed", () => {
    expect(suggestSchoolCode("")).toBe("");
    expect(suggestSchoolCode("123 !!")).toBe("");
    expect(suggestSchoolCode("सरस्वती विद्यालय")).toBe("");
  });

  it("always produces valid codes", () => {
    for (const name of ["Sunrise", "A B C", "Kendriya Vidyalaya No. 2", "x-y-z"]) {
      const code = suggestSchoolCode(name);
      expect(isValidSchoolCode(code), `${name} → ${code}`).toBe(true);
    }
  });
});

describe("isValidSchoolCode", () => {
  it("follows the contract: 3–40 of a-z 0-9 -, starting with a letter", () => {
    expect(isValidSchoolCode("abc")).toBe(true);
    expect(isValidSchoolCode("e2e-a-1234")).toBe(true);
    expect(isValidSchoolCode("ab")).toBe(false);
    expect(isValidSchoolCode("1abc")).toBe(false);
    expect(isValidSchoolCode("Abc")).toBe(false);
    expect(isValidSchoolCode("ab_c")).toBe(false);
    expect(isValidSchoolCode("a".repeat(40))).toBe(true);
    expect(isValidSchoolCode("a".repeat(41))).toBe(false);
  });
});

describe("isValidEmail", () => {
  it("accepts ordinary addresses and rejects obvious mistakes", () => {
    expect(isValidEmail("admin@school.edu")).toBe(true);
    expect(isValidEmail(" admin@school.edu ")).toBe(true);
    expect(isValidEmail("admin@school")).toBe(false);
    expect(isValidEmail("admin school.edu")).toBe(false);
  });
});
