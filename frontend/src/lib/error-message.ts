import { NETWORK_ERROR_STATUS, PLAN_FEATURE_TYPE, PLAN_STUDENT_LIMIT_TYPE, type ApiError } from "./api";
import { translate, translateOr, type MessageKey, type Translate, type TranslateVars } from "./i18n";

/*
 * The API writes its problem details in English. In English the server's own sentence is shown (it is the most
 * specific). In Hindi and Telugu the sentences the API uses everywhere (the shared handlers and bean validation)
 * are mapped to dictionary keys. A module's own sentence (for example "No copy has the accession number X.") has no
 * translation yet, so it follows a translated message for the status in brackets: the page still says in the
 * user's language what kind of problem it is, and the specific reason is not lost.
 */

type Mapped = { key: MessageKey; vars?: TranslateVars };

/** Whole `detail` sentences written by the API's shared handlers (backend ApiExceptionHandler, SecurityConfig). */
const DETAILS: Record<string, MessageKey> = {
  "Some fields need attention.": "validation.fixErrors",
  "Someone else changed this at the same time. Reload and try again.": "common.error.changed",
  "This conflicts with something that already exists.": "common.error.exists",
  "The request could not be read.": "common.error.badRequest",
  "A request parameter is out of range.": "common.error.badRequest",
  "A required request parameter is missing.": "common.error.badRequest",
  "There is nothing at this address.": "common.error.notFound",
  "You do not have permission to do this.": "common.error.forbidden",
  "Your session has ended or is missing. Sign in again.": "common.error.session",
  "The request failed on our side. Try again; if it keeps failing, contact support.": "common.error.generic",
  "Files can be at most 5 MB each.": "common.error.fileTooLarge",
  "Attach a file and try again.": "common.error.attachFile",
};

/** Problem titles that name the kind of failure on their own. */
const TITLES: Record<string, MessageKey> = {
  "Changed by someone else": "common.error.changed",
  "Already exists": "common.error.exists",
  "Check the form": "validation.fixErrors",
  "Not found": "common.error.notFound",
  "Bed taken": "common.error.bedTaken",
  "Already a boarder": "common.error.alreadyBoarder",
};

/** A translated sentence for each status when nothing more specific is known. */
function statusKey(status: number): MessageKey {
  if (status === 400 || status === 422) return "common.error.badRequest";
  if (status === 401) return "common.error.session";
  if (status === 403) return "common.error.forbidden";
  if (status === 404) return "common.error.notFound";
  if (status === 409) return "common.error.conflict";
  if (status === 413) return "common.error.fileTooLarge";
  return "common.error.generic";
}

/** A translated sentence followed by the API's own English reason, in brackets. */
function withReason(translated: string, reason: string | undefined): string {
  const text = reason?.trim();
  return text ? `${translated} (${text})` : translated;
}

/** True when `t` writes English, where the server's own sentence is the best one to show. */
function isEnglish(t: Translate): boolean {
  return t("common.error.generic") === translate("en", "common.error.generic");
}

function mapDetail(detail: string): Mapped | null {
  const exact = DETAILS[detail.trim()];
  if (exact) return { key: exact };
  // ApiException.notFound("Exam") → "Exam was not found."
  if (/^.{1,80} (?:was|were) not found\.?$/.test(detail.trim())) return { key: "common.error.notFound" };
  return null;
}

/**
 * A field message from the API (bean validation defaults and the shared handlers) as a dictionary key, or null
 * when it is a module's own sentence. Exported for tests.
 */
export function mapFieldMessage(message: string): Mapped | null {
  const text = message.trim();
  if (/^must not be (?:blank|null|empty)$/.test(text) || text === "This is required.") {
    return { key: "validation.required" };
  }
  if (text === "Give the date." || text === "Give the reason.") return { key: "validation.required" };
  if (text === "must be a well-formed email address") return { key: "validation.email" };
  if (text === "Use at least 10 characters.") return { key: "validation.password" };
  if (text === "Enter a whole number.") return { key: "validation.server.wholeNumber" };
  if (text === "This value is not valid." || /^must match /.test(text)) return { key: "validation.server.invalid" };
  if (text === "Files can be at most 5 MB each.") return { key: "common.error.fileTooLarge" };
  if (text === "Attach a file and try again.") return { key: "common.error.attachFile" };
  let m = /^(?:size|length) must be between (\d+) and (\d+)$/.exec(text);
  if (m) {
    return m[1] === "0" ? { key: "validation.server.maxLength", vars: { max: m[2] } }
      : { key: "validation.server.lengthBetween", vars: { min: m[1], max: m[2] } };
  }
  m = /^must be less than or equal to (-?[\d.]+)$/.exec(text);
  if (m) return { key: "validation.server.atMost", vars: { max: m[1] } };
  m = /^must be greater than or equal to (-?[\d.]+)$/.exec(text);
  if (m) return { key: "validation.server.atLeast", vars: { min: m[1] } };
  m = /^must be greater than (-?[\d.]+)$/.exec(text);
  if (m) return { key: "validation.server.moreThan", vars: { min: m[1] } };
  m = /^must be less than (-?[\d.]+)$/.exec(text);
  if (m) return { key: "validation.server.lessThan", vars: { max: m[1] } };
  if (/^must be a (?:date in the past|past date)/.test(text)) return { key: "validation.server.past" };
  if (/^must be a (?:future date|date in the (?:present or in the )?future)/.test(text)) {
    return { key: "validation.server.future" };
  }
  return null;
}

/** One field message from the API in the user's language. */
export function fieldErrorMessage(message: string, t: Translate): string {
  const mapped = mapFieldMessage(message);
  if (mapped) return t(mapped.key, mapped.vars);
  return isEnglish(t) ? message : withReason(t("validation.server.invalid"), message);
}

/** The API's `errors` map with every message in the user's language. */
export function fieldErrorMessages(errors: Record<string, string>, t: Translate): Record<string, string> {
  return Object.fromEntries(Object.entries(errors).map(([field, message]) => [field, fieldErrorMessage(message, t)]));
}

/** The first field message of a failed save, translated, or null when the API named no field. */
function firstFieldMessage(error: ApiError, t: Translate): string | null {
  const first = error.errors ? Object.values(error.errors)[0] : undefined;
  return first ? fieldErrorMessage(first, t) : null;
}

/** The school's plan refused the request: a feature it leaves out, or students past its limit. Exported for tests. */
export function planLimitMessage(error: ApiError, t: Translate): string | null {
  const props = error.properties ?? {};
  if (error.type === PLAN_FEATURE_TYPE) {
    const code = typeof props.feature === "string" ? props.feature : "";
    const feature = code ? translateOr(t, `billing.feature.${code}`, "") : "";
    return t("billing.limits.featureError", { feature: feature || t("billing.limits.thisFeature") });
  }
  if (error.type === PLAN_STUDENT_LIMIT_TYPE) {
    return t("billing.limits.studentError", { max: String(props.maxStudents ?? ""), active: String(props.activeStudents ?? "") });
  }
  return null;
}

/** A user-facing sentence for an API failure, in the user's language. */
export function errorMessage(error: ApiError, t: Translate): string {
  if (error.status === NETWORK_ERROR_STATUS) return t("common.error.network");
  const plan = planLimitMessage(error, t);
  if (plan) return plan;
  if (error.status === 403) return t("common.error.forbidden");
  if (error.status === 429) return t("login.error.tooMany");
  // "Some fields need attention." where the page shows no field: say what the field's problem is.
  if (error.detail === "Some fields need attention.") {
    const field = firstFieldMessage(error, t);
    if (field) return t("validation.server.fieldProblem", { problem: field });
  }
  if (isEnglish(t)) {
    if (error.detail) return error.detail;
    if (error.status >= 500 || !error.title) return t("common.error.generic");
    return error.title;
  }
  if (error.status >= 500) return t("common.error.generic");
  const mapped = error.detail ? mapDetail(error.detail) : null;
  if (mapped) return t(mapped.key, mapped.vars);
  const byTitle = error.title ? TITLES[error.title] : undefined;
  // A one-field "Check the form" carries the module's own reason, which the translated title alone would lose.
  if (byTitle === "validation.fixErrors") return withReason(t(byTitle), error.detail);
  if (byTitle) return t(byTitle);
  return withReason(t(statusKey(error.status)), error.detail);
}
