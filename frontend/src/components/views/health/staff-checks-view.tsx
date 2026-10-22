"use client";

import { GraduationCap, Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { healthApi } from "@/lib/health-api";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import type { StaffVerification, TrainingKind, TrainingRequest, VerificationRequest, VerificationStatus } from "@/lib/types";
import { TRAINING_KINDS, VERIFICATION_STATUSES } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { DATE, DuePill, HealthNav, VERIFICATION_TONE, verificationLabel } from "./health-shared";

type VerificationValues = { status: VerificationStatus; requestedOn: string; clearedOn: string; referenceNo: string; expiresOn: string; note: string };

/** Client-side checks mirroring VerificationForm: cleared needs the date. Pure and exported for tests. */
export function verificationProblems(values: VerificationValues): Problems {
  const problems: Problems = {};
  for (const field of ["requestedOn", "clearedOn", "expiresOn"] as const) {
    if (values[field] && !DATE.test(values[field])) problems[field] = "health.v.date";
  }
  if (values.status === "CLEARED" && !values.clearedOn) problems.clearedOn = "health.v.clearedOn";
  return problems;
}

/** /app/health/staff: police verification and POCSO / first-aid training for every member of staff. */
export function StaffChecksView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [today] = useState(() => todayInIndia());
  const [onlyDue, setOnlyDue] = useState(false);
  const checks = useApiData("health:staff:checks", healthApi.staffChecks);
  const [editing, setEditing] = useState<StaffVerification | null>(null);
  const [training, setTraining] = useState<StaffVerification | null>(null);
  const data = checks.data;
  const rows = (data?.rows ?? []).filter((r) => !onlyDue || r.due !== "OK" || r.pocsoTrainingDue || r.firstAidTrainingDue);
  const saved = () => {
    toast(t("health.staff.saved"));
    checks.reload();
  };
  return (
    <>
      <PageHead eyebrow={t("nav.health")} title={t("health.staff.title")} />
      <HealthNav />
      <p className="text-[13px] text-ink-3">{t("health.staff.intro")}</p>
      {data ? (
        <div className="toolbar no-print" data-testid="staff-checks-summary">
          <Pill tone={data.verificationsMissing > 0 ? "warn" : "good"}>{plural(t, "health.staff.missing", data.verificationsMissing)}</Pill>
          <Pill tone={data.verificationsDue > 0 ? "warn" : "good"}>{plural(t, "health.staff.due", data.verificationsDue)}</Pill>
          <Pill tone={data.trainingsDue > 0 ? "warn" : "good"}>{plural(t, "health.staff.trainingsDue", data.trainingsDue)}</Pill>
          <label className="check">
            <input type="checkbox" checked={onlyDue} onChange={(e) => setOnlyDue(e.target.checked)} />
            <span>{t("health.staff.onlyDue")}</span>
          </label>
        </div>
      ) : null}
      {checks.error && !data ? (
        <ErrorState error={checks.error} onRetry={checks.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : rows.length === 0 ? (
        <p className="empty">{t("health.staff.empty")}</p>
      ) : (
        <div className="table-wrap">
          <table className="table" data-testid="staff-checks">
            <caption className="sr-only">{t("health.staff.title")}</caption>
            <thead>
              <tr>
                <th scope="col">{t("health.staff.name")}</th>
                <th scope="col">{t("health.staff.verification")}</th>
                <th scope="col">{t("health.staff.reference")}</th>
                <th scope="col">{t("health.staff.expires")}</th>
                <th scope="col">{t("health.staff.trainings")}</th>
                <th scope="col" className="sr-only">
                  {t("common.actions")}
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.userId} data-testid="staff-check-row" data-status={r.status}>
                  <td>
                    <span className="font-semibold">{r.name}</span>
                    {r.designation ? <span className="block text-[12.5px] text-ink-3">{r.designation}</span> : null}
                  </td>
                  <td>
                    <span className="flex flex-wrap gap-1">
                      <Pill tone={VERIFICATION_TONE[r.status]}>{verificationLabel(t, r.status)}</Pill>
                      <DuePill due={r.due} />
                    </span>
                    {r.clearedOn ? <span className="block text-[12.5px] text-ink-3">{t("health.staff.clearedOn", { date: formatPlainDate(r.clearedOn, locale) })}</span> : r.requestedOn ? <span className="block text-[12.5px] text-ink-3">{t("health.staff.requestedOn", { date: formatPlainDate(r.requestedOn, locale) })}</span> : null}
                  </td>
                  <td className="mono">{r.referenceNoMasked ?? "—"}</td>
                  <td>{r.expiresOn ? formatPlainDate(r.expiresOn, locale) : "—"}</td>
                  <td>
                    <ul className="flex flex-wrap gap-1">
                      {TRAINING_KINDS.map((kind) => {
                        const latest = r.trainings.filter((x) => x.kind === kind).sort((a, b) => (a.trainedOn < b.trainedOn ? 1 : -1))[0];
                        const due = kind === "POCSO" ? r.pocsoTrainingDue : r.firstAidTrainingDue;
                        return (
                          <li key={kind}>
                            <Pill tone={due ? "warn" : "good"} title={latest ? formatPlainDate(latest.trainedOn, locale) : undefined}>
                              {translateOr(t, `health.training.${kind}`, kind)}
                              {latest ? ` · ${formatPlainDate(latest.trainedOn, locale)}` : ` · ${t("health.staff.none")}`}
                            </Pill>
                          </li>
                        );
                      })}
                    </ul>
                  </td>
                  <td className="r">
                    <span className="flex justify-end gap-1">
                      <button type="button" className="iconbtn" aria-label={t("health.staff.editVerification", { name: r.name })} onClick={() => setEditing(r)} data-testid="verification-edit">
                        <Pencil size={16} aria-hidden="true" />
                      </button>
                      <button type="button" className="iconbtn" aria-label={t("health.staff.addTraining", { name: r.name })} onClick={() => setTraining(r)} data-testid="training-add">
                        <GraduationCap size={16} aria-hidden="true" />
                      </button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <VerificationDialog key={editing?.userId ?? "no-verification"} row={editing} today={today} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); saved(); }} />
      <TrainingDialog key={training?.userId ?? "no-training"} row={training} today={today} onClose={() => setTraining(null)} onSaved={() => { setTraining(null); saved(); }} onRemoved={saved} />
    </>
  );
}

function VerificationDialog({ row, today, onClose, onSaved }: { row: StaffVerification | null; today: string; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<VerificationValues>({
    status: row?.status ?? "PENDING",
    requestedOn: row?.requestedOn ?? "",
    clearedOn: row?.clearedOn ?? "",
    referenceNo: "",
    expiresOn: row?.expiresOn ?? "",
    note: row?.note ?? "",
  });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!row) return;
    if (!form.check(verificationProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: VerificationRequest = { status: v.status, requestedOn: v.requestedOn || null, clearedOn: v.clearedOn || null, referenceNo: v.referenceNo.trim() || null, expiresOn: v.expiresOn || null, note: v.note.trim() || null };
    const ok = await form.submit(t, () => healthApi.saveVerification(row.userId, body).then(() => undefined));
    if (ok) onSaved();
  };
  return (
    <Dialog open={row !== null} onClose={onClose} title={t("health.staff.verificationTitle", { name: row?.name ?? "" })} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="verification-form">
        <FormAlert message={form.formError} />
        <SelectField label={t("health.staff.status")} name="status" value={form.values.status} onChange={(e) => form.set("status", e.target.value as VerificationStatus)} options={VERIFICATION_STATUSES.map((s) => ({ value: s, label: verificationLabel(t, s) }))} />
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t("health.staff.requestedOnField")} name="requestedOn" type="date" max={today} value={form.values.requestedOn} onChange={(e) => form.set("requestedOn", e.target.value)} error={form.errors.requestedOn} />
          <TextField label={t("health.staff.clearedOnField")} name="clearedOn" type="date" max={today} value={form.values.clearedOn} onChange={(e) => form.set("clearedOn", e.target.value)} error={form.errors.clearedOn} required={form.values.status === "CLEARED"} />
          <TextField label={t("health.staff.referenceField")} name="referenceNo" value={form.values.referenceNo} onChange={(e) => form.set("referenceNo", e.target.value)} error={form.errors.referenceNo} maxLength={60} hint={row?.referenceNoMasked ? t("health.staff.referenceOnFile", { masked: row.referenceNoMasked }) : t("health.staff.referenceHint")} />
          <TextField label={t("health.staff.expiresField")} name="expiresOn" type="date" value={form.values.expiresOn} onChange={(e) => form.set("expiresOn", e.target.value)} error={form.errors.expiresOn} />
        </div>
        <TextAreaField label={t("health.staff.note")} name="note" value={form.values.note} onChange={(e) => form.set("note", e.target.value)} error={form.errors.note} maxLength={500} rows={2} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="verification-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function TrainingDialog({ row, today, onClose, onSaved, onRemoved }: { row: StaffVerification | null; today: string; onClose: () => void; onSaved: () => void; onRemoved: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const form = useForm<{ kind: TrainingKind; trainedOn: string; expiresOn: string; provider: string }>({ kind: "POCSO", trainedOn: today, expiresOn: "", provider: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!row) return;
    const problems: Problems = {};
    if (!DATE.test(form.values.trainedOn)) problems.trainedOn = "health.v.date";
    else if (form.values.trainedOn > today) problems.trainedOn = "health.v.futureDate";
    if (form.values.expiresOn && !DATE.test(form.values.expiresOn)) problems.expiresOn = "health.v.date";
    if (!form.check(problems, t, formEl)) return;
    const body: TrainingRequest = { userId: row.userId, kind: form.values.kind, trainedOn: form.values.trainedOn, expiresOn: form.values.expiresOn || null, provider: form.values.provider.trim() || null };
    const ok = await form.submit(t, () => healthApi.addTraining(body).then(() => undefined));
    if (ok) onSaved();
  };
  return (
    <Dialog open={row !== null} onClose={onClose} title={t("health.staff.trainingTitle", { name: row?.name ?? "" })} closeLabel={t("common.close")}>
      {row && row.trainings.length > 0 ? (
        <ul className="rowcards mb-3" data-testid="training-list">
          {row.trainings.map((x) => (
            <li key={x.id} className="rowcard items-center">
              <div className="min-w-0 flex-1 text-[13.5px]">
                <b>{translateOr(t, `health.training.${x.kind}`, x.kind)}</b> · {formatPlainDate(x.trainedOn, locale)}
                {x.expiresOn ? ` · ${t("health.staff.validTill", { date: formatPlainDate(x.expiresOn, locale) })}` : ""}
                {x.provider ? ` · ${x.provider}` : ""}
                {x.needsRenewal ? <Pill tone="warn">{t("health.staff.renew")}</Pill> : null}
              </div>
              <button
                type="button"
                className="iconbtn"
                aria-label={t("common.delete")}
                onClick={async () => {
                  await healthApi.removeTraining(x.id);
                  onRemoved();
                }}
              >
                <Trash2 size={16} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="training-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-2 gap-3">
          <SelectField label={t("health.staff.trainingKind")} name="kind" value={form.values.kind} onChange={(e) => form.set("kind", e.target.value as TrainingKind)} options={TRAINING_KINDS.map((k) => ({ value: k, label: translateOr(t, `health.training.${k}`, k) }))} />
          <TextField label={t("health.staff.trainedOn")} name="trainedOn" type="date" max={today} value={form.values.trainedOn} onChange={(e) => form.set("trainedOn", e.target.value)} error={form.errors.trainedOn} required />
          <TextField label={t("health.staff.expiresField")} name="expiresOn" type="date" value={form.values.expiresOn} onChange={(e) => form.set("expiresOn", e.target.value)} error={form.errors.expiresOn} hint={t("health.staff.trainingExpiryHint")} />
          <TextField label={t("health.staff.provider")} name="provider" value={form.values.provider} onChange={(e) => form.set("provider", e.target.value)} error={form.errors.provider} maxLength={200} />
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="training-save">
            <Plus size={16} aria-hidden="true" />
            {t("health.staff.recordTraining")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
