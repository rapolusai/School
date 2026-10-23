"use client";

import Link from "next/link";
import { useState } from "react";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill, type PillTone } from "@/components/ui/pill";
import {
  ErrorState,
  FormAlert,
  LoadingRows,
  PageHead,
} from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api } from "@/lib/api";
import { formatPaise, formatPlainDate, parseRupees } from "@/lib/format";
import { govreportApi } from "@/lib/govreport-api";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import type { IntakeStatus, IntakeView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  GovReportNav,
  useCanManage,
  useYears,
  VerifyNote,
  YearSelect,
} from "./govreport-shared";

/* /app/govreport/rte: the RTE 25% intakes of a year, and a new intake. */

const STATUS_TONE: Record<IntakeStatus, PillTone> = {
  OPEN: "info",
  LOTTERY_DONE: "good",
  CLOSED: "neutral",
};

export function IntakeStatusPill({ status }: { status: IntakeStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={STATUS_TONE[status]}>
      {t(`govreport.rte.status.${status}` as MessageKey)}
    </Pill>
  );
}

export function RteIntakesView() {
  const { t } = useI18n();
  const canManage = useCanManage();
  const { current } = useYears();
  const [chosen, setChosen] = useState("");
  const yearId = chosen || current;
  const intakes = useApiData(
    yearId ? `govreport:intakes:${yearId}` : null,
    () => govreportApi.intakes(yearId),
  );
  return (
    <>
      <PageHead eyebrow={t("nav.govreport")} title={t("govreport.rte.title")} />
      <GovReportNav />
      <p className="-mt-1 max-w-3xl text-ink-2">{t("govreport.rte.intro")}</p>
      <VerifyNote />
      <div className="toolbar">
        <YearSelect value={yearId} onChange={setChosen} />
      </div>
      <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-3">
        <section
          className="card xl:col-span-2"
          aria-labelledby="rte-intakes"
          data-testid="rte-intakes"
        >
          <div className="card-head">
            <h2 id="rte-intakes">{t("govreport.rte.intakes")}</h2>
          </div>
          {intakes.error && !intakes.data ? (
            <ErrorState error={intakes.error} onRetry={intakes.reload} />
          ) : !intakes.data ? (
            <LoadingRows rows={3} />
          ) : intakes.data.length === 0 ? (
            <p className="empty">{t("govreport.rte.none")}</p>
          ) : (
            <ul className="rowcards">
              {intakes.data.map((i) => (
                <li key={i.id}>
                  <Link
                    href={`/app/govreport/rte/${encodeURIComponent(i.id)}`}
                    className="rowcard"
                    data-testid="rte-intake-link"
                  >
                    <span className="min-w-0 flex-1">
                      <b className="block font-semibold">
                        {i.className ?? "—"}
                      </b>
                      <span className="block text-[13px] text-ink-3">
                        {t("govreport.rte.seatsLine", {
                          rte: i.rteSeats,
                          total: i.totalSeats,
                          applicants: i.applicants,
                          eligible: i.eligible,
                        })}
                      </span>
                    </span>
                    <IntakeStatusPill status={i.status} />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
        {canManage && yearId ? (
          <NewIntakeCard yearId={yearId} onCreated={intakes.reload} />
        ) : null}
      </div>
    </>
  );
}

type IntakeValues = {
  entryClassId: string;
  totalSeats: string;
  rteSeats: string;
  stateCode: string;
  incomeLimit: string;
  closeOn: string;
};

/** Client checks mirroring the API. Exported for tests. */
export function validateIntake(v: IntakeValues): Problems {
  const problems: Problems = {};
  if (!v.entryClassId) problems.entryClassId = "govreport.v.class";
  const seats = Number(v.totalSeats);
  if (!Number.isInteger(seats) || seats < 1 || seats > 2000)
    problems.totalSeats = "govreport.v.seats";
  if (v.rteSeats.trim()) {
    const rte = Number(v.rteSeats);
    if (!Number.isInteger(rte) || rte < 0 || rte > 2000)
      problems.rteSeats = "govreport.v.seats";
    else if (Number.isInteger(seats) && rte > seats)
      problems.rteSeats = "govreport.v.rteSeats";
  }
  if (v.stateCode.trim() && !/^[A-Za-z]{2}$/.test(v.stateCode.trim()))
    problems.stateCode = "govreport.v.state";
  if (v.incomeLimit.trim() && parseRupees(v.incomeLimit) === null)
    problems.incomeLimit = "govreport.v.amount";
  if (!v.stateCode.trim() && !v.incomeLimit.trim())
    problems.incomeLimit = "govreport.v.incomeOrState";
  return problems;
}

function NewIntakeCard({
  yearId,
  onCreated,
}: {
  yearId: string;
  onCreated: () => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const classes = useApiData("govreport:classes", api.listClasses).data ?? [];
  const blank: IntakeValues = {
    entryClassId: "",
    totalSeats: "",
    rteSeats: "",
    stateCode: "",
    incomeLimit: "",
    closeOn: "",
  };
  const form = useForm<IntakeValues>(blank);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateIntake(form.values), t, event.currentTarget))
      return;
    const v = form.values;
    const ok = await form.submit(t, async () => {
      await govreportApi.createIntake({
        academicYearId: yearId,
        entryClassId: v.entryClassId,
        totalSeats: Number(v.totalSeats),
        rteSeats: v.rteSeats.trim() ? Number(v.rteSeats) : null,
        stateCode: v.stateCode.trim() ? v.stateCode.trim().toUpperCase() : null,
        incomeLimitPaise: v.incomeLimit.trim()
          ? parseRupees(v.incomeLimit)
          : null,
        applicationsCloseOn: v.closeOn || null,
      });
      form.reset(blank);
      onCreated();
    });
    if (ok) toast(t("govreport.rte.created"));
  };
  return (
    <form
      method="post"
      className="card flex flex-col gap-3"
      onSubmit={submit}
      noValidate
      data-testid="rte-new-intake"
      aria-labelledby="rte-new"
    >
      <div className="card-head">
        <h2 id="rte-new">{t("govreport.rte.new")}</h2>
      </div>
      <FormAlert message={form.formError} />
      <SelectField
        label={t("govreport.rte.entryClass")}
        name="entryClassId"
        value={form.values.entryClassId}
        onChange={(e) => form.set("entryClassId", e.target.value)}
        options={[
          { value: "", label: t("govreport.choose") },
          ...classes.map((c) => ({ value: c.id, label: c.name })),
        ]}
        error={form.errors.entryClassId}
      />
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label={t("govreport.rte.totalSeats")}
          name="totalSeats"
          inputMode="numeric"
          value={form.values.totalSeats}
          onChange={(e) => form.set("totalSeats", e.target.value)}
          error={form.errors.totalSeats}
        />
        <TextField
          label={t("govreport.rte.rteSeats")}
          name="rteSeats"
          inputMode="numeric"
          value={form.values.rteSeats}
          onChange={(e) => form.set("rteSeats", e.target.value)}
          error={form.errors.rteSeats}
          hint={t("govreport.rte.rteSeatsHint")}
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
          label={t("govreport.rte.incomeLimit")}
          name="incomeLimit"
          inputMode="decimal"
          value={form.values.incomeLimit}
          onChange={(e) => form.set("incomeLimit", e.target.value)}
          error={form.errors.incomeLimit}
          hint={t("govreport.rte.incomeHint")}
        />
        <TextField
          label={t("govreport.rte.closeOn")}
          name="closeOn"
          type="date"
          value={form.values.closeOn}
          onChange={(e) => form.set("closeOn", e.target.value)}
          error={form.errors.applicationsCloseOn}
        />
      </div>
      <div>
        <button
          type="submit"
          className="btn btn-primary"
          disabled={form.submitting}
        >
          {form.submitting ? t("common.working") : t("govreport.rte.create")}
        </button>
      </div>
    </form>
  );
}

/** The intake's rules as a short list. */
export function IntakeRules({ intake }: { intake: IntakeView }) {
  const { t, lang } = useI18n();
  return (
    <dl className="kv" data-testid="rte-intake-rules">
      <dt>{t("govreport.rte.entryClass")}</dt>
      <dd>{intake.className ?? "—"}</dd>
      <dt>{t("govreport.rte.seats")}</dt>
      <dd className="num">
        {t("govreport.rte.seatsOf", {
          rte: intake.rteSeats,
          total: intake.totalSeats,
        })}
      </dd>
      <dt>{t("govreport.rte.incomeLimit")}</dt>
      <dd className="num">{formatPaise(intake.incomeLimitPaise)}</dd>
      <dt>{t("govreport.rte.bands")}</dt>
      <dd>
        {t("govreport.rte.bandsLine", {
          b1: intake.distanceBand1Km,
          b2: intake.distanceBand2Km,
          b3: intake.distanceBand3Km,
        })}
      </dd>
      {intake.applicationsCloseOn ? (
        <>
          <dt>{t("govreport.rte.closeOn")}</dt>
          <dd>
            {formatPlainDate(intake.applicationsCloseOn, localeFor(lang))}
          </dd>
        </>
      ) : null}
    </dl>
  );
}
