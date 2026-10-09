/**
 * CSV helpers for the student import screen. Parsing happens on the server (it is the one
 * that decides); the browser only builds the template and counts rows for a quick preview.
 */

/** Columns the import requires, in the documented order (docs/api/phase-1.md). */
export const IMPORT_COLUMNS = [
  "admission_no",
  "first_name",
  "last_name",
  "date_of_birth",
  "gender",
  "class",
  "section",
  "guardian_name",
  "guardian_relation",
  "guardian_phone",
  "guardian_email",
] as const;

/** Columns that may be added. */
export const IMPORT_OPTIONAL_COLUMNS = ["admission_date", "roll_no"] as const;

export const IMPORT_MAX_ROWS = 2000;

/** Quote a value when it holds a comma, quote or line break (RFC 4180). */
export function csvCell(value: string): string {
  return /[",\r\n]/.test(value) || /^\s|\s$/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

export function csvLine(values: readonly string[]): string {
  return values.map(csvCell).join(",");
}

/** A template with the header and one example row. */
export function importTemplate(): string {
  return [
    csvLine([...IMPORT_COLUMNS, ...IMPORT_OPTIONAL_COLUMNS]),
    csvLine([
      "AKS/2026/101",
      "Aarav",
      "Reddy",
      "2019-05-14",
      "M",
      "Class 1",
      "A",
      "Srinivas Reddy",
      "FATHER",
      "98765 01234",
      "",
      "2026-06-01",
      "1",
    ]),
  ].join("\r\n") + "\r\n";
}

/**
 * Number of data rows (records after the header), counting quoted line breaks correctly and
 * skipping blank lines. Used only for the preview; the server reports the real numbers.
 */
export function countCsvRows(text: string): number {
  let records = 0;
  let inQuotes = false;
  let hasContent = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') i++;
        else inQuotes = false;
      }
      continue;
    }
    if (c === '"') {
      inQuotes = true;
      hasContent = true;
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      if (hasContent) records++;
      hasContent = false;
    } else if (c !== "," && c.trim() !== "" && c !== "﻿") {
      hasContent = true;
    }
  }
  if (hasContent) records++;
  return Math.max(0, records - 1);
}
