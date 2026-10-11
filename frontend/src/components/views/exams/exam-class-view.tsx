"use client";

import { ArrowLeft, ArrowRight, CalendarCheck2, ChartColumnBig, Send, TicketCheck, Undo2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { examsApi } from "@/lib/exams-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import type { DateSheetClash, ExamClassDetail, ExamPaper, MarkSheetRow, PaperRequest } from "@/lib/types";
import { MAX_PAPERS, REASON_MAX } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { isPlainDate } from "@/lib/validation";
import { formatScore, maxParts, paperWhen, parseDecimal, shortTime } from "./exams-shared";
import { reasonProblem } from "./marks-grid";

const ROOM_MAX = 60;
const MIN_DURATION = 15;
const MAX_DURATION = 600;
const MAX_MARKS = 1000;

/* ------------------------------------------------------------------ papers (pure, exported for tests) */

/** A subject's row in the papers editor, as typed. */
export type PaperDraft = {
  subjectId: string;
  subjectName: string;
  include: boolean;
  maxTheory: string;
  maxPractical: string;
  maxInternal: string;
  date: string;
  startTime: string;
  duration: string;
  room: string;
};

/** Every subject the class studies, with its paper when it has one, then papers of other subjects. */
export function paperDrafts(detail: Pick<ExamClassDetail, "classSubjects" | "papers">): PaperDraft[] {
  const bySubject = new Map(detail.papers.map((p) => [p.subjectId, p]));
  const subjects = [
    ...detail.classSubjects.map((s) => ({ id: s.id, name: s.name })),
    ...detail.papers
      .filter((p) => !detail.classSubjects.some((s) => s.id === p.subjectId))
      .map((p) => ({ id: p.subjectId, name: p.subjectName })),
  ];
  return subjects.map((s) => {
    const p = bySubject.get(s.id);
    const num = (value: number | null | undefined) => (value ? formatScore(value) : "");
    return {
      subjectId: s.id,
      subjectName: s.name,
      include: Boolean(p),
      maxTheory: p ? formatScore(p.maxTheory) : "",
      maxPractical: num(p?.maxPractical),
      maxInternal: num(p?.maxInternal),
      date: p?.date ?? "",
      startTime: shortTime(p?.startTime),
      duration: p?.durationMinutes ? String(p.durationMinutes) : "",
      room: p?.room ?? "",
    };
  });
}

/** What is wrong with a paper row, mirroring the API: marks 0–1000 adding up to 1–1000, the slot all or nothing. */
export function paperProblem(draft: PaperDraft): MessageKey | null {
  if (!draft.include) return null;
  const marks = [draft.maxTheory, draft.maxPractical, draft.maxInternal].map(parseDecimal);
  if (marks.some((m) => m !== null && (Number.isNaN(m) || m > MAX_MARKS))) return "exams.v.paperMarks";
  const total = marks.reduce<number>((sum, m) => sum + (m ?? 0), 0);
  if (total <= 0 || total > MAX_MARKS) return "exams.v.paperMarks";
  const slot = [draft.date, draft.startTime, draft.duration].map((v) => v.trim());
  if (slot.some(Boolean) && !slot.every(Boolean)) return "exams.v.paperSlot";
  if (draft.date && !isPlainDate(draft.date)) return "exams.v.date";
  if (draft.startTime && !/^\d{2}:\d{2}$/.test(draft.startTime)) return "exams.v.time";
  if (draft.duration) {
    const minutes = Number(draft.duration);
    if (!Number.isInteger(minutes) || minutes < MIN_DURATION || minutes > MAX_DURATION) return "exams.v.duration";
  }
  if (draft.room.length > ROOM_MAX) return "exams.v.room";
  return null;
}

export function toPaperRequests(drafts: PaperDraft[]): PaperRequest[] {
  return drafts
    .filter((d) => d.include)
    .map((d) => ({
      subjectId: d.subjectId,
      maxTheory: parseDecimal(d.maxTheory) ?? 0,
      maxPractical: parseDecimal(d.maxPractical),
      maxInternal: parseDecimal(d.maxInternal),
      date: d.date || null,
      startTime: d.startTime || null,
      durationMinutes: d.duration ? Number(d.duration) : null,
      room: d.room.trim() || null,
    }));
}

/* ------------------------------------------------------------------ view */

/** /app/exams/[examId]/[classId]: an exam for one class: papers, date sheet, sections and results. */
export function ExamClassView({ examId, classId }: { examId: string; classId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const loaded = useApiData(`exams:class:${examId}:${classId}`, () => examsApi.examClass(examId, classId));
  const [fresh, setFresh] = useState<ExamClassDetail | null>(null);
  const detail = fresh ?? loaded.data;
  const marks = useApiData(detail ? `exams:marks:${detail.academicYearId}` : null, () =>
    examsApi.marks(detail?.academicYearId),
  );
  const classes = useApiData("exams:school-classes", api.listClasses);
  const [action, setAction] = useState<"publishDates" | "withdrawDates" | "publishResults" | "withdrawResults" | null>(
    null,
  );

  const changed = (next: ExamClassDetail, message: string) => {
    setFresh(next);
    toast(message);
    marks.reload();
  };

  if (loaded.error && !detail) {
    return (
      <>
        <PageHead title={t("exams.title")} actions={<BackToExams />} />
        <section className="card">
          <ErrorState error={loaded.error} onRetry={loaded.reload} />
        </section>
      </>
    );
  }
  if (!detail) {
    return (
      <>
        <PageHead title={t("exams.title")} actions={<BackToExams />} />
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }

  const sheets = (marks.data?.exams.find((e) => e.examId === examId)?.sheets ?? []).filter(
    (s) => s.examClassId === detail.examClassId,
  );
  const sections = classes.data?.find((c) => c.id === classId)?.sections ?? [];
  const pending = sheets.filter((s) => s.status !== "LOCKED").length;
  const scheduled = detail.papers.filter((p) => p.date).length;
  const base = `/app/exams/${encodeURIComponent(examId)}/${encodeURIComponent(classId)}`;

  return (
    <>
      <PageHead
        eyebrow={`${detail.examName} · ${detail.termName} · ${detail.academicYearName}`}
        title={detail.className}
        actions={
          <>
            <BackToExams />
            {detail.papers.length > 0 ? (
              <Link href={`${base}/hall-tickets`} className="btn">
                <TicketCheck size={18} aria-hidden="true" />
                {t("exams.hallTickets.title")}
              </Link>
            ) : null}
            <Link
              href={`/app/reports/exam-analysis?exam=${encodeURIComponent(examId)}&class=${encodeURIComponent(classId)}`}
              className="btn"
            >
              <ChartColumnBig size={18} aria-hidden="true" />
              {t("exams.analysis.link")}
            </Link>
          </>
        }
      />

      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        <section className="card flex flex-col gap-3" aria-labelledby="date-sheet-status" data-testid="date-sheet-status">
          <h2 id="date-sheet-status">{t("exams.dateSheet.title")}</h2>
          {detail.dateSheetPublishedAt ? (
            <p className="text-[13.5px] text-ink-2">
              <Pill tone="info" dot>
                {t("exams.dateSheet.published")}
              </Pill>{" "}
              {t("exams.publishedBy", {
                name: detail.dateSheetPublishedBy ?? "—",
                date: formatDateTime(detail.dateSheetPublishedAt, locale),
              })}
            </p>
          ) : (
            <p className="text-[13.5px] text-ink-2">
              {t("exams.dateSheet.notPublished", { scheduled, total: detail.papers.length })}
            </p>
          )}
          {detail.canSetup && !detail.resultsPublishedAt ? (
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                className="btn btn-primary"
                disabled={detail.papers.length === 0}
                onClick={() => setAction("publishDates")}
              >
                <CalendarCheck2 size={16} aria-hidden="true" />
                {detail.dateSheetPublishedAt ? t("exams.dateSheet.republish") : t("exams.dateSheet.publish")}
              </button>
              {detail.dateSheetPublishedAt ? (
                <button type="button" className="btn" onClick={() => setAction("withdrawDates")}>
                  <Undo2 size={16} aria-hidden="true" />
                  {t("exams.dateSheet.withdraw")}
                </button>
              ) : null}
            </div>
          ) : null}
          <p className="text-[12.5px] text-ink-3">{t("exams.dateSheet.rule")}</p>
        </section>

        <section className="card flex flex-col gap-3" aria-labelledby="results-status" data-testid="results-status">
          <h2 id="results-status">{t("exams.results.title")}</h2>
          {detail.resultsPublishedAt ? (
            <p className="text-[13.5px] text-ink-2">
              <Pill tone="good" dot>
                {t("exams.results.published")}
              </Pill>{" "}
              {t("exams.publishedBy", {
                name: detail.resultsPublishedBy ?? "—",
                date: formatDateTime(detail.resultsPublishedAt, locale),
              })}
            </p>
          ) : (
            <p className="text-[13.5px] text-ink-2" data-testid="results-pending">
              {sheets.length === 0
                ? t("exams.results.noSheets")
                : pending > 0
                  ? t("exams.results.pending", { pending, total: sheets.length })
                  : t("exams.results.ready")}
            </p>
          )}
          {detail.canPublish ? (
            <div className="flex flex-wrap gap-2">
              {detail.resultsPublishedAt ? (
                <button type="button" className="btn" onClick={() => setAction("withdrawResults")}>
                  <Undo2 size={16} aria-hidden="true" />
                  {t("exams.results.withdraw")}
                </button>
              ) : (
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={sheets.length === 0 || pending > 0}
                  onClick={() => setAction("publishResults")}
                >
                  <Send size={16} aria-hidden="true" />
                  {t("exams.results.publish")}
                </button>
              )}
            </div>
          ) : null}
          <p className="text-[12.5px] text-ink-3">{t("exams.results.rule")}</p>
        </section>
      </div>

      <SectionsCard examId={examId} detail={detail} sections={sections} sheets={sheets} />

      <PapersCard key={detail.papers.map((p) => p.id + p.date + p.startTime).join("|")} detail={detail} onSaved={(next) => changed(next, t("exams.papers.saved"))} />

      <ConfirmDialog
        open={action === "publishDates"}
        title={t("exams.dateSheet.publishTitle", { exam: detail.examName, class: detail.className })}
        body={t("exams.dateSheet.publishBody")}
        confirmLabel={detail.dateSheetPublishedAt ? t("exams.dateSheet.republish") : t("exams.dateSheet.publish")}
        danger={false}
        onClose={() => setAction(null)}
        onConfirm={async () => {
          const next = await examsApi.publishDateSheet(examId, classId);
          setAction(null);
          changed(next, t("exams.dateSheet.publishedToast"));
        }}
      />
      <ConfirmDialog
        open={action === "withdrawDates"}
        title={t("exams.dateSheet.withdrawTitle")}
        body={t("exams.dateSheet.withdrawBody")}
        confirmLabel={t("exams.dateSheet.withdraw")}
        onClose={() => setAction(null)}
        onConfirm={async () => {
          const next = await examsApi.withdrawDateSheet(examId, classId);
          setAction(null);
          changed(next, t("exams.dateSheet.withdrawnToast"));
        }}
      />
      <ConfirmDialog
        open={action === "publishResults"}
        title={t("exams.results.publishTitle", { exam: detail.examName, class: detail.className })}
        body={t("exams.results.publishBody")}
        confirmLabel={t("exams.results.publish")}
        danger={false}
        onClose={() => setAction(null)}
        onConfirm={async () => {
          const next = await examsApi.publishResults(examId, classId);
          setAction(null);
          changed(next, t("exams.results.publishedToast"));
        }}
      />
      {action === "withdrawResults" ? (
        <WithdrawResultsDialog
          detail={detail}
          onClose={() => setAction(null)}
          onDone={(next) => {
            setAction(null);
            changed(next, t("exams.results.withdrawnToast"));
          }}
        />
      ) : null}
    </>
  );
}

function BackToExams() {
  const { t } = useI18n();
  return (
    <Link href="/app/exams" className="btn">
      <ArrowLeft size={18} aria-hidden="true" />
      {t("exams.back")}
    </Link>
  );
}

function SectionsCard({
  examId,
  detail,
  sections,
  sheets,
}: {
  examId: string;
  detail: ExamClassDetail;
  sections: { id: string; name: string }[];
  sheets: MarkSheetRow[];
}) {
  const { t } = useI18n();
  return (
    <section className="card flex flex-col gap-2" aria-labelledby="exam-sections" data-testid="exam-sections">
      <h2 id="exam-sections">{t("exams.sections.title")}</h2>
      {sections.length === 0 ? (
        <p className="text-sm text-ink-2">{t("exams.sections.none")}</p>
      ) : (
        <ul className="rowcards">
          {sections.map((section) => {
            const own = sheets.filter((s) => s.sectionId === section.id);
            const locked = own.filter((s) => s.status === "LOCKED").length;
            return (
              <li key={section.id} className="rowcard flex-wrap">
                <span className="min-w-0 flex-1">
                  <b className="block font-semibold">
                    {detail.className} {section.name}
                  </b>
                  <span className="block text-[12.5px] text-ink-3">
                    {own.length === 0 ? t("exams.sections.noSheets") : t("exams.marks.locked", { locked, total: own.length })}
                  </span>
                </span>
                <span className="flex flex-wrap gap-x-4 gap-y-1 text-[13.5px]">
                  <Link
                    className="link inline-flex items-center gap-1"
                    href={`/app/exams/results/${encodeURIComponent(examId)}/${encodeURIComponent(section.id)}`}
                  >
                    {t("exams.sections.results")}
                    <ArrowRight size={14} aria-hidden="true" />
                  </Link>
                  <Link
                    className="link inline-flex items-center gap-1"
                    href={`/app/exams/report-cards?section=${encodeURIComponent(section.id)}&term=${encodeURIComponent(detail.termId)}`}
                  >
                    {t("exams.sections.reportCards")}
                    <ArrowRight size={14} aria-hidden="true" />
                  </Link>
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/** The class's papers: which subjects, their maximum marks and their date sheet slot, with clashes shown in place. */
function PapersCard({ detail, onSaved }: { detail: ExamClassDetail; onSaved: (next: ExamClassDetail) => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [drafts, setDrafts] = useState<PaperDraft[]>(() => paperDrafts(detail));
  const [problems, setProblems] = useState<Record<string, MessageKey>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const editable = detail.canSetup && !detail.resultsPublishedAt;
  const clashesOf = (paper: ExamPaper | undefined): DateSheetClash[] =>
    paper ? detail.clashes.filter((c) => c.paperId === paper.id) : [];

  const update = (subjectId: string, next: Partial<PaperDraft>) => {
    setDrafts((prev) => prev.map((d) => (d.subjectId === subjectId ? { ...d, ...next } : d)));
    setProblems((prev) => {
      if (!prev[subjectId]) return prev;
      const rest = { ...prev };
      delete rest[subjectId];
      return rest;
    });
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found: Record<string, MessageKey> = {};
    for (const d of drafts) {
      const problem = paperProblem(d);
      if (problem) found[d.subjectId] = problem;
    }
    setProblems(found);
    if (Object.keys(found).length > 0) {
      setError(t("validation.fixErrors"));
      return;
    }
    const papers = toPaperRequests(drafts);
    if (papers.length > MAX_PAPERS) {
      setError(t("exams.v.tooManyPapers", { max: MAX_PAPERS }));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onSaved(await examsApi.savePapers(detail.examId, detail.classId, papers));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  if (!editable) {
    return (
      <section className="card flex flex-col gap-2" aria-labelledby="papers-title" data-testid="papers">
        <h2 id="papers-title">{t("exams.papers.title")}</h2>
        {detail.papers.length === 0 ? (
          <p className="text-sm text-ink-2">{t("exams.papers.empty")}</p>
        ) : (
          <ul className="rowcards">
            {detail.papers.map((p) => (
              <li key={p.id} className="rowcard flex-wrap">
                <span className="min-w-0 flex-1">
                  <b className="block font-semibold">{p.subjectName}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {maxParts(t, p)} · {paperWhen(t, p, locale)}
                    {p.room ? ` · ${p.room}` : ""}
                  </span>
                </span>
                <b className="num">{formatScore(p.maxTotal)}</b>
              </li>
            ))}
          </ul>
        )}
      </section>
    );
  }

  return (
    <section className="card flex flex-col gap-2" aria-labelledby="papers-title" data-testid="papers">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div>
          <h2 id="papers-title">{t("exams.papers.title")}</h2>
          <p className="mt-1 text-[13px] text-ink-3">{t("exams.papers.intro")}</p>
        </div>
      </div>
      {detail.clashes.length > 0 ? (
        <div className="alert alert-warn" role="status" data-testid="clashes">
          <span>
            {plural(t, "exams.papers.clashes", detail.clashes.length)}{" "}
            {detail.clashes.map((c) => c.detail).join(" ")}
          </span>
        </div>
      ) : null}
      {drafts.length === 0 ? (
        <p className="text-sm text-ink-2">{t("exams.papers.noSubjects")}</p>
      ) : (
        <form method="post" onSubmit={onSubmit} noValidate className="flex flex-col">
          {drafts.map((d) => {
            const paper = detail.papers.find((p) => p.subjectId === d.subjectId);
            const problem = problems[d.subjectId];
            const clashes = clashesOf(paper);
            return (
              <div key={d.subjectId} className="paper-row" data-testid="paper-row">
                <label className="flex items-center gap-2 font-semibold">
                  <input
                    type="checkbox"
                    name={`include-${d.subjectId}`}
                    checked={d.include}
                    disabled={Boolean(paper?.hasMarks)}
                    onChange={(e) => update(d.subjectId, { include: e.target.checked, maxTheory: d.maxTheory || "80" })}
                  />
                  {d.subjectName}
                  {paper?.hasMarks ? <Pill>{t("exams.papers.hasMarks")}</Pill> : null}
                </label>
                {d.include ? (
                  <>
                    <div className="paper-fields">
                      <PaperInput label={t("exams.part.theory")} value={d.maxTheory} name={`theory-${d.subjectId}`} subject={d.subjectName} onChange={(v) => update(d.subjectId, { maxTheory: v })} />
                      <PaperInput label={t("exams.part.practical")} value={d.maxPractical} name={`practical-${d.subjectId}`} subject={d.subjectName} onChange={(v) => update(d.subjectId, { maxPractical: v })} />
                      <PaperInput label={t("exams.part.internal")} value={d.maxInternal} name={`internal-${d.subjectId}`} subject={d.subjectName} onChange={(v) => update(d.subjectId, { maxInternal: v })} />
                    </div>
                    <div className="paper-slot">
                      <PaperInput label={t("exams.paper.date")} type="date" value={d.date} name={`date-${d.subjectId}`} subject={d.subjectName} onChange={(v) => update(d.subjectId, { date: v })} />
                      <PaperInput label={t("exams.paper.start")} type="time" value={d.startTime} name={`start-${d.subjectId}`} subject={d.subjectName} onChange={(v) => update(d.subjectId, { startTime: v })} />
                      <PaperInput label={t("exams.paper.minutes")} value={d.duration} name={`duration-${d.subjectId}`} subject={d.subjectName} inputMode="numeric" onChange={(v) => update(d.subjectId, { duration: v })} />
                      <PaperInput label={t("exams.paper.room")} value={d.room} name={`room-${d.subjectId}`} subject={d.subjectName} maxLength={ROOM_MAX} onChange={(v) => update(d.subjectId, { room: v })} />
                    </div>
                  </>
                ) : null}
                {problem ? <p className="field-error">{t(problem, { min: MIN_DURATION, max: MAX_DURATION })}</p> : null}
                {clashes.map((c) => (
                  <p key={`${c.kind}:${c.otherPaperId ?? ""}`} className="text-[13px] font-semibold text-warn">
                    {c.detail}
                  </p>
                ))}
              </div>
            );
          })}
          <div className="att-savebar">
            <FormAlert message={error} />
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? t("common.saving") : t("exams.papers.save")}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

function PaperInput({
  label,
  name,
  subject,
  value,
  onChange,
  type = "text",
  inputMode = "decimal",
  maxLength,
}: {
  label: string;
  name: string;
  subject: string;
  value: string;
  onChange: (value: string) => void;
  type?: "text" | "date" | "time";
  inputMode?: "decimal" | "numeric";
  maxLength?: number;
}) {
  const { t } = useI18n();
  return (
    <label className="field">
      <span className="field-label text-[12.5px]">{label}</span>
      <input
        className="input"
        type={type}
        name={name}
        value={value}
        inputMode={type === "text" ? inputMode : undefined}
        maxLength={maxLength}
        aria-label={t("exams.paper.fieldFor", { field: label, subject })}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}

function WithdrawResultsDialog({
  detail,
  onClose,
  onDone,
}: {
  detail: ExamClassDetail;
  onClose: () => void;
  onDone: (next: ExamClassDetail) => void;
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
      onDone(await examsApi.withdrawResults(detail.examId, detail.classId, reason.trim()));
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
      title={t("exams.results.withdrawTitle")}
      description={t("exams.results.withdrawBody", { exam: detail.examName, class: detail.className })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={error} />
        <TextAreaField
          label={t("exams.results.withdrawReason")}
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
          <button type="submit" className="btn btn-danger" disabled={busy}>
            {busy ? t("common.working") : t("exams.results.withdraw")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
