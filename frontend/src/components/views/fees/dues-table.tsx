"use client";

import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { InstalmentDue } from "@/lib/types";
import { DueStatusPill } from "./fee-ui";

type Selection = {
  selected: ReadonlySet<string>;
  onToggle: (instalmentId: string) => void;
  /** Only instalments with something to pay can be picked. */
  canSelect: (instalment: InstalmentDue) => boolean;
};

/**
 * A student's dues per instalment with the fee heads under each one: a table from 768px, cards
 * on phones. With `selection` each open instalment gets a checkbox; `action` adds a control per
 * instalment (such as waiving its late fee).
 */
export function DuesTable({
  instalments,
  selection,
  action,
  caption,
}: {
  instalments: InstalmentDue[];
  selection?: Selection;
  action?: (instalment: InstalmentDue) => React.ReactNode;
  caption: string;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);

  const checkbox = (i: InstalmentDue) =>
    selection && selection.canSelect(i) ? (
      <input
        type="checkbox"
        className="h-[18px] w-[18px] accent-[var(--accent)]"
        name="instalmentIds"
        value={i.instalmentId}
        checked={selection.selected.has(i.instalmentId)}
        onChange={() => selection.onToggle(i.instalmentId)}
        aria-label={t("fees.dues.select", { label: i.label })}
      />
    ) : null;

  const lateFee = (i: InstalmentDue) =>
    i.lateFeePaise > 0 ? (
      <span className="text-bad">{formatPaise(i.lateFeePaise)}</span>
    ) : i.lateFeeWaived ? (
      <span className="text-[12.5px] text-ink-3">{t("fees.dues.waived")}</span>
    ) : (
      "—"
    );

  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid="dues-table">
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr>
              {selection ? (
                <th scope="col" className="w-8">
                  <span className="sr-only">{t("fees.dues.pick")}</span>
                </th>
              ) : null}
              <th scope="col">{t("fees.dues.col.instalment")}</th>
              <th scope="col">{t("fees.dues.col.status")}</th>
              <th scope="col" className="r">
                {t("fees.dues.col.net")}
              </th>
              <th scope="col" className="r">
                {t("fees.dues.col.paid")}
              </th>
              <th scope="col" className="r">
                {t("fees.dues.col.balance")}
              </th>
              <th scope="col" className="r">
                {t("fees.dues.col.lateFee")}
              </th>
              {action ? (
                <th scope="col">
                  <span className="sr-only">{t("common.actions")}</span>
                </th>
              ) : null}
            </tr>
          </thead>
          {instalments.map((i) => (
            <tbody key={i.instalmentId} className="dues-group" data-testid="dues-row">
              <tr>
                {selection ? <td>{checkbox(i)}</td> : null}
                <td>
                  <b className="block font-semibold">{i.label}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {t("fees.dues.dueOn", { date: formatPlainDate(i.dueDate, locale) })}
                    {i.daysOverdue > 0 ? ` · ${plural(t, "fees.dues.daysLate", i.daysOverdue)}` : ""}
                  </span>
                </td>
                <td>
                  <DueStatusPill status={i.status} />
                </td>
                <td className="r num">{formatPaise(i.netPaise)}</td>
                <td className="r num">{formatPaise(i.paidPaise)}</td>
                <td className="r num font-semibold">{formatPaise(i.balancePaise)}</td>
                <td className="r num">{lateFee(i)}</td>
                {action ? <td className="r">{action(i)}</td> : null}
              </tr>
              {i.heads.map((h) => (
                <tr key={h.dueId} className="dues-head">
                  {selection ? <td /> : null}
                  <td colSpan={2}>
                    {h.headName}
                    {h.concessionPaise > 0 ? (
                      <span className="ml-2 text-ink-3">
                        {t("fees.dues.concession", {
                          gross: formatPaise(h.grossPaise),
                          concession: formatPaise(h.concessionPaise),
                        })}
                      </span>
                    ) : null}
                  </td>
                  <td className="r num">{formatPaise(h.netPaise)}</td>
                  <td className="r num">{formatPaise(h.paidPaise)}</td>
                  <td className="r num">{formatPaise(h.balancePaise)}</td>
                  <td />
                  {action ? <td /> : null}
                </tr>
              ))}
            </tbody>
          ))}
        </table>
      </div>

      <ul className="rowcards md:hidden" data-testid="dues-cards" aria-label={caption}>
        {instalments.map((i) => (
          <li key={i.instalmentId} className="dues-card">
            <div className="flex items-start gap-3">
              {selection ? <span className="pt-0.5">{checkbox(i)}</span> : null}
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <b className="font-semibold">{i.label}</b>
                  <DueStatusPill status={i.status} />
                </div>
                <p className="text-[12.5px] text-ink-3">
                  {t("fees.dues.dueOn", { date: formatPlainDate(i.dueDate, locale) })}
                  {i.daysOverdue > 0 ? ` · ${plural(t, "fees.dues.daysLate", i.daysOverdue)}` : ""}
                </p>
                <dl className="dues-card-amounts">
                  <dt>{t("fees.dues.col.net")}</dt>
                  <dd className="num">{formatPaise(i.netPaise)}</dd>
                  <dt>{t("fees.dues.col.paid")}</dt>
                  <dd className="num">{formatPaise(i.paidPaise)}</dd>
                  <dt>{t("fees.dues.col.balance")}</dt>
                  <dd className="num font-semibold">{formatPaise(i.balancePaise)}</dd>
                  {i.lateFeePaise > 0 || i.lateFeeWaived ? (
                    <>
                      <dt>{t("fees.dues.col.lateFee")}</dt>
                      <dd className="num">{lateFee(i)}</dd>
                    </>
                  ) : null}
                </dl>
                <ul className="mt-1 text-[12.5px] text-ink-3">
                  {i.heads.map((h) => (
                    <li key={h.dueId} className="flex justify-between gap-2">
                      <span className="min-w-0 truncate">{h.headName}</span>
                      <span className="num">{formatPaise(h.netPaise)}</span>
                    </li>
                  ))}
                </ul>
                {action ? <div className="mt-2">{action(i)}</div> : null}
              </div>
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}
