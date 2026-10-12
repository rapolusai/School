"use client";

import { Pencil, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { AccountantNote } from "@/components/views/payroll/payroll-ui";
import { blank, DEFAULT_TDS_RATES, validateVendor, type VendorValues } from "@/lib/accounts";
import { accountsApi } from "@/lib/accounts-api";
import { useAuth } from "@/lib/auth";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { TDS_SECTIONS, type TdsSection, type VendorView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { AccountsNav, ExpenseStatusPill, PeriodFilter, Stat, useModeLabel, useVendors } from "./accounts-ui";

/** /app/accounts/vendors: suppliers with their GSTIN, masked PAN, phone and bank details, and TDS section. */
export function VendorsView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const vendors = useVendors();
  const [editing, setEditing] = useState<VendorView | null | "new">(null);
  const [deleting, setDeleting] = useState<VendorView | null>(null);
  const data = vendors.data;
  return (
    <>
      <PageHead
        eyebrow={t("accounts.eyebrow")}
        title={t("accounts.vendors.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="vendor-new">
              <Plus size={18} aria-hidden="true" />
              {t("accounts.vendors.new")}
            </button>
          ) : null
        }
      />
      <AccountsNav />
      <AccountantNote>{t("accounts.vendors.tdsNote")}</AccountantNote>
      <section className="card" aria-labelledby="vendors-title">
        <div className="card-head">
          <h2 id="vendors-title">{t("accounts.vendors.list")}</h2>
        </div>
        {vendors.error && !data ? (
          <ErrorState error={vendors.error} onRetry={vendors.reload} />
        ) : !data ? (
          <LoadingRows rows={4} />
        ) : data.length === 0 ? (
          <p className="empty" data-testid="vendors-empty">
            {canManage ? t("accounts.vendors.emptyManage") : t("accounts.vendors.empty")}
          </p>
        ) : (
          <div className="table-wrap">
            <table className="table" data-testid="vendors-table">
              <thead>
                <tr>
                  <th scope="col">{t("accounts.col.name")}</th>
                  <th scope="col" className="hidden md:table-cell">
                    {t("accounts.vendors.gstin")}
                  </th>
                  <th scope="col" className="hidden md:table-cell">
                    {t("accounts.vendors.pan")}
                  </th>
                  <th scope="col" className="hidden sm:table-cell">
                    {t("accounts.vendors.phone")}
                  </th>
                  <th scope="col">{t("accounts.vendors.tds")}</th>
                  <th scope="col" className="r">
                    {t("accounts.col.actions")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.map((v) => (
                  <tr key={v.id} data-testid="vendor-row">
                    <td>
                      <Link href={`/app/accounts/vendors/${v.id}`} className="link font-semibold">
                        {v.name}
                      </Link>
                      {!v.active ? (
                        <Pill tone="neutral" dot>
                          {t("accounts.inactive")}
                        </Pill>
                      ) : null}
                    </td>
                    <td className="mono hidden md:table-cell">{v.gstin ?? "—"}</td>
                    <td className="mono hidden md:table-cell">{v.panMasked ?? "—"}</td>
                    <td className="mono hidden sm:table-cell">{v.phoneMasked ?? "—"}</td>
                    <td>{v.tdsSection ? `${v.tdsSection} · ${v.tdsRatePercent ?? 0}%` : "—"}</td>
                    <td className="r">
                      <span className="inline-flex gap-1">
                        {canManage ? (
                          <button type="button" className="iconbtn" aria-label={t("accounts.vendors.editName", { name: v.name })} onClick={() => setEditing(v)}>
                            <Pencil size={16} aria-hidden="true" />
                          </button>
                        ) : null}
                        {canManage && !v.inUse ? (
                          <button type="button" className="iconbtn" aria-label={t("accounts.vendors.deleteName", { name: v.name })} onClick={() => setDeleting(v)}>
                            <Trash2 size={16} aria-hidden="true" />
                          </button>
                        ) : null}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {editing ? (
        <VendorDialog
          vendor={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("accounts.vendors.saved"));
            vendors.reload();
          }}
        />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          open
          title={t("accounts.vendors.deleteTitle")}
          body={t("accounts.vendors.deleteBody", { name: deleting.name })}
          confirmLabel={t("common.delete")}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await accountsApi.deleteVendor(deleting.id);
            setDeleting(null);
            toast(t("common.deleted"));
            vendors.reload();
          }}
        />
      ) : null}
    </>
  );
}

function VendorDialog({ vendor, onClose, onSaved }: { vendor: VendorView | null; onClose: () => void; onSaved: (v: VendorView) => void }) {
  const { t } = useI18n();
  const form = useForm<VendorValues>({
    name: vendor?.name ?? "",
    gstin: vendor?.gstin ?? "",
    pan: "",
    phone: "",
    email: vendor?.email ?? "",
    address: vendor?.address ?? "",
    bankAccountNumber: "",
    bankIfsc: vendor?.bankIfsc ?? "",
    tdsSection: vendor?.tdsSection ?? "",
    tdsRatePercent: vendor?.tdsRatePercent !== null && vendor?.tdsRatePercent !== undefined ? String(vendor.tdsRatePercent) : "",
    active: vendor?.active ?? true,
  });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateVendor(form.values), t, event.currentTarget)) return;
    let saved: VendorView | undefined;
    const ok = await form.submit(t, async () => {
      const body = {
        name: form.values.name.trim(),
        gstin: blank(form.values.gstin, true),
        pan: blank(form.values.pan, true),
        phone: blank(form.values.phone),
        email: blank(form.values.email),
        address: blank(form.values.address),
        bankAccountNumber: blank(form.values.bankAccountNumber),
        bankIfsc: blank(form.values.bankIfsc, true),
        tdsSection: (blank(form.values.tdsSection) as TdsSection | null) ?? null,
        tdsRatePercent: form.values.tdsRatePercent.trim() ? Number(form.values.tdsRatePercent) : null,
        active: form.values.active,
      };
      saved = vendor ? await accountsApi.updateVendor(vendor.id, body) : await accountsApi.createVendor(body);
    });
    if (ok && saved) onSaved(saved);
  };
  const masked = (shown: string | null | undefined) => (vendor && shown ? t("accounts.vendors.keepMasked", { value: shown }) : undefined);
  return (
    <Dialog open onClose={onClose} title={vendor ? t("accounts.vendors.editTitle") : t("accounts.vendors.newTitle")} description={t("accounts.vendors.description")} closeLabel={t("common.close")} variant="sheet">
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="vendor-form">
        <FormAlert message={form.formError} />
        <TextField label={t("accounts.col.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={120} required data-autofocus />
        <div className="grid gap-3.5 sm:grid-cols-2">
          <TextField label={t("accounts.vendors.gstin")} name="gstin" value={form.values.gstin} onChange={(e) => form.set("gstin", e.target.value.toUpperCase())} error={form.errors.gstin} maxLength={15} hint={t("accounts.vendors.gstinHint")} />
          <TextField label={t("accounts.vendors.pan")} name="pan" value={form.values.pan} onChange={(e) => form.set("pan", e.target.value.toUpperCase())} error={form.errors.pan} maxLength={10} hint={masked(vendor?.panMasked)} />
          <TextField label={t("accounts.vendors.phone")} name="phone" inputMode="tel" value={form.values.phone} onChange={(e) => form.set("phone", e.target.value)} error={form.errors.phone} maxLength={15} hint={masked(vendor?.phoneMasked)} />
          <TextField label={t("accounts.vendors.email")} name="email" type="email" value={form.values.email} onChange={(e) => form.set("email", e.target.value)} error={form.errors.email} maxLength={200} />
          <TextField label={t("accounts.vendors.account")} name="bankAccountNumber" inputMode="numeric" value={form.values.bankAccountNumber} onChange={(e) => form.set("bankAccountNumber", e.target.value)} error={form.errors.bankAccountNumber} maxLength={18} hint={masked(vendor?.bankAccountMasked)} />
          <TextField label={t("accounts.vendors.ifsc")} name="bankIfsc" value={form.values.bankIfsc} onChange={(e) => form.set("bankIfsc", e.target.value.toUpperCase())} error={form.errors.bankIfsc} maxLength={11} />
          <SelectField
            label={t("accounts.vendors.tdsSection")}
            name="tdsSection"
            value={form.values.tdsSection}
            onChange={(e) => {
              form.set("tdsSection", e.target.value);
              if (e.target.value && !form.values.tdsRatePercent) form.set("tdsRatePercent", String(DEFAULT_TDS_RATES[e.target.value] ?? ""));
              if (!e.target.value) form.set("tdsRatePercent", "");
            }}
            options={[{ value: "", label: t("accounts.vendors.noTds") }, ...TDS_SECTIONS.map((s) => ({ value: s, label: t(`accounts.tdsSection.${s}`) }))]}
            error={form.errors.tdsSection}
          />
          <TextField label={t("accounts.vendors.tdsRate")} name="tdsRatePercent" inputMode="decimal" value={form.values.tdsRatePercent} onChange={(e) => form.set("tdsRatePercent", e.target.value)} error={form.errors.tdsRatePercent} hint={t("accounts.vendors.tdsRateHint")} disabled={!form.values.tdsSection} />
        </div>
        <TextAreaField label={t("accounts.vendors.address")} name="address" value={form.values.address} onChange={(e) => form.set("address", e.target.value)} rows={2} maxLength={300} />
        <label className="check">
          <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
          <span>{t("accounts.active")}</span>
        </label>
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

/** /app/accounts/vendors/[id]: the vendor's bills in a period, with what was paid and kept back as TDS. */
export function VendorLedgerView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const modeLabel = useModeLabel();
  const today = todayInIndia();
  const [from, setFrom] = useState(`${today.slice(0, 4)}-01-01` < today ? `${Number(today.slice(0, 4)) - (Number(today.slice(5, 7)) >= 4 ? 0 : 1)}-04-01` : today);
  const [to, setTo] = useState(today);
  const ledger = useApiData(`accounts:vendor-ledger:${id}:${from}:${to}`, () => accountsApi.vendorLedger(id, { from, to }));
  const data = ledger.data;
  return (
    <>
      <PageHead eyebrow={t("accounts.vendors.title")} title={data?.vendor.name ?? t("accounts.vendors.ledger")} />
      <AccountsNav />
      {data ? (
        <section className="card mb-4">
          <dl className="kv text-[13.5px]">
            <dt>{t("accounts.vendors.gstin")}</dt>
            <dd className="mono">{data.vendor.gstin ?? "—"}</dd>
            <dt>{t("accounts.vendors.pan")}</dt>
            <dd className="mono">{data.vendor.panMasked ?? "—"}</dd>
            <dt>{t("accounts.vendors.phone")}</dt>
            <dd className="mono">{data.vendor.phoneMasked ?? "—"}</dd>
            <dt>{t("accounts.vendors.bank")}</dt>
            <dd className="mono">{data.vendor.bankAccountMasked ? `${data.vendor.bankAccountMasked} · ${data.vendor.bankIfsc}` : "—"}</dd>
            <dt>{t("accounts.vendors.tds")}</dt>
            <dd>{data.vendor.tdsSection ? `${data.vendor.tdsSection} · ${data.vendor.tdsRatePercent ?? 0}%` : "—"}</dd>
          </dl>
        </section>
      ) : null}
      <section className="card" aria-labelledby="ledger-title">
        <div className="card-head">
          <h2 id="ledger-title">{t("accounts.vendors.ledger")}</h2>
        </div>
        <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
          <PeriodFilter
            from={from}
            to={to}
            onChange={(f, tt) => {
              setFrom(f);
              setTo(tt);
            }}
          />
        </form>
        {data ? (
          <ul className="rep-kpis mb-4">
            <Stat label={t("accounts.vendors.billed")} value={formatPaise(data.billedPaise)} />
            <Stat label={t("accounts.vendors.paid")} value={formatPaise(data.paidPaise)} />
            <Stat label={t("accounts.vendors.tdsKept")} value={formatPaise(data.tdsPaise)} />
            <Stat label={t("accounts.vendors.outstanding")} value={formatPaise(data.outstandingPaise)} attention={data.outstandingPaise > 0} testId="vendor-outstanding" />
          </ul>
        ) : null}
        {ledger.error && !data ? (
          <ErrorState error={ledger.error} onRetry={ledger.reload} />
        ) : !data ? (
          <LoadingRows rows={4} />
        ) : data.rows.length === 0 ? (
          <p className="empty">{t("accounts.vendors.ledgerEmpty")}</p>
        ) : (
          <div className="table-wrap">
            <table className="table" data-testid="vendor-ledger">
              <thead>
                <tr>
                  <th scope="col">{t("accounts.col.date")}</th>
                  <th scope="col">{t("accounts.col.number")}</th>
                  <th scope="col">{t("accounts.col.description")}</th>
                  <th scope="col" className="r">
                    {t("accounts.col.bill")}
                  </th>
                  <th scope="col" className="r hidden sm:table-cell">
                    {t("accounts.expense.gst")}
                  </th>
                  <th scope="col" className="r hidden sm:table-cell">
                    {t("accounts.vendors.tdsKept")}
                  </th>
                  <th scope="col" className="r">
                    {t("accounts.vendors.paid")}
                  </th>
                  <th scope="col">{t("accounts.col.status")}</th>
                </tr>
              </thead>
              <tbody>
                {data.rows.map((r) => (
                  <tr key={r.expenseId}>
                    <td className="whitespace-nowrap">{formatPlainDate(r.date, locale)}</td>
                    <td className="mono">
                      <Link href={`/app/accounts/expenses/${r.expenseId}`} className="link">
                        {r.number ?? "—"}
                      </Link>
                    </td>
                    <td>{r.description}</td>
                    <td className="r num">{formatPaise(r.billPaise)}</td>
                    <td className="r num hidden sm:table-cell">{formatPaise(r.gstPaise)}</td>
                    <td className="r num hidden sm:table-cell">{formatPaise(r.tdsPaise)}</td>
                    <td className="r num">
                      {formatPaise(r.paidPaise)}
                      {r.paidOn ? <span className="block text-[12px] text-ink-3">{formatPlainDate(r.paidOn, locale)} · {modeLabel(r.mode)}</span> : null}
                    </td>
                    <td>
                      <ExpenseStatusPill status={r.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
