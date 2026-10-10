"use client";

import { ArrowLeft, Download, Printer } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { FormAlert, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime } from "@/lib/format";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import { downloadXlsx } from "@/lib/reports-api";
import type { ClassView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/* Shared parts of the report screens: the page frame with Excel and print, filters and date ranges. */

/** The longest date range a report accepts (the API's limit, docs/api/phase-1-reports.md). */
export const MAX_RANGE_DAYS = 400;

const DATE = /^\d{4}-\d{2}-\d{2}$/;

function utc(date: string): number {
  return Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)) - 1, Number(date.slice(8, 10)));
}

/** "2026-10-09" plus n days, as "YYYY-MM-DD". */
export function addDays(date: string, days: number): string {
  return new Date(utc(date) + days * 86_400_000).toISOString().slice(0, 10);
}

/** The first day of the date's month. */
export function monthStart(date: string): string {
  return `${date.slice(0, 7)}-01`;
}

/** What is wrong with a from–to range, as a message key, or null when it is fine. Exported for tests. */
export function rangeProblem(from: string, to: string): MessageKey | null {
  if (!DATE.test(from) || !DATE.test(to)) return "reports.v.dates";
  if (to < from) return "reports.v.order";
  if ((utc(to) - utc(from)) / 86_400_000 >= MAX_RANGE_DAYS) return "reports.v.long";
  return null;
}

/** Classes for the class filter (academics.read, which every staff role holds). */
export function useClasses(): ClassView[] {
  return useApiData("reports:classes", api.listClasses).data ?? [];
}

/** The name of an option by id, for the print header's filter line. */
export function nameOf(list: { id: string; name: string }[], id: string): string | null {
  return id ? (list.find((item) => item.id === id)?.name ?? null) : null;
}

export function ClassFilter({
  classes,
  value,
  onChange,
}: {
  classes: ClassView[];
  value: string;
  onChange: (id: string) => void;
}) {
  const { t } = useI18n();
  return (
    <label className="field w-full sm:w-auto sm:min-w-44">
      <span className="field-label">{t("reports.filter.class")}</span>
      <select className="input" name="classId" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">{t("reports.filter.allClasses")}</option>
        {classes.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </select>
    </label>
  );
}

export function DateFilter({
  name,
  label,
  value,
  onChange,
  max,
  invalid,
}: {
  name: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  max?: string;
  invalid?: boolean;
}) {
  return (
    <label className="field w-[calc(50%-4px)] sm:w-auto">
      <span className="field-label">{label}</span>
      <input
        type="date"
        className="input"
        name={name}
        value={value}
        max={max}
        required
        aria-invalid={invalid || undefined}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}

/**
 * A report page: title, back link, Excel download and print, the filters (screen only), and a print header
 * with the school, the report, the filters and the date, so the browser's "Save as PDF" gives a clean A4 page.
 */
export function ReportFrame({
  title,
  testId,
  filters,
  filterSummary,
  problem,
  excel,
  ready,
  children,
}: {
  title: string;
  testId: string;
  filters: React.ReactNode;
  /** "Class: Class 5" and so on, for the print header */
  filterSummary: string[];
  /** a validation message; the report is not loaded while there is one */
  problem: string | null;
  /** the Excel path and the file name to use if the server sends none; null while it cannot be exported */
  excel: { path: string; fileName: string } | null;
  /** the report has loaded, so it can be printed */
  ready: boolean;
  children: React.ReactNode;
}) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const [printedAt] = useState(() => new Date());
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const download = async () => {
    if (!excel) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      const name = await downloadXlsx(excel.path, excel.fileName);
      toast(t("reports.downloaded", { name }));
    } catch (caught) {
      setDownloadError(errorMessage(toApiError(caught), t));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <>
      <PageHead
        eyebrow={t("nav.reports")}
        title={title}
        actions={
          <>
            <Link href="/app/reports" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("reports.back")}
            </Link>
            <button
              type="button"
              className="btn"
              onClick={download}
              disabled={!excel || !ready || problem !== null || downloading}
            >
              <Download size={18} aria-hidden="true" />
              {downloading ? t("common.working") : t("reports.excel")}
            </button>
            <button
              type="button"
              className="btn"
              onClick={() => window.print()}
              disabled={!ready || problem !== null}
            >
              <Printer size={18} aria-hidden="true" />
              {t("reports.print")}
            </button>
          </>
        }
      />
      <FormAlert message={downloadError} />

      <section className="card report-doc" data-testid={testId}>
        <header className="report-print-head" data-testid="report-print-head">
          <p className="report-print-school">{me?.tenant?.name}</p>
          <h1>{title}</h1>
          {filterSummary.length > 0 ? <p>{filterSummary.join(" · ")}</p> : null}
          <p>
            {t("reports.printedBy", {
              date: formatDateTime(printedAt, localeFor(lang)),
              name: me?.name ?? "",
            })}
          </p>
        </header>
        <form
          method="post"
          className="toolbar report-filters no-print"
          aria-label={t("reports.filters")}
          onSubmit={(e) => e.preventDefault()}
        >
          {filters}
        </form>
        {problem ? (
          <div className="no-print">
            <FormAlert message={problem} />
          </div>
        ) : null}
        <div className="report-body">{children}</div>
      </section>
    </>
  );
}

/** A labelled number in a report's summary strip. */
export function Figure({ label, value, testId }: { label: string; value: React.ReactNode; testId?: string }) {
  return (
    <div className="report-figure" data-testid={testId}>
      <span className="report-figure-label">{label}</span>
      <b className="report-figure-value num">{value}</b>
    </div>
  );
}
