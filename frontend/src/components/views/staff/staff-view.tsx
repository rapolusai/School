"use client";

import { Building2, ChevronLeft, ChevronRight, Plus, Search, UserCheck } from "lucide-react";
import Link from "next/link";
import { useRef, useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { initials } from "@/lib/format";
import { plural, roleLabel, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { staffApi } from "@/lib/staff-api";
import type { StaffDirectoryQuery, StaffMemberStatus, StaffRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AddStaffDialog } from "./staff-form";
import { STAFF_DAY_LABEL, staffDayTone } from "./staff-shared";

export const STAFF_PAGE_SIZE = 25;

export type StaffFilters = {
  departmentId: string;
  designation: string;
  role: string;
  status: "" | StaffMemberStatus;
  incomplete: boolean;
};

export const DEFAULT_STAFF_FILTERS: StaffFilters = {
  departmentId: "",
  designation: "",
  role: "",
  status: "ACTIVE",
  incomplete: false,
};

/** The API query for the directory's filters. Pure and exported for tests. */
export function toStaffQuery(filters: StaffFilters, search: string, page: number): StaffDirectoryQuery {
  return {
    departmentId: filters.departmentId || undefined,
    designation: filters.designation || undefined,
    role: filters.role || undefined,
    status: filters.status || undefined,
    incomplete: filters.incomplete || undefined,
    q: search.trim() || undefined,
    page,
    size: STAFF_PAGE_SIZE,
  };
}

/** The staff directory: filters, search, "profile incomplete" and "Add staff". */
export function StaffView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.staffManage);
  const [filters, setFilters] = useState<StaffFilters>(DEFAULT_STAFF_FILTERS);
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const departments = useApiData("staff:departments", staffApi.departments);
  const designations = useApiData("staff:designations", staffApi.designations);
  const roles = useApiData("staff:roles", staffApi.roles);
  const query = toStaffQuery(filters, search, page);
  const staff = useApiData(`staff:list:${JSON.stringify(query)}`, () => staffApi.list(query));
  const data = staff.data;
  const rows = data?.items ?? [];
  const filtered = Boolean(
    search.trim() || filters.departmentId || filters.designation || filters.role || filters.incomplete,
  );

  const setFilter = <K extends keyof StaffFilters>(key: K, value: StaffFilters[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
    setPage(0);
  };

  const onSearch = (value: string) => {
    setSearchText(value);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      setSearch(value);
      setPage(0);
    }, 250);
  };

  const first = data && data.total > 0 ? data.page * data.size + 1 : 0;
  const last = data ? Math.min(data.total, data.page * data.size + rows.length) : 0;
  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;

  return (
    <>
      <PageHead
        eyebrow={data ? plural(t, "staff.eyebrow", data.total) : t("common.loading")}
        title={t("staff.title")}
        actions={
          <>
            <Link href="/app/staff/departments" className="btn">
              <Building2 size={18} aria-hidden="true" />
              {t("staff.departments")}
            </Link>
            <Link href="/app/staff-attendance" className="btn">
              <UserCheck size={18} aria-hidden="true" />
              {t("nav.staffAttendance")}
            </Link>
            {canManage ? (
              <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
                <Plus size={18} aria-hidden="true" />
                {t("staff.add")}
              </button>
            ) : null}
          </>
        }
      />

      {data && data.incompleteProfiles > 0 && !filters.incomplete ? (
        <section className="alert alert-info flex flex-wrap items-center gap-2" role="status" data-testid="incomplete-notice">
          <span className="min-w-0 flex-1">{plural(t, "staff.incomplete.notice", data.incompleteProfiles)}</span>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => {
              setFilters({ ...DEFAULT_STAFF_FILTERS, status: "", incomplete: true });
              setPage(0);
            }}
          >
            {t("staff.incomplete.show")}
          </button>
        </section>
      ) : null}

      <section className="card">
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("staff.search")}</span>
            <input
              type="search"
              name="q"
              value={searchText}
              onChange={(e) => onSearch(e.target.value)}
              placeholder={t("staff.search")}
              maxLength={100}
            />
          </label>
          <label className="min-w-[140px] flex-1 sm:flex-none">
            <span className="sr-only">{t("staff.filter.department")}</span>
            <select
              className="input"
              value={filters.departmentId}
              onChange={(e) => setFilter("departmentId", e.target.value)}
              aria-label={t("staff.filter.department")}
            >
              <option value="">{t("staff.filter.allDepartments")}</option>
              {(departments.data ?? []).map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </select>
          </label>
          <label className="min-w-[140px] flex-1 sm:flex-none">
            <span className="sr-only">{t("staff.filter.designation")}</span>
            <select
              className="input"
              value={filters.designation}
              onChange={(e) => setFilter("designation", e.target.value)}
              aria-label={t("staff.filter.designation")}
            >
              <option value="">{t("staff.filter.allDesignations")}</option>
              {(designations.data ?? []).map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          </label>
          <label className="min-w-[120px] flex-1 sm:flex-none">
            <span className="sr-only">{t("staff.filter.role")}</span>
            <select
              className="input"
              value={filters.role}
              onChange={(e) => setFilter("role", e.target.value)}
              aria-label={t("staff.filter.role")}
            >
              <option value="">{t("staff.filter.allRoles")}</option>
              {(roles.data ?? []).map((r) => (
                <option key={r.code} value={r.code}>
                  {roleLabel(t, r.code, r.name)}
                </option>
              ))}
            </select>
          </label>
          <label className="min-w-[120px] flex-1 sm:flex-none">
            <span className="sr-only">{t("staff.filter.status")}</span>
            <select
              className="input"
              value={filters.status}
              onChange={(e) => setFilter("status", e.target.value as StaffFilters["status"])}
              aria-label={t("staff.filter.status")}
            >
              <option value="ACTIVE">{t("staff.status.ACTIVE")}</option>
              <option value="LEFT">{t("staff.status.LEFT")}</option>
              <option value="">{t("staff.filter.allStatuses")}</option>
            </select>
          </label>
          {filters.incomplete ? (
            <button type="button" className="btn btn-sm self-center" onClick={() => setFilter("incomplete", false)}>
              {t("staff.incomplete.clear")}
            </button>
          ) : null}
        </div>

        {staff.error && !data ? (
          <ErrorState error={staff.error} onRetry={staff.reload} />
        ) : !data ? (
          <LoadingRows rows={6} />
        ) : rows.length === 0 ? (
          <div className="empty flex flex-col items-center gap-3" data-testid="staff-empty">
            <p>{filtered || filters.status === "LEFT" ? t("staff.noMatch") : t("staff.empty")}</p>
            {!filtered && canManage ? (
              <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
                {t("staff.add")}
              </button>
            ) : null}
          </div>
        ) : (
          <div aria-busy={staff.loading}>
            <StaffTable rows={rows} />
            <StaffCards rows={rows} />
            <div className="pager">
              <span>{t("staff.showing", { first, last, total: data.total })}</span>
              {data.total > data.size ? (
                <div className="flex gap-2">
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={page === 0}
                  >
                    <ChevronLeft size={16} aria-hidden="true" />
                    {t("common.previous")}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setPage((p) => Math.min(lastPage, p + 1))}
                    disabled={page >= lastPage}
                  >
                    {t("common.next")}
                    <ChevronRight size={16} aria-hidden="true" />
                  </button>
                </div>
              ) : null}
            </div>
          </div>
        )}
      </section>

      {canManage ? (
        <AddStaffDialog
          open={adding}
          onClose={() => setAdding(false)}
          onCreated={(created) => {
            setAdding(false);
            staff.reload();
            designations.reload();
            toast(t("staff.added", { name: created.name }));
          }}
        />
      ) : null}
    </>
  );
}

function StatusPills({ row }: { row: StaffRow }) {
  const { t } = useI18n();
  return (
    <span className="flex flex-wrap gap-1">
      {row.status === "LEFT" ? <Pill tone="neutral">{t("staff.status.LEFT")}</Pill> : null}
      {!row.profileComplete ? <Pill tone="warn">{t("staff.profileIncomplete")}</Pill> : null}
      {row.status === "ACTIVE" && row.today ? (
        <Pill tone={staffDayTone(row.today)} dot>
          {t(STAFF_DAY_LABEL[row.today])}
        </Pill>
      ) : null}
    </span>
  );
}

function StaffTable({ rows }: { rows: StaffRow[] }) {
  const { t } = useI18n();
  return (
    <div className="table-wrap hidden md:block">
      <table className="table" data-testid="staff-table">
        <thead>
          <tr>
            <th scope="col">{t("staff.col.name")}</th>
            <th scope="col">{t("staff.col.designation")}</th>
            <th scope="col">{t("staff.col.roles")}</th>
            <th scope="col">{t("staff.col.mobile")}</th>
            <th scope="col">{t("staff.col.status")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((s) => (
            <tr key={s.userId}>
              <td>
                <Link href={`/app/staff/${s.userId}`} className="row-title person">
                  <span className="avatar" aria-hidden="true">
                    {initials(s.name)}
                  </span>
                  <span className="min-w-0">
                    <b>{s.name}</b>
                    <span className="sub mono">{s.employeeCode ?? s.email}</span>
                  </span>
                </Link>
              </td>
              <td>
                <span className="block leading-tight">
                  {s.designation ?? "—"}
                  {s.department ? <span className="block text-[12.5px] text-ink-3">{s.department.name}</span> : null}
                </span>
              </td>
              <td className="text-[13px]">{s.roles.map((r) => roleLabel(t, r)).join(", ")}</td>
              <td className="num whitespace-nowrap">{s.mobile ?? "—"}</td>
              <td>
                <StatusPills row={s} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function StaffCards({ rows }: { rows: StaffRow[] }) {
  return (
    <ul className="rowcards md:hidden" data-testid="staff-cards">
      {rows.map((s) => (
        <li key={s.userId}>
          <Link href={`/app/staff/${s.userId}`} className="rowcard">
            <span className="avatar" aria-hidden="true">
              {initials(s.name)}
            </span>
            <span className="min-w-0 flex-1">
              <b className="block truncate font-semibold">{s.name}</b>
              <span className="block truncate text-[13px] text-ink-3">
                {[s.designation, s.department?.name].filter(Boolean).join(" · ") || s.email}
              </span>
              <span className="mt-1 block">
                <StatusPills row={s} />
              </span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
