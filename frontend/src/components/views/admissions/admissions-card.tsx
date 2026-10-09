"use client";

import { ArrowRight, ClipboardList } from "lucide-react";
import Link from "next/link";
import { ErrorState } from "@/components/ui/states";
import { admissionsApi } from "@/lib/admissions-api";
import { plural, useI18n, type MessageKey } from "@/lib/i18n";
import type { AdmissionsSummary } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

const FIGURES: { key: keyof AdmissionsSummary; label: MessageKey }[] = [
  { key: "openEnquiries", label: "admissions.summary.openEnquiries" },
  { key: "offersPending", label: "admissions.summary.offersPending" },
  { key: "admittedThisYear", label: "admissions.summary.admittedThisYear" },
];

/** The dashboard's admissions numbers, for the people who run admissions. */
export function AdmissionsCard() {
  const { t } = useI18n();
  const summary = useApiData("admissions:summary", admissionsApi.summary);
  const data = summary.data;

  return (
    <section className="card" aria-labelledby="admissions-card-heading" data-testid="admissions-card">
      <div className="card-head">
        <h2 id="admissions-card-heading" className="flex items-center gap-2">
          <ClipboardList size={18} className="text-ink-3" aria-hidden="true" />
          {t("admissions.summary.title")}
        </h2>
        <Link href="/app/admissions" className="link inline-flex items-center gap-1 text-[13.5px]">
          {t("admissions.summary.link")}
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
      </div>
      {summary.error && !data ? (
        <ErrorState error={summary.error} onRetry={summary.reload} />
      ) : (
        <dl className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          {FIGURES.map(({ key, label }) => (
            <div key={key} className="flex flex-col-reverse gap-1 rounded-lg bg-surface-2 p-3">
              <dt className="text-[13px] font-medium text-ink-2">{t(label)}</dt>
              <dd className="m-0">
                {data ? (
                  <span className="kpi-value">{data[key]}</span>
                ) : (
                  <span className="skeleton block h-8 w-12" aria-hidden="true" />
                )}
              </dd>
            </div>
          ))}
        </dl>
      )}
      {data && data.upcomingSlots > 0 ? (
        <p className="mt-3 text-[13px] text-ink-3">{plural(t, "admissions.summary.upcoming", data.upcomingSlots)}</p>
      ) : null}
    </section>
  );
}
