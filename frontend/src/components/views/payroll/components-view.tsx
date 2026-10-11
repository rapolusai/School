"use client";

import { Layers, Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { useI18n } from "@/lib/i18n";
import { COMPONENT_CODE_PATTERN, RESERVED_COMPONENT_CODES } from "@/lib/payroll";
import { payrollApi } from "@/lib/payroll-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { ComponentKind, SalaryComponentView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { PayrollNav } from "./payroll-ui";

type ComponentValues = {
  code: string;
  kind: ComponentKind;
  name: string;
  pfWage: boolean;
  esiWage: boolean;
  taxable: boolean;
  prorated: boolean;
  displayOrder: string;
  active: boolean;
};

/** The checks the components API makes. Exported for tests. */
export function validateComponent(values: ComponentValues, isNew: boolean): Problems {
  const problems: Problems = {};
  if (isNew) {
    const code = values.code.trim().toUpperCase();
    if (!COMPONENT_CODE_PATTERN.test(code)) problems.code = "payroll.v.code";
    else if ((RESERVED_COMPONENT_CODES as readonly string[]).includes(code)) problems.code = "payroll.v.reservedCode";
  }
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 60) problems.name = "validation.tooLong";
  if (!/^\d{1,3}$/.test(values.displayOrder.trim())) problems.displayOrder = "payroll.v.order";
  return problems;
}

/** /app/payroll/components: the earnings and deductions a salary structure is made of. */
export function PayrollComponentsView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.payrollManage);
  const components = useApiData("payroll:components", payrollApi.components);
  const [editing, setEditing] = useState<SalaryComponentView | "new" | null>(null);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const list = components.data ?? [];

  const addStandard = async () => {
    setAdding(true);
    setError(null);
    try {
      await payrollApi.addStandardComponents();
      components.reload();
      toast(t("payroll.components.standardAdded"));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setAdding(false);
    }
  };

  const group = (kind: ComponentKind) => list.filter((c) => c.kind === kind);

  return (
    <>
      <PageHead
        eyebrow={t("payroll.eyebrow")}
        title={t("payroll.components.title")}
        actions={
          canManage ? (
            <>
              {list.length > 0 ? (
                <button type="button" className="btn" onClick={addStandard} disabled={adding}>
                  {adding ? t("common.working") : t("payroll.components.addStandard")}
                </button>
              ) : null}
              <button type="button" className="btn btn-primary" onClick={() => setEditing("new")}>
                <Plus size={18} aria-hidden="true" />
                {t("payroll.components.add")}
              </button>
            </>
          ) : null
        }
      />
      <PayrollNav />
      <p className="-mt-2 max-w-3xl text-ink-2">{t("payroll.components.intro")}</p>
      <FormAlert message={error} />
      {components.error && !components.data ? (
        <section className="card">
          <ErrorState error={components.error} onRetry={components.reload} />
        </section>
      ) : !components.data ? (
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      ) : list.length === 0 ? (
        <section className="card">
          <div className="empty flex flex-col items-center gap-3" data-testid="components-empty">
            <p>{t("payroll.components.empty")}</p>
            {canManage ? (
              <button type="button" className="btn btn-primary" onClick={addStandard} disabled={adding}>
                {adding ? t("common.working") : t("payroll.components.addStandard")}
              </button>
            ) : null}
          </div>
        </section>
      ) : (
        <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-2">
          {(["EARNING", "DEDUCTION"] as const).map((kind) => (
            <section key={kind} className="card" aria-labelledby={`components-${kind}`}>
              <div className="card-head">
                <h2 id={`components-${kind}`}>{t(`payroll.componentKind.${kind}`)}</h2>
              </div>
              {group(kind).length === 0 ? (
                <p className="empty">{t("payroll.components.noneOfKind")}</p>
              ) : (
                <ul className="list" data-testid={`components-${kind}`}>
                  {group(kind).map((c) => (
                    <li key={c.id} className="li items-center">
                      <span className="badge-ic">
                        <Layers size={18} aria-hidden="true" />
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="flex flex-wrap items-center gap-2 font-semibold">
                          {c.name}
                          <span className="mono text-[12.5px] font-normal text-ink-3">{c.code}</span>
                          {!c.active ? <Pill>{t("payroll.components.inactive")}</Pill> : null}
                        </p>
                        <p className="mt-1 flex flex-wrap gap-1">
                          {c.pfWage ? <span className="chip">{t("payroll.components.pfWage")}</span> : null}
                          {c.esiWage ? <span className="chip">{t("payroll.components.esiWage")}</span> : null}
                          {c.taxable ? <span className="chip">{t("payroll.components.taxable")}</span> : null}
                          {c.prorated ? <span className="chip">{t("payroll.components.prorated")}</span> : null}
                          {c.inUse ? <span className="chip">{t("payroll.components.inUse")}</span> : null}
                        </p>
                      </div>
                      {canManage ? (
                        <button
                          type="button"
                          className="iconbtn"
                          aria-label={t("payroll.components.editNamed", { name: c.name })}
                          onClick={() => setEditing(c)}
                        >
                          <Pencil size={18} aria-hidden="true" />
                        </button>
                      ) : null}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          ))}
        </div>
      )}
      <p className="text-[13px] text-ink-3">{t("payroll.components.statutory")}</p>
      {editing ? (
        <ComponentDialog
          component={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            setEditing(null);
            components.reload();
            toast(t("payroll.components.saved", { name: saved.name }));
          }}
        />
      ) : null}
    </>
  );
}

function ComponentDialog({
  component,
  onClose,
  onSaved,
}: {
  component: SalaryComponentView | null;
  onClose: () => void;
  onSaved: (saved: SalaryComponentView) => void;
}) {
  const { t } = useI18n();
  const form = useForm<ComponentValues>(
    component
      ? { ...component, displayOrder: String(component.displayOrder) }
      : {
          code: "",
          kind: "EARNING",
          name: "",
          pfWage: false,
          esiWage: true,
          taxable: true,
          prorated: true,
          displayOrder: "60",
          active: true,
        },
  );
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateComponent(values, component === null), t, event.currentTarget)) return;
    // A deduction is never part of PF, ESI or taxable wages.
    const earning = values.kind === "EARNING";
    const fields = {
      name: values.name.trim(),
      pfWage: earning && values.pfWage,
      esiWage: earning && values.esiWage,
      taxable: earning && values.taxable,
      prorated: values.prorated,
      displayOrder: Number(values.displayOrder.trim()),
      active: values.active,
    };
    let saved: SalaryComponentView | undefined;
    const ok = await form.submit(t, async () => {
      saved = component
        ? await payrollApi.updateComponent(component.id, fields)
        : await payrollApi.createComponent({ code: values.code.trim().toUpperCase(), kind: values.kind, fields });
    });
    if (ok && saved) onSaved(saved);
  };
  const check = (field: "pfWage" | "esiWage" | "taxable" | "prorated" | "active", label: string) => (
    <label className="check">
      <input type="checkbox" name={field} checked={values[field]} onChange={(e) => set(field, e.target.checked)} />
      <span>{label}</span>
    </label>
  );
  return (
    <Dialog
      open
      onClose={onClose}
      title={component ? t("payroll.components.edit") : t("payroll.components.add")}
      description={t("payroll.components.dialog")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        {component ? null : (
          <div className="grid2">
            <TextField
              label={t("payroll.components.code")}
              name="code"
              value={values.code}
              maxLength={20}
              placeholder="TRANSPORT"
              hint={t("payroll.components.codeHint")}
              onChange={(e) => set("code", e.target.value.toUpperCase())}
              error={errors.code}
              required
              data-autofocus
            />
            <SelectField
              label={t("payroll.components.kind")}
              name="kind"
              value={values.kind}
              onChange={(e) => set("kind", e.target.value as ComponentKind)}
              error={errors.kind}
              options={[
                { value: "EARNING", label: t("payroll.componentKind.EARNING") },
                { value: "DEDUCTION", label: t("payroll.componentKind.DEDUCTION") },
              ]}
            />
          </div>
        )}
        <TextField
          label={t("payroll.components.name")}
          name="name"
          value={values.name}
          maxLength={60}
          onChange={(e) => set("name", e.target.value)}
          error={errors.name}
          required
          data-autofocus={component ? true : undefined}
        />
        {values.kind === "EARNING" ? (
          <>
            {check("pfWage", t("payroll.components.pfWageLabel"))}
            {check("esiWage", t("payroll.components.esiWageLabel"))}
            {check("taxable", t("payroll.components.taxableLabel"))}
          </>
        ) : null}
        {check("prorated", t("payroll.components.proratedLabel"))}
        <TextField
          label={t("payroll.components.order")}
          name="displayOrder"
          inputMode="numeric"
          value={values.displayOrder}
          maxLength={3}
          hint={t("payroll.components.orderHint")}
          onChange={(e) => set("displayOrder", e.target.value)}
          error={errors.displayOrder}
        />
        {component ? check("active", t("payroll.components.activeLabel")) : null}
        <div className="flex justify-end gap-2 pt-1">
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
