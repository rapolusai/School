"use client";

import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { PageHead } from "@/components/ui/states";
import { formatPlainDate } from "@/lib/format";
import { hostelApi } from "@/lib/hostel-api";
import { plural, useI18n } from "@/lib/i18n";
import type { BoarderView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { HostelNav, ListState, useLocale } from "./hostel-shared";

/* /app/hostel/boarders: every boarder of the hostels in scope, grouped by class. Guardian phones are masked. */

/** Boarders grouped by class, in class order (unknown class last). Pure and exported for tests. */
export function byClass(rows: BoarderView[]): { className: string | null; rows: BoarderView[] }[] {
  const groups = new Map<string | null, BoarderView[]>();
  for (const row of rows) {
    const list = groups.get(row.className) ?? [];
    list.push(row);
    groups.set(row.className, list);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => (a === null ? 1 : b === null ? -1 : a.localeCompare(b, undefined, { numeric: true })))
    .map(([className, list]) => ({ className, rows: list }));
}

export function BoardersView() {
  const { t } = useI18n();
  const locale = useLocale();
  const boarders = useApiData("hostel:boarders", () => hostelApi.boarders());
  const [q, setQ] = useState("");
  const needle = q.trim().toLowerCase();
  return (
    <>
      <PageHead eyebrow={t("nav.hostel")} title={t("hostel.boarders.title")} />
      <HostelNav />
      <label className="field max-w-xs">
        <span className="field-label">{t("hostel.boarders.search")}</span>
        <input className="input" type="search" name="q" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("hostel.boarders.searchHint")} data-testid="boarders-search" />
      </label>
      <ListState data={boarders} empty={t("hostel.boarders.empty")}>
        {(rows) => {
          const shown = needle ? rows.filter((r) => `${r.studentName ?? ""} ${r.admissionNo ?? ""} ${r.roomNumber ?? ""}`.toLowerCase().includes(needle)) : rows;
          return (
            <>
              <p className="text-[13px] text-ink-3" data-testid="boarders-count">
                {plural(t, "hostel.boarders.count", shown.length)}
              </p>
              {byClass(shown).map((group) => (
                <section key={group.className ?? "none"} className="card" aria-label={group.className ?? t("hostel.boarders.noClass")} data-testid="boarders-class">
                  <div className="card-head">
                    <h2>{group.className ?? t("hostel.boarders.noClass")}</h2>
                    <Pill tone="neutral">{plural(t, "hostel.boarders.count", group.rows.length)}</Pill>
                  </div>
                  <div className="table-wrap">
                    <table className="table">
                      <thead>
                        <tr>
                          <th scope="col">{t("hostel.field.student")}</th>
                          <th scope="col">{t("hostel.field.hostel")}</th>
                          <th scope="col">{t("hostel.field.roomBed")}</th>
                          <th scope="col">{t("hostel.field.since")}</th>
                          <th scope="col">{t("hostel.field.guardian")}</th>
                          <th scope="col">{t("hostel.field.consent")}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {group.rows.map((row) => (
                          <tr key={row.allotmentId} data-testid="boarder-row">
                            <td>
                              <b>{row.studentName}</b>
                              <span className="block text-[12.5px] text-ink-3 mono">{row.admissionNo}</span>
                            </td>
                            <td>{row.hostelName}</td>
                            <td>
                              {row.blockName ? `${row.blockName} · ` : ""}
                              {row.roomNumber} / {row.bedLabel}
                            </td>
                            <td>{formatPlainDate(row.since, locale)}</td>
                            <td>
                              {row.guardianName ?? "—"}
                              {row.guardianPhoneMasked ? <span className="block text-[12.5px] text-ink-3">{row.guardianPhoneMasked}</span> : null}
                            </td>
                            <td>
                              <Pill tone={row.guardianConsent ? "good" : "warn"}>{row.guardianConsent ? t("hostel.consent.yes") : t("hostel.consent.no")}</Pill>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              ))}
            </>
          );
        }}
      </ListState>
    </>
  );
}
