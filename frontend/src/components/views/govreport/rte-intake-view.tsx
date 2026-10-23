"use client";

import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill, type PillTone } from "@/components/ui/pill";
import {
  ErrorState,
  FormAlert,
  LoadingRows,
  PageHead,
} from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import {
  formatDateTime,
  formatPaise,
  parseRupees,
  rupeesInput,
} from "@/lib/format";
import { govreportApi } from "@/lib/govreport-api";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import type {
  Eligibility,
  IntakeDetail,
  RteApplicationView,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  codeOptions,
  GovReportNav,
  labelOf,
  useCanManage,
  useCodes,
  VerifyNote,
} from "./govreport-shared";
import { IntakeRules, IntakeStatusPill } from "./rte-view";

/* /app/govreport/rte/[id]: the applications, their screening, and the lottery with its runs. */

const ELIGIBILITY_TONE: Record<Eligibility, PillTone> = {
  PENDING: "neutral",
  ELIGIBLE: "good",
  INELIGIBLE: "bad",
};

export function RteIntakeView({ id }: { id: string }) {
  const detail = useApiData(`govreport:intake:${id}`, () =>
    govreportApi.intake(id),
  );
  const data = detail.data;
  if (detail.error && !data)
    return <ErrorState error={detail.error} onRetry={detail.reload} />;
  if (!data) return <LoadingRows rows={6} />;
  return <IntakePanel detail={data} reload={detail.reload} />;
}

/** The intake page itself. Exported for tests. */
export function IntakePanel({
  detail,
  reload,
}: {
  detail: IntakeDetail;
  reload: () => void;
}) {
  const { t, lang } = useI18n();
  const canManage = useCanManage();
  const { intake } = detail;
  const open = intake.status === "OPEN";
  const categories = useCodes("SOCIAL_CATEGORY");
  const [screening, setScreening] = useState<RteApplicationView | null>(null);
  const eligible = detail.applications.filter(
    (a) => a.eligibility === "ELIGIBLE" && !a.withdrawn,
  ).length;
  return (
    <>
      <PageHead
        eyebrow={t("govreport.rte.title")}
        title={t("govreport.rte.intakeTitle", {
          className: intake.className ?? "—",
          year: intake.academicYearName ?? "",
        })}
        actions={<IntakeStatusPill status={intake.status} />}
      />
      <GovReportNav />
      <VerifyNote />
      <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-3">
        <section
          className="card xl:col-span-2"
          aria-labelledby="rte-apps"
          data-testid="rte-applications"
        >
          <div className="card-head">
            <h2 id="rte-apps">
              {t("govreport.rte.applications", {
                count: detail.applications.length,
              })}
            </h2>
          </div>
          {detail.applications.length === 0 ? (
            <p className="empty">{t("govreport.rte.noApplications")}</p>
          ) : (
            <ul className="rowcards">
              {detail.applications.map((a) => (
                <li
                  key={a.id}
                  className="rowcard flex-wrap"
                  data-testid="rte-application"
                >
                  <span className="min-w-0 flex-1">
                    <b className="block font-semibold">{a.childName}</b>
                    <span className="block text-[13px] text-ink-3">
                      {[
                        a.guardianName,
                        a.guardianPhone,
                        labelOf(categories, a.categoryCode),
                        a.distanceKm !== null
                          ? t("govreport.rte.km", { km: a.distanceKm })
                          : null,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                    {a.ineligibleReason ? (
                      <span className="block text-[13px] text-ink-3">
                        {a.ineligibleReason}
                      </span>
                    ) : null}
                  </span>
                  {a.withdrawn ? (
                    <Pill tone="neutral">{t("govreport.rte.withdrawn")}</Pill>
                  ) : (
                    <Pill tone={ELIGIBILITY_TONE[a.eligibility]}>
                      {t(
                        `govreport.rte.eligibility.${a.eligibility}` as MessageKey,
                      )}
                    </Pill>
                  )}
                  {canManage && open && !a.withdrawn ? (
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() => setScreening(a)}
                      data-testid="rte-screen"
                    >
                      {t("govreport.rte.screen")}
                    </button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
          {canManage && open ? (
            <AddApplication intakeId={intake.id} onAdded={reload} />
          ) : null}
        </section>
        <div className="flex flex-col gap-3.5">
          <section className="card" aria-labelledby="rte-rules">
            <div className="card-head">
              <h2 id="rte-rules">{t("govreport.rte.rules")}</h2>
            </div>
            <IntakeRules intake={intake} />
          </section>
          {canManage && intake.status !== "CLOSED" ? (
            <LotteryCard detail={detail} eligible={eligible} onRun={reload} />
          ) : null}
          <section
            className="card"
            aria-labelledby="rte-runs"
            data-testid="rte-runs"
          >
            <div className="card-head">
              <h2 id="rte-runs">{t("govreport.rte.runs")}</h2>
            </div>
            {detail.runs.length === 0 ? (
              <p className="empty">{t("govreport.rte.noRuns")}</p>
            ) : (
              <ul className="rowcards">
                {detail.runs.map((r) => (
                  <li key={r.id}>
                    <Link
                      href={`/app/govreport/rte/runs/${encodeURIComponent(r.id)}`}
                      className="rowcard"
                      data-testid="rte-run-link"
                    >
                      <span className="min-w-0 flex-1">
                        <b className="block font-semibold">
                          {t("govreport.rte.runNo", { no: r.runNo })}
                        </b>
                        <span className="block text-[13px] text-ink-3">
                          {formatDateTime(r.ranAt, localeFor(lang))} ·{" "}
                          {t("govreport.rte.seed", { seed: r.seed })}
                        </span>
                        {r.reason ? (
                          <span className="block text-[13px] text-ink-3">
                            {r.reason}
                          </span>
                        ) : null}
                      </span>
                      {r.current ? (
                        <Pill tone="good">{t("govreport.rte.current")}</Pill>
                      ) : null}
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>
      {screening ? (
        <ScreenDialog
          application={screening}
          documents={intake.documents}
          onClose={() => setScreening(null)}
          onSaved={reload}
        />
      ) : null}
    </>
  );
}

function AddApplication({
  intakeId,
  onAdded,
}: {
  intakeId: string;
  onAdded: () => void;
}) {
  const { t } = useI18n();
  const candidates = useApiData(`govreport:candidates:${intakeId}`, () =>
    govreportApi.candidates(intakeId),
  );
  const [chosen, setChosen] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const options = (candidates.data ?? []).filter((c) => !c.inIntake);
  const add = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!chosen) {
      setError(t("govreport.v.application"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await govreportApi.addApplication(intakeId, chosen);
      setChosen("");
      candidates.reload();
      onAdded();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form
      method="post"
      className="mt-3 flex flex-wrap items-end gap-2"
      onSubmit={add}
      noValidate
      data-testid="rte-add-application"
    >
      <SelectField
        label={t("govreport.rte.addFromAdmissions")}
        name="applicationId"
        value={chosen}
        onChange={(e) => setChosen(e.target.value)}
        options={[
          {
            value: "",
            label: options.length
              ? t("govreport.choose")
              : t("govreport.rte.noCandidates"),
          },
          ...options.map((c) => ({
            value: c.applicationId,
            label: c.childName,
          })),
        ]}
        error={error ?? undefined}
        className="min-w-56 flex-1"
      />
      <button
        type="submit"
        className="btn"
        disabled={busy || options.length === 0}
      >
        {t("govreport.rte.add")}
      </button>
    </form>
  );
}

type ScreenValues = {
  categoryCode: string;
  income: string;
  incomeDeclared: boolean;
  distanceKm: string;
  eligibility: Eligibility;
  ineligibleReason: string;
  docs: Record<string, boolean>;
};

/** Client checks mirroring the API. Exported for tests. */
export function validateScreening(v: ScreenValues): Problems {
  const problems: Problems = {};
  if (v.income.trim() && parseRupees(v.income) === null)
    problems.annualIncomePaise = "govreport.v.amount";
  if (v.distanceKm.trim()) {
    const km = Number(v.distanceKm);
    if (!Number.isFinite(km) || km < 0 || km > 999)
      problems.distanceKm = "govreport.v.distance";
  }
  if (v.eligibility === "ELIGIBLE" && !v.incomeDeclared)
    problems.incomeDeclared = "govreport.v.declaration";
  if (v.eligibility === "INELIGIBLE" && !v.ineligibleReason.trim())
    problems.ineligibleReason = "govreport.v.reason";
  return problems;
}

function ScreenDialog({
  application,
  documents,
  onClose,
  onSaved,
}: {
  application: RteApplicationView;
  documents: string[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const categories = useCodes("SOCIAL_CATEGORY");
  const docLabels = useCodes("RTE_DOCUMENT");
  const form = useForm<ScreenValues>({
    categoryCode: application.categoryCode ?? "",
    income:
      application.annualIncomePaise !== null
        ? rupeesInput(application.annualIncomePaise)
        : "",
    incomeDeclared: application.incomeDeclared,
    distanceKm:
      application.distanceKm !== null ? String(application.distanceKm) : "",
    eligibility:
      application.eligibility === "PENDING"
        ? "ELIGIBLE"
        : application.eligibility,
    ineligibleReason: application.ineligibleReason ?? "",
    docs: Object.fromEntries(
      documents.map((d) => [d, Boolean(application.documents[d])]),
    ),
  });
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateScreening(form.values), t, event.currentTarget))
      return;
    const v = form.values;
    const ok = await form.submit(t, async () => {
      await govreportApi.screen(application.id, {
        categoryCode: v.categoryCode || null,
        annualIncomePaise: v.income.trim() ? parseRupees(v.income) : null,
        incomeDeclared: v.incomeDeclared,
        distanceKm: v.distanceKm.trim() ? Number(v.distanceKm) : null,
        documents: v.docs,
        eligibility: v.eligibility,
        ineligibleReason:
          v.eligibility === "INELIGIBLE" ? v.ineligibleReason.trim() : null,
      });
      onSaved();
      onClose();
    });
    if (ok) toast(t("govreport.rte.screened"));
  };
  const withdraw = async () => {
    const ok = await form.submit(t, async () => {
      await govreportApi.withdraw(application.id);
      onSaved();
      onClose();
    });
    if (ok) toast(t("govreport.rte.withdrawnToast"));
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("govreport.rte.screenTitle", { name: application.childName })}
      closeLabel={t("common.close")}
    >
      <form
        method="post"
        className="flex flex-col gap-3"
        onSubmit={submit}
        noValidate
        data-testid="rte-screen-form"
      >
        <FormAlert message={form.formError} />
        <div className="grid gap-3 sm:grid-cols-2">
          <SelectField
            label={t("govreport.field.socialCategory")}
            name="categoryCode"
            value={form.values.categoryCode}
            onChange={(e) => form.set("categoryCode", e.target.value)}
            options={codeOptions(categories, t("govreport.notSet"))}
            error={form.errors.categoryCode}
          />
          <TextField
            label={t("govreport.rte.income")}
            name="annualIncomePaise"
            inputMode="decimal"
            value={form.values.income}
            onChange={(e) => form.set("income", e.target.value)}
            error={form.errors.annualIncomePaise}
          />
          <TextField
            label={t("govreport.rte.distance")}
            name="distanceKm"
            inputMode="decimal"
            value={form.values.distanceKm}
            onChange={(e) => form.set("distanceKm", e.target.value)}
            error={form.errors.distanceKm}
          />
          <SelectField
            label={t("govreport.rte.outcome")}
            name="eligibility"
            value={form.values.eligibility}
            onChange={(e) =>
              form.set("eligibility", e.target.value as Eligibility)
            }
            options={(["ELIGIBLE", "INELIGIBLE", "PENDING"] as const).map(
              (x) => ({
                value: x,
                label: t(`govreport.rte.eligibility.${x}` as MessageKey),
              }),
            )}
          />
        </div>
        <label className="check">
          <input
            type="checkbox"
            name="incomeDeclared"
            checked={form.values.incomeDeclared}
            onChange={(e) => form.set("incomeDeclared", e.target.checked)}
          />
          <span>{t("govreport.rte.declared")}</span>
        </label>
        {form.errors.incomeDeclared ? (
          <p className="field-error">{form.errors.incomeDeclared}</p>
        ) : null}
        <fieldset className="flex flex-col gap-1.5">
          <legend className="field-label">
            {t("govreport.rte.documents")}
          </legend>
          {documents.map((d) => (
            <label key={d} className="check">
              <input
                type="checkbox"
                name={`doc.${d}`}
                checked={form.values.docs[d] ?? false}
                onChange={(e) =>
                  form.set("docs", {
                    ...form.values.docs,
                    [d]: e.target.checked,
                  })
                }
              />
              <span>{labelOf(docLabels, d)}</span>
            </label>
          ))}
        </fieldset>
        {form.values.eligibility === "INELIGIBLE" ? (
          <TextAreaField
            label={t("govreport.rte.ineligibleReason")}
            name="ineligibleReason"
            rows={2}
            maxLength={300}
            value={form.values.ineligibleReason}
            onChange={(e) => form.set("ineligibleReason", e.target.value)}
            error={form.errors.ineligibleReason}
          />
        ) : null}
        <div className="flex flex-wrap justify-between gap-2">
          <button
            type="button"
            className="btn btn-danger"
            onClick={() => void withdraw()}
            disabled={form.submitting}
          >
            {t("govreport.rte.withdraw")}
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={form.submitting}
          >
            {form.submitting
              ? t("common.working")
              : t("govreport.rte.saveScreening")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type LotteryValues = { witnessName: string; reason: string };

/** Client checks mirroring the API: a witness always, a reason for a rerun. Exported for tests. */
export function validateLottery(v: LotteryValues, rerun: boolean): Problems {
  const problems: Problems = {};
  if (!v.witnessName.trim()) problems.witnessName = "govreport.v.witness";
  if (rerun && !v.reason.trim()) problems.reason = "govreport.v.rerunReason";
  return problems;
}

function LotteryCard({
  detail,
  eligible,
  onRun,
}: {
  detail: IntakeDetail;
  eligible: number;
  onRun: () => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const rerun = detail.runs.length > 0;
  const form = useForm<LotteryValues>({ witnessName: "", reason: "" });
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (
      !form.check(validateLottery(form.values, rerun), t, event.currentTarget)
    )
      return;
    const ok = await form.submit(t, async () => {
      await govreportApi.runLottery(detail.intake.id, {
        witnessName: form.values.witnessName.trim(),
        reason: form.values.reason.trim() || null,
      });
      form.reset({ witnessName: "", reason: "" });
      onRun();
    });
    if (ok) toast(t("govreport.rte.lotteryDone"));
  };
  return (
    <form
      method="post"
      className="card flex flex-col gap-3"
      onSubmit={submit}
      noValidate
      data-testid="rte-lottery"
      aria-labelledby="rte-lottery-head"
    >
      <div className="card-head">
        <h2 id="rte-lottery-head">
          {rerun ? t("govreport.rte.rerun") : t("govreport.rte.lottery")}
        </h2>
      </div>
      <p className="text-[13px] text-ink-3">
        {t("govreport.rte.lotteryHint", {
          eligible,
          seats: detail.intake.rteSeats,
        })}
      </p>
      <FormAlert message={form.formError} />
      <TextField
        label={t("govreport.rte.witness")}
        name="witnessName"
        maxLength={200}
        value={form.values.witnessName}
        onChange={(e) => form.set("witnessName", e.target.value)}
        error={form.errors.witnessName}
      />
      {rerun ? (
        <TextAreaField
          label={t("govreport.rte.reason")}
          name="reason"
          rows={2}
          maxLength={500}
          value={form.values.reason}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
        />
      ) : null}
      <div>
        <button
          type="submit"
          className="btn btn-primary"
          disabled={form.submitting || eligible === 0}
        >
          {form.submitting
            ? t("common.working")
            : t("govreport.rte.runLottery")}
        </button>
      </div>
      <p className="text-[13px] text-ink-3">
        {t("govreport.rte.immutable", {
          amount: formatPaise(detail.intake.incomeLimitPaise),
        })}
      </p>
    </form>
  );
}
