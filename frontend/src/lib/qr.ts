/*
 * A small QR code encoder (ISO/IEC 18004) for the verification link printed on certificates and ID cards.
 * Alphanumeric and byte modes, versions 1 to 10, error correction level M (L, Q and H are supported for the format
 * bits only). No dependencies. The result is a matrix of modules; `qrPath` turns it into an SVG path.
 */

export type EcLevel = "L" | "M" | "Q" | "H";

export type QrMatrix = {
  /** The symbol version, 1 to 10. */
  version: number;
  /** Modules per side: 17 + 4 × version. */
  size: number;
  /** The mask pattern used, 0 to 7. */
  mask: number;
  /** modules[row][column]: true is dark. */
  modules: boolean[][];
};

/** The format information's error correction bits: L=01, M=00, Q=11, H=10. */
const EC_FORMAT_BITS: Record<EcLevel, number> = { L: 1, M: 0, Q: 3, H: 2 };

/** Per version (index 1..10) at level M: total codewords, EC codewords per block and the data codewords of each block. */
export const VERSIONS_M: readonly { total: number; ecPerBlock: number; blocks: readonly number[] }[] = [
  { total: 0, ecPerBlock: 0, blocks: [] },
  { total: 26, ecPerBlock: 10, blocks: [16] },
  { total: 44, ecPerBlock: 16, blocks: [28] },
  { total: 70, ecPerBlock: 26, blocks: [44] },
  { total: 100, ecPerBlock: 18, blocks: [32, 32] },
  { total: 134, ecPerBlock: 24, blocks: [43, 43] },
  { total: 172, ecPerBlock: 16, blocks: [27, 27, 27, 27] },
  { total: 196, ecPerBlock: 18, blocks: [31, 31, 31, 31] },
  { total: 242, ecPerBlock: 22, blocks: [38, 38, 39, 39] },
  { total: 292, ecPerBlock: 22, blocks: [36, 36, 36, 37, 37] },
  { total: 346, ecPerBlock: 26, blocks: [43, 43, 43, 43, 44] },
];

/** Alignment pattern centres per version (none for version 1). */
const ALIGNMENT: readonly (readonly number[])[] = [
  [],
  [],
  [6, 18],
  [6, 22],
  [6, 26],
  [6, 30],
  [6, 34],
  [6, 22, 38],
  [6, 24, 42],
  [6, 26, 46],
  [6, 28, 50],
];

/** Remainder bits after the codewords, per version. */
const REMAINDER_BITS = [0, 0, 7, 7, 7, 7, 7, 0, 0, 0, 0];

export const MAX_VERSION = 10;

const ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

export type QrMode = "alphanumeric" | "byte";

/** Alphanumeric when every character is in the QR alphanumeric set (digits, capitals, space, $%*+-./:), else byte. */
export function modeFor(text: string): QrMode {
  for (const ch of text) if (!ALPHANUMERIC.includes(ch)) return "byte";
  return "alphanumeric";
}

/* ------------------------------------------------------------------ bits */

class BitBuffer {
  bits: number[] = [];

  append(value: number, length: number) {
    for (let i = length - 1; i >= 0; i--) this.bits.push((value >>> i) & 1);
  }

  get length() {
    return this.bits.length;
  }
}

/** The data bits of the text in the mode, before the terminator and padding. Exported for tests. */
export function dataBits(text: string, mode: QrMode, version: number): number[] {
  const buffer = new BitBuffer();
  if (mode === "alphanumeric") {
    buffer.append(0b0010, 4);
    buffer.append(text.length, version <= 9 ? 9 : 11);
    for (let i = 0; i + 1 < text.length; i += 2) {
      buffer.append(ALPHANUMERIC.indexOf(text[i]) * 45 + ALPHANUMERIC.indexOf(text[i + 1]), 11);
    }
    if (text.length % 2 === 1) buffer.append(ALPHANUMERIC.indexOf(text[text.length - 1]), 6);
  } else {
    const bytes = new TextEncoder().encode(text);
    buffer.append(0b0100, 4);
    buffer.append(bytes.length, version <= 9 ? 8 : 16);
    for (const b of bytes) buffer.append(b, 8);
  }
  return buffer.bits;
}

function bitLength(text: string, mode: QrMode, version: number): number {
  if (mode === "alphanumeric") {
    return 4 + (version <= 9 ? 9 : 11) + Math.floor(text.length / 2) * 11 + (text.length % 2) * 6;
  }
  return 4 + (version <= 9 ? 8 : 16) + new TextEncoder().encode(text).length * 8;
}

/** The smallest version (1 to 10) whose level-M data capacity holds the text, or null when none does. */
export function versionFor(text: string, mode: QrMode = modeFor(text)): number | null {
  for (let v = 1; v <= MAX_VERSION; v++) {
    const { total, ecPerBlock, blocks } = VERSIONS_M[v];
    const dataCodewords = total - ecPerBlock * blocks.length;
    if (bitLength(text, mode, v) <= dataCodewords * 8) return v;
  }
  return null;
}

/** The data codewords: mode, count, data, terminator, byte padding and the 0xEC/0x11 pad bytes. Exported for tests. */
export function dataCodewords(text: string, mode: QrMode, version: number): number[] {
  const { total, ecPerBlock, blocks } = VERSIONS_M[version];
  const capacity = (total - ecPerBlock * blocks.length) * 8;
  const bits = dataBits(text, mode, version);
  if (bits.length > capacity) throw new Error("Text too long for the version");
  for (let i = 0; i < 4 && bits.length < capacity; i++) bits.push(0);
  while (bits.length % 8 !== 0) bits.push(0);
  const codewords: number[] = [];
  for (let i = 0; i < bits.length; i += 8) {
    let b = 0;
    for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    codewords.push(b);
  }
  for (let pad = 0xec; codewords.length < capacity / 8; pad ^= 0xec ^ 0x11) codewords.push(pad);
  return codewords;
}

/* ------------------------------------------------------------------ Reed–Solomon over GF(256), polynomial 0x11D */

function gfMultiply(x: number, y: number): number {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}

/** The generator polynomial's coefficients (without the leading 1) for this many EC codewords. */
function rsDivisor(degree: number): number[] {
  const result = new Array<number>(degree).fill(0);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      result[j] = gfMultiply(result[j], root) ^ (j + 1 < degree ? result[j + 1] : 0);
    }
    root = gfMultiply(root, 2);
  }
  return result;
}

/** The EC codewords of a block of data codewords. Exported for tests. */
export function ecCodewords(data: readonly number[], degree: number): number[] {
  const divisor = rsDivisor(degree);
  const result = new Array<number>(degree).fill(0);
  for (const b of data) {
    const factor = b ^ (result.shift() as number);
    result.push(0);
    for (let i = 0; i < degree; i++) result[i] ^= gfMultiply(divisor[i], factor);
  }
  return result;
}

/** Data codewords split into blocks, each with its EC codewords, interleaved as they go into the symbol. */
export function interleave(data: readonly number[], version: number): number[] {
  const { ecPerBlock, blocks } = VERSIONS_M[version];
  const dataBlocks: number[][] = [];
  const ecBlocks: number[][] = [];
  let offset = 0;
  for (const length of blocks) {
    const block = data.slice(offset, offset + length);
    offset += length;
    dataBlocks.push(block);
    ecBlocks.push(ecCodewords(block, ecPerBlock));
  }
  const out: number[] = [];
  const longest = Math.max(...blocks);
  for (let i = 0; i < longest; i++) for (const block of dataBlocks) if (i < block.length) out.push(block[i]);
  for (let i = 0; i < ecPerBlock; i++) for (const block of ecBlocks) out.push(block[i]);
  return out;
}

/* ------------------------------------------------------------------ format and version information */

/** The 15 format bits (BCH coded and masked) for an EC level and mask pattern. */
export function formatBits(level: EcLevel, mask: number): number {
  const data = (EC_FORMAT_BITS[level] << 3) | mask;
  let rem = data;
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
  return ((data << 10) | rem) ^ 0x5412;
}

/** The 18 version information bits (versions 7 and up). */
export function versionBits(version: number): number {
  let rem = version;
  for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
  return (version << 12) | rem;
}

/* ------------------------------------------------------------------ the matrix */

const MASKS: readonly ((row: number, col: number) => boolean)[] = [
  (y, x) => (x + y) % 2 === 0,
  (y) => y % 2 === 0,
  (_y, x) => x % 3 === 0,
  (y, x) => (x + y) % 3 === 0,
  (y, x) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
  (y, x) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (y, x) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (y, x) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
];

class Symbol {
  readonly size: number;
  readonly modules: boolean[][];
  readonly isFunction: boolean[][];

  constructor(readonly version: number) {
    this.size = 17 + 4 * version;
    this.modules = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false));
    this.isFunction = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false));
  }

  private set(row: number, col: number, dark: boolean) {
    this.modules[row][col] = dark;
    this.isFunction[row][col] = true;
  }

  private finder(row: number, col: number) {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const r = row + dy;
        const c = col + dx;
        if (r < 0 || r >= this.size || c < 0 || c >= this.size) continue;
        const d = Math.max(Math.abs(dx), Math.abs(dy));
        this.set(r, c, d !== 2 && d !== 4);
      }
    }
  }

  private alignment(row: number, col: number) {
    for (let dy = -2; dy <= 2; dy++) {
      for (let dx = -2; dx <= 2; dx++) this.set(row + dy, col + dx, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
  }

  drawFunctionPatterns() {
    const n = this.size;
    for (let i = 0; i < n; i++) {
      this.set(6, i, i % 2 === 0);
      this.set(i, 6, i % 2 === 0);
    }
    this.finder(3, 3);
    this.finder(3, n - 4);
    this.finder(n - 4, 3);
    const centres = ALIGNMENT[this.version];
    for (let i = 0; i < centres.length; i++) {
      for (let j = 0; j < centres.length; j++) {
        const corner =
          (i === 0 && j === 0) || (i === 0 && j === centres.length - 1) || (i === centres.length - 1 && j === 0);
        if (!corner) this.alignment(centres[i], centres[j]);
      }
    }
    this.drawFormat(0, "M");
    this.drawVersion();
  }

  /** Writes (and reserves) the format bits around the finders, and the dark module. */
  drawFormat(mask: number, level: EcLevel) {
    const bits = formatBits(level, mask);
    const bit = (i: number) => ((bits >>> i) & 1) === 1;
    const n = this.size;
    for (let i = 0; i <= 5; i++) this.set(i, 8, bit(i));
    this.set(7, 8, bit(6));
    this.set(8, 8, bit(7));
    this.set(8, 7, bit(8));
    for (let i = 9; i < 15; i++) this.set(8, 14 - i, bit(i));
    for (let i = 0; i < 8; i++) this.set(8, n - 1 - i, bit(i));
    for (let i = 8; i < 15; i++) this.set(n - 15 + i, 8, bit(i));
    this.set(n - 8, 8, true);
  }

  private drawVersion() {
    if (this.version < 7) return;
    const bits = versionBits(this.version);
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >>> i) & 1) === 1;
      const a = this.size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      this.set(b, a, dark);
      this.set(a, b, dark);
    }
  }

  /** Places the codewords in the zigzag order, skipping function modules. */
  drawCodewords(codewords: readonly number[]) {
    const n = this.size;
    let i = 0;
    const totalBits = codewords.length * 8;
    for (let right = n - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < n; vert++) {
        for (let j = 0; j < 2; j++) {
          const col = right - j;
          const upward = ((right + 1) & 2) === 0;
          const row = upward ? n - 1 - vert : vert;
          if (!this.isFunction[row][col] && i < totalBits) {
            this.modules[row][col] = ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) === 1;
            i++;
          }
        }
      }
    }
  }

  applyMask(mask: number) {
    const f = MASKS[mask];
    for (let row = 0; row < this.size; row++) {
      for (let col = 0; col < this.size; col++) {
        if (!this.isFunction[row][col] && f(row, col)) this.modules[row][col] = !this.modules[row][col];
      }
    }
  }

  /** The penalty score of the current modules (lower is better), as the standard defines it. */
  penalty(): number {
    const n = this.size;
    const m = this.modules;
    let score = 0;
    const line = (cells: boolean[]) => {
      let runColour = cells[0];
      let run = 1;
      for (let i = 1; i <= cells.length; i++) {
        if (i < cells.length && cells[i] === runColour) {
          run++;
          continue;
        }
        if (run >= 5) score += 3 + (run - 5);
        if (i < cells.length) {
          runColour = cells[i];
          run = 1;
        }
      }
      const text = cells.map((c) => (c ? "1" : "0")).join("");
      for (let at = text.indexOf("10111010000"); at >= 0; at = text.indexOf("10111010000", at + 1)) score += 40;
      for (let at = text.indexOf("00001011101"); at >= 0; at = text.indexOf("00001011101", at + 1)) score += 40;
    };
    for (let row = 0; row < n; row++) line(m[row]);
    for (let col = 0; col < n; col++) line(m.map((r) => r[col]));
    for (let row = 0; row + 1 < n; row++) {
      for (let col = 0; col + 1 < n; col++) {
        const c = m[row][col];
        if (c === m[row][col + 1] && c === m[row + 1][col] && c === m[row + 1][col + 1]) score += 3;
      }
    }
    let dark = 0;
    for (const row of m) for (const c of row) if (c) dark++;
    const percent = (dark * 100) / (n * n);
    const k = Math.floor(Math.abs(percent - 50) / 5);
    score += k * 10;
    return score;
  }
}

/**
 * The QR code of the text at level M: the smallest of versions 1 to 10 that holds it, with the best mask.
 * Throws when the text is longer than version 10 holds (about 213 bytes).
 */
export function qrMatrix(text: string, options: { mask?: number } = {}): QrMatrix {
  const mode = modeFor(text);
  const version = versionFor(text, mode);
  if (version === null) throw new Error("Text too long for a QR code of version 10");
  const codewords = interleave(dataCodewords(text, mode, version), version);
  const symbol = new Symbol(version);
  symbol.drawFunctionPatterns();
  const padded = [...codewords];
  for (let i = 0; i < Math.ceil(REMAINDER_BITS[version] / 8); i++) padded.push(0);
  symbol.drawCodewords(padded);
  let best = options.mask ?? -1;
  if (best < 0) {
    let bestScore = Infinity;
    for (let mask = 0; mask < 8; mask++) {
      symbol.applyMask(mask);
      symbol.drawFormat(mask, "M");
      const score = symbol.penalty();
      if (score < bestScore) {
        bestScore = score;
        best = mask;
      }
      symbol.applyMask(mask);
    }
  }
  symbol.applyMask(best);
  symbol.drawFormat(best, "M");
  return { version, size: symbol.size, mask: best, modules: symbol.modules };
}

/** An SVG path (one unit per module) of the dark modules, for `<path d>` inside a viewBox of `size`×`size`. */
export function qrPath(matrix: QrMatrix): string {
  const parts: string[] = [];
  for (let row = 0; row < matrix.size; row++) {
    let col = 0;
    while (col < matrix.size) {
      if (!matrix.modules[row][col]) {
        col++;
        continue;
      }
      let run = 1;
      while (col + run < matrix.size && matrix.modules[row][col + run]) run++;
      parts.push(`M${col} ${row}h${run}v1h-${run}z`);
      col += run;
    }
  }
  return parts.join("");
}
