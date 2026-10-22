import { describe, expect, it } from "vitest";
import {
  dataCodewords,
  ecCodewords,
  formatBits,
  interleave,
  modeFor,
  qrMatrix,
  qrPath,
  versionBits,
  versionFor,
  VERSIONS_M,
  type QrMatrix,
} from "./qr";

/* Known vectors: the ISO/IEC 18004 worked example "HELLO WORLD" at 1-M, and the format and version information
   tables of the standard. */

const HELLO_DATA = [32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17];
const HELLO_EC = [196, 35, 39, 119, 235, 215, 231, 226, 93, 23];

/** Reads the format bits back out of the symbol (the copy around the top-left finder). */
function readFormat(m: QrMatrix): number {
  let bits = 0;
  const bit = (row: number, col: number, i: number) => {
    if (m.modules[row][col]) bits |= 1 << i;
  };
  for (let i = 0; i <= 5; i++) bit(i, 8, i);
  bit(7, 8, 6);
  bit(8, 8, 7);
  bit(8, 7, 8);
  for (let i = 9; i < 15; i++) bit(8, 14 - i, i);
  return bits;
}

describe("qr encoding", () => {
  it("picks alphanumeric mode only for the alphanumeric set", () => {
    expect(modeFor("HELLO WORLD")).toBe("alphanumeric");
    expect(modeFor("ABC-123/$%*+.:")).toBe("alphanumeric");
    expect(modeFor("hello")).toBe("byte");
    expect(modeFor("https://x.test/verify/Ab12")).toBe("byte");
  });

  it("encodes HELLO WORLD at 1-M as the standard's example", () => {
    expect(versionFor("HELLO WORLD")).toBe(1);
    expect(dataCodewords("HELLO WORLD", "alphanumeric", 1)).toEqual(HELLO_DATA);
    expect(ecCodewords(HELLO_DATA, 10)).toEqual(HELLO_EC);
    expect(interleave(HELLO_DATA, 1)).toEqual([...HELLO_DATA, ...HELLO_EC]);
  });

  it("computes the format information of the standard's table", () => {
    expect(formatBits("M", 0)).toBe(0x5412);
    expect(formatBits("L", 0)).toBe(0x77c4);
    expect(formatBits("Q", 0)).toBe(0x355f);
    expect(formatBits("H", 0)).toBe(0x1689);
    expect(formatBits("M", 1)).toBe(0x5125);
    expect(formatBits("M", 7)).toBe(0x4aa0);
  });

  it("computes the version information of versions 7 to 10", () => {
    expect(versionBits(7)).toBe(0x07c94);
    expect(versionBits(8)).toBe(0x085bc);
    expect(versionBits(9)).toBe(0x09a99);
    expect(versionBits(10)).toBe(0x0a4d3);
  });

  it("chooses the smallest version that holds the text, up to version 10", () => {
    // Byte mode: 4 + 8 bits of header, then 8 bits a byte; version 1 M holds 16 data codewords.
    expect(versionFor("a".repeat(14))).toBe(1);
    expect(versionFor("a".repeat(15))).toBe(2);
    expect(versionFor(`https://school.example.com/verify/${"A1b2C3d4E5f6G7h8I9j0K1l2"}`)).toBe(4);
    const biggest = VERSIONS_M[10].total - VERSIONS_M[10].ecPerBlock * VERSIONS_M[10].blocks.length;
    // Version 10 counts bytes in 16 bits: 4 + 16 + 8n ≤ 8 × 216.
    expect(versionFor("a".repeat(biggest - 3))).toBe(10);
    expect(versionFor("a".repeat(biggest - 2))).toBeNull();
    expect(() => qrMatrix("a".repeat(400))).toThrow();
  });

  it("interleaves the blocks of a multi-block version", () => {
    // Version 4-M: two blocks of 32 data codewords, 18 EC each.
    const data = Array.from({ length: 64 }, (_, i) => i);
    const out = interleave(data, 4);
    expect(out).toHaveLength(100);
    expect(out.slice(0, 4)).toEqual([0, 32, 1, 33]);
    expect(out.slice(64, 66)).toEqual([ecCodewords(data.slice(0, 32), 18)[0], ecCodewords(data.slice(32), 18)[0]]);
  });
});

describe("qrMatrix", () => {
  it("draws the finder patterns, timing, dark module and format bits of a version 1 symbol", () => {
    const m = qrMatrix("HELLO WORLD");
    expect(m.version).toBe(1);
    expect(m.size).toBe(21);
    const finderRow = [true, true, true, true, true, true, true];
    const ring = [true, false, false, false, false, false, true];
    for (const [top, left] of [
      [0, 0],
      [0, 14],
      [14, 0],
    ]) {
      expect(m.modules[top].slice(left, left + 7)).toEqual(finderRow);
      expect(m.modules[top + 1].slice(left, left + 7)).toEqual(ring);
      expect(m.modules[top + 3].slice(left, left + 7)).toEqual([true, false, true, true, true, false, true]);
      expect(m.modules[top + 6].slice(left, left + 7)).toEqual(finderRow);
    }
    // Separators are light; the timing pattern alternates from the finders; the dark module is dark.
    expect(m.modules[7].slice(0, 8).every((c) => !c)).toBe(true);
    expect(m.modules[6].slice(8, 13)).toEqual([true, false, true, false, true]);
    expect(m.modules[13][8]).toBe(true);
    expect(readFormat(m)).toBe(formatBits("M", m.mask));
    // The second copy of the format bits, below the top-right finder and right of the bottom-left one.
    for (let i = 0; i < 8; i++) expect(m.modules[8][20 - i]).toBe(((formatBits("M", m.mask) >>> i) & 1) === 1);
  });

  it("places alignment and version information on larger symbols", () => {
    // 150 bytes: version 7 holds 124 data codewords, version 8 holds 154.
    const m = qrMatrix("a".repeat(150));
    expect(m.version).toBe(8);
    expect(m.size).toBe(49);
    // Alignment patterns centred at 6, 24 and 42: dark centre, light ring, dark outer ring.
    expect(m.modules[24][24]).toBe(true);
    expect(m.modules[23][24]).toBe(false);
    expect(m.modules[22][24]).toBe(true);
    expect(m.modules[24].slice(22, 27)).toEqual([true, false, true, false, true]);
    expect(m.modules[42].slice(40, 45)).toEqual([true, false, true, false, true]);
    // Version bits: bit i at (floor(i/3), size-11+i%3) and mirrored.
    const bits = versionBits(8);
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >>> i) & 1) === 1;
      expect(m.modules[Math.floor(i / 3)][49 - 11 + (i % 3)]).toBe(dark);
      expect(m.modules[49 - 11 + (i % 3)][Math.floor(i / 3)]).toBe(dark);
    }
  });

  it("uses the mask with the lowest penalty and keeps the same modules whatever mask is forced", () => {
    const chosen = qrMatrix("HELLO WORLD");
    expect(chosen.mask).toBeGreaterThanOrEqual(0);
    expect(chosen.mask).toBeLessThan(8);
    const forced = qrMatrix("HELLO WORLD", { mask: chosen.mask });
    expect(forced.modules).toEqual(chosen.modules);
    // A different mask changes the data area but never the function patterns.
    const other = qrMatrix("HELLO WORLD", { mask: (chosen.mask + 1) % 8 });
    expect(other.modules[0].slice(0, 8)).toEqual(chosen.modules[0].slice(0, 8));
    expect(other.modules[0].slice(13)).toEqual(chosen.modules[0].slice(13));
    expect(other.modules).not.toEqual(chosen.modules);
  });

  it("round-trips the codewords through the symbol", () => {
    // Unmask the data area and read the codewords back in zigzag order: what went in must come out.
    const text = "AKSHARA/2026-27/0001";
    const m = qrMatrix(text);
    const masks = [
      (y: number, x: number) => (x + y) % 2 === 0,
      (y: number) => y % 2 === 0,
      (_y: number, x: number) => x % 3 === 0,
      (y: number, x: number) => (x + y) % 3 === 0,
      (y: number, x: number) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
      (y: number, x: number) => ((x * y) % 2) + ((x * y) % 3) === 0,
      (y: number, x: number) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
      (y: number, x: number) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
    ];
    const n = m.size;
    const isFunction = (row: number, col: number) =>
      (row < 9 && col < 9) || (row < 9 && col >= n - 8) || (row >= n - 8 && col < 9) || row === 6 || col === 6;
    const bits: number[] = [];
    for (let right = n - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < n; vert++) {
        for (let j = 0; j < 2; j++) {
          const col = right - j;
          const row = ((right + 1) & 2) === 0 ? n - 1 - vert : vert;
          if (!isFunction(row, col)) bits.push(m.modules[row][col] !== masks[m.mask](row, col) ? 1 : 0);
        }
      }
    }
    const expected = interleave(dataCodewords(text, "alphanumeric", 1), 1);
    const read: number[] = [];
    for (let i = 0; i + 8 <= expected.length * 8; i += 8) read.push(parseInt(bits.slice(i, i + 8).join(""), 2));
    expect(read).toEqual(expected);
  });

  it("writes an SVG path of the dark modules", () => {
    const m = qrMatrix("HELLO WORLD");
    const path = qrPath(m);
    expect(path.startsWith("M0 0h7v1h-7z")).toBe(true);
    expect(path).toContain("M14 0h7v1h-7z");
    expect((path.match(/M/g) ?? []).length).toBeGreaterThan(50);
  });
});
