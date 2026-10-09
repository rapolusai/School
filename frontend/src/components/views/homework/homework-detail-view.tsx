"use client";

import { ArrowLeft, ClipboardCheck, Pencil, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { AttachmentList } from "@/components/views/files/attachments";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPlainDate, initials } from "@/lib/format";
import { homeworkApi } from "@/lib/homework-api";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import { SUBMISSION_STATUSES, type HomeworkTracker, type SubmissionStatus, type TrackerRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { HomeworkFormDialog } from "./homework-form-dialog";
import { countsText, dueLabel, dueTone, HOMEWORK_STATUS_LABEL, Instructions, StatusPill } from "./homework-shared";

const FILTERS = ["all", "waiting", "missing", "late", "reviewed"] as const;
type Filter = (typeof FILTERS)[number];

const FILTER_LABEL: Record<Filter, MessageKey> = {
  all: "homework.tracker.all",
  waiting: "homework.tracker.waiting",
  missing: "homework.tracker.missing",
  late: "homework.tracker.late",
  reviewed: "homework.tracker.reviewed",
};

/** Which tracker rows a filter shows. Pure, exported for tests. */
export function trackerFilter(rows: TrackerRow[], filter: Filter): TrackerRow[] {
  switch (filter) {
    case "waiting":
      return rows.filter((r) => r.status === "SUBMITTED");
    case "missing":
      return rows.filter((r) => r.status === "MISSING");
    case "late":
      return rows.filter((r) => r.late);
    case "reviewed":
      return rows.filter((r) => r.status === "REVIEWED" || r.status === "NEEDS_REDO");
    default:
      return rows;
  }
}

function ReviewDialog({
  homeworkId,
  row,
  onClose,
  onSaved,
}: {
  homeworkId: string;
  row: TrackerRow;
  onClose: () => void;
  onSaved: (row: TrackerRow) => void;
}) {
  const { t, lang } = useI18n();
  const name = useId();
  // Opening a fresh submission suggests "Reviewed"; a reviewed one opens on its current status.
  const [status, setStatus] = useState<SubmissionStatus>(row.status === "NEEDS_REDO" ? "NEEDS_REDO" : "REVIEWED");
  const [grade, setGrade] = useState(row.grade ?? "");
  const [remark, setRemark] = useState(row.remark ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy || !row.submissionId) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await homeworkApi.review(homeworkId, row.submissionId, {
        status,
        grade: grade.trim() || null,
        remark: remark.trim() || null,
      });
      onSaved(saved);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} title={t("homework.review.title", { name: row.fullName })} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={submit} noValidate>
        <FormAlert message={error} />
        <div className="flex flex-col gap-1">
          <p className="text-[13px] text-ink-3">
            {[
              row.submittedAt ? t("homework.submittedAt", { time: formatDateTime(row.submittedAt, localeFor(lang)) }) : null,
              row.late ? t("homework.late") : null,
              row.attempts > 1 ? t("homework.attempts", { count: row.attempts }) : null,
            ]
              .filter(Boolean)
              .join(" · ")}
          </p>
          {row.body ? <p className="msg-body">{row.body}</p> : null}
          <AttachmentList files={row.files} />
        </div>
        <fieldset className="fieldset">
          <legend>{t("homework.review.status")}</legend>
          <div className="seg" role="radiogroup" aria-label={t("homework.review.status")}>
            {SUBMISSION_STATUSES.map((s) => (
              <label key={s}>
                <input type="radio" name={name} value={s} checked={status === s} onChange={() => setStatus(s)} />
                {t(HOMEWORK_STATUS_LABEL[s])}
              </label>
            ))}
          </div>
        </fieldset>
        <TextField
          label={t("homework.review.grade")}
          name="grade"
          value={grade}
          maxLength={10}
          hint={t("homework.review.gradeHint")}
          onChange={(e) => setGrade(e.target.value)}
        />
        <TextAreaField
          label={t("homework.review.remark")}
          name="remark"
          rows={3}
          maxLength={500}
          value={remark}
          onChange={(e) => setRemark(e.target.value)}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy} data-testid="review-save">
            {busy ? t("common.saving") : t("homework.review.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function TrackerList({
  tracker,
  onReview,
}: {
  tracker: HomeworkTracker;
  onReview: (row: TrackerRow) => void;
}) {
  const { t, lang } = useI18n();
  const name = useId();
  const [filter, setFilter] = useState<Filter>("all");
  const rows = trackerFilter(tracker.rows, filter);
  const counts: Record<Filter, number> = {
    all: tracker.rows.length,
    waiting: tracker.counts.waiting,
    missing: tracker.counts.missing,
    late: tracker.counts.late,
    reviewed: tracker.counts.reviewed + tracker.counts.needsRedo,
  };
  return (
    <section className="card" aria-labelledby="hw-tracker">
      <div className="card-head">
        <div className="min-w-0">
          <h2 id="hw-tracker">{t("homework.tracker.title")}</h2>
          <p className="mt-1 text-[13px] text-ink-3" data-testid="tracker-counts">
            {countsText(t, tracker.counts)}
          </p>
        </div>
      </div>
      <div className="seg mb-3" role="radiogroup" aria-label={t("homework.tracker.filter")}>
        {FILTERS.map((f) => (
          <label key={f}>
            <input type="radio" name={name} value={f} checked={filter === f} onChange={() => setFilter(f)} />
            {`${t(FILTER_LABEL[f])} (${counts[f]})`}
          </label>
        ))}
      </div>
      {tracker.rows.length === 0 ? (
        <p className="empty">{t("homework.tracker.noStudents")}</p>
      ) : rows.length === 0 ? (
        <p className="empty">{t("homework.tracker.none")}</p>
      ) : (
        <ul className="att-list" data-testid="tracker">
          {rows.map((row) => (
            <li key={row.studentId} className="att-row hw-tracker-row" data-testid="tracker-row">
              <div className="person">
                <span className="avatar" aria-hidden="true">
                  {initials(row.fullName)}
                </span>
                <span className="min-w-0">
                  <b>{row.fullName}</b>
                  <span className="sub">
                    {[
                      tracker.homework.sections.length > 1 ? row.sectionLabel : null,
                      row.rollNo ? t("students.rollShort", { roll: row.rollNo }) : row.admissionNo,
                      row.submittedAt ? formatDateTime(row.submittedAt, localeFor(lang)) : null,
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </span>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <StatusPill status={row.status} late={row.late} />
                {row.grade ? <Pill tone="accent">{row.grade}</Pill> : null}
                {row.submissionId ? (
                  <button type="button" className="btn btn-sm" onClick={() => onReview(row)}>
                    <ClipboardCheck size={16} aria-hidden="true" />
                    {row.status === "SUBMITTED" ? t("homework.review.open") : t("homework.review.change")}
                  </button>
                ) : null}
              </div>
              {row.remark ? <p className="w-full text-[12.5px] text-ink-3">{row.remark}</p> : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** One homework for staff: details, files, and the tracker with reviews. */
export function HomeworkDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const router = useRouter();
  const locale = localeFor(lang);
  const tracker = useApiData(`homework:tracker:${id}`, () => homeworkApi.tracker(id));
  const options = useApiData("homework:options", homeworkApi.options);
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [reviewing, setReviewing] = useState<TrackerRow | null>(null);
  const data = tracker.data;
  const hw = data?.homework;

  if (tracker.error?.status === 404) {
    return (
      <section className="card flex flex-col items-start gap-3">
        <h1>{t("homework.notFound")}</h1>
        <Link href="/app/homework" className="btn">
          {t("homework.back")}
        </Link>
      </section>
    );
  }

  return (
    <>
      <div>
        <Link href="/app/homework" className="link inline-flex items-center gap-1 text-[13.5px]">
          <ArrowLeft size={16} aria-hidden="true" />
          {t("homework.back")}
        </Link>
      </div>
      {tracker.error && !data ? (
        <ErrorState error={tracker.error} onRetry={tracker.reload} />
      ) : !hw || !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          <PageHead
            eyebrow={`${hw.subjectName} · ${hw.sections.map((s) => s.label).join(", ")}`}
            title={hw.title}
            actions={
              <>
                {hw.canEdit && options.data ? (
                  <button type="button" className="btn" onClick={() => setEditing(true)}>
                    <Pencil size={18} aria-hidden="true" />
                    {t("common.edit")}
                  </button>
                ) : null}
                {hw.canDelete ? (
                  <button type="button" className="btn btn-danger" onClick={() => setDeleting(true)}>
                    <Trash2 size={18} aria-hidden="true" />
                    {t("common.delete")}
                  </button>
                ) : null}
              </>
            }
          />
          <section className="card flex flex-col gap-3" aria-label={t("homework.details")}>
            <div className="flex flex-wrap items-center gap-2">
              <Pill tone={dueTone(hw.dueOn, hw.today)}>{dueLabel(t, hw.dueOn, hw.today, locale)}</Pill>
              {hw.onlineSubmission ? (
                <Pill tone="accent">{t("homework.online")}</Pill>
              ) : (
                <Pill>{t("homework.offline")}</Pill>
              )}
              {!hw.canEdit && hw.dueOn < hw.today ? <Pill>{t("homework.closed")}</Pill> : null}
            </div>
            <dl className="kv text-[13.5px]">
              <dt>{t("homework.assignedOn")}</dt>
              <dd>{formatPlainDate(hw.assignedOn, locale)}</dd>
              <dt>{t("homework.dueOn")}</dt>
              <dd>{formatPlainDate(hw.dueOn, locale)}</dd>
              <dt>{t("homework.setBy")}</dt>
              <dd>{hw.createdByName ?? "—"}</dd>
            </dl>
            <Instructions text={hw.instructions} />
            {hw.attachments.length > 0 ? (
              <div>
                <h2 className="mb-1 text-[15px]">{t("homework.attachments")}</h2>
                <AttachmentList files={hw.attachments} testId="homework-attachments" />
              </div>
            ) : null}
          </section>
          <TrackerList tracker={data} onReview={setReviewing} />

          {editing && options.data ? (
            <HomeworkFormDialog
              open
              options={options.data}
              homework={hw}
              onClose={() => setEditing(false)}
              onSaved={(saved) => {
                setEditing(false);
                toast(t("homework.form.saved", { title: saved.title }));
                tracker.reload();
              }}
            />
          ) : null}
          {reviewing ? (
            <ReviewDialog
              homeworkId={hw.id}
              row={reviewing}
              onClose={() => setReviewing(null)}
              onSaved={(row) => {
                setReviewing(null);
                toast(t("homework.review.saved", { name: row.fullName }));
                tracker.reload();
              }}
            />
          ) : null}
          <ConfirmDialog
            open={deleting}
            title={t("homework.delete.title", { title: hw.title })}
            body={t("homework.delete.body")}
            confirmLabel={t("common.delete")}
            onClose={() => setDeleting(false)}
            onConfirm={async () => {
              await homeworkApi.remove(hw.id);
              setDeleting(false);
              toast(t("homework.deleted"));
              router.push("/app/homework");
            }}
          />
        </>
      )}
    </>
  );
}
