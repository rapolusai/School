"use client";

import { ChevronRight } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { feesApi } from "@/lib/fees-api";
import { formatPaise, todayInIndia } from "@/lib/format";
import { plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { StructureSummary } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { FeesNav } from "./fee-ui";
import { StructureEditor } from "./structure-editor";

type Editing = { classId: string; className: string; structureId: string | null };

/** /app/fees/structures: each class's fees for a year, opened one class at a time in the editor. */
export function StructuresView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const canManage = hasPermission(me, PERMISSIONS.feesManage);
  const canAcademics = hasPermission(me, PERMISSIONS.academicsRead);
  const years = useApiData(canAcademics ? "academics:years" : null, api.listYears);
  const [yearId, setYearId] = useState("");
  const [editing, setEditing] = useState<Editing | null>(null);
  const yearList = years.data ?? [];
  const currentYear = yearList.find((y) => y.current);
  const year = yearId ? yearList.find((y) => y.id === yearId) : currentYear;
  const summaries = useApiData(`fees:structures:${yearId}`, () => feesApi.listStructures(yearId || undefined));
  const list = summaries.data ?? [];

  if (editing && year) {
    return (
      <>
        <PageHead eyebrow={t("fees.eyebrow")} title={t("fees.structures.title")} />
        <FeesNav />
        <StructureEditor
          academicYearId={year.id}
          yearName={year.name}
          yearStartsOn={year.startsOn || todayInIndia()}
          classId={editing.classId}
          className={editing.className}
          structureId={editing.structureId}
          canManage={canManage}
          onBack={() => setEditing(null)}
          onChanged={summaries.reload}
        />
      </>
    );
  }

  return (
    <>
      <PageHead
        eyebrow={[t("fees.eyebrow"), year?.name].filter(Boolean).join(" · ")}
        title={t("fees.structures.title")}
      />
      <FeesNav />
      <section className="card">
        <div className="card-head">
          <div>
            <h2>{t("fees.structures.byClass")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("fees.structures.sub")}</p>
          </div>
          {yearList.length > 1 ? (
            <label className="w-full sm:w-auto">
              <span className="sr-only">{t("students.filter.year")}</span>
              <select
                className="input"
                value={yearId}
                onChange={(e) => {
                  setYearId(e.target.value);
                  setEditing(null);
                }}
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
          ) : null}
        </div>
        {summaries.error && !summaries.data ? (
          <ErrorState error={summaries.error} onRetry={summaries.reload} />
        ) : !summaries.data || (canAcademics && !years.data && !years.error) ? (
          <LoadingRows rows={5} />
        ) : !year ? (
          <div className="empty flex flex-col items-center gap-3">
            <p>{t("fees.noYear")}</p>
            {canAcademics ? (
              <Link href="/app/setup" className="btn">
                {t("students.openSetup")}
              </Link>
            ) : null}
          </div>
        ) : list.length === 0 ? (
          <div className="empty flex flex-col items-center gap-3">
            <p>{t("fees.structures.noClasses")}</p>
            <Link href="/app/setup" className="btn">
              {t("students.openSetup")}
            </Link>
          </div>
        ) : (
          <StructureList
            rows={list}
            canManage={canManage}
            onOpen={(row) => setEditing({ classId: row.classId, className: row.className, structureId: row.id })}
          />
        )}
      </section>
    </>
  );
}

function StructureStatus({ row }: { row: StructureSummary }) {
  const { t } = useI18n();
  if (!row.status) return <Pill>{t("fees.structure.status.NONE")}</Pill>;
  return (
    <Pill tone={row.status === "PUBLISHED" ? "good" : "warn"} dot>
      {t(row.status === "PUBLISHED" ? "fees.structure.status.PUBLISHED" : "fees.structure.status.DRAFT")}
    </Pill>
  );
}

function StructureList({
  rows,
  canManage,
  onOpen,
}: {
  rows: StructureSummary[];
  canManage: boolean;
  onOpen: (row: StructureSummary) => void;
}) {
  const { t } = useI18n();
  const action = (row: StructureSummary) =>
    row.id ? (canManage ? t("common.edit") : t("fees.structures.view")) : t("fees.structures.setUp");
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid="structures-table">
          <thead>
            <tr>
              <th scope="col">{t("students.col.class")}</th>
              <th scope="col">{t("fees.receipts.status")}</th>
              <th scope="col" className="r">
                {t("fees.structures.col.total")}
              </th>
              <th scope="col" className="r">
                {t("fees.structures.col.instalments")}
              </th>
              <th scope="col" className="r">
                {t("fees.structures.col.students")}
              </th>
              <th scope="col">
                <span className="sr-only">{t("common.actions")}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.classId}>
                <td className="font-semibold">{row.className}</td>
                <td>
                  <StructureStatus row={row} />
                </td>
                <td className="r num">{row.id ? formatPaise(row.totalPaise) : "—"}</td>
                <td className="r num">{row.id ? row.instalmentCount : "—"}</td>
                <td className="r num">{row.id ? row.studentsWithDues : "—"}</td>
                <td className="r">
                  {row.id || canManage ? (
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() => onOpen(row)}
                      aria-label={`${action(row)} · ${row.className}`}
                    >
                      {action(row)}
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid="structures-cards">
        {rows.map((row) => (
          <li key={row.classId}>
            <button
              type="button"
              className="rowcard w-full bg-transparent text-left"
              onClick={() => onOpen(row)}
              disabled={!row.id && !canManage}
            >
              <span className="min-w-0 flex-1">
                <b className="block truncate font-semibold">{row.className}</b>
                <span className="block truncate text-[12.5px] text-ink-3">
                  {row.id
                    ? [formatPaise(row.totalPaise), plural(t, "fees.structures.instalmentCount", row.instalmentCount)].join(
                        " · ",
                      )
                    : t("fees.structures.notSet")}
                </span>
              </span>
              <StructureStatus row={row} />
              {row.id || canManage ? <ChevronRight size={18} aria-hidden="true" className="text-ink-3" /> : null}
            </button>
          </li>
        ))}
      </ul>
    </>
  );
}
