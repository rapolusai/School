"use client";

import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { HEAD_CODE_PATTERN, gstAt, GST_RATES } from "@/lib/accounts";
import { accountsApi } from "@/lib/accounts-api";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatPaise, formatPlainDate, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import { formatMonth } from "@/lib/payroll";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { AccountsSettingsView, BudgetMode, CostCentreView, HeadKind, HeadView, RecurringView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { AccountsNav, ManagementNote, useCostCentres, useHeads, useVendors } from "./accounts-ui";

type Tab = "heads" | "centres" | "recurring" | "settings";

type HeadValues = { code: string; name: string; kind: HeadKind; parentId: string; gstApplicable: boolean; active: boolean; displayOrder: string };
type CentreValues = { code: string; name: string; active: boolean };
type SettingsValues = { budgetMode: BudgetMode; donation80gRegistered: boolean; donation80gNumber: string; pettyCashFloat: string };
type RecurringValues = { name: string; headId: string; costCentreId: string; vendorId: string; amount: string; gstRate: string; gst: string; dayOfMonth: string; description: string; active: boolean };

/** The same checks the head endpoints make. Exported for tests. */
export function validateHead(values: HeadValues): Problems {
  const problems: Problems = {};
  if (!HEAD_CODE_PATTERN.test(values.code.trim())) problems.code = "accounts.v.code";
  if (!values.name.trim()) problems.name = "accounts.v.name";
  const order = Number(values.displayOrder);
  if (!Number.isInteger(order) || order < 0 || order > 999) problems.displayOrder = "accounts.v.order";
  return problems;
}

export function validateCentre(values: CentreValues): Problems {
  const problems: Problems = {};
  if (!HEAD_CODE_PATTERN.test(values.code.trim())) problems.code = "accounts.v.code";
  if (!values.name.trim()) problems.name = "accounts.v.name";
  return problems;
}

export function validateSettings(values: SettingsValues): Problems {
  const problems: Problems = {};
  const float = parseRupees(values.pettyCashFloat);
  if (float === null || float < 0) problems.pettyCashFloat = "accounts.v.amountOrZero";
  if (values.donation80gRegistered && !values.donation80gNumber.trim()) problems.donation80gNumber = "accounts.v.eightyG";
  return problems;
}

export function validateRecurring(values: RecurringValues, gstApplicable: boolean): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "accounts.v.name";
  if (!values.headId) problems.headId = "accounts.v.head";
  const amount = parseRupees(values.amount);
  if (amount === null || amount <= 0) problems.amount = "accounts.v.amount";
  const gst = values.gst.trim() === "" ? 0 : parseRupees(values.gst);
  if (gst === null) problems.gst = "accounts.v.gst";
  else if (gst > 0 && !gstApplicable) problems.gst = "accounts.v.gstHead";
  else if (amount !== null && gst > amount) problems.gst = "accounts.v.gstTooMuch";
  const day = Number(values.dayOfMonth);
  if (!Number.isInteger(day) || day < 1 || day > 28) problems.dayOfMonth = "accounts.v.dayOfMonth";
  return problems;
}

/** /app/accounts/setup: the chart of heads, cost centres, recurring expenses, and the settings with approvers. */
export function SetupView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const [tab, setTab] = useState<Tab>("heads");
  const tabs: { key: Tab; label: string }[] = [
    { key: "heads", label: t("accounts.setup.tabHeads") },
    { key: "centres", label: t("accounts.setup.tabCentres") },
    { key: "recurring", label: t("accounts.setup.tabRecurring") },
    { key: "settings", label: t("accounts.setup.tabSettings") },
  ];
  return (
    <>
      <PageHead eyebrow={t("accounts.eyebrow")} title={t("accounts.setup.title")} />
      <AccountsNav />
      <ManagementNote />
      <div className="subnav no-print" role="tablist" aria-label={t("accounts.setup.title")}>
        {tabs.map((item) => (
          <button key={item.key} type="button" role="tab" aria-selected={tab === item.key} aria-current={tab === item.key ? "page" : undefined} onClick={() => setTab(item.key)} data-testid={`setup-tab-${item.key}`}>
            {item.label}
          </button>
        ))}
      </div>
      {tab === "heads" ? <HeadsTab canManage={canManage} /> : tab === "centres" ? <CentresTab canManage={canManage} /> : tab === "recurring" ? <RecurringTab canManage={canManage} /> : <SettingsTab canManage={canManage} />}
    </>
  );
}

/* ------------------------------------------------------------------ heads */

function HeadsTab({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const heads = useHeads();
  const [editing, setEditing] = useState<HeadView | null | HeadKind>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const data = heads.data;
  const addStandard = async () => {
    setBusy(true);
    setError(null);
    try {
      await accountsApi.addStandardHeads();
      toast(t("accounts.setup.standardAdded"));
      heads.reload();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  const kinds: HeadKind[] = ["INCOME", "EXPENSE"];
  return (
    <>
      <FormAlert message={error} />
      {kinds.map((kind) => {
        const rows = (data ?? []).filter((h) => h.kind === kind);
        return (
          <section key={kind} className="card" aria-labelledby={`heads-${kind}`}>
            <div className="card-head">
              <div>
                <h2 id={`heads-${kind}`}>{kind === "INCOME" ? t("accounts.setup.incomeHeads") : t("accounts.setup.expenseHeads")}</h2>
                {kind === "INCOME" ? <p className="mt-1 text-sm text-ink-2">{t("accounts.setup.feeHeadsNote")}</p> : null}
              </div>
              {canManage ? (
                <div className="flex flex-wrap gap-2">
                  {kind === "INCOME" ? (
                    <button type="button" className="btn" onClick={addStandard} disabled={busy} data-testid="heads-standard">
                      {busy ? t("common.working") : t("accounts.setup.addStandard")}
                    </button>
                  ) : null}
                  <button type="button" className="btn btn-primary" onClick={() => setEditing(kind)} data-testid={`head-new-${kind.toLowerCase()}`}>
                    <Plus size={18} aria-hidden="true" />
                    {t("accounts.setup.addHead")}
                  </button>
                </div>
              ) : null}
            </div>
            {heads.error && !data ? (
              <ErrorState error={heads.error} onRetry={heads.reload} />
            ) : !data ? (
              <LoadingRows rows={4} />
            ) : rows.length === 0 ? (
              <p className="empty">{t("accounts.setup.noHeads")}</p>
            ) : (
              <div className="table-wrap">
                <table className="table" data-testid={`heads-${kind.toLowerCase()}`}>
                  <thead>
                    <tr>
                      <th scope="col">{t("accounts.col.code")}</th>
                      <th scope="col">{t("accounts.col.name")}</th>
                      <th scope="col" className="hidden sm:table-cell">
                        {t("accounts.setup.parent")}
                      </th>
                      <th scope="col" className="hidden md:table-cell">
                        {t("accounts.setup.gst")}
                      </th>
                      <th scope="col">{t("accounts.col.status")}</th>
                      {canManage ? <th scope="col" className="r">{t("accounts.col.actions")}</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((h) => (
                      <tr key={h.id}>
                        <td className="mono">{h.code}</td>
                        <td>
                          {h.parentId ? <span className="text-ink-3">↳ </span> : null}
                          {h.name}
                          {h.feeHeadId ? <span className="ml-1 text-[12px] text-ink-3">{t("accounts.setup.fromFees")}</span> : null}
                        </td>
                        <td className="hidden sm:table-cell">{h.parentName ?? "—"}</td>
                        <td className="hidden md:table-cell">{h.gstApplicable ? t("accounts.setup.gstYes") : "—"}</td>
                        <td>
                          <Pill tone={h.active ? "good" : "neutral"} dot>
                            {h.active ? t("accounts.active") : t("accounts.inactive")}
                          </Pill>
                        </td>
                        {canManage ? (
                          <td className="r">
                            <button type="button" className="iconbtn" aria-label={t("accounts.setup.editHead", { name: h.name })} onClick={() => setEditing(h)}>
                              <Pencil size={16} aria-hidden="true" />
                            </button>
                          </td>
                        ) : null}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        );
      })}
      {editing ? (
        <HeadDialog
          head={typeof editing === "string" ? null : editing}
          kind={typeof editing === "string" ? editing : editing.kind}
          parents={(data ?? []).filter((h) => !h.parentId && h.kind === (typeof editing === "string" ? editing : editing.kind) && (typeof editing === "string" || h.id !== editing.id))}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("accounts.setup.headSaved"));
            heads.reload();
          }}
        />
      ) : null}
    </>
  );
}

function HeadDialog({ head, kind, parents, onClose, onSaved }: { head: HeadView | null; kind: HeadKind; parents: HeadView[]; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<HeadValues>({
    code: head?.code ?? "",
    name: head?.name ?? "",
    kind,
    parentId: head?.parentId ?? "",
    gstApplicable: head?.gstApplicable ?? false,
    active: head?.active ?? true,
    displayOrder: String(head?.displayOrder ?? 100),
  });
  const system = Boolean(head?.systemCode || head?.feeHeadId);
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateHead(form.values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      const body = {
        code: form.values.code.trim().toUpperCase(),
        name: form.values.name.trim(),
        kind,
        parentId: form.values.parentId || null,
        gstApplicable: form.values.gstApplicable,
        active: form.values.active,
        displayOrder: Number(form.values.displayOrder),
      };
      if (head) await accountsApi.updateHead(head.id, body);
      else await accountsApi.createHead(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open onClose={onClose} title={head ? t("accounts.setup.editHeadTitle") : t("accounts.setup.addHeadTitle")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="head-form">
        <FormAlert message={form.formError} />
        <div className="grid gap-3.5 sm:grid-cols-2">
          <TextField label={t("accounts.col.code")} name="code" value={form.values.code} onChange={(e) => form.set("code", e.target.value.toUpperCase())} error={form.errors.code} maxLength={20} required disabled={system} data-autofocus hint={t("accounts.v.code")} />
          <TextField label={t("accounts.col.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={80} required />
          <SelectField label={t("accounts.setup.parent")} name="parentId" value={form.values.parentId} onChange={(e) => form.set("parentId", e.target.value)} options={[{ value: "", label: t("accounts.setup.noParent") }, ...parents.map((p) => ({ value: p.id, label: `${p.code} · ${p.name}` }))]} disabled={system} />
          <TextField label={t("accounts.setup.order")} name="displayOrder" inputMode="numeric" value={form.values.displayOrder} onChange={(e) => form.set("displayOrder", e.target.value)} error={form.errors.displayOrder} />
        </div>
        <label className="check">
          <input type="checkbox" name="gstApplicable" checked={form.values.gstApplicable} onChange={(e) => form.set("gstApplicable", e.target.checked)} />
          <span>{t("accounts.setup.gstApplicable")}</span>
        </label>
        {head ? (
          <label className="check">
            <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
            <span>{t("accounts.active")}</span>
          </label>
        ) : null}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ cost centres */

function CentresTab({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const centres = useCostCentres();
  const [editing, setEditing] = useState<CostCentreView | null | "new">(null);
  const [deleting, setDeleting] = useState<CostCentreView | null>(null);
  const data = centres.data;
  return (
    <>
      <section className="card" aria-labelledby="centres-title">
        <div className="card-head">
          <div>
            <h2 id="centres-title">{t("accounts.setup.centres")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("accounts.setup.centresSub")}</p>
          </div>
          {canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="centre-new">
              <Plus size={18} aria-hidden="true" />
              {t("accounts.setup.addCentre")}
            </button>
          ) : null}
        </div>
        {centres.error && !data ? (
          <ErrorState error={centres.error} onRetry={centres.reload} />
        ) : !data ? (
          <LoadingRows rows={3} />
        ) : data.length === 0 ? (
          <p className="empty">{t("accounts.setup.noCentres")}</p>
        ) : (
          <div className="table-wrap">
            <table className="table" data-testid="centres-table">
              <thead>
                <tr>
                  <th scope="col">{t("accounts.col.code")}</th>
                  <th scope="col">{t("accounts.col.name")}</th>
                  <th scope="col">{t("accounts.col.status")}</th>
                  {canManage ? <th scope="col" className="r">{t("accounts.col.actions")}</th> : null}
                </tr>
              </thead>
              <tbody>
                {data.map((c) => (
                  <tr key={c.id}>
                    <td className="mono">{c.code}</td>
                    <td>{c.name}</td>
                    <td>
                      <Pill tone={c.active ? "good" : "neutral"} dot>
                        {c.active ? t("accounts.active") : t("accounts.inactive")}
                      </Pill>
                    </td>
                    {canManage ? (
                      <td className="r whitespace-nowrap">
                        <button type="button" className="iconbtn" aria-label={t("accounts.setup.editCentre", { name: c.name })} onClick={() => setEditing(c)}>
                          <Pencil size={16} aria-hidden="true" />
                        </button>
                        {!c.inUse ? (
                          <button type="button" className="iconbtn" aria-label={t("accounts.setup.deleteCentre", { name: c.name })} onClick={() => setDeleting(c)}>
                            <Trash2 size={16} aria-hidden="true" />
                          </button>
                        ) : null}
                      </td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {editing ? (
        <CentreDialog
          centre={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("accounts.setup.centreSaved"));
            centres.reload();
          }}
        />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          open
          title={t("accounts.setup.deleteCentreTitle")}
          body={t("accounts.setup.deleteCentreBody", { name: deleting.name })}
          confirmLabel={t("common.delete")}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await accountsApi.deleteCostCentre(deleting.id);
            setDeleting(null);
            toast(t("common.deleted"));
            centres.reload();
          }}
        />
      ) : null}
    </>
  );
}

function CentreDialog({ centre, onClose, onSaved }: { centre: CostCentreView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<CentreValues>({ code: centre?.code ?? "", name: centre?.name ?? "", active: centre?.active ?? true });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateCentre(form.values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      const body = { code: form.values.code.trim().toUpperCase(), name: form.values.name.trim(), active: form.values.active };
      if (centre) await accountsApi.updateCostCentre(centre.id, body);
      else await accountsApi.createCostCentre(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open onClose={onClose} title={centre ? t("accounts.setup.editCentreTitle") : t("accounts.setup.addCentreTitle")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="centre-form">
        <FormAlert message={form.formError} />
        <TextField label={t("accounts.col.code")} name="code" value={form.values.code} onChange={(e) => form.set("code", e.target.value.toUpperCase())} error={form.errors.code} maxLength={20} required data-autofocus hint={t("accounts.v.code")} />
        <TextField label={t("accounts.col.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={80} required />
        {centre ? (
          <label className="check">
            <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
            <span>{t("accounts.active")}</span>
          </label>
        ) : null}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ recurring expenses */

function RecurringTab({ canManage }: { canManage: boolean }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const list = useApiData("accounts:recurring", accountsApi.recurring);
  const [editing, setEditing] = useState<RecurringView | null | "new">(null);
  const [deleting, setDeleting] = useState<RecurringView | null>(null);
  const [month, setMonth] = useState(todayInIndia().slice(0, 7));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const data = list.data;
  const generate = async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await accountsApi.generateRecurring(month);
      toast(t("accounts.recurring.generated", { created: result.created, skipped: result.skipped, month: formatMonth(result.month, locale) }));
      list.reload();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <section className="card" aria-labelledby="recurring-title">
        <div className="card-head">
          <div>
            <h2 id="recurring-title">{t("accounts.recurring.title")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("accounts.recurring.sub")}</p>
          </div>
          {canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="recurring-new">
              <Plus size={18} aria-hidden="true" />
              {t("accounts.recurring.add")}
            </button>
          ) : null}
        </div>
        {canManage ? (
          <form method="post" className="toolbar no-print" onSubmit={(e) => { e.preventDefault(); generate(); }} aria-label={t("accounts.recurring.generate")}>
            <label className="field w-full sm:w-auto">
              <span className="field-label">{t("accounts.col.month")}</span>
              <input type="month" className="input" name="month" value={month} max={todayInIndia().slice(0, 7)} onChange={(e) => setMonth(e.target.value)} required />
            </label>
            <button type="submit" className="btn self-end" disabled={busy || !data || data.length === 0} data-testid="recurring-generate">
              {busy ? t("common.working") : t("accounts.recurring.generate")}
            </button>
          </form>
        ) : null}
        <FormAlert message={error} />
        {list.error && !data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !data ? (
          <LoadingRows rows={3} />
        ) : data.length === 0 ? (
          <p className="empty" data-testid="recurring-empty">
            {t("accounts.recurring.empty")}
          </p>
        ) : (
          <div className="table-wrap">
            <table className="table" data-testid="recurring-table">
              <thead>
                <tr>
                  <th scope="col">{t("accounts.col.name")}</th>
                  <th scope="col" className="hidden sm:table-cell">
                    {t("accounts.col.head")}
                  </th>
                  <th scope="col" className="hidden md:table-cell">
                    {t("accounts.col.vendor")}
                  </th>
                  <th scope="col" className="r">
                    {t("accounts.col.amount")}
                  </th>
                  <th scope="col" className="r hidden sm:table-cell">
                    {t("accounts.recurring.day")}
                  </th>
                  <th scope="col" className="hidden md:table-cell">
                    {t("accounts.recurring.lastGenerated")}
                  </th>
                  <th scope="col">{t("accounts.col.status")}</th>
                  {canManage ? <th scope="col" className="r">{t("accounts.col.actions")}</th> : null}
                </tr>
              </thead>
              <tbody>
                {data.map((r) => (
                  <tr key={r.id}>
                    <td className="font-semibold">{r.name}</td>
                    <td className="hidden sm:table-cell">{r.headName}</td>
                    <td className="hidden md:table-cell">{r.vendorName ?? "—"}</td>
                    <td className="r num">{formatPaise(r.amountPaise + r.gstPaise)}</td>
                    <td className="r num hidden sm:table-cell">{r.dayOfMonth}</td>
                    <td className="hidden md:table-cell">{r.lastGeneratedMonth ? formatMonth(r.lastGeneratedMonth.slice(0, 7), locale) : "—"}</td>
                    <td>
                      <Pill tone={r.active ? "good" : "neutral"} dot>
                        {r.active ? t("accounts.active") : t("accounts.inactive")}
                      </Pill>
                    </td>
                    {canManage ? (
                      <td className="r whitespace-nowrap">
                        <button type="button" className="iconbtn" aria-label={t("accounts.recurring.editFor", { name: r.name })} onClick={() => setEditing(r)}>
                          <Pencil size={16} aria-hidden="true" />
                        </button>
                        <button type="button" className="iconbtn" aria-label={t("accounts.recurring.deleteFor", { name: r.name })} onClick={() => setDeleting(r)}>
                          <Trash2 size={16} aria-hidden="true" />
                        </button>
                      </td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {editing ? (
        <RecurringDialog
          item={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("accounts.recurring.saved"));
            list.reload();
          }}
        />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          open
          title={t("accounts.recurring.deleteTitle")}
          body={t("accounts.recurring.deleteBody", { name: deleting.name })}
          confirmLabel={t("common.delete")}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await accountsApi.deleteRecurring(deleting.id);
            setDeleting(null);
            toast(t("common.deleted"));
            list.reload();
          }}
        />
      ) : null}
    </>
  );
}

function RecurringDialog({ item, onClose, onSaved }: { item: RecurringView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const heads = useHeads();
  const centres = useCostCentres();
  const vendors = useVendors();
  const form = useForm<RecurringValues>({
    name: item?.name ?? "",
    headId: item?.headId ?? "",
    costCentreId: item?.costCentreId ?? "",
    vendorId: item?.vendorId ?? "",
    amount: item ? rupeesInput(item.amountPaise) : "",
    gstRate: "",
    gst: item && item.gstPaise ? rupeesInput(item.gstPaise) : "",
    dayOfMonth: String(item?.dayOfMonth ?? 1),
    description: item?.description ?? "",
    active: item?.active ?? true,
  });
  const head = (heads.data ?? []).find((h) => h.id === form.values.headId);
  const gstApplicable = Boolean(head?.gstApplicable);
  const onRate = (rate: string) => {
    form.set("gstRate", rate);
    const amount = parseRupees(form.values.amount);
    if (rate && amount !== null) form.set("gst", rupeesInput(gstAt(amount, Number(rate))));
  };
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateRecurring(form.values, gstApplicable), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      const body = {
        name: form.values.name.trim(),
        headId: form.values.headId,
        costCentreId: form.values.costCentreId || null,
        vendorId: form.values.vendorId || null,
        amountPaise: parseRupees(form.values.amount) ?? 0,
        gstPaise: form.values.gst.trim() ? (parseRupees(form.values.gst) ?? 0) : 0,
        dayOfMonth: Number(form.values.dayOfMonth),
        description: form.values.description.trim() || null,
        active: form.values.active,
      };
      if (item) await accountsApi.updateRecurring(item.id, body);
      else await accountsApi.createRecurring(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open onClose={onClose} title={item ? t("accounts.recurring.editTitle") : t("accounts.recurring.addTitle")} description={t("accounts.recurring.description")} closeLabel={t("common.close")} variant="sheet">
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="recurring-form">
        <FormAlert message={form.formError} />
        <TextField label={t("accounts.col.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={120} required data-autofocus />
        <div className="grid gap-3.5 sm:grid-cols-2">
          <SelectField label={t("accounts.col.head")} name="headId" value={form.values.headId} onChange={(e) => form.set("headId", e.target.value)} options={[{ value: "", label: t("accounts.expense.pickHead") }, ...(heads.data ?? []).filter((h) => h.kind === "EXPENSE" && h.active).map((h) => ({ value: h.id, label: `${h.code} · ${h.name}` }))]} error={form.errors.headId} required />
          <SelectField label={t("accounts.col.costCentre")} name="costCentreId" value={form.values.costCentreId} onChange={(e) => form.set("costCentreId", e.target.value)} options={[{ value: "", label: t("accounts.expense.noCostCentre") }, ...(centres.data ?? []).filter((c) => c.active).map((c) => ({ value: c.id, label: c.name }))]} />
          <SelectField label={t("accounts.col.vendor")} name="vendorId" value={form.values.vendorId} onChange={(e) => form.set("vendorId", e.target.value)} options={[{ value: "", label: t("accounts.expense.noVendor") }, ...(vendors.data ?? []).filter((v) => v.active).map((v) => ({ value: v.id, label: v.name }))]} />
          <TextField label={t("accounts.recurring.day")} name="dayOfMonth" inputMode="numeric" value={form.values.dayOfMonth} onChange={(e) => form.set("dayOfMonth", e.target.value)} error={form.errors.dayOfMonth} hint={t("accounts.recurring.dayHint")} required />
          <TextField label={t("accounts.expense.amountBeforeGst")} name="amount" inputMode="decimal" value={form.values.amount} onChange={(e) => form.set("amount", e.target.value)} error={form.errors.amount} hint={t("accounts.expense.amountHint")} required />
          {gstApplicable ? (
            <>
              <SelectField label={t("accounts.expense.gstRate")} name="gstRate" value={form.values.gstRate} onChange={(e) => onRate(e.target.value)} options={[{ value: "", label: t("accounts.expense.gstEnter") }, ...GST_RATES.map((r) => ({ value: String(r), label: `${r}%` }))]} />
              <TextField label={t("accounts.expense.gst")} name="gst" inputMode="decimal" value={form.values.gst} onChange={(e) => form.set("gst", e.target.value)} error={form.errors.gst} />
            </>
          ) : null}
        </div>
        <TextAreaField label={t("accounts.col.description")} name="description" value={form.values.description} onChange={(e) => form.set("description", e.target.value)} rows={2} maxLength={500} />
        {item ? (
          <label className="check">
            <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
            <span>{t("accounts.active")}</span>
          </label>
        ) : null}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ settings and approvers */

function SettingsTab({ canManage }: { canManage: boolean }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const settings = useApiData("accounts:settings", accountsApi.settings);
  const data = settings.data;
  if (settings.error && !data) return <ErrorState error={settings.error} onRetry={settings.reload} />;
  if (!data) return <LoadingRows rows={4} />;
  return (
    <>
      <section className="card" aria-labelledby="settings-title">
        <div className="card-head">
          <div>
            <h2 id="settings-title">{t("accounts.setup.settings")}</h2>
            <p className="mt-1 text-sm text-ink-2">{data.updatedAt ? t("accounts.setup.updatedAt", { date: formatPlainDate(data.updatedAt.slice(0, 10), locale) }) : t("accounts.setup.defaults")}</p>
          </div>
        </div>
        <SettingsForm key={data.updatedAt ?? "new"} data={data} canManage={canManage} onSaved={() => { toast(t("accounts.setup.settingsSaved")); settings.reload(); }} />
      </section>
      <section className="card" aria-labelledby="approvers-title">
        <div className="card-head">
          <div>
            <h2 id="approvers-title">{t("accounts.setup.approvers")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("accounts.setup.approversSub")}</p>
          </div>
        </div>
        <ApproversForm key={data.approvers.map((a) => `${a.userId}:${a.limitPaise}`).join(",")} data={data} canManage={canManage} onSaved={() => { toast(t("accounts.setup.approversSaved")); settings.reload(); }} />
      </section>
    </>
  );
}

function SettingsForm({ data, canManage, onSaved }: { data: AccountsSettingsView; canManage: boolean; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<SettingsValues>({
    budgetMode: data.budgetMode,
    donation80gRegistered: data.donation80gRegistered,
    donation80gNumber: data.donation80gNumber ?? "",
    pettyCashFloat: rupeesInput(data.pettyCashFloatPaise),
  });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateSettings(form.values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await accountsApi.saveSettings({
        budgetMode: form.values.budgetMode,
        donation80gRegistered: form.values.donation80gRegistered,
        donation80gNumber: form.values.donation80gRegistered ? form.values.donation80gNumber.trim() || null : null,
        pettyCashFloatPaise: parseRupees(form.values.pettyCashFloat) ?? 0,
      });
    });
    if (ok) onSaved();
  };
  return (
    <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="settings-form">
      <FormAlert message={form.formError} />
      <div className="grid gap-3.5 sm:grid-cols-2">
        <SelectField
          label={t("accounts.setup.budgetMode")}
          name="budgetMode"
          value={form.values.budgetMode}
          onChange={(e) => form.set("budgetMode", e.target.value as BudgetMode)}
          options={[
            { value: "WARN", label: t("accounts.setup.budgetWarn") },
            { value: "BLOCK", label: t("accounts.setup.budgetBlock") },
          ]}
          hint={t("accounts.setup.budgetModeHint")}
          disabled={!canManage}
        />
        <TextField label={t("accounts.setup.pettyFloat")} name="pettyCashFloat" inputMode="decimal" value={form.values.pettyCashFloat} onChange={(e) => form.set("pettyCashFloat", e.target.value)} error={form.errors.pettyCashFloat} hint={t("accounts.setup.pettyFloatHint")} disabled={!canManage} />
      </div>
      <label className="check">
        <input type="checkbox" name="donation80gRegistered" checked={form.values.donation80gRegistered} onChange={(e) => form.set("donation80gRegistered", e.target.checked)} disabled={!canManage} />
        <span>{t("accounts.setup.eightyG")}</span>
      </label>
      {form.values.donation80gRegistered ? (
        <TextField label={t("accounts.setup.eightyGNumber")} name="donation80gNumber" value={form.values.donation80gNumber} onChange={(e) => form.set("donation80gNumber", e.target.value)} error={form.errors.donation80gNumber} maxLength={60} hint={t("accounts.setup.eightyGHint")} disabled={!canManage} required />
      ) : null}
      {canManage ? (
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="settings-save">
            {form.submitting ? t("common.working") : t("common.save")}
          </button>
        </div>
      ) : null}
    </form>
  );
}

function ApproversForm({ data, canManage, onSaved }: { data: AccountsSettingsView; canManage: boolean; onSaved: () => void }) {
  const { t } = useI18n();
  const [rows, setRows] = useState<{ userId: string; limit: string }[]>(() => data.approvers.map((a) => ({ userId: a.userId, limit: a.limitPaise === null ? "" : rupeesInput(a.limitPaise) })));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const names = new Map(data.candidates.map((c) => [c.id, c.name]));
  const free = data.candidates.filter((c) => !rows.some((r) => r.userId === c.id));
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    for (const r of rows) {
      if (r.limit.trim()) {
        const n = parseRupees(r.limit);
        if (n === null || n <= 0) {
          setError(t("accounts.v.limit"));
          return;
        }
      }
    }
    setBusy(true);
    try {
      await accountsApi.saveApprovers({ approvers: rows.map((r) => ({ userId: r.userId, limitPaise: r.limit.trim() ? parseRupees(r.limit) : null })) });
      onSaved();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form method="post" className="flex flex-col gap-3.5" onSubmit={save} noValidate data-testid="approvers-form">
      <FormAlert message={error} />
      {rows.length === 0 ? <p className="empty">{t("accounts.setup.noApprovers")}</p> : null}
      <ul className="flex flex-col gap-2">
        {rows.map((r, i) => (
          <li key={r.userId} className="flex flex-wrap items-end gap-2">
            <span className="min-w-40 flex-1 font-semibold">
              {names.get(r.userId) ?? t("common.system")}
              {!names.has(r.userId) ? <span className="block text-[12px] font-normal text-warn">{t("accounts.setup.cannotApprove")}</span> : null}
            </span>
            <TextField
              label={t("accounts.setup.limit")}
              name={`limit.${i}`}
              inputMode="decimal"
              value={r.limit}
              onChange={(e) => setRows((prev) => prev.map((row, j) => (j === i ? { ...row, limit: e.target.value } : row)))}
              hint={t("accounts.setup.limitHint")}
              disabled={!canManage}
              className="w-48"
            />
            {canManage ? (
              <button type="button" className="iconbtn mb-6" aria-label={t("accounts.setup.removeApprover", { name: names.get(r.userId) ?? "" })} onClick={() => setRows((prev) => prev.filter((_, j) => j !== i))}>
                <Trash2 size={16} aria-hidden="true" />
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {canManage && free.length > 0 ? (
        <SelectField
          label={t("accounts.setup.addApprover")}
          name="addApprover"
          value=""
          onChange={(e) => {
            if (e.target.value) setRows((prev) => [...prev, { userId: e.target.value, limit: "" }]);
          }}
          options={[{ value: "", label: t("accounts.setup.pickPerson") }, ...free.map((c) => ({ value: c.id, label: c.name }))]}
          className="sm:max-w-sm"
        />
      ) : null}
      <p className="text-[13px] text-ink-2">{plural(t, "accounts.setup.approverCount", rows.length)} · {translateOr(t, "accounts.setup.approversNote", "")}</p>
      {canManage ? (
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={busy} data-testid="approvers-save">
            {busy ? t("common.working") : t("common.save")}
          </button>
        </div>
      ) : null}
    </form>
  );
}
