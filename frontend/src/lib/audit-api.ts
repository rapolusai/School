import { apiFetch } from "./api";
import type { AuditEvent } from "./types";

/* The school's audit trail and the Super Admin's platform trail (docs/api/phase-3-subscriptions.md, section 6). */

/** Filters of an audit trail; empty strings are left out. `before`/`beforeId` load older entries ("load more"). */
export type AuditFilters = {
  action?: string;
  actorId?: string;
  schoolId?: string;
  from?: string;
  to?: string;
  before?: string;
  beforeId?: string;
};

export type AuditFacets = { actions: string[]; people: { id: string; name: string | null }[] };

/** An entry of the platform trail. `platformAdmin`: a Super Admin did it (not the school, the job or the gateway). */
export type PlatformAuditEvent = AuditEvent & {
  platformAdmin: boolean;
  schoolId: string | null;
  schoolName: string | null;
  schoolCode: string | null;
  ip: string | null;
};

export type PlatformAuditFacets = { actions: string[]; schools: { id: string; name: string; code: string }[] };

/** "?action=…&from=…" with only the filters that are set (and `limit` when given). */
export function auditQuery(filters: AuditFilters, limit?: number): string {
  const params = new URLSearchParams();
  if (limit) params.set("limit", String(limit));
  for (const [key, value] of Object.entries(filters)) {
    if (typeof value === "string" && value.trim()) params.set(key, value.trim());
  }
  const query = params.toString();
  return query ? `?${query}` : "";
}

/** Filters without the paging cursor: what an export takes. */
function exportFilters(filters: AuditFilters): AuditFilters {
  return { ...filters, before: undefined, beforeId: undefined };
}

export const auditApi = {
  list: (filters: AuditFilters, limit: number) =>
    apiFetch<AuditEvent[]>(`/api/audit-events${auditQuery(filters, limit)}`),
  facets: () => apiFetch<AuditFacets>("/api/audit-events/facets"),
  exportPath: (filters: AuditFilters) => `/api/audit-events/export.csv${auditQuery(exportFilters(filters))}`,
};

export const platformAuditApi = {
  list: (filters: AuditFilters, limit: number) =>
    apiFetch<PlatformAuditEvent[]>(`/api/platform/audit-events${auditQuery(filters, limit)}`),
  facets: () => apiFetch<PlatformAuditFacets>("/api/platform/audit-events/facets"),
  exportPath: (filters: AuditFilters) =>
    `/api/platform/audit-events/export.csv${auditQuery(exportFilters(filters))}`,
};
