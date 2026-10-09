"use client";

import { Trash2, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { plural, useI18n } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import type { SubjectLoad, TeacherSummary } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { clashText } from "./clashes-panel";

type Draft = { teacherId: string; periodsPerWeek: string };

function AssignmentRow({
  sectionId,
  subject,
  teachers,
  onChanged,
}: {
  sectionId: string;
  subject: SubjectLoad;
  teachers: TeacherSummary[];
  /** Called after a save or removal, with a warning when moved periods now clash. */
  onChanged: (warning?: string) => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const initial: Draft = {
    teacherId: subject.teacherId ?? "",
    periodsPerWeek: subject.periodsPerWeek === null ? "" : String(subject.periodsPerWeek),
  };
  const [draft, setDraft] = useState<Draft>(initial);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [removing, setRemoving] = useState(false);
  const dirty = draft.teacherId !== initial.teacherId || draft.periodsPerWeek !== initial.periodsPerWeek;
  // An inactive teacher still holding the subject stays listed so the row reads correctly.
  const options = teachers.some((x) => x.id === subject.teacherId) || !subject.teacherId
    ? teachers
    : [...teachers, { id: subject.teacherId, name: subject.teacherName ?? "—", periodsPerWeek: 0, scheduled: 0, subjects: [] }];

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    const periods = Number(draft.periodsPerWeek);
    if (!draft.teacherId) {
      setError(t("timetable.assign.v.teacher"));
      return;
    }
    if (draft.periodsPerWeek === "" || !Number.isInteger(periods) || periods < 0 || periods > 60) {
      setError(t("timetable.assign.v.periods"));
      return;
    }
    setSaving(true);
    setError(null);
    try {
      let warning: string | undefined;
      if (subject.assignmentId) {
        const result = await timetableApi.updateAssignment(subject.assignmentId, {
          teacherId: draft.teacherId,
          periodsPerWeek: periods,
        });
        if (result.clashes.length > 0) {
          warning = `${plural(t, "timetable.assign.moved", result.periodsMoved)} ${result.clashes.map((c) => clashText(t, c)).join(" ")}`;
        }
      } else {
        await timetableApi.createAssignment({
          sectionId,
          subjectId: subject.subjectId,
          teacherId: draft.teacherId,
          periodsPerWeek: periods,
        });
      }
      toast(t("timetable.assign.saved", { subject: subject.subjectName }));
      onChanged(warning);
    } catch (caught) {
      const failure = toApiError(caught);
      setError(failure.errors ? Object.values(failure.errors)[0] ?? errorMessage(failure, t) : errorMessage(failure, t));
    } finally {
      setSaving(false);
    }
  };

  return (
    <li className="assign-row" data-testid="assignment-row">
      <form method="post" onSubmit={save} noValidate className="assign-form" aria-label={subject.subjectName}>
        <div className="min-w-0">
          <b className="block">{subject.subjectName}</b>
          <span className="text-[12.5px] text-ink-3">
            {subject.periodsPerWeek === null
              ? t("timetable.assign.unassigned")
              : t("timetable.assign.placed", { placed: subject.scheduled, allowed: subject.periodsPerWeek })}
          </span>
        </div>
        <label className="field">
          <span className="field-label">{t("timetable.teacher")}</span>
          <select
            className="input"
            value={draft.teacherId}
            onChange={(e) => {
              setError(null);
              setDraft((d) => ({ ...d, teacherId: e.target.value }));
            }}
          >
            <option value="">{t("common.choose")}</option>
            {options.map((x) => (
              <option key={x.id} value={x.id}>
                {x.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field assign-periods">
          <span className="field-label">{t("timetable.assign.perWeek")}</span>
          <input
            className="input num"
            type="number"
            inputMode="numeric"
            min={0}
            max={60}
            value={draft.periodsPerWeek}
            onChange={(e) => {
              setError(null);
              setDraft((d) => ({ ...d, periodsPerWeek: e.target.value }));
            }}
          />
        </label>
        <div className="assign-actions">
          <button type="submit" className="btn btn-primary btn-sm" disabled={!dirty || saving}>
            {saving ? t("common.saving") : t("common.save")}
          </button>
          {subject.assignmentId ? (
            <button
              type="button"
              className="iconbtn"
              aria-label={t("timetable.assign.remove", { subject: subject.subjectName })}
              onClick={() => setRemoving(true)}
            >
              <Trash2 size={18} aria-hidden="true" />
            </button>
          ) : null}
        </div>
      </form>
      {error ? <p className="field-error">{error}</p> : null}
      {subject.assignmentId ? (
        <ConfirmDialog
          open={removing}
          title={t("timetable.assign.removeTitle", { subject: subject.subjectName })}
          body={t("timetable.assign.removeBody")}
          confirmLabel={t("common.remove")}
          onClose={() => setRemoving(false)}
          onConfirm={async () => {
            await timetableApi.deleteAssignment(subject.assignmentId ?? "");
            setRemoving(false);
            toast(t("timetable.assign.removed", { subject: subject.subjectName }));
            onChanged();
          }}
        />
      ) : null}
    </li>
  );
}

/** Who teaches each subject of a section, and how many periods a week it gets. */
export function AssignmentsPanel() {
  const { t } = useI18n();
  const overview = useApiData("timetable:sections", timetableApi.sections);
  const teachers = useApiData("timetable:teachers", timetableApi.teachers);
  const [chosen, setChosen] = useState("");
  const [version, setVersion] = useState(0);
  const [warning, setWarning] = useState<string | null>(null);
  const list = overview.data?.sections ?? [];
  const sectionId = list.some((s) => s.sectionId === chosen) ? chosen : (list[0]?.sectionId ?? "");
  const section = useApiData(sectionId ? `timetable:assign:${sectionId}` : null, () => timetableApi.section(sectionId));
  const view = section.data?.sectionId === sectionId ? section.data : undefined;

  const changed = (next?: string) => {
    setWarning(next ?? null);
    setVersion((v) => v + 1);
    section.reload();
    teachers.reload();
  };

  if (overview.error && !overview.data) return <ErrorState error={overview.error} onRetry={overview.reload} />;
  if (teachers.error && !teachers.data) return <ErrorState error={teachers.error} onRetry={teachers.reload} />;
  if (!overview.data || !teachers.data) return <LoadingRows rows={6} />;
  if (!overview.data.academicYear) return <p className="empty">{t("timetable.noYear")}</p>;
  if (list.length === 0) return <p className="empty">{t("timetable.noSections")}</p>;

  return (
    <div className="flex flex-col gap-3.5">
      <div className="toolbar" style={{ marginBottom: 0 }}>
        <label className="field min-w-0 flex-1 sm:max-w-sm">
          <span className="field-label">{t("timetable.section")}</span>
          <select
            className="input"
            name="section"
            value={sectionId}
            onChange={(e) => {
              setChosen(e.target.value);
              setWarning(null);
            }}
          >
            {list.map((s) => (
              <option key={s.sectionId} value={s.sectionId}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
      </div>
      {teachers.data.length === 0 ? <p className="alert alert-info">{t("timetable.noTeachers")}</p> : null}
      {warning ? (
        <div className="alert" role="status" style={{ background: "var(--warn-soft)", color: "var(--warn)" }}>
          <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
          <span>{warning}</span>
        </div>
      ) : null}
      {section.error && !view ? (
        <ErrorState error={section.error} onRetry={section.reload} />
      ) : !view ? (
        <LoadingRows rows={6} />
      ) : view.subjects.length === 0 ? (
        <p className="empty">{t("timetable.assign.noSubjects")}</p>
      ) : (
        <ul className="assign-list" aria-label={t("timetable.assign.title", { section: view.label })}>
          {view.subjects.map((subject) => (
            <AssignmentRow
              key={`${subject.subjectId}:${subject.assignmentId ?? "new"}:${subject.teacherId ?? ""}:${subject.periodsPerWeek ?? ""}:${version}`}
              sectionId={view.sectionId}
              subject={subject}
              teachers={teachers.data ?? []}
              onChanged={changed}
            />
          ))}
        </ul>
      )}

      <section className="border-t border-line pt-4" aria-labelledby="tt-load">
        <div className="card-head">
          <h2 id="tt-load">{t("timetable.assign.load")}</h2>
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">{t("timetable.teacher")}</th>
                <th scope="col">{t("timetable.assign.subjects")}</th>
                <th scope="col" className="r">
                  {t("timetable.assign.loadCol")}
                </th>
              </tr>
            </thead>
            <tbody>
              {teachers.data.map((x) => (
                <tr key={x.id}>
                  <td>{x.name}</td>
                  <td className="text-ink-2">{x.subjects.join(", ") || "—"}</td>
                  <td className="r num">{`${x.scheduled} / ${x.periodsPerWeek}`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
