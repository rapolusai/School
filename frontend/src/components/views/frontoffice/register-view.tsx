"use client";

import { Printer } from "lucide-react";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { frontOfficeApi } from "@/lib/frontoffice-api";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";
import { FrontOfficeNav, idTypeLabel, purposeLabel, VisitorStatusPill, when } from "./frontoffice-shared";

/** /app/frontoffice/register: the printable daily visitor register for a day. */
export function RegisterView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { me } = useAuth();
  const [today] = useState(() => todayInIndia());
  const [date, setDate] = useState(today);
  const valid = /^\d{4}-\d{2}-\d{2}$/.test(date);
  const register = useApiData(valid ? `frontoffice:register:${date}` : null, () => frontOfficeApi.register(date));
  const rows = register.data ?? [];
  return (
    <>
      <PageHead
        eyebrow={t("nav.frontoffice")}
        title={t("frontoffice.register.title")}
        actions={
          <button type="button" className="btn btn-primary" onClick={() => window.print()} disabled={!register.data}>
            <Printer size={18} aria-hidden="true" />
            {t("frontoffice.register.print")}
          </button>
        }
      />
      <FrontOfficeNav />
      <div className="toolbar no-print">
        <label className="field w-full sm:w-auto">
          <span className="field-label">{t("frontoffice.register.date")}</span>
          <input type="date" className="input" name="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} required />
        </label>
      </div>
      <section className="card print-area" data-testid="visitor-register">
        <header className="report-print-head">
          <p className="receipt-school">{me?.tenant?.name ?? ""}</p>
          <h2>{t("frontoffice.register.heading", { date: valid ? formatPlainDate(date, locale) : date })}</h2>
          <p className="text-[13px] text-ink-3">{register.data ? plural(t, "frontoffice.visitors.count", rows.length) : ""}</p>
        </header>
        {register.error && !register.data ? (
          <ErrorState error={register.error} onRetry={register.reload} />
        ) : !register.data ? (
          <LoadingRows rows={4} />
        ) : rows.length === 0 ? (
          <p className="empty">{t("frontoffice.register.empty")}</p>
        ) : (
          <div className="table-wrap">
            <table className="table report-table">
              <caption className="sr-only">{t("frontoffice.register.title")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("frontoffice.field.badge")}</th>
                  <th scope="col">{t("frontoffice.field.name")}</th>
                  <th scope="col">{t("frontoffice.field.phone")}</th>
                  <th scope="col">{t("frontoffice.field.purpose")}</th>
                  <th scope="col">{t("frontoffice.field.toMeet")}</th>
                  <th scope="col" className="r">
                    {t("frontoffice.field.people")}
                  </th>
                  <th scope="col">{t("frontoffice.field.idType")}</th>
                  <th scope="col">{t("frontoffice.slip.in")}</th>
                  <th scope="col">{t("frontoffice.slip.out")}</th>
                  <th scope="col">{t("frontoffice.col.status")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((v) => (
                  <tr key={v.id}>
                    <td className="mono">{v.badgeNo ?? "—"}</td>
                    <td className="font-semibold">{v.name}</td>
                    <td className="num">{v.phoneMasked || "—"}</td>
                    <td>
                      {purposeLabel(t, v.purpose)}
                      {v.purposeNote ? <span className="block text-[12.5px] text-ink-3">{v.purposeNote}</span> : null}
                    </td>
                    <td>{v.toMeetName ?? "—"}</td>
                    <td className="num r">{v.peopleCount}</td>
                    <td>{idTypeLabel(t, v.idType)}</td>
                    <td>{when(v.checkedInAt, locale)}</td>
                    <td>{when(v.checkedOutAt, locale)}</td>
                    <td>
                      <VisitorStatusPill status={v.status} />
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
