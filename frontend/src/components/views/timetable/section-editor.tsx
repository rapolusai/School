"use client";

import { TriangleAlert } from "lucide-react";
import { useState } from "react";
import { FormAlert } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { plural, useI18n } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import type { SectionTimetable, WeekDay } from "@/lib/types";
import {
  cellKey,
  gridFromSlots,
  overLimit,
  sameGrid,
  teacherClashes,
  teacherFor,
  toCells,
  type Grid,
} from "./timetable-logic";
import { cellName, SlotChip, subjectColours, WeekGrid } from "./timetable-shared";

/** A section's week, read only: subject, teacher and room in each period. */
export function SectionGrid({ view, today }: { view: SectionTimetable; today?: WeekDay }) {
  const { t } = useI18n();
  const byKey = new Map(view.slots.map((s) => [cellKey(s.day, s.period), s]));
  const colours = subjectColours(view.subjects.map((s) => s.subjectName));
  return (
    <WeekGrid
      bells={view.bells}
      today={today}
      caption={t("timetable.sectionCaption", { section: view.label })}
      testId="section-grid"
      cell={(day, period) => {
        const slot = byKey.get(cellKey(day, period));
        return slot ? (
          <SlotChip title={slot.subjectName} sub={slot.teacherName} room={slot.room} colour={colours.get(slot.subjectName)} />
        ) : null;
      }}
    />
  );
}

/**
 * The grid editor: a subject in each period (its assigned teacher follows), an optional room, and
 * warnings while editing — a teacher already in another section in that period, or a subject over
 * its weekly allowance. The API refuses clashes on save (409) and the cells it names are marked.
 */
export function SectionEditor({
  view,
  onSaved,
}: {
  view: SectionTimetable;
  onSaved: (view: SectionTimetable) => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [base] = useState<Grid>(() => gridFromSlots(view.slots));
  const [grid, setGrid] = useState<Grid>(base);
  const [cellErrors, setCellErrors] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const names = new Map<string, string>();
  for (const s of view.subjects) if (s.teacherId && s.teacherName) names.set(s.teacherId, s.teacherName);
  for (const s of view.slots) if (s.teacherId && s.teacherName) names.set(s.teacherId, s.teacherName);
  const original = gridFromSlots(view.slots);
  const clashes = teacherClashes(grid, view.busy);
  const clashCount = Object.keys(clashes).length;
  const excess = overLimit(grid, view.subjects);
  const dirty = !sameGrid(grid, base);
  const counts = new Map<string, number>();
  for (const cell of Object.values(grid)) counts.set(cell.subjectId, (counts.get(cell.subjectId) ?? 0) + 1);

  const clearError = (key: string) => {
    setProblem(null);
    setCellErrors((prev) => {
      if (!prev[key]) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  const setSubject = (day: WeekDay, period: number, subjectId: string) => {
    const key = cellKey(day, period);
    clearError(key);
    setGrid((prev) => {
      const next = { ...prev };
      if (!subjectId) {
        delete next[key];
        return next;
      }
      const kept = original[key]?.subjectId === subjectId ? original[key].teacherId : teacherFor(view.subjects, subjectId);
      next[key] = { subjectId, teacherId: kept, room: prev[key]?.room ?? "" };
      return next;
    });
  };

  const setRoom = (day: WeekDay, period: number, room: string) => {
    const key = cellKey(day, period);
    clearError(key);
    setGrid((prev) => (prev[key] ? { ...prev, [key]: { ...prev[key], room } } : prev));
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    if (clashCount > 0) {
      setProblem(plural(t, "timetable.editor.v.clashes", clashCount));
      return;
    }
    setSaving(true);
    setProblem(null);
    try {
      const saved = await timetableApi.saveSection(view.sectionId, toCells(grid));
      setCellErrors({});
      toast(t("timetable.editor.saved", { section: view.label }));
      onSaved(saved);
    } catch (caught) {
      const error = toApiError(caught);
      setCellErrors(error.errors ?? {});
      setProblem(errorMessage(error, t));
    } finally {
      setSaving(false);
    }
  };

  const subjectOptions = view.subjects;

  return (
    <form method="post" onSubmit={onSubmit} noValidate aria-label={t("timetable.editor.title", { section: view.label })}>
      <WeekGrid
        bells={view.bells}
        caption={t("timetable.sectionCaption", { section: view.label })}
        testId="section-editor"
        wide
        cell={(day, period) => {
          const key = cellKey(day, period);
          const cell = grid[key];
          const clash = clashes[key];
          const error = cellErrors[key];
          const teacher = cell?.teacherId ? (names.get(cell.teacherId) ?? "—") : null;
          return (
            <div className={`tt-edit${clash || error ? " tt-edit-bad" : ""}`} data-testid={`cell-${key}`}>
              <select
                className="input"
                aria-label={cellName(t, day, period)}
                aria-invalid={clash || error ? true : undefined}
                value={cell?.subjectId ?? ""}
                onChange={(e) => setSubject(day, period, e.target.value)}
              >
                <option value="">{t("timetable.editor.free")}</option>
                {subjectOptions.map((s) => (
                  <option key={s.subjectId} value={s.subjectId}>
                    {s.subjectName}
                  </option>
                ))}
              </select>
              {cell ? (
                <>
                  <span className="tt-cell-teacher">{teacher ?? t("timetable.editor.noTeacher")}</span>
                  <input
                    className="input tt-room-input"
                    value={cell.room}
                    maxLength={40}
                    placeholder={t("timetable.room")}
                    aria-label={t("timetable.editor.roomFor", { cell: cellName(t, day, period) })}
                    onChange={(e) => setRoom(day, period, e.target.value)}
                  />
                </>
              ) : null}
              {clash ? (
                <p className="tt-cell-warn">
                  <TriangleAlert size={13} aria-hidden="true" />
                  {t("timetable.editor.busy", {
                    teacher: names.get(clash.teacherId) ?? "—",
                    section: clash.sectionLabel,
                  })}
                </p>
              ) : null}
              {error && !clash ? <p className="tt-cell-warn">{error}</p> : null}
            </div>
          );
        }}
      />

      <div className="mt-3 flex flex-col gap-2">
        {excess.map((x) => (
          <div key={x.subjectId} className="alert" style={{ background: "var(--warn-soft)", color: "var(--warn)" }}>
            <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
            <span>
              {t("timetable.editor.overLimit", { subject: x.subjectName, scheduled: x.scheduled, allowed: x.allowed })}
            </span>
          </div>
        ))}
      </div>

      <div className="table-wrap mt-3">
        <table className="table">
          <caption className="sr-only">{t("timetable.editor.loadCaption")}</caption>
          <thead>
            <tr>
              <th scope="col">{t("timetable.subject")}</th>
              <th scope="col">{t("timetable.teacher")}</th>
              <th scope="col" className="r">
                {t("timetable.editor.placed")}
              </th>
            </tr>
          </thead>
          <tbody>
            {view.subjects.map((s) => {
              const placed = counts.get(s.subjectId) ?? 0;
              const over = s.periodsPerWeek !== null && placed > s.periodsPerWeek;
              return (
                <tr key={s.subjectId}>
                  <td>{s.subjectName}</td>
                  <td>{s.teacherName ?? <span className="text-ink-3">{t("timetable.editor.noTeacher")}</span>}</td>
                  <td className={`r num${over ? " text-warn font-semibold" : ""}`}>
                    {s.periodsPerWeek === null ? placed : `${placed} / ${s.periodsPerWeek}`}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="att-savebar">
        <FormAlert message={problem} />
        <p className="text-[13px] text-ink-3" data-testid="editor-status">
          {clashCount > 0
            ? plural(t, "timetable.editor.clashCount", clashCount)
            : excess.length > 0
              ? t("timetable.editor.warningsOnly")
              : t("timetable.editor.noClashes")}
        </p>
        <button type="submit" className="btn btn-primary btn-lg" disabled={saving || !dirty} data-testid="timetable-save">
          {saving ? t("common.saving") : t("timetable.editor.save")}
        </button>
      </div>
    </form>
  );
}
