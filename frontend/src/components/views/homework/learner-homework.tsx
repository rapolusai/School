"use client";

import { ArrowLeft, Paperclip, Send } from "lucide-react";
import Link from "next/link";
import { useId, useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { AttachmentList, FilePicker } from "@/components/views/files/attachments";
import { api, toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPlainDate } from "@/lib/format";
import { homeworkApi } from "@/lib/homework-api";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import type { StudentHomework, StudentHomeworkDetail, StudentHomeworkRow } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { dueLabel, dueTone, Instructions, StatusPill } from "./homework-shared";

/** The most a student may write in an answer (the API's limit). */
export const ANSWER_MAX = 5000;

const GROUPS = ["todo", "done", "all"] as const;
type Group = (typeof GROUPS)[number];

const GROUP_LABEL: Record<Group, MessageKey> = {
  todo: "homework.learner.todo",
  done: "homework.learner.done",
  all: "homework.learner.all",
};

/** Still to do: nothing handed in yet, or sent back for another try. Pure, exported for tests. */
export function isToDo(row: Pick<StudentHomeworkRow, "status">): boolean {
  return row.status === "PENDING" || row.status === "NEEDS_REDO";
}

/** Homework in a group, soonest due first for work still to do and newest first otherwise. Pure. */
export function learnerGroup(items: StudentHomeworkRow[], group: Group): StudentHomeworkRow[] {
  const chosen = group === "all" ? items : items.filter((row) => (group === "todo" ? isToDo(row) : !isToDo(row)));
  return [...chosen].sort((a, b) =>
    group === "todo" ? a.dueOn.localeCompare(b.dueOn) : b.dueOn.localeCompare(a.dueOn) || a.title.localeCompare(b.title),
  );
}

/** One line per homework: title, subject, due date and how it stands. */
export function LearnerHomeworkRows({
  items,
  today,
  hrefFor,
  testId,
}: {
  items: StudentHomeworkRow[];
  today: string;
  hrefFor: (row: StudentHomeworkRow) => string;
  testId?: string;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <ul className="rowcards" data-testid={testId}>
      {items.map((row) => (
        <li key={row.id}>
          <Link href={hrefFor(row)} className="rowcard hw-row">
            <span className="min-w-0 flex-1">
              <b className="block">{row.title}</b>
              <span className="block text-[13px] text-ink-2">
                {row.subjectName}
                {row.grade ? ` · ${t("homework.gradeShort", { grade: row.grade })}` : ""}
              </span>
            </span>
            <span className="hw-row-side">
              {isToDo(row) ? (
                <Pill tone={row.overdue ? "bad" : dueTone(row.dueOn, today)}>{dueLabel(t, row.dueOn, today, locale)}</Pill>
              ) : (
                <span className="text-[12.5px] text-ink-3">{dueLabel(t, row.dueOn, today, locale)}</span>
              )}
              <span className="flex items-center gap-2">
                {row.attachments > 0 ? (
                  <span className="inline-flex items-center gap-1 text-[12.5px] text-ink-3" title={t("homework.attachments")}>
                    <Paperclip size={14} aria-hidden="true" />
                    <span className="sr-only">{t("homework.attachments")}</span>
                    {row.attachments}
                  </span>
                ) : null}
                {row.onlineSubmission || row.status !== "PENDING" ? (
                  <StatusPill status={row.status} late={row.late} />
                ) : (
                  <Pill>{t("homework.offline")}</Pill>
                )}
              </span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

/** A student's (or a child's) homework, grouped into to do and done. */
function LearnerHomeworkBody({ data, hrefFor }: { data: ApiData<StudentHomework>; hrefFor: (row: StudentHomeworkRow) => string }) {
  const { t } = useI18n();
  const name = useId();
  const [group, setGroup] = useState<Group>("todo");
  const view = data.data;
  if (data.error?.status === 404 && !view) return <p className="empty">{t("children.notLinked")}</p>;
  if (data.error && !view) return <ErrorState error={data.error} onRetry={data.reload} />;
  if (!view) return <LoadingRows rows={5} />;
  if (!view.sectionId) return <p className="empty">{t("timetable.family.noSection")}</p>;
  const rows = learnerGroup(view.items, group);
  const counts: Record<Group, number> = {
    todo: view.items.filter(isToDo).length,
    done: view.items.filter((row) => !isToDo(row)).length,
    all: view.items.length,
  };
  return (
    <section className="card" aria-label={t("homework.title")}>
      <div className="seg mb-3" role="radiogroup" aria-label={t("homework.learner.show")}>
        {GROUPS.map((g) => (
          <label key={g}>
            <input type="radio" name={name} value={g} checked={group === g} onChange={() => setGroup(g)} />
            {`${t(GROUP_LABEL[g])} (${counts[g]})`}
          </label>
        ))}
      </div>
      {view.items.length === 0 ? (
        <p className="empty" data-testid="learner-homework-empty">
          {t("homework.learner.none")}
        </p>
      ) : rows.length === 0 ? (
        <p className="empty">{group === "todo" ? t("homework.learner.allDone") : t("homework.learner.noneDone")}</p>
      ) : (
        <LearnerHomeworkRows items={rows} today={view.today} hrefFor={hrefFor} testId="learner-homework" />
      )}
    </section>
  );
}

/** /app/homework for a student: their section's homework. */
export function StudentHomeworkView() {
  const { t } = useI18n();
  const data = useApiData("homework:mine", homeworkApi.mine);
  return (
    <>
      <PageHead eyebrow={data.data?.sectionLabel ?? undefined} title={t("homework.title")} />
      <LearnerHomeworkBody data={data} hrefFor={(row) => `/app/homework/${encodeURIComponent(row.id)}`} />
    </>
  );
}

function ChildHomework({ studentId }: { studentId: string }) {
  const data = useApiData(`homework:child:${studentId}`, () => homeworkApi.child(studentId));
  return (
    <LearnerHomeworkBody
      data={data}
      hrefFor={(row) => `/app/homework/${encodeURIComponent(row.id)}?child=${encodeURIComponent(studentId)}`}
    />
  );
}

/** /app/homework for a parent: one child at a time, read only. */
export function ParentHomeworkView({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const children = useApiData("me:children", api.myChildren);
  const [chosen, setChosen] = useState(initialChildId ?? "");
  const list = children.data ?? [];
  const childId = list.some((c) => c.id === chosen) ? chosen : (list[0]?.id ?? "");
  const child = list.find((c) => c.id === childId);
  return (
    <>
      <PageHead eyebrow={child?.fullName} title={t("homework.title")} />
      {children.error && !children.data ? (
        <ErrorState error={children.error} onRetry={children.reload} />
      ) : !children.data ? (
        <LoadingRows rows={4} />
      ) : list.length === 0 ? (
        <p className="empty">{t("children.empty")}</p>
      ) : (
        <>
          {list.length > 1 ? (
            <div className="toolbar">
              <label className="field min-w-0 flex-1 sm:max-w-xs">
                <span className="field-label">{t("timetable.family.child")}</span>
                <select className="input" name="child" value={childId} onChange={(e) => setChosen(e.target.value)}>
                  {list.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.fullName}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          ) : null}
          <ChildHomework key={childId} studentId={childId} />
        </>
      )}
    </>
  );
}

/** The student's answer: text and files, sent again (keeping chosen earlier files) until reviewed. */
export function SubmissionForm({
  detail,
  onSubmitted,
}: {
  detail: StudentHomeworkDetail;
  onSubmitted: (detail: StudentHomeworkDetail) => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const previous = detail.submission;
  const [text, setText] = useState(previous?.body ?? "");
  const [keep, setKeep] = useState<string[]>(previous?.files.map((f) => f.id) ?? []);
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const room = Math.max(0, detail.maxFiles - keep.length);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    if (!text.trim() && keep.length === 0 && files.length === 0) {
      setFieldErrors({ text: t("homework.submit.v.empty") });
      setError(null);
      event.currentTarget.querySelector<HTMLTextAreaElement>("textarea")?.focus();
      return;
    }
    if (text.length > ANSWER_MAX) {
      setFieldErrors({ text: t("validation.tooLong") });
      return;
    }
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      const next = await homeworkApi.submit(detail.id, { text, files, keepFileIds: keep });
      setFiles([]);
      toast(next.submission?.late ? t("homework.submit.sentLate") : t("homework.submit.sent"));
      onSubmitted(next);
    } catch (caught) {
      const failure = toApiError(caught);
      if (failure.errors) setFieldErrors(failure.errors);
      setError(errorMessage(failure, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form method="post" onSubmit={submit} noValidate className="flex flex-col gap-3.5" aria-labelledby="hw-answer">
      <h2 id="hw-answer">{previous ? t("homework.submit.again") : t("homework.submit.title")}</h2>
      {detail.dueOn < detail.today ? (
        <p className="alert" style={{ background: "var(--warn-soft)", color: "var(--warn)" }}>
          {t("homework.submit.lateWarning")}
        </p>
      ) : null}
      <FormAlert message={error} />
      <div className="field">
        <label htmlFor="hw-answer-text" className="field-label">
          {t("homework.submit.text")}
        </label>
        <textarea
          id="hw-answer-text"
          name="text"
          className="input"
          rows={6}
          maxLength={ANSWER_MAX}
          value={text}
          aria-invalid={fieldErrors.text ? true : undefined}
          aria-describedby="hw-answer-hint"
          onChange={(e) => {
            setText(e.target.value);
            setFieldErrors((prev) => ({ ...prev, text: "" }));
          }}
        />
        <p id="hw-answer-hint" className="field-hint">
          {t("homework.submit.textHint")}
        </p>
        {fieldErrors.text ? <p className="field-error">{fieldErrors.text}</p> : null}
      </div>
      {previous && previous.files.length > 0 ? (
        <fieldset className="fieldset">
          <legend>{t("homework.submit.keep")}</legend>
          {previous.files.map((file) => (
            <label key={file.id} className="check">
              <input
                type="checkbox"
                name="keepFileIds"
                value={file.id}
                checked={keep.includes(file.id)}
                onChange={(e) =>
                  setKeep((prev) => (e.target.checked ? [...prev, file.id] : prev.filter((id) => id !== file.id)))
                }
              />
              <span className="min-w-0 break-words">{file.name}</span>
            </label>
          ))}
        </fieldset>
      ) : null}
      <FilePicker
        label={t("homework.submit.files")}
        files={files}
        onChange={(next) => {
          setFiles(next);
          setFieldErrors((prev) => ({ ...prev, files: "", text: "" }));
        }}
        max={room}
        maxBytes={detail.maxFileBytes}
        error={fieldErrors.files || undefined}
        name="files"
      />
      <div className="flex justify-end">
        <button type="submit" className="btn btn-primary" disabled={busy} data-testid="submission-send">
          <Send size={18} aria-hidden="true" />
          {busy ? t("common.saving") : previous ? t("homework.submit.sendAgain") : t("homework.submit.send")}
        </button>
      </div>
    </form>
  );
}

/** What was handed in, and the teacher's review. */
function SubmissionSummary({ detail }: { detail: StudentHomeworkDetail }) {
  const { t, lang } = useI18n();
  const sub = detail.submission;
  if (!sub) return null;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="hw-submission" data-testid="submission">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="hw-submission">{t("homework.submission.title")}</h2>
        <StatusPill status={sub.status} late={sub.late} />
      </div>
      <p className="text-[13px] text-ink-3">
        {[
          t("homework.submittedAt", { time: formatDateTime(sub.submittedAt, localeFor(lang)) }),
          sub.attempts > 1 ? t("homework.attempts", { count: sub.attempts }) : null,
        ]
          .filter(Boolean)
          .join(" · ")}
      </p>
      {sub.body ? <p className="msg-body">{sub.body}</p> : null}
      <AttachmentList files={sub.files} testId="submission-files" />
      {sub.status !== "SUBMITTED" ? (
        <div className="hw-review" data-testid="submission-review">
          <p className="font-semibold">
            {sub.status === "REVIEWED" ? t("homework.submission.reviewed") : t("homework.submission.redo")}
            {sub.reviewedByName ? ` · ${sub.reviewedByName}` : ""}
          </p>
          {sub.grade ? <p>{t("homework.gradeShort", { grade: sub.grade })}</p> : null}
          {sub.remark ? <p className="msg-body">{sub.remark}</p> : null}
        </div>
      ) : (
        <p className="text-[13px] text-ink-2">{t("homework.submission.waiting")}</p>
      )}
    </section>
  );
}

/**
 * One homework for a student (who can hand it in) or a parent (read only): instructions and files,
 * what was handed in, the teacher's review, and the answer form while it is open.
 */
export function LearnerHomeworkDetail({
  data,
  backHref,
  self,
}: {
  data: ApiData<StudentHomeworkDetail>;
  backHref: string;
  self: boolean;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [fresh, setFresh] = useState<StudentHomeworkDetail | null>(null);
  const [version, setVersion] = useState(0);
  const detail = fresh ?? data.data;

  const back = (
    <div>
      <Link href={backHref} className="link inline-flex items-center gap-1 text-[13.5px]">
        <ArrowLeft size={16} aria-hidden="true" />
        {t("homework.back")}
      </Link>
    </div>
  );

  if (data.error?.status === 404 && !detail) {
    return (
      <section className="card flex flex-col items-start gap-3">
        <h1>{t("homework.notFound")}</h1>
        <Link href={backHref} className="btn">
          {t("homework.back")}
        </Link>
      </section>
    );
  }
  if (data.error && !detail) {
    return (
      <>
        {back}
        <ErrorState error={data.error} onRetry={data.reload} />
      </>
    );
  }
  if (!detail) {
    return (
      <>
        {back}
        <LoadingRows rows={6} />
      </>
    );
  }

  const sub = detail.submission;
  const open = isToDo({ status: sub?.status ?? "PENDING" });
  return (
    <>
      {back}
      <PageHead eyebrow={self ? detail.subjectName : `${detail.studentName} · ${detail.subjectName}`} title={detail.title} />
      <section className="card flex flex-col gap-3" aria-label={t("homework.details")}>
        <div className="flex flex-wrap items-center gap-2">
          {open ? (
            <Pill tone={detail.overdue ? "bad" : dueTone(detail.dueOn, detail.today)}>
              {dueLabel(t, detail.dueOn, detail.today, locale)}
            </Pill>
          ) : null}
          {detail.onlineSubmission ? (
            <Pill tone="accent">{t("homework.online")}</Pill>
          ) : (
            <Pill>{t("homework.offline")}</Pill>
          )}
          {!sub && detail.onlineSubmission ? <StatusPill status="PENDING" /> : null}
        </div>
        <dl className="kv text-[13.5px]">
          <dt>{t("homework.assignedOn")}</dt>
          <dd>{formatPlainDate(detail.assignedOn, locale)}</dd>
          <dt>{t("homework.dueOn")}</dt>
          <dd>{formatPlainDate(detail.dueOn, locale)}</dd>
          <dt>{t("homework.setBy")}</dt>
          <dd>{detail.createdByName ?? "—"}</dd>
        </dl>
        <Instructions text={detail.instructions} />
        {detail.attachments.length > 0 ? (
          <div>
            <h2 className="mb-1 text-[15px]">{t("homework.attachments")}</h2>
            <AttachmentList files={detail.attachments} testId="homework-attachments" />
          </div>
        ) : null}
        {!detail.onlineSubmission ? <p className="text-[13.5px] text-ink-2">{t("homework.learner.atSchool")}</p> : null}
      </section>

      <SubmissionSummary detail={detail} />

      {detail.canSubmit ? (
        <section className="card">
          <SubmissionForm
            key={version}
            detail={detail}
            onSubmitted={(next) => {
              setFresh(next);
              setVersion((v) => v + 1);
            }}
          />
        </section>
      ) : !self && detail.onlineSubmission && !sub ? (
        <p className="text-[13.5px] text-ink-2" data-testid="parent-not-submitted">
          {detail.overdue ? t("homework.learner.parentOverdue") : t("homework.learner.parentPending")}
        </p>
      ) : null}
    </>
  );
}

/** /app/homework/[id] for a student. */
export function StudentHomeworkDetailView({ id }: { id: string }) {
  const data = useApiData(`homework:mine:${id}`, () => homeworkApi.myDetail(id));
  return <LearnerHomeworkDetail data={data} backHref="/app/homework" self />;
}

/** /app/homework/[id]?child=… for a parent: read only. */
export function ChildHomeworkDetailView({ studentId, id }: { studentId: string; id: string }) {
  const data = useApiData(`homework:child:${studentId}:${id}`, () => homeworkApi.childDetail(studentId, id));
  return (
    <LearnerHomeworkDetail
      data={data}
      backHref={`/app/homework?child=${encodeURIComponent(studentId)}`}
      self={false}
    />
  );
}
