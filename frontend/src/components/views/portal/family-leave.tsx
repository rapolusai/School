"use client";

import { CalendarPlus } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { isParent } from "@/lib/family";
import { classLabel, formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { portalApi } from "@/lib/portal-api";
import type { ChildLeave, FamilyLeave } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";
import { calendarDaysBetween, ChildSwitcher, leaveDates, LeaveStatusPill, useFamilyChildren } from "./family-shared";

/** The API's limits (docs/api/phase-1-portal.md). */
export const CHILD_LEAVE_MAX_DAYS = 31;
export const CHILD_LEAVE_REASON_MAX = 500;

export type ChildLeaveValues = { fromDate: string; toDate: string; halfDay: boolean; reason: string };

export const EMPTY_CHILD_LEAVE: ChildLeaveValues = { fromDate: "", toDate: "", halfDay: false, reason: "" };

/**
 * The checks the API makes before accepting an absence note, with the dates the parent may pick
 * (`earliest` to `latest`, from the API). Sundays and holidays are checked by the API. Pure.
 */
export function validateChildLeave(
  values: ChildLeaveValues,
  limits: Pick<FamilyLeave, "earliest" | "latest">,
): Problems {
  const problems: Problems = {};
  const from = values.fromDate;
  const to = values.toDate;
  if (!isPlainDate(from)) problems.fromDate = "validation.required";
  else if (from < limits.earliest) problems.fromDate = "portal.leave.v.tooEarly";
  else if (from > limits.latest) problems.fromDate = "portal.leave.v.tooLate";
  if (!isPlainDate(to)) problems.toDate = "validation.required";
  else if (isPlainDate(from) && to < from) problems.toDate = "leave.v.toBeforeFrom";
  else if (to > limits.latest) problems.toDate = "portal.leave.v.tooLate";
  else if (isPlainDate(from) && calendarDaysBetween(from, to) > CHILD_LEAVE_MAX_DAYS) problems.toDate = "portal.leave.v.tooLong";
  if (values.halfDay && from !== to) problems.halfDay = "leave.v.halfDaySingle";
  const reason = values.reason.trim();
  if (!reason) problems.reason = "portal.leave.v.reason";
  else if (reason.length > CHILD_LEAVE_REASON_MAX) problems.reason = "validation.tooLong";
  return problems;
}

/** /app/family/leave: a parent applies for their child's leave and follows it; a student sees theirs. */
export function FamilyLeaveView({ initialChildId }: { initialChildId?: string }) {
  const { me } = useAuth();
  return isParent(me) ? <ParentLeave initialChildId={initialChildId} /> : <StudentLeave />;
}

function ParentLeave({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const { children, list, child, choose } = useFamilyChildren(initialChildId);
  return (
    <>
      {children.error && !children.data ? (
        <>
          <PageHead title={t("portal.leave.title")} />
          <ErrorState error={children.error} onRetry={children.reload} />
        </>
      ) : !children.data ? (
        <>
          <PageHead title={t("portal.leave.title")} />
          <LoadingRows rows={4} />
        </>
      ) : !child ? (
        <>
          <PageHead title={t("portal.leave.title")} />
          <p className="empty">{t("children.empty")}</p>
        </>
      ) : (
        <ChildLeavePanel
          key={child.id}
          studentId={child.id}
          eyebrow={[child.fullName, classLabel(child.className, child.sectionName)].filter(Boolean).join(" · ")}
          switcher={<ChildSwitcher list={list} value={child.id} onChange={choose} />}
        />
      )}
    </>
  );
}

function ChildLeavePanel({
  studentId,
  eyebrow,
  switcher,
}: {
  studentId: string;
  eyebrow: string;
  switcher: React.ReactNode;
}) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const data = useApiData(`portal:leave:${studentId}`, () => portalApi.childLeave(studentId));
  const [applying, setApplying] = useState(false);
  const [cancelling, setCancelling] = useState<ChildLeave | null>(null);
  const view = data.data;
  return (
    <>
      <PageHead
        eyebrow={eyebrow}
        title={t("portal.leave.title")}
        actions={
          view?.canApply ? (
            <button type="button" className="btn btn-primary" onClick={() => setApplying(true)}>
              <CalendarPlus size={18} aria-hidden="true" />
              {t("portal.leave.apply")}
            </button>
          ) : null
        }
      />
      {switcher}
      <LeaveList
        data={data}
        intro={
          view
            ? view.canApply
              ? view.classTeacherName
                ? t("portal.leave.intro", { name: view.classTeacherName })
                : t("portal.leave.introNoTeacher")
              : t("portal.leave.cannotApply")
            : null
        }
        onCancel={setCancelling}
      />
      {view ? (
        <ApplyDialog
          open={applying}
          family={view}
          onClose={() => setApplying(false)}
          onApplied={() => {
            setApplying(false);
            data.reload();
            toast(t("portal.leave.sent"));
          }}
        />
      ) : null}
      <ConfirmDialog
        open={cancelling !== null}
        title={t("portal.leave.cancel.title")}
        body={cancelling ? t("portal.leave.cancel.body", { dates: leaveDates(t, cancelling, localeFor(lang)) }) : ""}
        confirmLabel={t("portal.leave.cancel")}
        onClose={() => setCancelling(null)}
        onConfirm={async () => {
          if (!cancelling) return;
          await portalApi.cancelLeave(studentId, cancelling.id);
          setCancelling(null);
          data.reload();
          toast(t("portal.leave.cancelled"));
        }}
      />
    </>
  );
}

function StudentLeave() {
  const { t } = useI18n();
  const data = useApiData("portal:leave:me", portalApi.myLeave);
  return (
    <>
      <PageHead eyebrow={data.data?.sectionLabel ?? undefined} title={t("portal.leave.title")} />
      <LeaveList data={data} intro={t("portal.leave.studentIntro")} />
    </>
  );
}

function LeaveList({
  data,
  intro,
  onCancel,
}: {
  data: ApiData<FamilyLeave>;
  intro: string | null;
  onCancel?: (request: ChildLeave) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const view = data.data;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="family-leave-list">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h2 id="family-leave-list">{t("portal.leave.requests")}</h2>
          {intro ? <p className="mt-1 text-[13px] text-ink-3">{intro}</p> : null}
        </div>
      </div>
      {data.error?.status === 404 ? (
        <p className="empty">{t("children.notLinked")}</p>
      ) : data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={3} />
      ) : view.requests.length === 0 ? (
        <p className="empty" data-testid="family-leave-empty">
          {onCancel ? t("portal.leave.none") : t("portal.leave.noneStudent")}
        </p>
      ) : (
        <ul className="list" data-testid="family-leave-list">
          {view.requests.map((request) => (
            <li key={request.id} className="li flex-col items-stretch gap-1.5" data-testid="family-leave-row">
              <div className="flex flex-wrap items-center gap-2">
                <b className="min-w-0 flex-1">{leaveDates(t, request, locale)}</b>
                <LeaveStatusPill status={request.status} />
              </div>
              <p className="text-[13px] text-ink-3">
                {[
                  request.halfDay ? t("portal.leave.halfDay") : plural(t, "portal.leave.schoolDays", request.schoolDays),
                  request.requestedByName
                    ? t("portal.leave.askedBy", { name: request.requestedByName, time: formatDateTime(request.createdAt, locale) })
                    : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
              <p className="whitespace-pre-line break-words text-[14px] text-ink">{request.reason}</p>
              {request.decidedByName && request.decidedAt ? (
                <p className="text-[13px] text-ink-2">
                  {t(request.status === "REJECTED" ? "portal.leave.rejectedBy" : "portal.leave.approvedBy", {
                    name: request.decidedByName,
                  })}
                  {request.decisionComment ? `: “${request.decisionComment}”` : ""}
                </p>
              ) : null}
              {onCancel && request.canCancel ? (
                <button type="button" className="btn btn-sm self-start" onClick={() => onCancel(request)}>
                  {t("portal.leave.cancel")}
                </button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The absence note: first and last day (or half of one day) and why. */
export function ApplyDialog({
  open,
  family,
  onClose,
  onApplied,
}: {
  open: boolean;
  family: FamilyLeave;
  onClose: () => void;
  onApplied: (request: ChildLeave) => void;
}) {
  const { t } = useI18n();
  const form = useForm<ChildLeaveValues>(EMPTY_CHILD_LEAVE);
  const { values, set, errors } = form;

  const close = () => {
    form.reset(EMPTY_CHILD_LEAVE);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateChildLeave(values, family), t, event.currentTarget)) return;
    let applied: ChildLeave | undefined;
    const ok = await form.submit(t, async () => {
      applied = await portalApi.applyLeave(family.studentId, {
        fromDate: values.fromDate,
        toDate: values.toDate,
        halfDay: values.halfDay && values.fromDate === values.toDate,
        reason: values.reason.trim(),
      });
    });
    if (ok && applied) {
      form.reset(EMPTY_CHILD_LEAVE);
      onApplied(applied);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("portal.leave.apply.title", { name: family.studentName })}
      description={
        family.classTeacherName ? t("portal.leave.intro", { name: family.classTeacherName }) : t("portal.leave.introNoTeacher")
      }
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="child-leave-form">
        <FormAlert message={form.formError} />
        <div className="grid2">
          <TextField
            label={t("leave.field.from")}
            name="fromDate"
            type="date"
            value={values.fromDate}
            min={family.earliest}
            max={family.latest}
            onChange={(e) => {
              const from = e.target.value;
              set("fromDate", from);
              if (isPlainDate(from) && (!values.toDate || values.toDate < from || values.halfDay)) set("toDate", from);
            }}
            error={errors.fromDate}
            required
            data-autofocus
          />
          <TextField
            label={t("leave.field.to")}
            name="toDate"
            type="date"
            value={values.toDate}
            min={values.fromDate || family.earliest}
            max={family.latest}
            onChange={(e) => set("toDate", e.target.value)}
            error={errors.toDate}
            required
          />
        </div>
        <div className="field">
          <label className="check">
            <input
              type="checkbox"
              name="halfDay"
              checked={values.halfDay}
              onChange={(e) => {
                set("halfDay", e.target.checked);
                if (e.target.checked && isPlainDate(values.fromDate)) set("toDate", values.fromDate);
              }}
            />
            <span>{t("leave.field.halfDay")}</span>
          </label>
          {errors.halfDay ? (
            <p className="field-error">{errors.halfDay}</p>
          ) : (
            <p className="field-hint">{t("portal.leave.halfDayHint")}</p>
          )}
        </div>
        <TextAreaField
          label={t("leave.field.reason")}
          name="reason"
          value={values.reason}
          onChange={(e) => set("reason", e.target.value)}
          error={errors.reason}
          hint={t("portal.leave.reasonHint")}
          maxLength={CHILD_LEAVE_REASON_MAX}
          rows={3}
          required
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("portal.leave.send")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
