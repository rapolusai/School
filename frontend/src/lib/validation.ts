/** schoolCode rules from the API contract: 3–40 chars, a-z 0-9 and "-", starting with a letter. */
export const SCHOOL_CODE_PATTERN = /^[a-z][a-z0-9-]{2,39}$/;

export function isValidSchoolCode(code: string): boolean {
  return SCHOOL_CODE_PATTERN.test(code);
}

/**
 * Suggest a school code from the school's name:
 * "St. Mary's Convent School, Pune" → "st-marys-convent-school-pune".
 * Returns "" when the name has nothing usable yet.
 */
export function suggestSchoolCode(name: string): string {
  let slug = name
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/['’`]/g, "")
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");

  // Must start with a letter.
  slug = slug.replace(/^[^a-z]+/, "");
  if (!slug) return "";

  if (slug.length > 40) {
    slug = slug.slice(0, 40);
    const lastHyphen = slug.lastIndexOf("-");
    if (lastHyphen >= 3) slug = slug.slice(0, lastHyphen);
    slug = slug.replace(/-+$/, "");
  }
  if (slug.length < 3) slug = `${slug}-school`.slice(0, 40);
  return slug;
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function isValidEmail(email: string): boolean {
  return EMAIL_PATTERN.test(email.trim());
}

export const MIN_PASSWORD_LENGTH = 10;

/* ---------------------------------------------------------------------------------------------- */
/* Phase 1: the same rules the students and school setup APIs apply (docs/api/phase-1.md).         */
/* ---------------------------------------------------------------------------------------------- */

/** Indian mobile number as typed: optional +91 / 91 / 0, spaces or hyphens, then 10 digits from 6–9. */
export const INDIAN_MOBILE_PATTERN = /^(\+91|91|0)?[\s-]*[6-9](?:[\s-]*[0-9]){9}$/;

export function isValidIndianMobile(phone: string): boolean {
  return INDIAN_MOBILE_PATTERN.test(phone.trim());
}

/** "+91 98765-00001" → "9876500001"; null when it is not an Indian mobile number. */
export function normalizeIndianMobile(phone: string): string | null {
  const trimmed = phone.trim();
  if (!INDIAN_MOBILE_PATTERN.test(trimmed)) return null;
  let digits = trimmed.replace(/\D/g, "");
  if (digits.length === 12 && digits.startsWith("91")) digits = digits.slice(2);
  else if (digits.length === 11 && digits.startsWith("0")) digits = digits.slice(1);
  return /^[6-9]\d{9}$/.test(digits) ? digits : null;
}

export const ADMISSION_NO_PATTERN = /^[A-Za-z0-9][A-Za-z0-9/._-]*$/;
export const ADMISSION_NO_MAX = 30;

export function isValidAdmissionNo(value: string): boolean {
  const trimmed = value.trim();
  return trimmed.length > 0 && trimmed.length <= ADMISSION_NO_MAX && ADMISSION_NO_PATTERN.test(trimmed);
}

/** APAAR ID (the national student ID): 12 digits. Aadhaar numbers are never collected. */
export const APAAR_PATTERN = /^\d{12}$/;
/** UDISE+ school code: 11 digits. */
export const UDISE_PATTERN = /^\d{11}$/;
/** A school's landline or mobile: 8–15 digits with optional +, spaces, brackets or hyphens. */
export const SCHOOL_PHONE_PATTERN = /^\+?[0-9][0-9 ()-]{6,18}[0-9]$/;
export const SUBJECT_CODE_PATTERN = /^[A-Za-z0-9-]*$/;

export function isValidSchoolPhone(phone: string): boolean {
  const trimmed = phone.trim();
  return SCHOOL_PHONE_PATTERN.test(trimmed) && trimmed.replace(/\D/g, "").length >= 8;
}

/** A real calendar date written as YYYY-MM-DD. */
export function isPlainDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

/** Whole number in [min, max], given as text from an input. Empty text is not a number. */
export function isWholeNumberInRange(value: string, min: number, max: number): boolean {
  if (!/^\d+$/.test(value.trim())) return false;
  const n = Number(value.trim());
  return n >= min && n <= max;
}

/** "2026-06-01" → "2026-27": the usual name of an academic year starting on that date. */
export function suggestYearName(startsOn: string): string {
  if (!isPlainDate(startsOn)) return "";
  const year = Number(startsOn.slice(0, 4));
  return `${year}-${String((year + 1) % 100).padStart(2, "0")}`;
}
