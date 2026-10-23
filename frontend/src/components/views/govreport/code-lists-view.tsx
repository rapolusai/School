"use client";

import { useState } from "react";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import {
  ErrorState,
  FormAlert,
  LoadingRows,
  PageHead,
} from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { govreportApi } from "@/lib/govreport-api";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import { CODE_LIST_TYPES, type CodeListType } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { GovReportNav, useCanManage, VerifyNote } from "./govreport-shared";

/* /app/govreport/code-lists: the codes the exports use, as dated data the school can correct. */

const listLabel = (list: CodeListType): MessageKey =>
  `govreport.list.${list}` as MessageKey;

export function CodeListsView() {
  const { t, lang } = useI18n();
  const canManage = useCanManage();
  const [list, setList] = useState<CodeListType>("SOCIAL_CATEGORY");
  const codes = useApiData(`govreport:codes:${list}`, () =>
    govreportApi.codes(list),
  );
  return (
    <>
      <PageHead
        eyebrow={t("nav.govreport")}
        title={t("govreport.codes.title")}
      />
      <GovReportNav />
      <p className="-mt-1 max-w-3xl text-ink-2">{t("govreport.codes.intro")}</p>
      <VerifyNote />
      <div className="toolbar">
        <SelectField
          label={t("govreport.codes.list")}
          name="list"
          value={list}
          onChange={(e) => setList(e.target.value as CodeListType)}
          options={CODE_LIST_TYPES.map((l) => ({
            value: l,
            label: t(listLabel(l)),
          }))}
          className="w-full sm:w-auto sm:min-w-56"
        />
      </div>
      <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-3">
        <section
          className="card xl:col-span-2"
          aria-labelledby="gov-codes"
          data-testid="govreport-codes"
        >
          <div className="card-head">
            <h2 id="gov-codes">{t(listLabel(list))}</h2>
          </div>
          {codes.error && !codes.data ? (
            <ErrorState error={codes.error} onRetry={codes.reload} />
          ) : !codes.data ? (
            <LoadingRows rows={5} />
          ) : (
            <ul className="rowcards">
              {codes.data.map((c) => (
                <li key={c.id} className="rowcard flex-wrap">
                  <span className="mono w-28 flex-none">{c.code}</span>
                  <span className="min-w-0 flex-1">
                    <b className="block font-semibold">{c.label}</b>
                    <span className="block text-[13px] text-ink-3">
                      {[
                        c.value
                          ? t("govreport.codes.value", { value: c.value })
                          : null,
                        c.stateCode,
                        t("govreport.codes.from", {
                          date: formatPlainDate(
                            c.effectiveFrom,
                            localeFor(lang),
                          ),
                        }),
                        c.effectiveTo
                          ? t("govreport.codes.to", {
                              date: formatPlainDate(
                                c.effectiveTo,
                                localeFor(lang),
                              ),
                            })
                          : null,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </span>
                  {c.verify ? (
                    <Pill tone="warn">{t("govreport.codes.verify")}</Pill>
                  ) : null}
                  {!c.inForce ? (
                    <Pill tone="neutral">
                      {t("govreport.codes.notInForce")}
                    </Pill>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </section>
        {canManage ? <NewCodeCard list={list} onSaved={codes.reload} /> : null}
      </div>
    </>
  );
}

type CodeValues = {
  code: string;
  label: string;
  value: string;
  stateCode: string;
  effectiveFrom: string;
};

/** Client checks mirroring the API. Exported for tests. */
export function validateCode(v: CodeValues): Problems {
  const problems: Problems = {};
  if (!/^[A-Z0-9_]{1,30}$/.test(v.code.trim()))
    problems.code = "govreport.v.code";
  if (!v.label.trim()) problems.label = "govreport.v.label";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v.effectiveFrom))
    problems.effectiveFrom = "govreport.v.date";
  return problems;
}

function NewCodeCard({
  list,
  onSaved,
}: {
  list: CodeListType;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const blank: CodeValues = {
    code: "",
    label: "",
    value: "",
    stateCode: "",
    effectiveFrom: todayInIndia(),
  };
  const form = useForm<CodeValues>(blank);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateCode(form.values), t, event.currentTarget)) return;
    const v = form.values;
    const ok = await form.submit(t, async () => {
      await govreportApi.addCode({
        list,
        code: v.code.trim(),
        label: v.label.trim(),
        value: v.value.trim() || null,
        stateCode: v.stateCode.trim().toUpperCase() || null,
        effectiveFrom: v.effectiveFrom,
        displayOrder: 99,
      });
      form.reset(blank);
      onSaved();
    });
    if (ok) toast(t("govreport.codes.added"));
  };
  return (
    <form
      method="post"
      className="card flex flex-col gap-3"
      onSubmit={submit}
      noValidate
      data-testid="govreport-new-code"
      aria-labelledby="gov-new-code"
    >
      <div className="card-head">
        <h2 id="gov-new-code">{t("govreport.codes.add")}</h2>
      </div>
      <FormAlert message={form.formError} />
      <TextField
        label={t("govreport.codes.code")}
        name="code"
        maxLength={30}
        value={form.values.code}
        onChange={(e) => form.set("code", e.target.value.toUpperCase())}
        error={form.errors.code}
      />
      <TextField
        label={t("govreport.codes.label")}
        name="label"
        maxLength={120}
        value={form.values.label}
        onChange={(e) => form.set("label", e.target.value)}
        error={form.errors.label}
      />
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label={t("govreport.codes.portalValue")}
          name="value"
          maxLength={60}
          value={form.values.value}
          onChange={(e) => form.set("value", e.target.value)}
          error={form.errors.value}
        />
        <TextField
          label={t("govreport.field.stateCode")}
          name="stateCode"
          maxLength={2}
          value={form.values.stateCode}
          onChange={(e) => form.set("stateCode", e.target.value)}
          error={form.errors.stateCode}
        />
        <TextField
          label={t("govreport.codes.effectiveFrom")}
          name="effectiveFrom"
          type="date"
          value={form.values.effectiveFrom}
          onChange={(e) => form.set("effectiveFrom", e.target.value)}
          error={form.errors.effectiveFrom}
        />
      </div>
      <div>
        <button
          type="submit"
          className="btn btn-primary"
          disabled={form.submitting}
        >
          {form.submitting ? t("common.working") : t("govreport.codes.save")}
        </button>
      </div>
    </form>
  );
}
