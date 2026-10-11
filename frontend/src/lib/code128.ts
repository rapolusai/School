/*
 * A small Code 128 barcode encoder for the library's accession number labels (no package needed).
 *
 * Every symbol is 11 modules wide: three bars and three spaces, each 1 to 4 modules. A barcode is a start symbol
 * (code set B, or C when the value is all digits), the data symbols, a check symbol and the stop symbol (13 modules).
 * Code set B covers printable ASCII; code set C packs two digits into one symbol, so long runs of digits (the
 * running number of "LIB00042") switch to C to keep the label short. The check symbol is the start value plus each
 * data symbol's value times its position (1, 2, ...), modulo 103.
 */

/** Bar and space widths of symbols 0 to 106 (106 is the stop symbol, with its final bar). */
export const CODE128_PATTERNS: readonly string[] = [
  "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
  "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
  "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
  "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
  "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
  "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
  "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
  "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
  "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
  "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
  "114131", "311141", "411131", "211412", "211214", "211232", "2331112",
];

export const START_B = 104;
export const START_C = 105;
export const CODE_B = 100;
export const CODE_C = 99;
export const STOP = 106;
/** Blank modules on each side, so a scanner finds the start and end. */
export const QUIET_ZONE = 10;

export class Code128Error extends Error {}

const isDigit = (c: string) => c >= "0" && c <= "9";

/** Digits in a row from position i. */
function digitRun(text: string, i: number): number {
  let n = 0;
  while (i + n < text.length && isDigit(text[i + n])) n++;
  return n;
}

/**
 * The symbol values of a barcode for `text`, from the start symbol to the check symbol (the stop symbol is not
 * included). Throws Code128Error for characters outside printable ASCII.
 */
export function code128Values(text: string): number[] {
  if (text.length === 0) throw new Code128Error("Nothing to encode");
  for (const c of text) {
    const code = c.charCodeAt(0);
    if (code < 32 || code > 126) throw new Code128Error(`Code 128 B cannot encode "${c}"`);
  }
  // An even number of digits only starts in C; anything else starts in B and switches for long digit runs.
  const allDigits = digitRun(text, 0) === text.length;
  let set: "B" | "C" = allDigits && text.length % 2 === 0 ? "C" : "B";
  const values: number[] = [set === "C" ? START_C : START_B];
  let i = 0;
  while (i < text.length) {
    const run = digitRun(text, i);
    const atEnd = i + run === text.length;
    if (set === "B" && run >= (atEnd ? 4 : 6)) {
      // Leave an odd digit in B, then switch to C for the pairs.
      if (run % 2 === 1) {
        values.push(text.charCodeAt(i) - 32);
        i++;
      }
      values.push(CODE_C);
      set = "C";
      continue;
    }
    if (set === "C") {
      if (run >= 2) {
        values.push(Number(text.slice(i, i + 2)));
        i += 2;
        continue;
      }
      values.push(CODE_B);
      set = "B";
      continue;
    }
    values.push(text.charCodeAt(i) - 32);
    i++;
  }
  values.push(code128Check(values));
  return values;
}

/** The check symbol of a start symbol and data symbols: start + Σ position × value, modulo 103. */
export function code128Check(values: readonly number[]): number {
  let sum = values[0] ?? 0;
  for (let p = 1; p < values.length; p++) sum += p * values[p];
  return sum % 103;
}

/** Bar and space widths in modules, starting with a bar and ending with the stop symbol's last bar. */
export function code128Widths(text: string): number[] {
  const widths: number[] = [];
  for (const v of [...code128Values(text), STOP]) {
    for (const w of CODE128_PATTERNS[v]) widths.push(Number(w));
  }
  return widths;
}

/** Bars as [x, width] in modules, quiet zones included; and the total width in modules. */
export function code128Bars(text: string): { bars: [number, number][]; modules: number } {
  const widths = code128Widths(text);
  const bars: [number, number][] = [];
  let x = QUIET_ZONE;
  widths.forEach((w, index) => {
    if (index % 2 === 0) bars.push([x, w]);
    x += w;
  });
  return { bars, modules: x + QUIET_ZONE };
}
