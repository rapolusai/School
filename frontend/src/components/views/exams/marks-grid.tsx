"use client";

import { ArrowLeft, Lock, LockOpen, RefreshCw } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError, type ApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { examsApi } from "@/lib/exams-api";
import { formatDateTime, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import type { MarkCode, MarkEntryRequest, MarkRow, MarkSheet } from "@/lib/types";
import { MARK_CODES, REASON_MAX } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { codeLabel, decimalText, formatScore, parseDecimal, SheetPill } from "./exams-shared";

/* ------------------------------------------------------------------ pure helpers (exported for tests) */

export const PARTS = ["theory", "practical", "internal"] as const;
export type Part = (typeof PARTS)[number];

/** One student's line as typed: the marks as text, or a code. */
export type EntryText = { theory: string; practical: string; internal: string; code: MarkCode | null };

type Limits = Pick<MarkSheet, "maxTheory" | "maxPractical" | "maxInternal">;

const MAX_OF: Record<Part, keyof Limits> = { theory: "maxTheory", practical: "maxPractical", internal: "maxInternal" };

/** The components a paper has (maximum above 0), in order. */
export function partsOf(sheet: Limits): Part[] {
  return PARTS.filter((part) => sheet[MAX_OF[part]] > 0);
}

export function entryOf(row: MarkRow): EntryText {
  return {
    theory: decimalText(row.theory),
    practical: decimalText(row.practical),
    internal: decimalText(row.internal),
    code: row.code,
  };
}

/** What is wrong with each typed mark: not a number (two decimals at most) or outside 0 to the maximum. */
export function entryProblems(entry: EntryText, sheet: Limits): Partial<Record<Part, MessageKey>> {
  const problems: Partial<Record<Part, MessageKey>> = {};
  if (entry.code) return problems;
  for (const part of partsOf(sheet)) {
    const value = parseDecimal(entry[part]);
    if (value === null) continue;
    if (Number.isNaN(value)) problems[part] = "exams.v.mark";
    else if (value > sheet[MAX_OF[part]]) problems[part] = "exams.v.markMax";
  }
  return problems;
}

/** Marks for every component, or a code: the line counts as entered. */
export function isComplete(entry: EntryText, sheet: Limits): boolean {
  if (entry.code) return true;
  return partsOf(sheet).every((part) => {
    const value = parseDecimal(entry[part]);
    return value !== null && !Number.isNaN(value);
  });
}

/** The line's total while typing: the sum of the components, null until all are in (or with a code). */
export function entryTotal(entry: EntryText, sheet: Limits): number | null {
  if (entry.code || !isComplete(entry, sheet)) return null;
  return Math.round(partsOf(sheet).reduce((sum, part) => sum + (parseDecimal(entry[part]) as number), 0) * 100) / 100;
}

/** The body of a save: every student's line, blanks included (a blank line clears it). */
export function toEntries(sheet: Limits, entries: Record<string, EntryText>): MarkEntryRequest[] {
  const parts = partsOf(sheet);
  return Object.entries(entries).map(([studentId, entry]) => {
    const value = (part: Part) => (entry.code || !parts.includes(part) ? null : parseDecimal(entry[part]));
    return {
      studentId,
      theory: value("theory"),
      practical: value("practical"),
      internal: value("internal"),
      code: entry.code,
    };
  });
}

/** Students of the section still without complete marks (they block submitting). */
export function missingStudents(sheet: MarkSheet, entries: Record<string, EntryText>): MarkRow[] {
  return sheet.students.filter((s) => s.inSection && !isComplete(entries[s.studentId], sheet));
}

function entriesFrom(sheet: MarkSheet): Record<string, EntryText> {
  return Object.fromEntries(sheet.students.map((row) => [row.studentId, entryOf(row)]));
}

function sameEntry(a: EntryText, b: EntryText): boolean {
  return a.theory === b.theory && a.practical === b.practical && a.internal === b.internal && a.code === b.code;
}

const PART_LABEL: Record<Part, MessageKey> = {
  theory: "exams.part.theory",
  practical: "exams.part.practical",
  internal: "exams.part.internal",
};

/* ------------------------------------------------------------------ view */

/** /app/exams/marks/[paperId]/[sectionId]: one paper's marks for one section. */
export function MarksGridView({ paperId, sectionId }: { paperId: string; sectionId: string }) {
  const { t } = useI18n();
  const loaded = useApiData(`exams:sheet:${paperId}:${sectionId}`, () => examsApi.sheet(paperId, sectionId));
  const [fresh, setFresh] = useState<MarkSheet | null>(null);
  const sheet = fresh ?? loaded.data;

  return (
    <>
      <PageHead
        eyebrow={sheet ? `${sheet.examName} · ${sheet.termName}` : t("exams.marks.title")}
        title={sheet ? `${sheet.sectionLabel} · ${sheet.subjectName}` : t("exams.marks.sheet")}
        actions={
          <Link href="/app/exams/marks" className="btn">
            <ArrowLeft size={18} aria-hidden="true" />
            {t("exams.marks.back")}
          </Link>
        }
      />
      {loaded.error && !sheet ? (
        <section className="card">
          <ErrorState error={loaded.error} onRetry={loaded.reload} />
        </section>
      ) : !sheet ? (
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      ) : (
        <SheetEditor
          key={`${sheet.sheetId ?? "new"}:${sheet.version ?? 0}:${sheet.status ?? ""}`}
          sheet={sheet}
          onChanged={setFresh}
          onReload={() => {
            setFresh(null);
            loaded.reload();
          }}
        />
      )}
    </>
  );
}

function SheetEditor({
  sheet,
  onChanged,
  onReload,
}: {
  sheet: MarkSheet;
  onChanged: (sheet: MarkSheet) => void;
  onReload: () => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [entries, setEntries] = useState<Record<string, EntryText>>(() => entriesFrom(sheet));
  const [saving, setSaving] = useState<"save" | "submit" | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [clientError, setClientError] = useState<string | null>(null);
  const [confirmSubmit, setConfirmSubmit] = useState(false);
  const [unlocking, setUnlocking] = useState(false);

  const locked = sheet.status === "LOCKED";
  const editable = sheet.canEdit && !locked && !sheet.resultsPublished;
  const parts = partsOf(sheet);
  const saved = entriesFrom(sheet);
  const dirty = sheet.students.some((s) => !sameEntry(entries[s.studentId], saved[s.studentId]));
  const problems = Object.fromEntries(sheet.students.map((s) => [s.studentId, entryProblems(entries[s.studentId], sheet)]));
  const hasProblems = Object.values(problems).some((p) => Object.keys(p).length > 0);
  const inSection = sheet.students.filter((s) => s.inSection);
  const enteredNow = inSection.filter((s) => isComplete(entries[s.studentId], sheet)).length;
  const conflict = error?.status === 409 && Boolean(error.errors?.version);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const change = (studentId: string, next: Partial<EntryText>) =>
    setEntries((prev) => ({ ...prev, [studentId]: { ...prev[studentId], ...next } }));

  const typeMark = (studentId: string, part: Part, text: string) => change(studentId, { [part]: text, code: null });

  const pickCode = (studentId: string, code: MarkCode) => {
    const current = entries[studentId];
    change(
      studentId,
      current.code === code ? { code: null } : { code, theory: "", practical: "", internal: "" },
    );
  };

  /** Saves the draft; returns the saved sheet, or null when it failed. */
  const save = async (): Promise<MarkSheet | null> => {
    if (hasProblems) {
      setClientError(t("exams.v.fixMarks"));
      return null;
    }
    setClientError(null);
    setError(null);
    try {
      const next = await examsApi.saveSheet(sheet.paperId, sheet.sectionId, {
        version: sheet.version,
        entries: toEntries(sheet, entries),
      });
      return next;
    } catch (caught) {
      setError(toApiError(caught));
      return null;
    }
  };

  const onSave = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    setSaving("save");
    const next = await save();
    setSaving(null);
    if (next) {
      toast(t("exams.marks.saved", { count: next.entered, total: next.students.filter((s) => s.inSection).length }));
      onChanged(next);
    }
  };

  const askSubmit = () => {
    if (hasProblems) {
      setClientError(t("exams.v.fixMarks"));
      return;
    }
    const missing = missingStudents(sheet, entries);
    if (missing.length > 0) {
      setClientError(
        plural(t, "exams.v.missing", missing.length, {
          names: missing
            .slice(0, 3)
            .map((s) => s.fullName)
            .join(", "),
        }),
      );
      return;
    }
    setClientError(null);
    setConfirmSubmit(true);
  };

  const submit = async () => {
    setSaving("submit");
    try {
      let version = sheet.version;
      if (dirty || sheet.version === null) {
        const next = await save();
        if (!next) {
          setConfirmSubmit(false);
          return;
        }
        version = next.version;
      }
      const lockedSheet = await examsApi.submitSheet(sheet.paperId, sheet.sectionId, version);
      setConfirmSubmit(false);
      toast(t("exams.marks.submitted"));
      onChanged(lockedSheet);
    } finally {
      setSaving(null);
    }
  };

  const status = sheet.status ?? "NOT_STARTED";
  return (
    <section className="card flex flex-col gap-3.5" aria-label={t("exams.marks.sheet")} data-testid="marks-sheet">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <SheetPill status={status} />
        <span className="text-[13.5px] text-ink-2 num" data-testid="marks-entered">
          {t("exams.marks.enteredOf", { entered: enteredNow, total: inSection.length })}
        </span>
        <span className="text-[13px] text-ink-3">
          {t("exams.marks.outOf", {
            max: formatScore(sheet.maxTotal),
            parts: parts.map((part) => `${t(PART_LABEL[part])} ${formatScore(sheet[MAX_OF[part]])}`).join(" + "),
          })}
          {sheet.paperDate ? ` · ${formatPlainDate(sheet.paperDate, locale)}` : ""}
        </span>
      </div>

      {locked ? (
        <div className="alert alert-good" role="status" data-testid="marks-locked">
          <Lock size={18} aria-hidden="true" className="mt-0.5 flex-none" />
          <span className="min-w-0 flex-1">
            {t("exams.marks.lockedBy", {
              name: sheet.submittedByName ?? "—",
              date: sheet.submittedAt ? formatDateTime(sheet.submittedAt, locale) : "—",
            })}{" "}
            {sheet.resultsPublished ? t("exams.marks.lockedPublished") : sheet.canUnlock ? "" : t("exams.marks.lockedAsk")}
          </span>
          {sheet.canUnlock && !sheet.resultsPublished ? (
            <button type="button" className="btn btn-sm" onClick={() => setUnlocking(true)}>
              <LockOpen size={16} aria-hidden="true" />
              {t("exams.marks.unlock")}
            </button>
          ) : null}
        </div>
      ) : null}
      {!locked && sheet.unlockedByName ? (
        <div className="alert alert-info" role="status">
          <span>
            {t("exams.marks.unlockedBy", {
              name: sheet.unlockedByName,
              date: sheet.unlockedAt ? formatDateTime(sheet.unlockedAt, locale) : "—",
              reason: sheet.unlockReason ?? "",
            })}
          </span>
        </div>
      ) : null}
      {!locked && !sheet.canEdit ? <p className="text-sm text-ink-2">{t("exams.marks.readOnly")}</p> : null}

      <p className="text-[12.5px] text-ink-3" data-testid="marks-legend">
        {MARK_CODES.map((code) => `${code} ${codeLabel(t, code)}`).join(" · ")}. {t("exams.marks.codesRule")}
      </p>

      {sheet.students.length === 0 ? (
        <p className="empty">{t("exams.marks.noStudents")}</p>
      ) : (
        <form method="post" onSubmit={onSave} noValidate className="flex flex-col gap-3">
          <table className="marks-table" data-testid="marks-table">
            <caption className="sr-only">
              {t("exams.marks.caption", { section: sheet.sectionLabel, subject: sheet.subjectName })}
            </caption>
            <thead>
              <tr>
                <th scope="col" className="marks-roll">
                  {t("exams.col.roll")}
                </th>
                <th scope="col">{t("exams.col.student")}</th>
                {parts.map((part) => (
                  <th key={part} scope="col" className="r">
                    {t(PART_LABEL[part])} ({formatScore(sheet[MAX_OF[part]])})
                  </th>
                ))}
                <th scope="col">{t("exams.col.code")}</th>
                <th scope="col" className="r">
                  {t("exams.col.total")}
                </th>
                <th scope="col">{t("exams.col.grade")}</th>
              </tr>
            </thead>
            <tbody>
              {sheet.students.map((row) => {
                const entry = entries[row.studentId];
                const rowProblems = problems[row.studentId];
                const total = entryTotal(entry, sheet);
                const unchanged = sameEntry(entry, saved[row.studentId]);
                return (
                  <tr key={row.studentId} className={row.inSection ? undefined : "marks-row-left"} data-testid="marks-row">
                    <td className="marks-roll num">{row.rollNo ?? "—"}</td>
                    <td className="marks-student">
                      <b className="block font-semibold leading-tight">{row.fullName}</b>
                      <span className="block text-[12px] text-ink-3">
                        {row.admissionNo}
                        {row.rollNo !== null ? ` · ${t("exams.marks.roll", { roll: row.rollNo })}` : ""}
                      </span>
                      {!row.inSection ? <Pill>{t("exams.marks.left")}</Pill> : null}
                    </td>
                    {parts.map((part) => (
                      <td key={part} className="marks-part">
                        <span className="marks-label" aria-hidden="true">
                          {t(PART_LABEL[part])} / {formatScore(sheet[MAX_OF[part]])}
                        </span>
                        <input
                          className="input marks-input num"
                          name={`${part}-${row.studentId}`}
                          inputMode="decimal"
                          autoComplete="off"
                          aria-label={t("exams.marks.inputLabel", {
                            part: t(PART_LABEL[part]),
                            name: row.fullName,
                            max: formatScore(sheet[MAX_OF[part]]),
                          })}
                          value={entry[part]}
                          placeholder={entry.code ?? ""}
                          disabled={!editable}
                          aria-invalid={rowProblems[part] ? true : undefined}
                          onChange={(e) => typeMark(row.studentId, part, e.target.value)}
                        />
                        {rowProblems[part] ? (
                          <span className="field-error">
                            {t(rowProblems[part], { max: formatScore(sheet[MAX_OF[part]]) })}
                          </span>
                        ) : null}
                      </td>
                    ))}
                    <td className="marks-code-cell">
                      <span className="marks-codes" role="group" aria-label={t("exams.marks.codeFor", { name: row.fullName })}>
                        {MARK_CODES.map((code) => (
                          <button
                            key={code}
                            type="button"
                            data-code={code}
                            aria-pressed={entry.code === code}
                            title={codeLabel(t, code)}
                            disabled={!editable}
                            onClick={() => pickCode(row.studentId, code)}
                          >
                            {code}
                          </button>
                        ))}
                      </span>
                    </td>
                    <td className="marks-total r num">
                      <span className="marks-label" aria-hidden="true">
                        {t("exams.col.total")}
                      </span>
                      {entry.code ?? (total === null ? "—" : formatScore(total))}
                    </td>
                    <td className="marks-grade">
                      <span className="marks-label" aria-hidden="true">
                        {t("exams.col.grade")}
                      </span>
                      {unchanged && row.grade ? row.grade : "—"}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          {editable ? (
            <div className="att-savebar">
              <FormAlert message={clientError} />
              {error && !conflict ? <FormAlert message={errorMessage(error, t)} /> : null}
              {conflict ? (
                <div className="alert alert-warn" role="alert">
                  <span className="min-w-0 flex-1">{error?.detail ?? t("exams.marks.conflict")}</span>
                  <button type="button" className="btn btn-sm" onClick={onReload}>
                    <RefreshCw size={16} aria-hidden="true" />
                    {t("exams.marks.reload")}
                  </button>
                </div>
              ) : null}
              <span className="mr-auto text-[13px] text-ink-3" aria-live="polite">
                {dirty
                  ? t("exams.marks.unsaved")
                  : sheet.updatedByName && sheet.updatedAt
                    ? t("exams.marks.lastSaved", {
                        name: sheet.updatedByName,
                        date: formatDateTime(sheet.updatedAt, locale),
                      })
                    : t("exams.marks.notSaved")}
              </span>
              <button type="submit" className="btn" disabled={saving !== null || !dirty}>
                {saving === "save" ? t("common.saving") : t("exams.marks.saveDraft")}
              </button>
              <button type="button" className="btn btn-primary" disabled={saving !== null} onClick={askSubmit}>
                <Lock size={16} aria-hidden="true" />
                {t("exams.marks.submit")}
              </button>
            </div>
          ) : null}
        </form>
      )}

      <ConfirmDialog
        open={confirmSubmit}
        title={t("exams.marks.submitTitle")}
        body={t("exams.marks.submitBody", { section: sheet.sectionLabel, subject: sheet.subjectName })}
        confirmLabel={t("exams.marks.submitConfirm")}
        danger={false}
        onClose={() => setConfirmSubmit(false)}
        onConfirm={submit}
      />
      {unlocking ? (
        <UnlockDialog
          sheet={sheet}
          onClose={() => setUnlocking(false)}
          onUnlocked={(next) => {
            setUnlocking(false);
            toast(t("exams.marks.unlocked"));
            onChanged(next);
          }}
        />
      ) : null}
    </section>
  );
}

/** The reason is required (it goes into the audit trail and is shown on the sheet). Pure, exported for tests. */
export function reasonProblem(reason: string): MessageKey | null {
  if (!reason.trim()) return "exams.v.reason";
  if (reason.length > REASON_MAX) return "exams.v.reasonLong";
  return null;
}

function UnlockDialog({
  sheet,
  onClose,
  onUnlocked,
}: {
  sheet: MarkSheet;
  onClose: () => void;
  onUnlocked: (sheet: MarkSheet) => void;
}) {
  const { t } = useI18n();
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const key = reasonProblem(reason);
    if (key) {
      setProblem(t(key));
      return;
    }
    setProblem(null);
    setBusy(true);
    setError(null);
    try {
      onUnlocked(await examsApi.unlockSheet(sheet.paperId, sheet.sectionId, reason.trim()));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={t("exams.marks.unlockTitle")}
      description={t("exams.marks.unlockBody", { section: sheet.sectionLabel, subject: sheet.subjectName })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={error} />
        <TextAreaField
          label={t("exams.marks.unlockReason")}
          name="reason"
          value={reason}
          maxLength={REASON_MAX}
          required
          error={problem ?? undefined}
          onChange={(e) => setReason(e.target.value)}
          data-autofocus
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? t("common.working") : t("exams.marks.unlock")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
