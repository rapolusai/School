"use client";

import { ArrowLeft, Pencil, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { healthApi } from "@/lib/health-api";
import { localeFor, translateOr, useI18n, type Translate } from "@/lib/i18n";
import type { AllergySeverity, GrowthPoint, GrowthRequest, HealthItem, HealthItemRequest, ItemKind, MedicalRecord, MedicalRecordRequest } from "@/lib/types";
import { ALLERGY_SEVERITIES, HEALTH_BLOOD_GROUPS } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isValidIndianMobile } from "@/lib/validation";
import { AllergyBadges, badgesOf, DATE, HealthNav, numberOrNull, SEVERITY_TONE, severityLabel, useCanManageHealth } from "./health-shared";
import { VisitLine } from "./infirmary-view";

/* ------------------------------------------------------------------ the growth chart */

export type ChartPoint = { x: number; y: number; label: string; value: number };

/**
 * Where each measurement sits on a 100×100 chart: x by age (or by order when the birth date is unknown), y by the
 * value between the series' low and high with a little head room. Pure and exported for tests.
 */
export function chartPoints(points: readonly GrowthPoint[], key: "heightCm" | "weightKg"): ChartPoint[] {
  const dated = points.filter((p) => p[key] !== null);
  if (dated.length === 0) return [];
  const byAge = dated.some((p) => p.ageMonths > 0);
  const xs = dated.map((p, i) => (byAge ? p.ageMonths : i));
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const values = dated.map((p) => p[key] as number);
  const low = Math.min(...values);
  const high = Math.max(...values);
  const pad = Math.max((high - low) * 0.15, key === "heightCm" ? 2 : 1);
  const floor = low - pad;
  const span = high + pad - floor;
  return dated.map((p, i) => ({
    x: maxX === minX ? 50 : ((xs[i] - minX) / (maxX - minX)) * 100,
    y: 100 - ((values[i] - floor) / span) * 100,
    label: p.date,
    value: values[i],
  }));
}

/** An SVG line of height or weight over the child's age. */
export function GrowthChart({ points, series, unit, caption, testId }: { points: readonly GrowthPoint[]; series: "heightCm" | "weightKg"; unit: string; caption: string; testId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const plotted = chartPoints(points, series);
  if (plotted.length === 0) return <p className="empty">{t("health.growth.noData")}</p>;
  const line = plotted.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ");
  return (
    <figure className="chart" data-testid={testId}>
      <div className="chart-frame" aria-hidden="true">
        <svg className="chart-svg" viewBox="0 0 100 100" preserveAspectRatio="none" focusable="false">
          {plotted.length > 1 ? <polyline className="chart-line" points={line} vectorEffect="non-scaling-stroke" /> : null}
        </svg>
        {plotted.map((p) => (
          <span key={p.label} className="chart-dot" style={{ left: `${p.x}%`, top: `${p.y}%` }} title={`${formatPlainDate(p.label, locale)}: ${p.value} ${unit}`} />
        ))}
      </div>
      <div className="chart-xaxis" aria-hidden="true">
        <span>{formatPlainDate(plotted[0].label, locale)}</span>
        <span>{plotted.length > 1 ? formatPlainDate(plotted[plotted.length - 1].label, locale) : ""}</span>
      </div>
      <table className="sr-only">
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{t("reports.col.date")}</th>
            <th scope="col">{unit}</th>
          </tr>
        </thead>
        <tbody>
          {plotted.map((p) => (
            <tr key={p.label}>
              <td>{formatPlainDate(p.label, locale)}</td>
              <td>{p.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}

/* ------------------------------------------------------------------ forms */

export type RecordValues = { bloodGroup: string; doctorName: string; doctorPhone: string; insuranceProvider: string; insurancePolicyNo: string; notes: string };

export function recordProblems(values: RecordValues): Problems {
  const problems: Problems = {};
  const phone = values.doctorPhone.replace(/[\s-]/g, "");
  if (phone && !isValidIndianMobile(phone)) problems.doctorPhone = "health.v.phone";
  return problems;
}

export type ItemValues = { kind: ItemKind; name: string; detail: string; severity: AllergySeverity | ""; itemDate: string };

/** Client-side checks mirroring ItemForm on the API: an allergy needs a severity, a vaccination a date. */
export function itemProblems(values: ItemValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "health.v.name";
  if (values.kind === "ALLERGY" && !values.severity) problems.severity = "health.v.severity";
  if (values.kind === "VACCINATION" && !DATE.test(values.itemDate)) problems.itemDate = "health.v.date";
  if (values.itemDate && !DATE.test(values.itemDate)) problems.itemDate = "health.v.date";
  return problems;
}

export type GrowthValues = { measuredOn: string; heightCm: string; weightKg: string };

export function growthProblems(values: GrowthValues, today: string): Problems {
  const problems: Problems = {};
  if (!DATE.test(values.measuredOn)) problems.measuredOn = "health.v.date";
  else if (values.measuredOn > today) problems.measuredOn = "health.v.futureDate";
  const h = numberOrNull(values.heightCm);
  const w = numberOrNull(values.weightKg);
  if (h === null && w === null) problems.heightCm = "health.v.growth";
  if (h !== null && (Number.isNaN(h) || h < 30 || h > 250)) problems.heightCm = "health.v.height";
  if (w !== null && (Number.isNaN(w) || w < 1 || w > 250)) problems.weightKg = "health.v.weight";
  return problems;
}

export function itemBody(v: ItemValues): HealthItemRequest {
  return {
    kind: v.kind,
    name: v.name.trim(),
    detail: v.detail.trim() || null,
    severity: v.kind === "ALLERGY" ? (v.severity || null) : null,
    itemDate: v.itemDate || null,
  };
}

/** The add/edit dialog for an allergy, condition, medication or vaccination; `kinds` limits what the parent may add. */
export function ItemDialog({
  item,
  kinds,
  open,
  onClose,
  onSave,
}: {
  item: HealthItem | null;
  kinds: readonly ItemKind[];
  open: boolean;
  onClose: () => void;
  onSave: (body: HealthItemRequest) => Promise<void>;
}) {
  const { t } = useI18n();
  const form = useForm<ItemValues>(
    item
      ? { kind: item.kind, name: item.name, detail: item.detail ?? "", severity: item.severity ?? "", itemDate: item.itemDate ?? "" }
      : { kind: kinds[0], name: "", detail: "", severity: "", itemDate: "" },
  );
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(itemProblems(form.values), t, formEl)) return;
    const ok = await form.submit(t, () => onSave(itemBody(form.values)));
    if (ok) onClose();
  };
  return (
    <Dialog open={open} onClose={onClose} title={item ? t("health.item.edit") : t("health.item.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="item-form">
        <FormAlert message={form.formError} />
        <SelectField label={t("health.item.kind")} name="kind" value={form.values.kind} onChange={(e) => form.set("kind", e.target.value as ItemKind)} options={kinds.map((k) => ({ value: k, label: translateOr(t, `health.kind.${k}`, k) }))} disabled={item !== null} error={form.errors.kind} />
        <TextField label={t("health.item.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={120} required />
        {form.values.kind === "ALLERGY" ? (
          <SelectField label={t("health.item.severity")} name="severity" value={form.values.severity} onChange={(e) => form.set("severity", e.target.value as AllergySeverity | "")} options={[{ value: "", label: t("health.item.pickSeverity") }, ...ALLERGY_SEVERITIES.map((s) => ({ value: s, label: severityLabel(t, s) }))]} error={form.errors.severity} required />
        ) : null}
        <TextField label={form.values.kind === "VACCINATION" ? t("health.item.givenOn") : t("health.item.since")} name="itemDate" type="date" value={form.values.itemDate} onChange={(e) => form.set("itemDate", e.target.value)} error={form.errors.itemDate} required={form.values.kind === "VACCINATION"} />
        <TextAreaField label={t("health.item.detail")} name="detail" value={form.values.detail} onChange={(e) => form.set("detail", e.target.value)} error={form.errors.detail} maxLength={500} rows={2} hint={t("health.item.detailHint")} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="item-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** One kind's entries with the add, edit and remove controls when the viewer may keep them. */
export function ItemList({
  kind,
  items,
  canEdit,
  onAdd,
  onEdit,
  onRemove,
}: {
  kind: ItemKind;
  items: readonly HealthItem[];
  canEdit: boolean;
  onAdd?: () => void;
  onEdit?: (item: HealthItem) => void;
  onRemove?: (item: HealthItem) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <section className="card" data-testid={`items-${kind}`}>
      <div className="card-head">
        <h2>{translateOr(t, `health.kindPlural.${kind}`, kind)}</h2>
        {canEdit && onAdd ? (
          <button type="button" className="btn btn-sm" onClick={onAdd} data-testid={`add-${kind}`}>
            <Plus size={14} aria-hidden="true" />
            {t("health.add")}
          </button>
        ) : null}
      </div>
      {items.length === 0 ? (
        <p className="empty">{t("health.item.none")}</p>
      ) : (
        <ul className="rowcards">
          {items.map((item) => (
            <li key={item.id} className="rowcard items-start" data-testid="health-item">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold">{item.name}</span>
                  {item.severity ? <Pill tone={SEVERITY_TONE[item.severity]}>{severityLabel(t, item.severity)}</Pill> : null}
                  <Pill tone="neutral">{translateOr(t, `health.source.${item.source}`, item.source)}</Pill>
                </div>
                {item.detail ? <p className="text-[13px] text-ink-2">{item.detail}</p> : null}
                <p className="text-[12.5px] text-ink-3">
                  {item.itemDate ? formatPlainDate(item.itemDate, locale) : ""}
                  {item.addedByName ? `${item.itemDate ? " · " : ""}${t("health.item.by", { name: item.addedByName })}` : ""}
                </p>
              </div>
              {canEdit && onEdit && onRemove ? (
                <div className="flex gap-1">
                  <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => onEdit(item)}>
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                  <button type="button" className="iconbtn" aria-label={t("common.delete")} onClick={() => onRemove(item)}>
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The consent block: first aid and hospital, who gave it and when. */
export function ConsentCard({ record, canEdit, onSave }: { record: MedicalRecord; canEdit: boolean; onSave: (firstAid: boolean, hospital: boolean) => Promise<void> }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [saving, setSaving] = useState(false);
  const c = record.consent;
  const toggle = async (firstAid: boolean, hospital: boolean) => {
    setSaving(true);
    try {
      await onSave(firstAid, hospital);
    } finally {
      setSaving(false);
    }
  };
  return (
    <section className="card" data-testid="consent-card">
      <div className="card-head">
        <h2>{t("health.consent.title")}</h2>
      </div>
      <p className="mb-2 text-[13px] text-ink-3">{t("health.consent.intro")}</p>
      <div className="flex flex-col gap-2">
        <label className="check">
          <input type="checkbox" name="firstAid" checked={c.firstAid} disabled={!canEdit || saving} onChange={(e) => toggle(e.target.checked, c.hospital)} />
          <span>{t("health.consent.firstAid")}</span>
        </label>
        <label className="check">
          <input type="checkbox" name="hospital" checked={c.hospital} disabled={!canEdit || saving} onChange={(e) => toggle(c.firstAid, e.target.checked)} />
          <span>{t("health.consent.hospital")}</span>
        </label>
      </div>
      {c.byName ? <p className="mt-2 text-[12.5px] text-ink-3">{t("health.consent.by", { name: c.byName, date: c.at ? formatPlainDate(c.at.slice(0, 10), locale) : "" })}</p> : null}
    </section>
  );
}

/** Blood group, doctor, insurance (masked), emergency contact. */
function headLines(t: Translate, r: MedicalRecord): [string, string][] {
  return [
    [t("health.record.bloodGroup"), r.bloodGroup ?? "—"],
    [t("health.record.doctor"), r.doctorName ? `${r.doctorName}${r.doctorPhone ? ` · ${r.doctorPhone}` : ""}` : "—"],
    [t("health.record.insurance"), r.insuranceProvider ? `${r.insuranceProvider}${r.insurancePolicyNoMasked ? ` · ${r.insurancePolicyNoMasked}` : ""}` : "—"],
    [t("health.record.emergency"), r.emergencyContact ? `${r.emergencyContact.name} · ${r.emergencyContact.phoneMasked}` : "—"],
  ];
}

/* ------------------------------------------------------------------ the page */

/** /app/health/students/[id]: the student's medical record for the nurse, leadership and the class teacher. */
export function MedicalRecordView({ studentId }: { studentId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageHealth();
  const [today] = useState(() => todayInIndia());
  const record = useApiData(`health:record:${studentId}`, () => healthApi.record(studentId, "student_medical"));
  const [editingHead, setEditingHead] = useState(false);
  const [item, setItem] = useState<{ kind: ItemKind; item: HealthItem | null } | null>(null);
  const [removing, setRemoving] = useState<HealthItem | null>(null);
  const [addingGrowth, setAddingGrowth] = useState(false);
  const r = record.data;
  const back = (
    <Link href="/app/health" className="link inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("health.record.back")}
    </Link>
  );
  if (record.error && !r) {
    return (
      <>
        {back}
        <section className="card">{record.error.status === 404 ? <p className="empty">{t("health.record.notFound")}</p> : <ErrorState error={record.error} onRetry={record.reload} />}</section>
      </>
    );
  }
  if (!r) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      </>
    );
  }
  const saved = () => {
    toast(t("health.record.saved"));
    record.reload();
  };
  return (
    <>
      {back}
      <PageHead
        eyebrow={t("health.record.eyebrow")}
        title={
          <span className="flex flex-wrap items-center gap-2">
            {r.studentName}
            {r.className ? <span className="text-[14px] font-normal text-ink-3">{r.className}</span> : null}
          </span>
        }
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditingHead(true)} data-testid="record-edit">
              <Pencil size={18} aria-hidden="true" />
              {t("health.record.edit")}
            </button>
          ) : undefined
        }
      />
      <HealthNav />
      <AllergyBadges badges={badgesOf(r.studentId, r.allergies)} testId="record-allergy-badges" />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <section className="card" data-testid="record-head">
          <div className="card-head">
            <h2>{t("health.record.title")}</h2>
          </div>
          <dl className="kv">
            {headLines(t, r).map(([k, v]) => (
              <div key={k}>
                <dt>{k}</dt>
                <dd>{v}</dd>
              </div>
            ))}
            {r.dateOfBirth ? (
              <div>
                <dt>{t("health.record.dateOfBirth")}</dt>
                <dd>{formatPlainDate(r.dateOfBirth, locale)}</dd>
              </div>
            ) : null}
          </dl>
          {r.notes ? <p className="mt-2 text-[13px] text-ink-2">{r.notes}</p> : null}
        </section>
        <ConsentCard record={r} canEdit={canManage} onSave={async (firstAid, hospital) => { await healthApi.saveConsent(studentId, { firstAid, hospital }); saved(); }} />
        {(["ALLERGY", "CONDITION", "MEDICATION", "VACCINATION"] as const).map((kind) => (
          <ItemList
            key={kind}
            kind={kind}
            items={r[kind === "ALLERGY" ? "allergies" : kind === "CONDITION" ? "conditions" : kind === "MEDICATION" ? "medications" : "vaccinations"]}
            canEdit={canManage}
            onAdd={() => setItem({ kind, item: null })}
            onEdit={(i) => setItem({ kind, item: i })}
            onRemove={setRemoving}
          />
        ))}
        <section className="card lg:col-span-2" data-testid="growth-card">
          <div className="card-head">
            <h2>{t("health.growth.title")}</h2>
            {canManage ? (
              <button type="button" className="btn btn-sm" onClick={() => setAddingGrowth(true)} data-testid="add-growth">
                <Plus size={14} aria-hidden="true" />
                {t("health.growth.add")}
              </button>
            ) : null}
          </div>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div>
              <h3 className="mb-1 text-[13px] font-semibold text-ink-2">{t("health.growth.height")}</h3>
              <GrowthChart points={r.chart.points} series="heightCm" unit="cm" caption={t("health.growth.height")} testId="height-chart" />
            </div>
            <div>
              <h3 className="mb-1 text-[13px] font-semibold text-ink-2">{t("health.growth.weight")}</h3>
              <GrowthChart points={r.chart.points} series="weightKg" unit="kg" caption={t("health.growth.weight")} testId="weight-chart" />
            </div>
          </div>
          {r.growth.length > 0 ? (
            <div className="table-wrap mt-3">
              <table className="table">
                <caption className="sr-only">{t("health.growth.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("reports.col.date")}</th>
                    <th scope="col" className="r">
                      {t("health.growth.height")}
                    </th>
                    <th scope="col" className="r">
                      {t("health.growth.weight")}
                    </th>
                    <th scope="col">{t("health.growth.by")}</th>
                    {canManage ? <th scope="col" className="sr-only">{t("common.actions")}</th> : null}
                  </tr>
                </thead>
                <tbody>
                  {r.growth.map((g) => (
                    <tr key={g.id} data-testid="growth-row">
                      <td>{formatPlainDate(g.measuredOn, locale)}</td>
                      <td className="num r">{g.heightCm ?? "—"}</td>
                      <td className="num r">{g.weightKg ?? "—"}</td>
                      <td>{g.recordedByName ?? "—"}</td>
                      {canManage ? (
                        <td className="r">
                          <button
                            type="button"
                            className="iconbtn"
                            aria-label={t("common.delete")}
                            onClick={async () => {
                              await healthApi.removeGrowth(studentId, g.id);
                              saved();
                            }}
                          >
                            <Trash2 size={16} aria-hidden="true" />
                          </button>
                        </td>
                      ) : null}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </section>
        <section className="card lg:col-span-2" data-testid="record-visits">
          <div className="card-head">
            <h2>{t("health.record.visits")}</h2>
          </div>
          {r.visits.length === 0 ? (
            <p className="empty">{t("health.record.noVisits")}</p>
          ) : (
            <ul className="rowcards">
              {r.visits.map((v) => (
                <VisitLine key={v.id} visit={v} locale={locale} />
              ))}
            </ul>
          )}
        </section>
      </div>
      <RecordDialog key={editingHead ? "edit" : "closed"} record={r} open={editingHead} onClose={() => setEditingHead(false)} onSaved={() => { setEditingHead(false); saved(); }} />
      <ItemDialog
        key={item ? `${item.kind}:${item.item?.id ?? "new"}` : "no-item"}
        item={item?.item ?? null}
        kinds={item ? [item.kind] : ["ALLERGY"]}
        open={item !== null}
        onClose={() => setItem(null)}
        onSave={async (body) => {
          if (item?.item) await healthApi.updateItem(studentId, item.item.id, body);
          else await healthApi.addItem(studentId, body);
          saved();
        }}
      />
      <GrowthDialog key={addingGrowth ? "growth" : "no-growth"} today={today} open={addingGrowth} onClose={() => setAddingGrowth(false)} onSave={async (body) => { await healthApi.addGrowth(studentId, body); saved(); }} />
      <ConfirmDialog
        open={removing !== null}
        title={t("health.item.removeTitle")}
        body={removing ? t("health.item.removeBody", { name: removing.name }) : ""}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          if (!removing) return;
          await healthApi.removeItem(studentId, removing.id);
          setRemoving(null);
          saved();
        }}
        onClose={() => setRemoving(null)}
      />
    </>
  );
}

function RecordDialog({ record, open, onClose, onSaved }: { record: MedicalRecord; open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<RecordValues>({
    bloodGroup: record.bloodGroup ?? "",
    doctorName: record.doctorName ?? "",
    doctorPhone: record.doctorPhone ?? "",
    insuranceProvider: record.insuranceProvider ?? "",
    insurancePolicyNo: "",
    notes: record.notes ?? "",
  });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(recordProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: MedicalRecordRequest = {
      bloodGroup: v.bloodGroup || null,
      doctorName: v.doctorName.trim() || null,
      doctorPhone: v.doctorPhone.replace(/[\s-]/g, "") || null,
      insuranceProvider: v.insuranceProvider.trim() || null,
      // Blank keeps the number already on file; the full number is never shown back.
      insurancePolicyNo: v.insurancePolicyNo.trim() || null,
      notes: v.notes.trim() || null,
    };
    const ok = await form.submit(t, () => healthApi.saveRecord(record.studentId, body).then(() => undefined));
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={t("health.record.edit")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="record-form">
        <FormAlert message={form.formError} />
        <p className="text-[12.5px] text-ink-3">{t("health.record.minimum")}</p>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <SelectField label={t("health.record.bloodGroup")} name="bloodGroup" value={form.values.bloodGroup} onChange={(e) => form.set("bloodGroup", e.target.value)} options={[{ value: "", label: t("health.record.unknown") }, ...HEALTH_BLOOD_GROUPS.map((g) => ({ value: g, label: g }))]} error={form.errors.bloodGroup} />
          <TextField label={t("health.record.doctorName")} name="doctorName" value={form.values.doctorName} onChange={(e) => form.set("doctorName", e.target.value)} error={form.errors.doctorName} maxLength={200} />
          <TextField label={t("health.record.doctorPhone")} name="doctorPhone" type="tel" inputMode="numeric" value={form.values.doctorPhone} onChange={(e) => form.set("doctorPhone", e.target.value)} error={form.errors.doctorPhone} maxLength={20} />
          <TextField label={t("health.record.insuranceProvider")} name="insuranceProvider" value={form.values.insuranceProvider} onChange={(e) => form.set("insuranceProvider", e.target.value)} error={form.errors.insuranceProvider} maxLength={200} />
          <TextField label={t("health.record.policyNo")} name="insurancePolicyNo" value={form.values.insurancePolicyNo} onChange={(e) => form.set("insurancePolicyNo", e.target.value)} error={form.errors.insurancePolicyNo} maxLength={40} hint={record.insurancePolicyNoMasked ? t("health.record.policyOnFile", { masked: record.insurancePolicyNoMasked }) : undefined} />
        </div>
        <TextAreaField label={t("health.record.notes")} name="notes" value={form.values.notes} onChange={(e) => form.set("notes", e.target.value)} error={form.errors.notes} maxLength={1000} rows={2} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="record-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function GrowthDialog({ today, open, onClose, onSave }: { today: string; open: boolean; onClose: () => void; onSave: (body: GrowthRequest) => Promise<void> }) {
  const { t } = useI18n();
  const form = useForm<GrowthValues>({ measuredOn: today, heightCm: "", weightKg: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(growthProblems(form.values, today), t, formEl)) return;
    const ok = await form.submit(t, () => onSave({ measuredOn: form.values.measuredOn, heightCm: numberOrNull(form.values.heightCm), weightKg: numberOrNull(form.values.weightKg) }));
    if (ok) onClose();
  };
  return (
    <Dialog open={open} onClose={onClose} title={t("health.growth.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="growth-form">
        <FormAlert message={form.formError} />
        <TextField label={t("health.growth.measuredOn")} name="measuredOn" type="date" max={today} value={form.values.measuredOn} onChange={(e) => form.set("measuredOn", e.target.value)} error={form.errors.measuredOn} required />
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t("health.growth.heightCm")} name="heightCm" type="number" inputMode="decimal" step="0.1" min={30} max={250} value={form.values.heightCm} onChange={(e) => form.set("heightCm", e.target.value)} error={form.errors.heightCm} />
          <TextField label={t("health.growth.weightKg")} name="weightKg" type="number" inputMode="decimal" step="0.1" min={1} max={250} value={form.values.weightKg} onChange={(e) => form.set("weightKg", e.target.value)} error={form.errors.weightKg} />
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="growth-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
