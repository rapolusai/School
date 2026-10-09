"use client";

import { ArrowLeft, Ban, Check, Pencil, Send, Trash2, Undo2, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { noticesApi } from "@/lib/communication-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, translateOr, useI18n, type Translate } from "@/lib/i18n";
import type { CircularDetail, CircularDelivery, EstimateRequest } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import {
  categoryLabel,
  categoryTone,
  channelLabel,
  MAX_NOTE,
  MAX_REASON,
  statusLabel,
  statusTone,
} from "./communication-labels";
import { EstimatePanel } from "./estimate-panel";
import { NoteDialog } from "./note-dialog";

type Step = "submit" | "approve" | "reject" | "cancel" | "withdraw" | "delete";

/** One sentence on where the circular stands. Exported for tests. */
export function standing(c: CircularDetail, t: Translate, locale: string): string {
  const at = (iso: string | null) => formatDateTime(iso, locale);
  switch (c.status) {
    case "DRAFT":
      return c.reviewOutcome === "REJECTED" ? t("notices.standing.sentBack") : t("notices.standing.draft");
    case "PENDING_APPROVAL":
      return t("notices.standing.pending", { time: at(c.submittedAt) });
    case "SCHEDULED":
      return t("notices.standing.scheduled", { time: at(c.scheduledAt) });
    case "SENT":
      return c.sentByName
        ? t("notices.standing.sentBy", { time: at(c.sentAt), name: c.sentByName })
        : t("notices.standing.sent", { time: at(c.sentAt) });
    case "WITHDRAWN":
      return t("notices.standing.withdrawn", { time: at(c.withdrawnAt), name: c.withdrawnByName ?? "—" });
  }
}

function DeliveryCard({ delivery }: { delivery: CircularDelivery }) {
  const { t } = useI18n();
  const percent = delivery.readPercent === null ? null : Math.round(delivery.readPercent);
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="delivery-heading" data-testid="delivery">
      <h2 id="delivery-heading">{t("notices.delivery.title")}</h2>
      <div className="flex flex-wrap items-end gap-x-6 gap-y-2">
        <div>
          <span className="kpi-value">{percent === null ? "—" : `${percent}%`}</span>
          <p className="text-[12.5px] font-semibold text-ink-3">{t("notices.delivery.readShare")}</p>
        </div>
        <p className="text-[13.5px] text-ink-2" data-testid="delivery-read">
          {t("notices.readOf", { read: delivery.read, total: delivery.inApp })}
        </p>
      </div>
      <span className="meter" aria-hidden="true">
        <span style={{ width: `${percent ?? 0}%` }} />
      </span>
      <dl className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        {delivery.kinds.map((k) => (
          <div key={k.kind} className="rounded-lg bg-surface-2 p-3">
            <dt className="text-[13px] font-medium text-ink-2">{t(`notices.kind.${k.kind}`)}</dt>
            <dd className="m-0 text-[13.5px]">
              <b className="num">{k.recipients}</b> · {t("notices.delivery.read", { count: k.read })}
            </dd>
          </div>
        ))}
      </dl>
      <p className="text-[13px] text-ink-3">
        {t("notices.delivery.reached", {
          parents: delivery.parents,
          students: delivery.students,
          staff: delivery.staff,
        })}
      </p>
      {delivery.messages.length > 0 ? (
        <div className="table-wrap">
          <table className="table" data-testid="delivery-messages">
            <thead>
              <tr>
                <th scope="col">{t("notices.estimate.channel")}</th>
                <th scope="col" className="r">
                  {t("notices.estimate.messages")}
                </th>
                <th scope="col">{t("notices.col.status")}</th>
              </tr>
            </thead>
            <tbody>
              {delivery.messages.map((m) => (
                <tr key={m.channel}>
                  <td>{channelLabel(t, m.channel)}</td>
                  <td className="r num">{m.total}</td>
                  <td>
                    <span className="flex flex-wrap gap-1.5">
                      {Object.entries(m.byStatus)
                        .filter(([, n]) => (n ?? 0) > 0)
                        .map(([status, n]) => (
                          <Pill key={status} tone={status === "FAILED" ? "bad" : status === "QUEUED" ? "warn" : "neutral"}>
                            {`${translateOr(t, `messages.status.${status}`, status)} ${n}`}
                          </Pill>
                        ))}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {delivery.messages.length > 0 ? (
        <p className="text-[12.5px] text-ink-3">
          {t("notices.delivery.simulated")}{" "}
          <Link href="/app/messages" className="link">
            {t("notices.delivery.log")}
          </Link>
        </p>
      ) : null}
    </section>
  );
}

/** One circular: its text and audience, where it stands, the next steps, and delivery and read counts once sent. */
export function NoticeDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const router = useRouter();
  const { toast } = useToast();
  const [current, setCurrent] = useState<CircularDetail | null>(null);
  const [step, setStep] = useState<Step | null>(null);
  const detail = useApiData(`notices:detail:${id}`, () => noticesApi.get(id));
  const c = current?.id === id ? current : detail.data?.id === id ? detail.data : undefined;

  const unsent = c && (c.status === "DRAFT" || c.status === "PENDING_APPROVAL" || c.status === "SCHEDULED");
  const estimateBody: EstimateRequest | null =
    c && unsent
      ? {
          title: c.title,
          body: c.body,
          audience: {
            wholeSchool: c.audience.wholeSchool,
            classIds: c.audience.classes.map((x) => x.id),
            sectionIds: c.audience.sections.map((x) => x.id),
            roles: c.audience.roles.map((x) => x.code),
          },
          channels: c.channels,
        }
      : null;
  const estimate = useApiData(estimateBody && c ? `notices:estimate:${c.id}:${c.updatedAt}` : null, () =>
    noticesApi.estimate(estimateBody as EstimateRequest),
  );

  if (detail.error && !c) {
    return (
      <>
        <PageHead title={t("notices.detail.title")} />
        <section className="card">
          <ErrorState error={detail.error} onRetry={detail.reload} />
        </section>
      </>
    );
  }
  if (!c) {
    return (
      <>
        <PageHead title={t("notices.detail.title")} />
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }

  const done = (next: CircularDetail, message: string) => {
    setCurrent(next);
    setStep(null);
    toast(message);
  };

  const submitTitle = c.needsApproval
    ? t("notices.confirm.submitTitle")
    : c.scheduledAt
      ? t("notices.confirm.scheduleTitle")
      : t("notices.confirm.sendTitle");
  const submitLabel = c.needsApproval
    ? t("notices.submitForApproval")
    : c.scheduledAt
      ? t("notices.schedule")
      : t("notices.sendNow");
  const submitBody = c.needsApproval
    ? t("notices.confirm.submitBody")
    : c.scheduledAt
      ? t("notices.confirm.scheduleBody", { time: formatDateTime(c.scheduledAt, locale) })
      : t("notices.confirm.sendBody");
  const approveLabel = c.scheduledAt ? t("notices.approveSchedule") : t("notices.approveSend");
  const actions = c.actions;

  return (
    <>
      <PageHead
        eyebrow={[t("notices.detail.eyebrow"), categoryLabel(t, c.category)].join(" · ")}
        title={c.title}
        actions={
          <Link href="/app/notices" className="btn">
            <ArrowLeft size={18} aria-hidden="true" />
            {t("notices.backToList")}
          </Link>
        }
      />

      <section className="card flex flex-col gap-3" data-testid="notice-status">
        <div className="flex flex-wrap items-center gap-2">
          <Pill tone={statusTone(c.status)} dot>
            {statusLabel(t, c.status)}
          </Pill>
          <span className="text-[13.5px] text-ink-2">{standing(c, t, locale)}</span>
        </div>
        {c.status === "DRAFT" && c.reviewOutcome === "REJECTED" && c.reviewNote ? (
          <div className="alert alert-bad" data-testid="review-note">
            <span>
              {t("notices.sentBackBy", { name: c.reviewedByName ?? "—" })} <q>{c.reviewNote}</q>
            </span>
          </div>
        ) : null}
        {c.status === "WITHDRAWN" && c.withdrawReason ? (
          <p className="text-[13.5px] text-ink-2">
            {t("notices.withdrawnReason")} <q>{c.withdrawReason}</q>
          </p>
        ) : null}
        {c.reviewOutcome === "APPROVED" && c.reviewedByName ? (
          <p className="text-[13px] text-ink-3">
            {t("notices.approvedBy", { name: c.reviewedByName, time: formatDateTime(c.reviewedAt, locale) })}
            {c.reviewNote ? (
              <>
                {" "}
                <q>{c.reviewNote}</q>
              </>
            ) : null}
          </p>
        ) : null}
        <div className="flex flex-wrap gap-2" data-testid="notice-actions">
          {actions.approve ? (
            <button type="button" className="btn btn-primary" onClick={() => setStep("approve")}>
              <Check size={18} aria-hidden="true" />
              {approveLabel}
            </button>
          ) : null}
          {actions.reject ? (
            <button type="button" className="btn" onClick={() => setStep("reject")}>
              <X size={18} aria-hidden="true" />
              {t("notices.reject")}
            </button>
          ) : null}
          {actions.submit ? (
            <button type="button" className="btn btn-primary" onClick={() => setStep("submit")}>
              <Send size={18} aria-hidden="true" />
              {submitLabel}
            </button>
          ) : null}
          {actions.edit ? (
            <Link href={`/app/notices/${c.id}/edit`} className="btn">
              <Pencil size={18} aria-hidden="true" />
              {t("common.edit")}
            </Link>
          ) : null}
          {actions.cancel ? (
            <button type="button" className="btn" onClick={() => setStep("cancel")}>
              <Undo2 size={18} aria-hidden="true" />
              {t("notices.cancel")}
            </button>
          ) : null}
          {actions.withdraw ? (
            <button type="button" className="btn" onClick={() => setStep("withdraw")}>
              <Ban size={18} aria-hidden="true" />
              {t("notices.withdraw")}
            </button>
          ) : null}
          {actions.delete ? (
            <button type="button" className="btn" onClick={() => setStep("delete")}>
              <Trash2 size={18} aria-hidden="true" />
              {t("common.delete")}
            </button>
          ) : null}
        </div>
      </section>

      <div className="notice-detail">
        <section className="card flex min-w-0 flex-col gap-3" aria-labelledby="notice-text-heading">
          <h2 id="notice-text-heading" className="sr-only">
            {t("notices.field.body")}
          </h2>
          <p className="msg-body" data-testid="notice-body">
            {c.body}
          </p>
          <dl className="kv text-[13.5px]">
            <dt>{t("notices.field.category")}</dt>
            <dd>
              <Pill tone={categoryTone(c.category)}>{categoryLabel(t, c.category)}</Pill>
            </dd>
            <dt>{t("notices.col.audience")}</dt>
            <dd data-testid="notice-audience">{c.audience.label}</dd>
            <dt>{t("notices.channels.title")}</dt>
            <dd>{[t("notices.channels.appShort"), ...c.channels.map((ch) => channelLabel(t, ch))].join(", ")}</dd>
            {c.scheduledAt ? (
              <>
                <dt>{t("notices.field.scheduledAt")}</dt>
                <dd>{formatDateTime(c.scheduledAt, locale)}</dd>
              </>
            ) : null}
            <dt>{t("notices.writtenBy")}</dt>
            <dd>
              {c.source === "CALENDAR"
                ? t("notices.source.CALENDAR")
                : [c.createdByName, formatDateTime(c.createdAt, locale)].filter(Boolean).join(" · ")}
            </dd>
          </dl>
        </section>

        <aside className="flex min-w-0 flex-col gap-3.5">
          {c.delivery ? <DeliveryCard delivery={c.delivery} /> : null}
          {unsent ? (
            <section className="card">
              <EstimatePanel
                estimate={estimate.data}
                channels={c.channels}
                loading={estimate.loading}
                error={estimate.error}
                empty={null}
              />
            </section>
          ) : null}
        </aside>
      </div>

      <ConfirmDialog
        open={step === "submit"}
        danger={false}
        title={submitTitle}
        body={submitBody}
        confirmLabel={submitLabel}
        onConfirm={async () => {
          const next = await noticesApi.submit(c.id);
          done(
            next,
            next.status === "SENT"
              ? t("notices.toast.sent")
              : next.status === "SCHEDULED"
                ? t("notices.toast.scheduled")
                : t("notices.toast.submitted"),
          );
        }}
        onClose={() => setStep(null)}
      />
      <NoteDialog
        open={step === "approve"}
        title={t("notices.approve.title")}
        description={c.scheduledAt ? t("notices.approve.scheduleBody") : t("notices.approve.sendBody")}
        label={t("notices.approve.note")}
        required={false}
        maxLength={MAX_NOTE}
        confirmLabel={approveLabel}
        onConfirm={async (note) => {
          const next = await noticesApi.approve(c.id, note);
          done(next, next.status === "SENT" ? t("notices.toast.sent") : t("notices.toast.scheduled"));
        }}
        onClose={() => setStep(null)}
      />
      <NoteDialog
        open={step === "reject"}
        title={t("notices.reject.title")}
        description={t("notices.reject.body")}
        label={t("notices.reject.note")}
        required
        maxLength={MAX_NOTE}
        confirmLabel={t("notices.reject")}
        onConfirm={async (note) => {
          done(await noticesApi.reject(c.id, note), t("notices.toast.sentBack"));
        }}
        onClose={() => setStep(null)}
      />
      <NoteDialog
        open={step === "withdraw"}
        title={t("notices.withdraw.title")}
        description={t("notices.withdraw.body")}
        label={t("notices.withdraw.reason")}
        required
        maxLength={MAX_REASON}
        confirmLabel={t("notices.withdraw")}
        danger
        onConfirm={async (reason) => {
          done(await noticesApi.withdraw(c.id, reason), t("notices.toast.withdrawn"));
        }}
        onClose={() => setStep(null)}
      />
      <ConfirmDialog
        open={step === "cancel"}
        danger={false}
        title={t("notices.cancel.title")}
        body={t("notices.cancel.body")}
        confirmLabel={t("notices.cancel")}
        onConfirm={async () => {
          done(await noticesApi.cancel(c.id), t("notices.toast.cancelled"));
        }}
        onClose={() => setStep(null)}
      />
      <ConfirmDialog
        open={step === "delete"}
        title={t("notices.delete.title")}
        body={t("notices.delete.body")}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          await noticesApi.remove(c.id);
          setStep(null);
          toast(t("common.deleted"));
          router.push("/app/notices");
        }}
        onClose={() => setStep(null)}
      />
    </>
  );
}
