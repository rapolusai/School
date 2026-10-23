"use client";

import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { Figure } from "@/components/views/reports/report-frame";
import { assistApi } from "@/lib/assist-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";
import { AssistNav, currentMonth } from "./assist-shared";

/** School Admin: the month's assistance use by person and feature. Only a hash of each request is kept. */
export function AssistUsageView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [month, setMonth] = useState(currentMonth());
  const usage = useApiData(`assist:usage:${month}`, () => assistApi.usage(month));
  const data = usage.data;
  return (
    <div className="flex flex-col gap-4">
      <PageHead eyebrow={t("assist.eyebrow")} title={t("assist.usage.title")} />
      <AssistNav />
      <label className="field w-48">
        <span className="field-label">{t("assist.usage.month")}</span>
        <input className="input" type="month" name="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} />
      </label>
      {usage.error && !data ? (
        <ErrorState error={usage.error} onRetry={usage.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("assist.usage.used")} value={`${data.used} / ${data.limit}`} testId="usage-used" />
            <Figure label={t("assist.usage.tokens")} value={data.tokens} />
            {data.byFeature.map((f) => (
              <Figure key={f.feature} label={t(`assist.feature.${f.feature}` as MessageKey)} value={f.calls} />
            ))}
          </div>
          <p className="text-[13px] text-ink-3">{t("assist.usage.hint")}</p>
          {data.rows.length === 0 ? (
            <p className="empty">{t("assist.usage.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table" data-testid="usage-table">
                <caption className="sr-only">{t("assist.usage.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("assist.usage.when")}</th>
                    <th scope="col">{t("assist.usage.who")}</th>
                    <th scope="col">{t("assist.usage.feature")}</th>
                    <th scope="col">{t("assist.usage.provider")}</th>
                    <th scope="col" className="r">
                      {t("assist.usage.tokens")}
                    </th>
                    <th scope="col">{t("assist.usage.hash")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={row.id}>
                      <td>{formatDateTime(row.at, locale)}</td>
                      <td>{row.userName}</td>
                      <td>{t(`assist.feature.${row.feature}` as MessageKey)}</td>
                      <td>{row.provider}</td>
                      <td className="r num">{row.tokenEstimate}</td>
                      <td>
                        <code title={row.requestHash}>{row.requestHash.slice(0, 12)}</code>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}
