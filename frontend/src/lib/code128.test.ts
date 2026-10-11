import { describe, expect, it } from "vitest";
import {
  CODE128_PATTERNS,
  code128Bars,
  code128Check,
  code128Values,
  code128Widths,
  Code128Error,
  CODE_B,
  CODE_C,
  QUIET_ZONE,
  START_B,
  START_C,
  STOP,
} from "./code128";

const sum = (values: readonly number[]) => values.reduce((a, b) => a + b, 0);

describe("the Code 128 table", () => {
  it("has 107 symbols of 11 modules (three bars, three spaces) and a 13-module stop", () => {
    expect(CODE128_PATTERNS).toHaveLength(107);
    CODE128_PATTERNS.slice(0, STOP).forEach((pattern, value) => {
      expect(pattern, String(value)).toMatch(/^[1-4]{6}$/);
      expect(sum([...pattern].map(Number)), String(value)).toBe(11);
    });
    expect(CODE128_PATTERNS[STOP]).toBe("2331112");
    expect(CODE128_PATTERNS[START_B]).toBe("211214");
    expect(CODE128_PATTERNS[START_C]).toBe("211232");
  });

  it("has every pattern once, so a scanner can tell the symbols apart", () => {
    expect(new Set(CODE128_PATTERNS).size).toBe(107);
  });
});

describe("code128Values", () => {
  it("encodes text in code set B with the check symbol (the well-known Wikipedia example)", () => {
    // W i k i p e d i a → ASCII − 32; check = (104 + Σ position × value) mod 103 = 3281 mod 103 = 88
    expect(code128Values("Wikipedia")).toEqual([START_B, 55, 73, 75, 73, 80, 69, 68, 73, 65, 88]);
  });

  it("packs an even number of digits two to a symbol in code set C", () => {
    // 105 + 1×12 + 2×34 + 3×56 = 353; 353 mod 103 = 44
    expect(code128Values("123456")).toEqual([START_C, 12, 34, 56, 44]);
  });

  it("switches to code set C for the running number of an accession number, leaving an odd digit in B", () => {
    // L I B 0 | C: 00 42 → 104 + 44 + 82 + 102 + 64 + 495 + 0 + 294 = 1185; 1185 mod 103 = 52
    expect(code128Values("LIB00042")).toEqual([START_B, 44, 41, 34, 16, CODE_C, 0, 42, 52]);
    // An odd number of digits starts in B too
    const odd = code128Values("12345");
    expect(odd.slice(0, 5)).toEqual([START_B, 17, CODE_C, 23, 45]);
  });

  it("keeps short digit runs in B and switches back to B after a long run in the middle", () => {
    const short = code128Values("AB1234CD");
    expect(short).not.toContain(CODE_C);
    expect(short).toHaveLength(1 + 8 + 1);
    const long = code128Values("AB123456CD");
    expect(long.slice(0, -1)).toEqual([START_B, 33, 34, CODE_C, 12, 34, 56, CODE_B, 35, 36]);
    expect(long.at(-1)).toBe(code128Check(long.slice(0, -1)));
  });

  it("encodes the characters accession numbers use: capitals, digits, / and -", () => {
    const values = code128Values("KV/2026-7");
    expect(values[0]).toBe(START_B);
    expect(values.slice(1, 4)).toEqual([43, 54, 15]); // K V /
  });

  it("refuses empty text and characters outside printable ASCII", () => {
    expect(() => code128Values("")).toThrow(Code128Error);
    expect(() => code128Values("café")).toThrow(Code128Error);
    expect(() => code128Values("LIB\n1")).toThrow(Code128Error);
  });
});

describe("code128Check", () => {
  it("weights each data symbol by its position and takes the remainder by 103", () => {
    expect(code128Check([START_B, 1])).toBe(105 % 103);
    expect(code128Check([START_C, 12, 34, 56])).toBe(44);
    expect(code128Check([START_B])).toBe(1);
  });
});

describe("code128Widths and code128Bars", () => {
  it("starts with the start symbol and ends with the stop symbol", () => {
    const widths = code128Widths("Wikipedia");
    expect(widths.slice(0, 6).join("")).toBe("211214");
    expect(widths.slice(-13, -7).join("")).toBe(CODE128_PATTERNS[88]);
    expect(widths.slice(-7).join("")).toBe("2331112");
    // 11 modules for each of start, 9 data and check, 13 for the stop
    expect(sum(widths)).toBe(11 * 11 + 13);
  });

  it("places bars only, after a quiet zone, and counts the quiet zones in the width", () => {
    const { bars, modules } = code128Bars("123456");
    const widths = code128Widths("123456");
    expect(modules).toBe(QUIET_ZONE + sum(widths) + QUIET_ZONE);
    expect(bars[0]).toEqual([QUIET_ZONE, 2]);
    // three bars in each of the five symbols (start, three pairs, check) and four in the stop
    expect(bars).toHaveLength(5 * 3 + 4);
    const last = bars.at(-1)!;
    expect(last[0] + last[1]).toBe(modules - QUIET_ZONE);
    // bars never overlap
    for (let i = 1; i < bars.length; i++) expect(bars[i][0]).toBeGreaterThan(bars[i - 1][0] + bars[i - 1][1] - 1);
  });

  it("gives a short label for a typical accession number", () => {
    expect(code128Bars("LIB00042").modules).toBe(QUIET_ZONE * 2 + 11 * 9 + 13);
  });
});
