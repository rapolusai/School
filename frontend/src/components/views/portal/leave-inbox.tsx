"use client";

import { ArrowLeft, CalendarCheck } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatDateTime, initials } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { portalApi } from "@/lib/portal-api";
import type { ChildLeave } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { leaveDates, LeaveStatusPill } from "./family-shared";

export type Decision = "approve" | "reject";

/** The checks the API makes on a decision: a rejection needs a reason; at most 500 characters. Pure. */
export function validateDecision(decision: Decision, comment: string): Problems {
  const text = comment.trim();
  if (decision === "reject" && !text) return { comment: "portal.inbox.v.comment" };
  if (text.length > 500) return { comment: "validation.tooLong" };
  return {};
}

/** Attendance header: "Leave requests" with how many wait for the caller. */
export function LeaveRequestsLink() {
  const { t } = useI18n();
  const inbox = useApiData("portal:inbox", portalApi.inbox);
  const waiting = inbox.data?.pending.filter((r) => r.canDecide).length ?? 0;
  return (
    <Link href="/app/attendance/leave-requests" className="btn" data-testid="leave-requests-link">
      <CalendarCheck size={18} aria-hidden="true" />
      {t("portal.inbox.title")}
      {waiting > 0 ? <Pill tone="accent">{waiting}</Pill> : null}
    </Link>
  );
}

/**
 * /app/attendance/leave-requests: child leave requests for the caller's sections (class teachers)
 * or the whole school (attendance.manage), waiting ones first, with approve and reject.
 */
export function LeaveInboxView() {
  const { t } = useI18n();
  const { toast } = useToast();
  const inbox = useApiData("portal:inbox", portalApi.inbox);
  const [deciding, setDeciding] = useState<{ request: ChildLeave; decision: Decision } | null>(null);
  const data = inbox.data;
  return (
    <>
      <Link href="/app/attendance" className="link inline-flex items-center gap-1 text-[13.5px]">
        <ArrowLeft size={16} aria-hidden="true" />
        {t("nav.attendance")}
      </Link>
      <PageHead
        eyebrow={data ? (data.wholeSchool ? t("portal.inbox.wholeSchool") : t("portal.inbox.ownSections")) : undefined}
        title={t("portal.inbox.title")}
      />
      {inbox.error && !data ? (
        <section className="card">
          <ErrorState error={inbox.error} onRetry={inbox.reload} />
        </section>
      ) : !data ? (
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      ) : (
        <>
          <section className="card flex flex-col gap-3" aria-labelledby="inbox-pending">
            <div className="card-head" style={{ marginBottom: 0 }}>
              <h2 id="inbox-pending">{t("portal.inbox.pending")}</h2>
              {data.pending.length > 0 ? <Pill tone="warn">{plural(t, "portal.leave.waiting", data.pending.length)}</Pill> : null}
            </div>
            {data.pending.length === 0 ? (
              <p className="empty" data-testid="inbox-empty">
                {t("portal.inbox.nonePending")}
              </p>
            ) : (
              <ul className="list" data-testid="inbox-pending">
                {data.pending.map((request) => (
                  <RequestRow key={request.id} request={request} onDecide={(decision) => setDeciding({ request, decision })} />
                ))}
              </ul>
            )}
          </section>
          <section className="card flex flex-col gap-3" aria-labelledby="inbox-recent">
            <h2 id="inbox-recent">{t("portal.inbox.recent")}</h2>
            {data.recent.length === 0 ? (
              <p className="empty">{t("portal.inbox.noneRecent")}</p>
            ) : (
              <ul className="list" data-testid="inbox-recent">
                {data.recent.map((request) => (
                  <RequestRow key={request.id} request={request} />
                ))}
              </ul>
            )}
          </section>
        </>
      )}
      <DecisionDialog
        target={deciding}
        onClose={() => setDeciding(null)}
        onDone={(request) => {
          setDeciding(null);
          inbox.reload();
          toast(
            t(request.status === "APPROVED" ? "portal.inbox.approved" : "portal.inbox.rejected", {
              name: request.studentName ?? "",
            }),
          );
        }}
      />
    </>
  );
}

function RequestRow({ request, onDecide }: { request: ChildLeave; onDecide?: (decision: Decision) => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const name = request.studentName ?? request.admissionNo ?? "";
  return (
    <li className="li flex-col items-stretch gap-2" data-testid="inbox-row" aria-label={name}>
      <div className="person">
        <span className="avatar" aria-hidden="true">
          {initials(name)}
        </span>
        <span className="min-w-0 flex-1">
          <b>{name}</b>
          <span className="sub">
            {[request.sectionLabel, request.rollNo ? t("students.rollShort", { roll: request.rollNo }) : null]
              .filter(Boolean)
              .join(" · ")}
          </span>
        </span>
        <LeaveStatusPill status={request.status} />
      </div>
      <p className="font-semibold text-ink">
        {leaveDates(t, request, locale)}
        <span className="font-normal text-ink-3">
          {" · "}
          {request.halfDay ? t("portal.leave.halfDay") : plural(t, "portal.leave.schoolDays", request.schoolDays)}
        </span>
      </p>
      <p className="whitespace-pre-line break-words text-[14px]">{request.reason}</p>
      <p className="text-[12.5px] text-ink-3">
        {request.requestedByName
          ? t("portal.leave.askedBy", { name: request.requestedByName, time: formatDateTime(request.createdAt, locale) })
          : formatDateTime(request.createdAt, locale)}
        {request.decidedByName && request.decidedAt
          ? ` · ${t(request.status === "REJECTED" ? "portal.leave.rejectedBy" : "portal.leave.approvedBy", {
              name: request.decidedByName,
            })}${request.decisionComment ? `: “${request.decisionComment}”` : ""}`
          : ""}
        {request.cancelledByName && request.status === "CANCELLED"
          ? ` · ${t("portal.leave.cancelledBy", { name: request.cancelledByName })}`
          : ""}
      </p>
      {onDecide && request.canDecide ? (
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn btn-primary btn-sm" onClick={() => onDecide("approve")}>
            {t("portal.inbox.approve")}
          </button>
          <button type="button" className="btn btn-danger btn-sm" onClick={() => onDecide("reject")}>
            {t("portal.inbox.reject")}
          </button>
        </div>
      ) : null}
    </li>
  );
}

function DecisionDialog({
  target,
  onClose,
  onDone,
}: {
  target: { request: ChildLeave; decision: Decision } | null;
  onClose: () => void;
  onDone: (request: ChildLeave) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const form = useForm<{ comment: string }>({ comment: "" });
  const decision = target?.decision ?? "approve";

  const close = () => {
    form.reset({ comment: "" });
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!target) return;
    if (!form.check(validateDecision(decision, form.values.comment), t, event.currentTarget)) return;
    const comment = form.values.comment.trim();
    let done: ChildLeave | undefined;
    const ok = await form.submit(t, async () => {
      done =
        decision === "approve"
          ? await portalApi.approve(target.request.id, comment)
          : await portalApi.reject(target.request.id, comment);
    });
    if (ok && done) {
      form.reset({ comment: "" });
      onDone(done);
    }
  };

  return (
    <Dialog
      open={target !== null}
      onClose={close}
      title={decision === "approve" ? t("portal.inbox.approve.title") : t("portal.inbox.reject.title")}
      description={
        target ? `${target.request.studentName ?? ""} · ${leaveDates(t, target.request, locale)}`.replace(/^ · /, "") : undefined
      }
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="decision-form">
        <FormAlert message={form.formError} />
        {decision === "approve" ? <p className="text-sm text-ink-2">{t("portal.inbox.approve.hint")}</p> : null}
        <TextAreaField
          label={decision === "reject" ? t("portal.inbox.comment") : t("leave.field.commentOptional")}
          name="comment"
          value={form.values.comment}
          onChange={(e) => form.set("comment", e.target.value)}
          error={form.errors.comment}
          maxLength={500}
          rows={3}
          required={decision === "reject"}
          data-autofocus
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.close")}
          </button>
          <button type="submit" className={decision === "approve" ? "btn btn-primary" : "btn btn-danger"} disabled={form.submitting}>
            {form.submitting ? t("common.working") : decision === "approve" ? t("portal.inbox.approve") : t("portal.inbox.reject")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
