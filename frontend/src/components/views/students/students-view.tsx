"use client";

import { ArrowUpFromLine, ChevronLeft, ChevronRight, Plus, Search, Upload } from "lucide-react";
import Link from "next/link";
import { useRef, useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { classLabel, formatPhone, initials } from "@/lib/format";
import { plural, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { STUDENT_STATUSES, type StudentQuery, type StudentRow, type StudentStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AddStudentDialog } from "./add-student-dialog";
import { PromoteDialog } from "./promote-dialog";
import { studentStatusTone } from "./student-status";

export const PAGE_SIZE = 25;

type Filters = { yearId: string; classId: string; sectionId: string; status: "" | StudentStatus };

const NO_FILTERS: Filters = { yearId: "", classId: "", sectionId: "", status: "" };

/** The API query for the current filters. Exported for tests. */
export function toStudentQuery(filters: Filters, search: string, page: number): StudentQuery {
  return {
    yearId: filters.yearId || undefined,
    classId: filters.classId || undefined,
    sectionId: filters.sectionId || undefined,
    status: filters.status || undefined,
    q: search.trim() || undefined,
    page,
    size: PAGE_SIZE,
  };
}

export function StudentsView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.studentsManage);
  const canSetup = hasPermission(me, PERMISSIONS.academicsRead);

  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [promoting, setPromoting] = useState(false);
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const years = useApiData(canSetup ? "academics:years" : null, api.listYears);
  const classes = useApiData(canSetup ? "academics:classes" : null, api.listClasses);
  const query = toStudentQuery(filters, search, page);
  const students = useApiData(`students:${JSON.stringify(query)}`, () => api.listStudents(query));

  const yearList = years.data ?? [];
  const classList = classes.data ?? [];
  const currentYear = yearList.find((y) => y.current);
  const shownYear = filters.yearId ? yearList.find((y) => y.id === filters.yearId) : currentYear;
  const sectionsOfClass = classList.find((c) => c.id === filters.classId)?.sections ?? [];
  const data = students.data;
  const rows = data?.items ?? [];
  const filtered = Boolean(search.trim() || filters.classId || filters.sectionId || filters.status);
  const noYear = data !== undefined && data.academicYearId === null;

  const setFilter = <K extends keyof Filters>(key: K, value: Filters[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value, ...(key === "classId" ? { sectionId: "" } : {}) }));
    setPage(0);
  };

  const onSearch = (value: string) => {
    setSearchText(value);
    if (searchTimer.current) clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => {
      setSearch(value);
      setPage(0);
    }, 250);
  };

  const first = data && data.total > 0 ? data.page * data.size + 1 : 0;
  const last = data ? Math.min(data.total, data.page * data.size + rows.length) : 0;
  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;

  const eyebrow = data
    ? [plural(t, "students.eyebrow", data.total), shownYear?.name].filter(Boolean).join(" · ")
    : t("common.loading");

  return (
    <>
      <PageHead
        eyebrow={eyebrow}
        title={t("students.title")}
        actions={
          canManage ? (
            <>
              <Link href="/app/students/import" className="btn">
                <Upload size={18} aria-hidden="true" />
                {t("students.import")}
              </Link>
              <button type="button" className="btn" onClick={() => setPromoting(true)}>
                <ArrowUpFromLine size={18} aria-hidden="true" />
                {t("students.promote")}
              </button>
              <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
                <Plus size={18} aria-hidden="true" />
                {t("students.add")}
              </button>
            </>
          ) : null
        }
      />

      <section className="card">
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("students.search")}</span>
            <input
              type="search"
              name="q"
              value={searchText}
              onChange={(event) => onSearch(event.target.value)}
              placeholder={t("students.search")}
              maxLength={100}
            />
          </label>
          {canSetup ? (
            <>
              {/* Full width on phones so "2026-27 (current)" is not cut off. */}
              <label className="w-full sm:w-auto sm:flex-none">
                <span className="sr-only">{t("students.filter.year")}</span>
                <select
                  className="input"
                  value={filters.yearId}
                  onChange={(e) => setFilter("yearId", e.target.value)}
                  aria-label={t("students.filter.year")}
                >
                  <option value="">
                    {currentYear ? t("students.filter.currentYear", { name: currentYear.name }) : t("students.filter.year")}
                  </option>
                  {yearList
                    .filter((y) => !y.current)
                    .map((y) => (
                      <option key={y.id} value={y.id}>
                        {y.name}
                      </option>
                    ))}
                </select>
              </label>
              <label className="min-w-[120px] flex-1 sm:flex-none">
                <span className="sr-only">{t("students.filter.class")}</span>
                <select
                  className="input"
                  value={filters.classId}
                  onChange={(e) => setFilter("classId", e.target.value)}
                  aria-label={t("students.filter.class")}
                >
                  <option value="">{t("students.filter.allClasses")}</option>
                  {classList.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
              {filters.classId && sectionsOfClass.length > 1 ? (
                <label className="min-w-[100px] flex-1 sm:flex-none">
                  <span className="sr-only">{t("students.filter.section")}</span>
                  <select
                    className="input"
                    value={filters.sectionId}
                    onChange={(e) => setFilter("sectionId", e.target.value)}
                    aria-label={t("students.filter.section")}
                  >
                    <option value="">{t("students.filter.allSections")}</option>
                    {sectionsOfClass.map((s) => (
                      <option key={s.id} value={s.id}>
                        {t("setup.sections.label", { name: s.name })}
                      </option>
                    ))}
                  </select>
                </label>
              ) : null}
            </>
          ) : null}
          <label className="min-w-[120px] flex-1 sm:flex-none">
            <span className="sr-only">{t("students.filter.status")}</span>
            <select
              className="input"
              value={filters.status}
              onChange={(e) => setFilter("status", e.target.value as Filters["status"])}
              aria-label={t("students.filter.status")}
            >
              <option value="">{t("students.filter.allStatuses")}</option>
              {STUDENT_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {translateOr(t, `studentStatus.${s}`, s)}
                </option>
              ))}
            </select>
          </label>
        </div>

        {students.error && !data ? (
          <ErrorState error={students.error} onRetry={students.reload} />
        ) : !data ? (
          <LoadingRows rows={6} />
        ) : noYear ? (
          <div className="empty flex flex-col items-center gap-3" data-testid="students-no-year">
            <p>{t("students.noYear")}</p>
            {canSetup ? (
              <Link href="/app/setup" className="btn">
                {t("students.openSetup")}
              </Link>
            ) : null}
          </div>
        ) : rows.length === 0 ? (
          <div className="empty flex flex-col items-center gap-3" data-testid="students-empty">
            <p>{filtered ? t("students.noMatch") : t("students.empty")}</p>
            {!filtered && canManage ? (
              <div className="flex flex-wrap justify-center gap-2">
                <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
                  {t("students.add")}
                </button>
                <Link href="/app/students/import" className="btn">
                  {t("students.import")}
                </Link>
              </div>
            ) : null}
          </div>
        ) : (
          <div aria-busy={students.loading}>
            <StudentTable rows={rows} />
            <StudentCards rows={rows} />
            <div className="pager">
              <span>{t("students.showing", { first, last, total: data.total })}</span>
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
        <>
          <AddStudentDialog
            open={adding}
            onClose={() => setAdding(false)}
            onCreated={(student) => {
              setAdding(false);
              students.reload();
              classes.reload();
              toast(t("students.added", { name: student.fullName }));
            }}
          />
          <PromoteDialog
            open={promoting}
            onClose={() => setPromoting(false)}
            onDone={() => {
              students.reload();
              classes.reload();
            }}
          />
        </>
      ) : null}
    </>
  );
}

function StudentTable({ rows }: { rows: StudentRow[] }) {
  const { t } = useI18n();
  return (
    <div className="table-wrap hidden md:block">
      <table className="table" data-testid="students-table">
        <thead>
          <tr>
            <th scope="col">{t("students.col.student")}</th>
            <th scope="col">{t("students.col.class")}</th>
            <th scope="col" className="r">
              {t("students.col.roll")}
            </th>
            <th scope="col">{t("students.col.guardian")}</th>
            <th scope="col">{t("students.col.status")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((s) => (
            <tr key={s.id}>
              <td>
                <Link href={`/app/students/${s.id}`} className="row-title person">
                  <span className="avatar" aria-hidden="true">
                    {initials(s.fullName)}
                  </span>
                  <span className="min-w-0">
                    <b>{s.fullName}</b>
                    <span className="sub mono">{s.admissionNo}</span>
                  </span>
                </Link>
              </td>
              <td className="whitespace-nowrap">{classLabel(s.className, s.sectionName) || "—"}</td>
              <td className="r num">{s.rollNo ?? "—"}</td>
              <td>
                {s.guardianName ? (
                  <span className="block leading-tight">
                    {s.guardianName}
                    <span className="block text-[12.5px] text-ink-3 num">{formatPhone(s.guardianPhone)}</span>
                  </span>
                ) : (
                  "—"
                )}
              </td>
              <td>
                <Pill tone={studentStatusTone(s.status)} dot>
                  {translateOr(t, `studentStatus.${s.status}`, s.status)}
                </Pill>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function StudentCards({ rows }: { rows: StudentRow[] }) {
  const { t } = useI18n();
  return (
    <ul className="rowcards md:hidden" data-testid="students-cards">
      {rows.map((s) => (
        <li key={s.id}>
          <Link href={`/app/students/${s.id}`} className="rowcard">
            <span className="avatar" aria-hidden="true">
              {initials(s.fullName)}
            </span>
            <span className="min-w-0 flex-1">
              <b className="block truncate font-semibold">{s.fullName}</b>
              <span className="block truncate text-[13px] text-ink-3">
                {[classLabel(s.className, s.sectionName), s.rollNo ? t("students.rollShort", { roll: s.rollNo }) : null]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
              {s.guardianName ? (
                <span className="block truncate text-[12.5px] text-ink-3">
                  {s.guardianName} · <span className="num">{formatPhone(s.guardianPhone)}</span>
                </span>
              ) : null}
            </span>
            {s.status !== "ACTIVE" ? (
              <Pill tone={studentStatusTone(s.status)}>{translateOr(t, `studentStatus.${s.status}`, s.status)}</Pill>
            ) : null}
          </Link>
        </li>
      ))}
    </ul>
  );
}
