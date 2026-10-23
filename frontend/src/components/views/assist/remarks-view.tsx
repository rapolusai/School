"use client";

import { Check, RefreshCw, Wand2 } from "lucide-react";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, toApiError } from "@/lib/api";
import { assistApi } from "@/lib/assist-api";
import { examsApi } from "@/lib/exams-api";
import { plural, translateOr, useI18n } from "@/lib/i18n";
import { REMARK_TONES, type RemarkDraft, type RemarkSheet, type RemarkStudent, type RemarkTone } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AssistNav, AssistOffNotice, SimulatedLabel, assistError, useAssistSettings } from "./assist-shared";

/** How many students go in one request while a section is drafted, so progress can be shown. */
export const BATCH_SIZE = 5;

/** The fields a remark request was built from, in the order they are shown under "What was used". */
const INPUT_FIELDS = ["first", "classLabel", "percent", "grade", "attendance", "best", "weak"] as const;

/** Splits ids into batches of {@link BATCH_SIZE}. Pure, exported for tests. */
export function batches<T>(items: readonly T[], size = BATCH_SIZE): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

/** Students whose overall remark is not accepted yet: the ones a section-wide draft covers. */
export function studentsToDraft(sheet: RemarkSheet): string[] {
  return sheet.students.filter((s) => s.draft?.status !== "ACCEPTED").map((s) => s.studentId);
}

/**
 * Report card remark drafts for a section and term (docs/api/phase-3-assist.md): draft the whole section in batches
 * with progress, see what each draft was built from, edit, and accept into the report card.
 */
export function RemarksView() {
  const { t } = useI18n();
  const settings = useAssistSettings();
  const structure = useApiData("assist:exams-structure", () => examsApi.structure());
  const classes = useApiData("assist:classes", () => api.listClasses());
  const [termId, setTermId] = useState("");
  const [sectionId, setSectionId] = useState("");
  const [tone, setTone] = useState<RemarkTone>("ENCOURAGING");
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const sheet = useApiData(termId && sectionId ? `assist:remarks:${termId}:${sectionId}` : null, () =>
    assistApi.remarks(termId, sectionId),
  );
  const terms = structure.data?.terms ?? [];
  const sections = (classes.data ?? []).flatMap((c) => c.sections.map((s) => ({ id: s.id, label: `${c.name} ${s.name}` })));

  const draftAll = async () => {
    if (!sheet.data) return;
    const ids = studentsToDraft(sheet.data);
    setProblem(null);
    setProgress({ done: 0, total: ids.length });
    try {
      let done = 0;
      for (const batch of batches(ids)) {
        await assistApi.draftRemarks({ termId, sectionId, tone, studentIds: batch });
        done += batch.length;
        setProgress({ done, total: ids.length });
      }
    } catch (error) {
      setProblem(assistError(toApiError(error), t));
    } finally {
      setProgress(null);
      sheet.reload();
      settings.reload();
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHead eyebrow={t("assist.eyebrow")} title={t("assist.remarks.title")} actions={<SimulatedLabel simulated={settings.data?.simulated ?? true} />} />
      <AssistNav />
      {settings.data ? <AssistOffNotice settings={settings.data} feature="remarksEnabled" /> : null}
      <form
        method="post"
        className="card flex flex-wrap items-end gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          void draftAll();
        }}
      >
        <label className="field w-full sm:w-auto sm:min-w-40">
          <span className="field-label">{t("assist.remarks.term")}</span>
          <select className="input" name="termId" value={termId} onChange={(e) => setTermId(e.target.value)} required>
            <option value="">{t("assist.choose")}</option>
            {terms.map((term) => (
              <option key={term.id} value={term.id}>
                {term.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field w-full sm:w-auto sm:min-w-40">
          <span className="field-label">{t("assist.remarks.section")}</span>
          <select className="input" name="sectionId" value={sectionId} onChange={(e) => setSectionId(e.target.value)} required>
            <option value="">{t("assist.choose")}</option>
            {sections.map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <label className="field w-full sm:w-auto sm:min-w-40">
          <span className="field-label">{t("assist.remarks.tone")}</span>
          <select className="input" name="tone" value={tone} onChange={(e) => setTone(e.target.value as RemarkTone)}>
            {REMARK_TONES.map((value) => (
              <option key={value} value={value}>
                {t(value === "ENCOURAGING" ? "assist.tone.ENCOURAGING" : "assist.tone.FORMAL")}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" className="btn btn-primary w-full sm:w-auto" disabled={!sheet.data || progress !== null} data-testid="assist-draft-all">
          <Wand2 size={16} aria-hidden="true" />
          {t("assist.remarks.draftAll")}
        </button>
      </form>
      {progress ? (
        <div className="card flex flex-col gap-2" role="status" data-testid="assist-progress">
          <p>{t("assist.remarks.progress", { done: progress.done, total: progress.total })}</p>
          <progress max={progress.total || 1} value={progress.done} className="w-full" />
        </div>
      ) : null}
      <FormAlert message={problem} />
      {structure.error && !structure.data ? <ErrorState error={structure.error} onRetry={structure.reload} /> : null}
      {!termId || !sectionId ? (
        <p className="empty">{t("assist.remarks.pick")}</p>
      ) : sheet.error && !sheet.data ? (
        <ErrorState error={sheet.error} onRetry={sheet.reload} />
      ) : !sheet.data ? (
        <LoadingRows rows={5} />
      ) : sheet.data.students.length === 0 ? (
        <p className="empty">{t("assist.remarks.empty")}</p>
      ) : (
        <>
          <p className="text-[13px] text-ink-3">
            {plural(t, "assist.remarks.count", sheet.data.students.length)} · {t("assist.remarks.privacy")}
          </p>
          <ul className="flex flex-col gap-3" data-testid="remark-list">
            {sheet.data.students.map((student) => (
              <RemarkRow
                key={student.studentId}
                student={student}
                canAccept={sheet.data?.canAccept ?? false}
                onRedraft={async () => {
                  await assistApi.draftRemarks({ termId, sectionId, tone, studentIds: [student.studentId] });
                  sheet.reload();
                  settings.reload();
                }}
                onChanged={sheet.reload}
              />
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function RemarkRow({
  student,
  canAccept,
  onRedraft,
  onChanged,
}: {
  student: RemarkStudent;
  canAccept: boolean;
  onRedraft: () => Promise<void>;
  onChanged: () => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const draft = student.draft;
  const [text, setText] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const value = text ?? draft?.acceptedText ?? draft?.draftText ?? "";
  const accepted = draft?.status === "ACCEPTED";

  const run = async (work: () => Promise<unknown>, done?: string) => {
    setBusy(true);
    setProblem(null);
    try {
      await work();
      setText(null);
      if (done) toast(done);
      onChanged();
    } catch (error) {
      setProblem(assistError(toApiError(error), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className="card flex flex-col gap-3" data-testid="remark-row" data-student={student.fullName}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-[15px] font-semibold">
          {student.rollNo !== null ? `${student.rollNo}. ` : ""}
          {student.fullName}
        </h2>
        <div className="flex flex-wrap items-center gap-2">
          {draft ? <SimulatedLabel simulated={draft.simulated} /> : null}
          {accepted ? (
            <Pill tone="good" dot>
              {t("assist.remarks.accepted")}
            </Pill>
          ) : null}
        </div>
      </div>
      {student.currentRemark ? (
        <p className="text-[13px] text-ink-3">
          {t("assist.remarks.current")}: {student.currentRemark}
        </p>
      ) : null}
      {draft ? (
        <>
          <label className="field">
            <span className="field-label">{t("assist.remarks.draft", { name: student.fullName })}</span>
            <textarea
              className="input"
              rows={3}
              maxLength={500}
              name={`remark-${student.studentId}`}
              value={value}
              readOnly={accepted}
              onChange={(e) => setText(e.target.value)}
            />
          </label>
          <UsedInputs draft={draft} />
          <FormAlert message={problem} />
          <div className="flex flex-wrap gap-2">
            {!accepted && canAccept ? (
              <button
                type="button"
                className="btn btn-primary btn-sm"
                disabled={busy || !value.trim()}
                onClick={() => void run(() => assistApi.acceptRemark(draft.id, value), t("assist.remarks.acceptedToast", { name: student.fullName }))}
              >
                <Check size={14} aria-hidden="true" />
                {t("assist.remarks.accept")}
              </button>
            ) : null}
            {!accepted && text !== null ? (
              <button type="button" className="btn btn-sm" disabled={busy || !value.trim()} onClick={() => void run(() => assistApi.editRemark(draft.id, value), t("assist.saved"))}>
                {t("assist.remarks.saveEdit")}
              </button>
            ) : null}
            {!accepted ? (
              <button type="button" className="btn btn-sm" disabled={busy} onClick={() => void run(onRedraft)}>
                <RefreshCw size={14} aria-hidden="true" />
                {t("assist.remarks.redraft")}
              </button>
            ) : null}
          </div>
          {!canAccept && !accepted ? <p className="text-[13px] text-ink-3">{t("assist.remarks.cannotAccept")}</p> : null}
        </>
      ) : (
        <div className="flex flex-col gap-2">
          <p className="text-[13px] text-ink-3">{t("assist.remarks.noDraft")}</p>
          <FormAlert message={problem} />
          <button type="button" className="btn btn-sm self-start" disabled={busy} onClick={() => void run(onRedraft)}>
            <Wand2 size={14} aria-hidden="true" />
            {t("assist.remarks.draftOne")}
          </button>
        </div>
      )}
    </li>
  );
}

/** "What was used": the minimum fields the draft was built from. Nothing else about the student is sent. */
export function UsedInputs({ draft }: { draft: Pick<RemarkDraft, "inputs" | "inputLines"> }) {
  const { t } = useI18n();
  return (
    <details className="text-[13px]" data-testid="used-inputs">
      <summary className="cursor-pointer text-ink-3">{t("assist.used.title")}</summary>
      <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
        {INPUT_FIELDS.filter((key) => draft.inputs[key]).map((key) => (
          <div key={key} className="contents">
            <dt className="text-ink-3">{translateOr(t, `assist.used.${key}`, key)}</dt>
            <dd>{draft.inputs[key]}</dd>
          </div>
        ))}
      </dl>
      {draft.inputLines.length > 0 ? (
        <ul className="mt-1 list-disc pl-5">
          {draft.inputLines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      ) : null}
      <p className="mt-1 text-ink-3">{t("assist.used.never")}</p>
    </details>
  );
}
