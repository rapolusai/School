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
