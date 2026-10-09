import { NETWORK_ERROR_STATUS, type ApiError } from "./api";
import type { Translate } from "./i18n";

/** A user-facing sentence for an API failure. Prefers the server's `detail`. */
export function errorMessage(error: ApiError, t: Translate): string {
  if (error.status === NETWORK_ERROR_STATUS) return t("common.error.network");
  if (error.status === 403) return t("common.error.forbidden");
  if (error.status === 429) return t("login.error.tooMany");
  if (error.detail) return error.detail;
  if (error.status >= 500 || !error.title) return t("common.error.generic");
  return error.title;
}
