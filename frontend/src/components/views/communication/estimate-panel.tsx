"use client";

import { Users } from "lucide-react";
import type { ApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { plural, useI18n } from "@/lib/i18n";
import type { CircularChannel, CircularEstimate } from "@/lib/types";
import { channelLabel, formatPaise } from "./communication-labels";

/**
 * Who a circular reaches and what its messages would cost (indicative), worked out by the API before anything is
 * sent. `empty` explains what to choose when there is nothing to estimate yet.
 */
export function EstimatePanel({
  estimate,
  channels,
  loading,
  error,
  empty,
}: {
  estimate: CircularEstimate | undefined;
  channels: CircularChannel[];
  loading: boolean;
  error: ApiError | undefined;
  empty: string | null;
}) {
  const { t } = useI18n();
  const phoneChannels = channels.some((c) => c === "SMS" || c === "WHATSAPP");
  return (
    <section className="estimate" aria-labelledby="estimate-heading" aria-live="polite" data-testid="estimate">
      <h2 id="estimate-heading" className="flex items-center gap-2 text-[15px]">
        <Users size={18} className="text-ink-3" aria-hidden="true" />
        {t("notices.estimate.title")}
      </h2>
      {empty ? (
        <p className="text-[13.5px] text-ink-3">{empty}</p>
      ) : error && !estimate ? (
        <p className="text-[13.5px] text-bad">{errorMessage(error, t)}</p>
      ) : !estimate ? (
        <div className="skeleton h-16" aria-hidden="true" />
      ) : (
        <div className="flex flex-col gap-2.5" aria-busy={loading}>
          <p className="text-[13.5px]" data-testid="estimate-people">
            {t("notices.estimate.people", {
              parents: estimate.parents,
              students: estimate.students,
              staff: estimate.staff,
            })}
          </p>
          <p className="text-[13.5px] text-ink-2">{plural(t, "notices.estimate.inApp", estimate.inApp)}</p>
          {phoneChannels ? (
            <p className="text-[13.5px] text-ink-2">{plural(t, "notices.estimate.phones", estimate.phones)}</p>
          ) : null}
          {phoneChannels && estimate.parentsWithoutPhone > 0 ? (
            <p className="text-[13px] text-warn">
              {plural(t, "notices.estimate.noPhone", estimate.parentsWithoutPhone)}
            </p>
          ) : null}
          {channels.includes("EMAIL") ? (
            <p className="text-[13.5px] text-ink-2">{plural(t, "notices.estimate.emails", estimate.emails)}</p>
          ) : null}
          {estimate.channels.length > 0 ? (
            <table className="estimate-table" data-testid="estimate-channels">
              <caption className="sr-only">{t("notices.estimate.costCaption")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("notices.estimate.channel")}</th>
                  <th scope="col" className="r">
                    {t("notices.estimate.messages")}
                  </th>
                  <th scope="col" className="r">
                    {t("notices.estimate.cost")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {estimate.channels.map((c) => (
                  <tr key={c.channel}>
                    <th scope="row">{channelLabel(t, c.channel)}</th>
                    <td className="r num">
                      {c.messages}
                      {c.channel === "SMS" && c.messages > 0 ? (
                        <span className="block text-[11.5px] text-ink-3">
                          {plural(t, "notices.estimate.parts", estimate.smsParts)}
                        </span>
                      ) : null}
                    </td>
                    <td className="r num">{formatPaise(c.costPaise)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("notices.estimate.total")}</th>
                  <td />
                  <td className="r num" data-testid="estimate-total">
                    {formatPaise(estimate.costPaise)}
                  </td>
                </tr>
              </tfoot>
            </table>
          ) : (
            <p className="text-[13px] text-ink-3">{t("notices.estimate.appOnly")}</p>
          )}
          {channels.includes("SMS") && estimate.unicode ? (
            <p className="text-[12.5px] text-ink-3">{t("notices.estimate.unicode")}</p>
          ) : null}
          {estimate.channels.length > 0 ? (
            <p className="text-[12.5px] text-ink-3">{t("notices.estimate.note")}</p>
          ) : null}
        </div>
      )}
    </section>
  );
}
