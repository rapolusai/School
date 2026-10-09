"use client";

import { BadgePercent, Plus, X } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { feesApi } from "@/lib/fees-api";
import { classLabel, formatDateTime, formatPaise, parseRupees } from "@/lib/format";
import { localeFor, useI18n, type Translate } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import {
  CONCESSION_TYPES,
  MAX_AMOUNT_PAISE,
  type Concession,
  type ConcessionMode,
  type ConcessionRequest,
  type ConcessionType,
  type FeeHead,
  type StudentHit,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { FeesNav } from "./fee-ui";
import { StudentSearch } from "./student-search";

export type ConcessionValues = {
  student: StudentHit | null;
  type: ConcessionType;
  mode: ConcessionMode;
  percent: string;
  fixed: string;
  headIds: string[];
  reason: string;
};

const PERCENT_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** The same checks POST /api/fees/concessions makes. RTE needs only the student and a reason. */
export function validateConcession(values: ConcessionValues): Problems {
  const problems: Problems = {};
  if (!values.student) problems.studentId = "fees.v.student";
  if (values.type !== "RTE") {
    if (values.mode === "PERCENT") {
      const text = values.percent.trim();
      if (!text) problems.percent = "validation.required";
      else if (!PERCENT_PATTERN.test(text) || Number(text) < 0.01 || Number(text) > 100) {
        problems.percent = "fees.v.percent";
      }
    } else {
      const paise = parseRupees(values.fixed);
      if (!values.fixed.trim()) problems.fixedPaise = "validation.required";
      else if (paise === null || paise < 1 || paise > MAX_AMOUNT_PAISE) problems.fixedPaise = "fees.v.amount";
    }
    if (values.headIds.length === 0) problems.headIds = "fees.v.heads";
  }
  if (!values.reason.trim()) problems.reason = "validation.required";
  else if (values.reason.trim().length > 500) problems.reason = "validation.tooLong";
  return problems;
}

export function toConcessionRequest(values: ConcessionValues): ConcessionRequest {
  const rte = values.type === "RTE";
  return {
    studentId: values.student?.id ?? "",
    type: values.type,
    mode: rte ? null : values.mode,
    percent: !rte && values.mode === "PERCENT" ? Number(values.percent.trim()) : null,
    fixedPaise: !rte && values.mode === "FIXED" ? parseRupees(values.fixed) : null,
    headIds: rte ? null : values.headIds,
    reason: values.reason.trim(),
  };
}

/** "10% of Tuition fee", "₹2,000 off Tuition fee, Transport fee", "100% of tuition (RTE)". */
export function describeConcession(c: Concession, t: Translate): string {
  const heads = c.heads.map((h) => h.name).join(", ");
  if (c.mode === "PERCENT") return t("fees.concession.percentOf", { percent: c.percent ?? 0, heads });
  return t("fees.concession.fixedOff", { amount: formatPaise(c.fixedPaise ?? 0), heads });
}

/** /app/fees/concessions: concessions granted this year, granting new ones and revoking them. */
export function ConcessionsView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = hasPermission(me, PERMISSIONS.feesManage);
  const concessions = useApiData("fees:concessions", () => feesApi.listConcessions());
  const [granting, setGranting] = useState(false);
  const [revoking, setRevoking] = useState<Concession | null>(null);
  const list = concessions.data ?? [];
  const active = list.filter((c) => c.status === "ACTIVE");
  const total = active.reduce((sum, c) => sum + c.studentConcessionPaise, 0);

  return (
    <>
      <PageHead
        eyebrow={[t("fees.eyebrow"), list[0]?.academicYearName].filter(Boolean).join(" · ")}
        title={t("fees.concessions.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setGranting(true)}>
              <Plus size={18} aria-hidden="true" />
              {t("fees.concessions.grant")}
            </button>
          ) : null
        }
      />
      <FeesNav />
      <section className="card">
        {concessions.error && !concessions.data ? (
          <ErrorState error={concessions.error} onRetry={concessions.reload} />
        ) : !concessions.data ? (
          <LoadingRows rows={4} />
        ) : list.length === 0 ? (
          <p className="empty">{t("fees.concessions.empty")}</p>
        ) : (
          <>
            <p className="mb-3 text-sm text-ink-2">
              {t("fees.concessions.summary", { count: active.length, amount: formatPaise(total) })}
            </p>
            <ul className="list" data-testid="concessions-list">
              {list.map((c) => (
                <li key={c.id} className="li">
                  <span className="badge-ic">
                    <BadgePercent size={18} aria-hidden="true" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="flex flex-wrap items-center gap-2 font-semibold">
                      {c.studentName}
                      <Pill tone={c.type === "RTE" ? "info" : "accent"}>{t(`fees.concessionType.${c.type}`)}</Pill>
                      {c.status === "REVOKED" ? <Pill tone="neutral">{t("fees.concession.revoked")}</Pill> : null}
                    </p>
                    <p className="text-[13px] text-ink-3">
                      {[classLabel(c.className, c.sectionName), c.admissionNo].filter(Boolean).join(" · ")}
                    </p>
                    <p className="mt-1 text-[13.5px]">
                      {describeConcession(c, t)} ·{" "}
                      <span className="num font-semibold">
                        {t("fees.concession.worth", { amount: formatPaise(c.studentConcessionPaise) })}
                      </span>
                    </p>
                    <p className="text-[12.5px] text-ink-3">
                      {t("fees.concession.approved", {
                        reason: c.reason,
                        name: c.approvedByName ?? t("common.system"),
                        date: formatDateTime(c.createdAt, locale),
                      })}
                    </p>
                    {c.status === "REVOKED" ? (
                      <p className="text-[12.5px] text-ink-3">
                        {t("fees.concession.revokedBy", {
                          name: c.revokedByName ?? t("common.system"),
                          date: formatDateTime(c.revokedAt, locale),
                          reason: c.revokeReason ?? "",
                        })}
                      </p>
                    ) : null}
                  </div>
                  {canManage && c.status === "ACTIVE" ? (
                    <button type="button" className="btn btn-sm" onClick={() => setRevoking(c)}>
                      <X size={16} aria-hidden="true" />
                      {t("fees.concession.revoke")}
                    </button>
                  ) : null}
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
      {granting ? (
        <GrantDialog
          onClose={() => setGranting(false)}
          onGranted={(c) => {
            setGranting(false);
            concessions.reload();
            toast(t("fees.concession.granted", { name: c.studentName }));
          }}
        />
      ) : null}
      {revoking ? (
        <RevokeDialog
          concession={revoking}
          onClose={() => setRevoking(null)}
          onDone={() => {
            setRevoking(null);
            concessions.reload();
            toast(t("fees.concession.revokedToast", { name: revoking.studentName }));
          }}
        />
      ) : null}
    </>
  );
}

function GrantDialog({ onClose, onGranted }: { onClose: () => void; onGranted: (c: Concession) => void }) {
  const { t } = useI18n();
  const heads = useApiData("fees:heads", feesApi.listHeads);
  const form = useForm<ConcessionValues>({
    student: null,
    type: "SIBLING",
    mode: "PERCENT",
    percent: "",
    fixed: "",
    headIds: [],
    reason: "",
  });
  const { values, set, errors } = form;
  const activeHeads: FeeHead[] = (heads.data ?? []).filter((h) => h.active);
  const rte = values.type === "RTE";

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateConcession(values), t, event.currentTarget)) return;
    let saved: Concession | undefined;
    const ok = await form.submit(t, async () => {
      saved = await feesApi.grantConcession(toConcessionRequest(values));
    });
    if (ok && saved) onGranted(saved);
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={t("fees.concessions.grant")}
      description={t("fees.concessions.grantHint")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="field">
          <span className="field-label">{t("fees.receipt.student")}</span>
          {values.student ? (
            <div className="flex items-center justify-between gap-2 rounded-lg border border-line px-3 py-2">
              <span className="min-w-0">
                <b className="block truncate">{values.student.fullName}</b>
                <span className="block truncate text-[12.5px] text-ink-3">
                  {[classLabel(values.student.className, values.student.sectionName), values.student.admissionNo]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              </span>
              <button type="button" className="btn btn-sm" onClick={() => set("student", null)}>
                {t("fees.concessions.change")}
              </button>
            </div>
          ) : (
            <StudentSearch onPick={(hit) => set("student", hit)} label={t("fees.search.placeholder")} />
          )}
          {errors.studentId ? <p className="field-error">{errors.studentId}</p> : null}
        </div>

        <fieldset className="field m-0 border-0 p-0">
          <legend className="field-label mb-1.5">{t("fees.concessions.type")}</legend>
          <div className="seg">
            {CONCESSION_TYPES.map((type) => (
              <label key={type}>
                <input
                  type="radio"
                  name="type"
                  value={type}
                  checked={values.type === type}
                  onChange={() => set("type", type)}
                />
                {t(`fees.concessionType.${type}`)}
              </label>
            ))}
          </div>
          {errors.type ? <p className="field-error">{errors.type}</p> : null}
        </fieldset>

        {rte ? (
          <p className="alert alert-info">{t("fees.concessions.rteNote")}</p>
        ) : (
          <>
            <fieldset className="field m-0 border-0 p-0">
              <legend className="field-label mb-1.5">{t("fees.concessions.mode")}</legend>
              <div className="seg">
                <label>
                  <input
                    type="radio"
                    name="mode"
                    value="PERCENT"
                    checked={values.mode === "PERCENT"}
                    onChange={() => set("mode", "PERCENT")}
                  />
                  {t("fees.concessions.mode.PERCENT")}
                </label>
                <label>
                  <input
                    type="radio"
                    name="mode"
                    value="FIXED"
                    checked={values.mode === "FIXED"}
                    onChange={() => set("mode", "FIXED")}
                  />
                  {t("fees.concessions.mode.FIXED")}
                </label>
              </div>
            </fieldset>
            {values.mode === "PERCENT" ? (
              <TextField
                label={t("fees.concessions.percent")}
                name="percent"
                inputMode="decimal"
                value={values.percent}
                maxLength={6}
                hint={t("fees.concessions.percent.hint")}
                onChange={(e) => set("percent", e.target.value)}
                error={errors.percent}
                required
              />
            ) : (
              <TextField
                label={t("fees.concessions.fixed")}
                name="fixedPaise"
                inputMode="decimal"
                value={values.fixed}
                hint={t("fees.concessions.fixed.hint")}
                onChange={(e) => set("fixed", e.target.value)}
                error={errors.fixedPaise}
                required
              />
            )}
            <fieldset className="field m-0 border-0 p-0">
              <legend className="field-label mb-1.5">{t("fees.concessions.heads")}</legend>
              {heads.error && !heads.data ? (
                <ErrorState error={heads.error} onRetry={heads.reload} />
              ) : (
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                  {activeHeads.map((head) => (
                    <label key={head.id} className="check">
                      <input
                        type="checkbox"
                        name="headIds"
                        value={head.id}
                        checked={values.headIds.includes(head.id)}
                        onChange={(e) =>
                          set(
                            "headIds",
                            e.target.checked
                              ? [...values.headIds, head.id]
                              : values.headIds.filter((id) => id !== head.id),
                          )
                        }
                      />
                      <span>{head.name}</span>
                    </label>
                  ))}
                </div>
              )}
              {errors.headIds ? <p className="field-error">{errors.headIds}</p> : null}
            </fieldset>
          </>
        )}

        <TextAreaField
          label={t("fees.reason")}
          name="reason"
          value={values.reason}
          maxLength={500}
          placeholder={t("fees.concessions.reason.placeholder")}
          onChange={(e) => set("reason", e.target.value)}
          error={errors.reason}
          required
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("fees.concessions.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function RevokeDialog({
  concession,
  onClose,
  onDone,
}: {
  concession: Concession;
  onClose: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const form = useForm({ reason: "" });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const reason = form.values.reason.trim();
    const problems: Problems = {};
    if (!reason) problems.reason = "validation.required";
    else if (reason.length > 500) problems.reason = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await feesApi.revokeConcession(concession.id, reason);
    });
    if (ok) onDone();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("fees.concession.revokeTitle", { name: concession.studentName })}
      description={t("fees.concession.revokeBody")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("fees.reason")}
          name="reason"
          value={form.values.reason}
          maxLength={500}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("fees.concession.revoke")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
