"use client";

import { SelectField, TextField } from "@/components/ui/field";
import {
  ErrorState,
  FormAlert,
  LoadingRows,
  PageHead,
} from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { classLabel } from "@/lib/format";
import { govreportApi } from "@/lib/govreport-api";
import { useI18n } from "@/lib/i18n";
import type { CodeView, FamilyFields } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { codeOptions } from "./govreport-shared";

/* /app/family/govreport: the government details a parent may fill for each child (mother tongue, APAAR ID once). */

export function FamilyGovReportView() {
  const { t } = useI18n();
  const mine = useApiData("govreport:mine", govreportApi.mine);
  const data = mine.data;
  return (
    <>
      <PageHead title={t("govreport.family.title")} />
      <p className="-mt-2 max-w-2xl text-ink-2">
        {t("govreport.family.intro")}
      </p>
      {mine.error && !data ? (
        <ErrorState error={mine.error} onRetry={mine.reload} />
      ) : !data ? (
        <LoadingRows rows={3} />
      ) : data.children.length === 0 ? (
        <p className="empty">{t("govreport.family.none")}</p>
      ) : (
        <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
          {data.children.map((child) => (
            <ChildCard
              key={child.studentId}
              child={child}
              tongues={data.motherTongues}
              onSaved={mine.reload}
            />
          ))}
        </div>
      )}
    </>
  );
}

type Values = { motherTongue: string; apaarId: string };

/** Client checks mirroring the API. Exported for tests. */
export function validateFamily(v: Values): Problems {
  return v.apaarId.trim() && !/^\d{12}$/.test(v.apaarId.trim())
    ? { apaarId: "govreport.v.apaar" }
    : {};
}

function ChildCard({
  child,
  tongues,
  onSaved,
}: {
  child: FamilyFields;
  tongues: CodeView[];
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm<Values>({
    motherTongue: child.motherTongue ?? "",
    apaarId: child.apaarEditable ? "" : (child.apaarId ?? ""),
  });
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateFamily(form.values), t, event.currentTarget))
      return;
    const ok = await form.submit(t, async () => {
      await govreportApi.saveMine(child.studentId, {
        motherTongue: form.values.motherTongue || null,
        apaarId: child.apaarEditable
          ? form.values.apaarId.trim() || null
          : null,
      });
      onSaved();
    });
    if (ok) toast(t("govreport.family.saved"));
  };
  return (
    <form
      method="post"
      className="card flex flex-col gap-3"
      onSubmit={submit}
      noValidate
      data-testid="family-govreport-child"
      aria-label={child.fullName}
    >
      <div className="card-head">
        <h2>{child.fullName}</h2>
        <span className="text-ink-3">
          {classLabel(child.className, child.sectionName)}
        </span>
      </div>
      <FormAlert message={form.formError} />
      <SelectField
        label={t("govreport.field.motherTongue")}
        name="motherTongue"
        value={form.values.motherTongue}
        onChange={(e) => form.set("motherTongue", e.target.value)}
        options={codeOptions(tongues, t("govreport.notSet"))}
        error={form.errors.motherTongue}
      />
      {child.apaarEditable ? (
        <TextField
          label={t("govreport.field.apaarId")}
          name="apaarId"
          inputMode="numeric"
          maxLength={12}
          value={form.values.apaarId}
          onChange={(e) => form.set("apaarId", e.target.value)}
          error={form.errors.apaarId}
          hint={t("govreport.family.apaarHint")}
        />
      ) : (
        <p className="text-[13px]" data-testid="family-apaar-locked">
          {t("govreport.field.apaarId")}:{" "}
          <span className="mono">{child.apaarId ?? "—"}</span> ·{" "}
          {t("govreport.family.apaarLocked")}
        </p>
      )}
      <p className="text-[13px] text-ink-3">
        {t("govreport.family.noAadhaar")}
      </p>
      <div>
        <button
          type="submit"
          className="btn btn-primary"
          disabled={form.submitting}
        >
          {form.submitting ? t("common.working") : t("govreport.family.save")}
        </button>
      </div>
    </form>
  );
}
