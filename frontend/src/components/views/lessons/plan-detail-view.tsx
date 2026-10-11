"use client";

import { ArrowLeft, Check, Pencil, Send, Trash2, Undo2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { LESSONS_LIMITS, type PlanDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";
import { LessonsNav, PlanStatusPill, useLessonsOptions, weekLabel } from "./lessons-shared";
import { PlanDialog } from "./plan-editor";

/** /app/lessons/plans/[planId]: one plan with what its teacher and approver can do next. */
export function PlanDetailView({ planId }: { planId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const router = useRouter();
  const plan = useApiData(`lessons:plan:${planId}`, () => lessonsApi.plan(planId));
  const [current, setCurrent] = useState<PlanDetail | null>(null);
  const data = current && current.id === planId ? current : plan.data;
  const options = useLessonsOptions(data?.yearId ?? "");
  const [editing, setEditing] = useState(false);
  const [review, setReview] = useState<"approve" | "return" | null>(null);
  const [taught, setTaught] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = async (action: () => Promise<PlanDetail>, message: MessageKey) => {
    setBusy(true);
    setError(null);
    try {
      setCurrent(await action());
      toast(t(message));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  if (plan.error && !data) {
    return (
      <>
        <PageHead title={t("lessons.plans.title")} />
        <section className="card">
          <ErrorState error={plan.error} onRetry={plan.reload} />
        </section>
      </>
    );
  }
  if (!data) {
    return (
      <>
        <PageHead title={t("lessons.plans.title")} />
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }

  const facts: [MessageKey, string | null][] = [
    ["lessons.section", data.sectionLabel],
    ["lessons.subject", data.subjectName],
    ["lessons.unit", data.unitTitle],
    ["lessons.topic", data.topicTitle],
    ["lessons.plan.week", weekLabel(t, data.weekStart, locale)],
    ["lessons.plan.day", data.planDate ? formatPlainDate(data.planDate, locale) : null],
    ["lessons.plan.periods", String(data.periods)],
    ["lessons.plan.teacher", data.teacherName],
  ];
  const texts: [MessageKey, string | null][] = [
    ["lessons.plan.objectives", data.objectives],
    ["lessons.plan.method", data.teachingMethod],
    ["lessons.plan.resources", data.resources],
    ["lessons.plan.homework", data.homeworkIdea],
    ["lessons.plan.assessment", data.assessmentNote],
  ];

  return (
    <>
      <PageHead
        eyebrow={[data.sectionLabel, data.subjectName].join(" · ")}
        title={data.title}
        actions={
          <Link href="/app/lessons" className="btn">
            <ArrowLeft size={18} aria-hidden="true" />
            {t("lessons.plans.back")}
          </Link>
        }
      />
      <LessonsNav />
      <section className="card flex flex-col gap-4" data-testid="plan-detail">
        <div className="flex flex-wrap items-center gap-2">
          <PlanStatusPill status={data.status} />
          <span className="text-[13px] text-ink-3" data-testid="plan-status-line">
            {data.status === "TAUGHT" && data.taughtOn
              ? t("lessons.plan.taughtOn", { date: formatPlainDate(data.taughtOn, locale) })
              : data.status === "SUBMITTED" && data.submittedAt
                ? t("lessons.plan.submittedOn", { date: formatDateTime(data.submittedAt, locale) })
                : data.reviewedAt && data.reviewedByName
                  ? t("lessons.plan.reviewedBy", { name: data.reviewedByName, date: formatDateTime(data.reviewedAt, locale) })
                  : t("lessons.plan.updated", { date: formatDateTime(data.updatedAt, locale) })}
          </span>
        </div>
        {data.reviewComment ? (
          <div className={`alert ${data.status === "RETURNED" ? "alert-warn" : "alert-info"}`} data-testid="plan-comment">
            <span>
              <b className="block">{t("lessons.plan.comment")}</b>
              {data.reviewComment}
            </span>
          </div>
        ) : null}
        <FormAlert message={error} />
        <div className="flex flex-wrap gap-2 no-print">
          {data.canSubmit ? (
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy}
              onClick={() => void run(() => lessonsApi.submitPlan(data.id), "lessons.plan.submitted")}
            >
              <Send size={16} aria-hidden="true" />
              {t("lessons.plan.submit")}
            </button>
          ) : null}
          {data.canReview ? (
            <>
              <button type="button" className="btn btn-primary" disabled={busy} onClick={() => setReview("approve")}>
                <Check size={16} aria-hidden="true" />
                {t("lessons.plan.approve")}
              </button>
              <button type="button" className="btn" disabled={busy} onClick={() => setReview("return")}>
                <Undo2 size={16} aria-hidden="true" />
                {t("lessons.plan.return")}
              </button>
            </>
          ) : null}
          {data.canMarkTaught ? (
            <button type="button" className={`btn${data.status === "APPROVED" ? " btn-primary" : ""}`} disabled={busy} onClick={() => setTaught(true)}>
              <Check size={16} aria-hidden="true" />
              {data.status === "TAUGHT" ? t("lessons.plan.changeTaught") : t("lessons.plan.markTaught")}
            </button>
          ) : null}
          {data.canMarkTaught && data.status === "TAUGHT" ? (
            <button
              type="button"
              className="btn"
              disabled={busy}
              onClick={() => void run(() => lessonsApi.markUntaught(data.id), "lessons.plan.untaughtDone")}
            >
              <Undo2 size={16} aria-hidden="true" />
              {t("lessons.plan.untaught")}
            </button>
          ) : null}
          {data.canEdit ? (
            <>
              <button type="button" className="btn" disabled={busy || !options.data} onClick={() => setEditing(true)}>
                <Pencil size={16} aria-hidden="true" />
                {t("common.edit")}
              </button>
              <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => setDeleting(true)}>
                <Trash2 size={16} aria-hidden="true" />
                {t("common.delete")}
              </button>
            </>
          ) : null}
        </div>
        <dl className="kv text-[14px]">
          {facts
            .filter(([, value]) => value)
            .map(([label, value]) => (
              <div key={label} className="contents">
                <dt>{t(label)}</dt>
                <dd>{value}</dd>
              </div>
            ))}
        </dl>
        {texts
          .filter(([, value]) => value)
          .map(([label, value]) => (
            <div key={label}>
              <h2 className="text-base">{t(label)}</h2>
              <p className="mt-1 whitespace-pre-line text-[14px] text-ink-2">{value}</p>
            </div>
          ))}
        {data.resourceLinks.length > 0 ? (
          <div>
            <h2 className="text-base">{t("lessons.plan.links")}</h2>
            <ul className="mt-1 flex flex-col gap-1">
              {data.resourceLinks.map((link) => (
                <li key={link} className="min-w-0 truncate">
                  <a className="link" href={link} target="_blank" rel="noopener noreferrer">
                    {link}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </section>

      {editing && options.data ? (
        <PlanDialog
          open
          plan={data}
          options={options.data}
          week={data.weekStart}
          onClose={() => setEditing(false)}
          onSaved={(saved) => {
            setEditing(false);
            setCurrent(saved);
            toast(t("lessons.plan.saved"));
          }}
        />
      ) : null}
      {review ? (
        <ReviewDialog
          mode={review}
          plan={data}
          onClose={() => setReview(null)}
          onDone={(saved) => {
            setReview(null);
            setCurrent(saved);
            toast(review === "approve" ? t("lessons.plan.approved") : t("lessons.plan.returned"));
          }}
        />
      ) : null}
      {taught ? (
        <TaughtDialog
          plan={data}
          onClose={() => setTaught(false)}
          onDone={(saved) => {
            setTaught(false);
            setCurrent(saved);
            toast(t("lessons.plan.taughtDone"));
          }}
        />
      ) : null}
      <ConfirmDialog
        open={deleting}
        title={t("lessons.plan.deleteTitle", { title: data.title })}
        body={t("lessons.plan.deleteBody")}
        confirmLabel={t("common.delete")}
        onClose={() => setDeleting(false)}
        onConfirm={async () => {
          await lessonsApi.deletePlan(data.id);
          setDeleting(false);
          toast(t("common.deleted"));
          router.push("/app/lessons");
        }}
      />
    </>
  );
}

/** Approving (optional comment) or returning (comment required) a submitted plan. */
function ReviewDialog({
  mode,
  plan,
  onClose,
  onDone,
}: {
  mode: "approve" | "return";
  plan: PlanDetail;
  onClose: () => void;
  onDone: (plan: PlanDetail) => void;
}) {
  const { t } = useI18n();
  const form = useForm({ comment: "" });
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const comment = values.comment.trim();
    const problems =
      mode === "return" && !comment
        ? ({ comment: "lessons.v.returnComment" } as const)
        : comment.length > LESSONS_LIMITS.reviewComment
          ? ({ comment: "lessons.v.long1000" } as const)
          : {};
    if (!form.check(problems, t, event.currentTarget)) return;
    let saved: PlanDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved =
        mode === "approve"
          ? await lessonsApi.approvePlan(plan.id, comment || null)
          : await lessonsApi.returnPlan(plan.id, comment);
    });
    if (ok && saved) onDone(saved);
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={mode === "approve" ? t("lessons.plan.approveTitle") : t("lessons.plan.returnTitle")}
      description={plan.title}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextAreaField
          label={mode === "approve" ? t("lessons.plan.commentOptional") : t("lessons.plan.commentRequired")}
          name="comment"
          rows={4}
          maxLength={LESSONS_LIMITS.reviewComment}
          value={values.comment}
          onChange={(e) => set("comment", e.target.value)}
          error={errors.comment}
          required={mode === "return"}
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : mode === "approve" ? t("lessons.plan.approve") : t("lessons.plan.return")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** Marking an approved plan as taught on a day (today by default, never in the future). */
function TaughtDialog({ plan, onClose, onDone }: { plan: PlanDetail; onClose: () => void; onDone: (plan: PlanDetail) => void }) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const form = useForm({ taughtOn: plan.taughtOn ?? plan.planDate ?? today });
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems = !isPlainDate(values.taughtOn)
      ? ({ taughtOn: "lessons.v.date" } as const)
      : values.taughtOn > today
        ? ({ taughtOn: "lessons.v.taughtFuture" } as const)
        : {};
    if (!form.check(problems, t, event.currentTarget)) return;
    let saved: PlanDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = await lessonsApi.markTaught(plan.id, values.taughtOn);
    });
    if (ok && saved) onDone(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("lessons.plan.markTaught")} description={plan.title} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("lessons.plan.taughtDay")}
          name="taughtOn"
          type="date"
          max={today}
          value={values.taughtOn}
          onChange={(e) => set("taughtOn", e.target.value)}
          error={errors.taughtOn}
          required
          data-autofocus
        />
        <p className="text-[13px] text-ink-3">{plural(t, "lessons.plan.periodsCount", plan.periods)}</p>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
