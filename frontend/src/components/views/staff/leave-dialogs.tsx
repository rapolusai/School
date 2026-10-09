"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n, type Translate } from "@/lib/i18n";
import { leaveApi } from "@/lib/staff-api";
import type {
  LeaveBalance,
  LeaveType,
  LeaveYear,
  StaffLeaveRequest,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";
import { calendarDays, dateRange, formatDays, formatNumber } from "./staff-shared";

/** The API's limits (docs/api/phase-1-staff.md). */
export const MAX_LEAVE_CALENDAR_DAYS = 200;
export const LEAVE_TYPE_CODE_PATTERN = /^[A-Za-z0-9]{1,10}$/;

/** "12", "1.5" within [min, max] in whole or half days. */
export function isHalfStep(value: string, min: number, max: number): boolean {
  const text = value.trim();
  if (!/^\d+(\.\d+)?$/.test(text)) return false;
  const n = Number(text);
  return Number.isFinite(n) && n >= min && n <= max && Number.isInteger(n * 2);
}

/* ------------------------------------------------------------------ apply */

export type LeaveValues = {
  leaveTypeId: string;
  fromDate: string;
  toDate: string;
  halfDay: boolean;
  reason: string;
};

export const EMPTY_LEAVE: LeaveValues = { leaveTypeId: "", fromDate: "", toDate: "", halfDay: false, reason: "" };

/** The same checks the API makes before applying. Pure and exported for tests. */
export function validateLeave(values: LeaveValues, types: LeaveType[]): Problems {
  const problems: Problems = {};
  const type = types.find((x) => x.id === values.leaveTypeId);
  if (!type) problems.leaveTypeId = "validation.required";
  if (!isPlainDate(values.fromDate)) problems.fromDate = "validation.required";
  if (!isPlainDate(values.toDate)) problems.toDate = "validation.required";
  else if (isPlainDate(values.fromDate) && values.toDate < values.fromDate) problems.toDate = "leave.v.toBeforeFrom";
  else if (isPlainDate(values.fromDate) && calendarDays(values.fromDate, values.toDate) > MAX_LEAVE_CALENDAR_DAYS)
    problems.toDate = "leave.v.tooLong";
  if (values.halfDay) {
    if (type && !type.halfDayAllowed) problems.halfDay = "leave.v.halfDayType";
    else if (values.fromDate !== values.toDate) problems.halfDay = "leave.v.halfDaySingle";
  }
  if (!values.reason.trim()) problems.reason = "validation.required";
  else if (values.reason.trim().length > 500) problems.reason = "validation.tooLong";
  return problems;
}

/** True when the dates and type are complete enough to ask the API what the request would cost. */
function previewable(values: LeaveValues): boolean {
  return (
    Boolean(values.leaveTypeId) &&
    isPlainDate(values.fromDate) &&
    isPlainDate(values.toDate) &&
    values.toDate >= values.fromDate &&
    calendarDays(values.fromDate, values.toDate) <= MAX_LEAVE_CALENDAR_DAYS
  );
}

function LeavePreviewBox({ values }: { values: LeaveValues }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const ready = previewable(values);
  const halfDay = values.halfDay && values.fromDate === values.toDate;
  const key = ready ? `leave:preview:${values.leaveTypeId}:${values.fromDate}:${values.toDate}:${halfDay}` : null;
  const preview = useApiData(key, () =>
    leaveApi.preview({ leaveTypeId: values.leaveTypeId, fromDate: values.fromDate, toDate: values.toDate, halfDay }),
  );
  if (!ready) return <p className="field-hint">{t("leave.preview.hint")}</p>;
  const data =
    preview.data &&
    preview.data.fromDate === values.fromDate &&
    preview.data.toDate === values.toDate &&
    preview.data.halfDay === halfDay &&
    preview.data.balance.leaveTypeId === values.leaveTypeId
      ? preview.data
      : undefined;
  if (preview.error && !preview.loading) {
    return (
      <div className="alert alert-bad" role="status" data-testid="leave-preview">
        <span>{errorMessage(preview.error, t)}</span>
      </div>
    );
  }
  if (!data) {
    return (
      <div className="skeleton h-14" aria-hidden="true" data-testid="leave-preview-loading" />
    );
  }
  const lossOfPay = data.balance.lossOfPay;
  const bad = !data.enough || data.overlaps;
  return (
    <div
      className={`alert ${bad ? "alert-bad" : "alert-info"} flex-col items-start gap-1`}
      role="status"
      aria-live="polite"
      data-testid="leave-preview"
    >
      <p>
        <b>{t("leave.preview.days", { days: formatDays(t, data.days) })}</b>
        {data.nonWorkingDays > 0 ? ` ${plural(t, "leave.preview.offDays", data.nonWorkingDays)}` : ""}
      </p>
      {data.workingDays.length > 0 && data.workingDays.length <= 7 ? (
        <p className="text-[13px]">{data.workingDays.map((d) => formatPlainDate(d, locale)).join(", ")}</p>
      ) : null}
      <p data-testid="leave-preview-balance">
        {lossOfPay
          ? t("leave.preview.lossOfPay")
          : t("leave.preview.after", {
              available: formatNumber(data.balance.available),
              pending: formatNumber(data.balance.pending),
              after: formatNumber(data.availableAfter),
            })}
      </p>
      {!data.enough ? <p className="font-semibold">{t("leave.preview.notEnough")}</p> : null}
      {data.overlaps ? <p className="font-semibold">{t("leave.preview.overlaps")}</p> : null}
    </div>
  );
}

/** Apply for leave: type, dates, half day and reason, with the working days and balance after shown live. */
export function ApplyLeaveDialog({
  open,
  types,
  balances,
  onClose,
  onApplied,
}: {
  open: boolean;
  types: LeaveType[];
  balances: LeaveBalance[];
  onClose: () => void;
  onApplied: (request: StaffLeaveRequest) => void;
}) {
  const { t } = useI18n();
  const form = useForm<LeaveValues>(EMPTY_LEAVE);
  const { values, set, errors } = form;
  const active = types.filter((type) => type.active);
  const type = active.find((x) => x.id === values.leaveTypeId);
  const balanceOf = (id: string) => balances.find((b) => b.leaveTypeId === id);

  const close = () => {
    form.reset(EMPTY_LEAVE);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateLeave(values, active), t, event.currentTarget)) return;
    let applied: StaffLeaveRequest | undefined;
    const ok = await form.submit(t, async () => {
      applied = await leaveApi.apply({
        leaveTypeId: values.leaveTypeId,
        fromDate: values.fromDate,
        toDate: values.toDate,
        halfDay: values.halfDay && values.fromDate === values.toDate,
        reason: values.reason.trim(),
      });
    });
    if (ok && applied) {
      form.reset(EMPTY_LEAVE);
      onApplied(applied);
    }
  };

  const typeLabel = (x: LeaveType) => {
    const balance = balanceOf(x.id);
    if (x.lossOfPay || !balance || balance.available === null) return x.name;
    return `${x.name} · ${t("leave.availableShort", { days: formatNumber(balance.available - balance.pending) })}`;
  };

  return (
    <Dialog open={open} onClose={close} title={t("leave.apply.title")} description={t("leave.apply.description")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="apply-leave-form">
        <FormAlert message={form.formError} />
        {active.length === 0 ? <p className="alert alert-info">{t("leave.noTypes")}</p> : null}
        <SelectField
          label={t("leave.field.type")}
          name="leaveTypeId"
          value={values.leaveTypeId}
          onChange={(e) => {
            set("leaveTypeId", e.target.value);
            const next = active.find((x) => x.id === e.target.value);
            if (next && !next.halfDayAllowed && values.halfDay) set("halfDay", false);
          }}
          error={errors.leaveTypeId}
          options={[{ value: "", label: t("leave.field.type.pick") }, ...active.map((x) => ({ value: x.id, label: typeLabel(x) }))]}
          required
          data-autofocus
        />
        <div className="grid2">
          <TextField
            label={t("leave.field.from")}
            name="fromDate"
            type="date"
            value={values.fromDate}
            onChange={(e) => {
              const from = e.target.value;
              set("fromDate", from);
              if (isPlainDate(from) && (!values.toDate || values.toDate < from)) set("toDate", from);
            }}
            error={errors.fromDate}
            required
          />
          <TextField
            label={t("leave.field.to")}
            name="toDate"
            type="date"
            value={values.toDate}
            min={values.fromDate || undefined}
            onChange={(e) => set("toDate", e.target.value)}
            error={errors.toDate}
            required
          />
        </div>
        {type?.halfDayAllowed ? (
          <div className="field">
            <label className="check">
              <input
                type="checkbox"
                name="halfDay"
                checked={values.halfDay}
                onChange={(e) => set("halfDay", e.target.checked)}
              />
              <span>{t("leave.field.halfDay")}</span>
            </label>
            {errors.halfDay ? <p className="field-error">{errors.halfDay}</p> : <p className="field-hint">{t("leave.field.halfDay.hint")}</p>}
          </div>
        ) : null}
        <LeavePreviewBox values={values} />
        <TextAreaField
          label={t("leave.field.reason")}
          name="reason"
          value={values.reason}
          onChange={(e) => set("reason", e.target.value)}
          error={errors.reason}
          maxLength={500}
          rows={3}
          required
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting || active.length === 0}>
            {form.submitting ? t("common.saving") : t("leave.apply.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ decide and cancel */

export type LeaveAction = "approve" | "reject" | "cancel";

/** One line describing a request: "Casual leave · 3 Oct 2026 – 5 Oct 2026 · 3 days". */
export function requestSummary(t: Translate, request: StaffLeaveRequest, locale: string): string {
  return [
    request.leaveTypeName ?? request.leaveTypeCode ?? "",
    dateRange(request.fromDate, request.toDate, (d) => formatPlainDate(d, locale)),
    request.halfDay ? t("leave.halfDay") : formatDays(t, request.days),
  ]
    .filter(Boolean)
    .join(" · ");
}

/** Approve (optional comment), reject (comment required) or cancel (optional comment) a request. */
export function LeaveActionDialog({
  request,
  action,
  onClose,
  onDone,
}: {
  request: StaffLeaveRequest | null;
  action: LeaveAction;
  onClose: () => void;
  onDone: (request: StaffLeaveRequest) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const form = useForm<{ comment: string }>({ comment: "" });

  const close = () => {
    form.reset({ comment: "" });
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!request) return;
    const comment = form.values.comment.trim();
    const problems: Problems = {};
    if (action === "reject" && !comment) problems.comment = "leave.v.commentRequired";
    else if (comment.length > 500) problems.comment = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    let done: StaffLeaveRequest | undefined;
    const ok = await form.submit(t, async () => {
      if (action === "approve") done = await leaveApi.approve(request.id, comment);
      else if (action === "reject") done = await leaveApi.reject(request.id, comment);
      else done = await leaveApi.cancel(request.id, comment);
    });
    if (ok && done) {
      form.reset({ comment: "" });
      onDone(done);
    }
  };

  const title =
    action === "approve" ? t("leave.approve.title") : action === "reject" ? t("leave.reject.title") : t("leave.cancel.title");
  const submit =
    action === "approve" ? t("leave.approve") : action === "reject" ? t("leave.reject") : t("leave.cancel");

  return (
    <Dialog
      open={request !== null}
      onClose={close}
      title={title}
      description={request ? `${request.userName ?? ""} · ${requestSummary(t, request, locale)}`.replace(/^ · /, "") : undefined}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        {request && action === "cancel" && request.status === "APPROVED" ? (
          <p className="text-sm text-ink-2">{t("leave.cancel.approvedHint")}</p>
        ) : null}
        <TextAreaField
          label={action === "reject" ? t("leave.field.comment") : t("leave.field.commentOptional")}
          name="comment"
          value={form.values.comment}
          onChange={(e) => form.set("comment", e.target.value)}
          error={form.errors.comment}
          maxLength={500}
          rows={3}
          required={action === "reject"}
          data-autofocus
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.close")}
          </button>
          <button
            type="submit"
            className={action === "approve" ? "btn btn-primary" : "btn btn-danger"}
            disabled={form.submitting}
          >
            {form.submitting ? t("common.working") : submit}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ leave types */

export type LeaveTypeValues = {
  name: string;
  code: string;
  yearlyQuota: string;
  carryForwardCap: string;
  halfDayAllowed: boolean;
  lossOfPay: boolean;
  active: boolean;
};

export const EMPTY_LEAVE_TYPE: LeaveTypeValues = {
  name: "",
  code: "",
  yearlyQuota: "0",
  carryForwardCap: "0",
  halfDayAllowed: true,
  lossOfPay: false,
  active: true,
};

/** Pure and exported for tests. */
export function validateLeaveType(values: LeaveTypeValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 60) problems.name = "validation.tooLong";
  if (!values.code.trim()) problems.code = "validation.required";
  else if (!LEAVE_TYPE_CODE_PATTERN.test(values.code.trim())) problems.code = "leave.v.code";
  if (!values.lossOfPay) {
    if (!isHalfStep(values.yearlyQuota, 0, 366)) problems.yearlyQuota = "leave.v.days";
    if (!isHalfStep(values.carryForwardCap, 0, 366)) problems.carryForwardCap = "leave.v.days";
  }
  return problems;
}

function typeValues(type: LeaveType | null): LeaveTypeValues {
  if (!type) return EMPTY_LEAVE_TYPE;
  return {
    name: type.name,
    code: type.code,
    yearlyQuota: formatNumber(type.yearlyQuota),
    carryForwardCap: formatNumber(type.carryForwardCap),
    halfDayAllowed: type.halfDayAllowed,
    lossOfPay: type.lossOfPay,
    active: type.active,
  };
}

/** Add or change a leave type. */
export function LeaveTypeDialog({
  open,
  type,
  onClose,
  onSaved,
}: {
  open: boolean;
  type: LeaveType | null;
  onClose: () => void;
  onSaved: (type: LeaveType) => void;
}) {
  const { t } = useI18n();
  const initial = typeValues(type);
  const form = useForm<LeaveTypeValues>(initial);
  const { values, set, errors } = form;

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateLeaveType(values), t, event.currentTarget)) return;
    const body = {
      name: values.name.trim(),
      code: values.code.trim().toUpperCase(),
      yearlyQuota: values.lossOfPay ? 0 : Number(values.yearlyQuota),
      carryForwardCap: values.lossOfPay ? 0 : Number(values.carryForwardCap),
      halfDayAllowed: values.halfDayAllowed,
      lossOfPay: values.lossOfPay,
      active: values.active,
    };
    let saved: LeaveType | undefined;
    const ok = await form.submit(t, async () => {
      saved = type ? await leaveApi.updateType(type.id, body) : await leaveApi.createType(body);
    });
    if (ok && saved) {
      form.reset(EMPTY_LEAVE_TYPE);
      onSaved(saved);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={type ? t("leave.types.editTitle", { name: type.name }) : t("leave.types.addTitle")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="grid2">
          <TextField
            label={t("leave.types.field.name")}
            name="name"
            value={values.name}
            onChange={(e) => set("name", e.target.value)}
            error={errors.name}
            maxLength={60}
            required
            data-autofocus
          />
          <TextField
            label={t("leave.types.field.code")}
            name="code"
            value={values.code}
            onChange={(e) => set("code", e.target.value)}
            error={errors.code}
            maxLength={10}
            autoComplete="off"
            className="[&_input]:font-mono [&_input]:uppercase"
            hint={t("leave.types.field.code.hint")}
            required
          />
        </div>
        <label className="check">
          <input type="checkbox" name="lossOfPay" checked={values.lossOfPay} onChange={(e) => set("lossOfPay", e.target.checked)} />
          <span>{t("leave.types.field.lossOfPay")}</span>
        </label>
        {!values.lossOfPay ? (
          <div className="grid2">
            <TextField
              label={t("leave.types.field.quota")}
              name="yearlyQuota"
              inputMode="decimal"
              value={values.yearlyQuota}
              onChange={(e) => set("yearlyQuota", e.target.value)}
              error={errors.yearlyQuota}
              hint={t("leave.types.field.quota.hint")}
              maxLength={5}
              required
            />
            <TextField
              label={t("leave.types.field.carry")}
              name="carryForwardCap"
              inputMode="decimal"
              value={values.carryForwardCap}
              onChange={(e) => set("carryForwardCap", e.target.value)}
              error={errors.carryForwardCap}
              hint={t("leave.types.field.carry.hint")}
              maxLength={5}
              required
            />
          </div>
        ) : (
          <p className="field-hint">{t("leave.types.lossOfPay.hint")}</p>
        )}
        <label className="check">
          <input
            type="checkbox"
            name="halfDayAllowed"
            checked={values.halfDayAllowed}
            onChange={(e) => set("halfDayAllowed", e.target.checked)}
          />
          <span>{t("leave.types.field.halfDay")}</span>
        </label>
        {type ? (
          <label className="check">
            <input type="checkbox" name="active" checked={values.active} onChange={(e) => set("active", e.target.checked)} />
            <span>{t("leave.types.field.active")}</span>
          </label>
        ) : null}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
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

/* ------------------------------------------------------------------ balance by hand */

export type BalanceValues = { opening: string; accrued: string };

/** Pure and exported for tests. */
export function validateBalance(values: BalanceValues): Problems {
  const problems: Problems = {};
  if (!isHalfStep(values.opening, 0, 999)) problems.opening = "leave.v.balance";
  if (!isHalfStep(values.accrued, 0, 999)) problems.accrued = "leave.v.balance";
  return problems;
}

/** Sets one person's opening balance and allowance of a type for a year. */
export function BalanceDialog({
  userId,
  name,
  year,
  balance,
  onClose,
  onSaved,
}: {
  userId: string;
  name: string;
  year: LeaveYear | null;
  balance: LeaveBalance | null;
  onClose: () => void;
  onSaved: (balance: LeaveBalance) => void;
}) {
  const { t } = useI18n();
  const [initial] = useState<BalanceValues>(() => ({
    opening: formatNumber(balance?.opening ?? 0),
    accrued: formatNumber(balance?.accrued ?? 0),
  }));
  const form = useForm<BalanceValues>(initial);

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!balance) return;
    if (!form.check(validateBalance(form.values), t, event.currentTarget)) return;
    let saved: LeaveBalance | undefined;
    const ok = await form.submit(t, async () => {
      saved = await leaveApi.setBalance({
        userId,
        leaveTypeId: balance.leaveTypeId,
        academicYearId: year?.id ?? null,
        opening: Number(form.values.opening),
        accrued: Number(form.values.accrued),
      });
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog
      open={balance !== null}
      onClose={close}
      title={t("leave.balance.title", { type: balance?.leaveTypeName ?? "", name })}
      description={t("leave.balance.description", { year: year?.name ?? "" })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="grid2">
          <TextField
            label={t("leave.balance.opening")}
            name="opening"
            inputMode="decimal"
            value={form.values.opening}
            onChange={(e) => form.set("opening", e.target.value)}
            error={form.errors.opening}
            maxLength={5}
            required
            data-autofocus
          />
          <TextField
            label={t("leave.balance.accrued")}
            name="accrued"
            inputMode="decimal"
            value={form.values.accrued}
            onChange={(e) => form.set("accrued", e.target.value)}
            error={form.errors.accrued}
            maxLength={5}
            required
          />
        </div>
        {balance ? (
          <p className="field-hint">{t("leave.balance.taken", { taken: formatNumber(balance.taken) })}</p>
        ) : null}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
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
