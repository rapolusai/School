"use client";

import { BookOpen } from "lucide-react";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { FineStatusPill, fineKindLabel, HoldStatusPill } from "@/components/views/library/library-shared";
import { formatDate, formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import type { FamilyLibraryMember, FamilyLoan } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/** "Due 14 Oct 2026", "Due today" or "3 days late" for a book on loan. Pure and exported for tests. */
export function dueLine(loan: FamilyLoan, today: string, t: ReturnType<typeof useI18n>["t"], locale: string): string {
  if (loan.overdue) return plural(t, "library.loan.daysOverdue", loan.daysOverdue);
  if (loan.dueOn === today) return t("library.family.dueToday");
  return t("library.loan.due", { date: formatPlainDate(loan.dueOn, locale) });
}

/** /app/family/library: the books each child (or the student) has from the school library, due dates and fines. */
export function FamilyLibraryView() {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const mine = useApiData("me:library", libraryApi.mine);
  const members = mine.data?.members ?? [];
  return (
    <>
      <PageHead title={t("nav.library")} />
      {mine.error && !mine.data ? (
        <ErrorState error={mine.error} onRetry={mine.reload} />
      ) : !mine.data ? (
        <LoadingRows rows={4} />
      ) : members.length === 0 ? (
        <p className="empty">{t("library.family.none")}</p>
      ) : (
        members.map((member) => <MemberCard key={`${member.type}:${member.id}`} member={member} today={today} single={members.length === 1} />)
      )}
      <p className="text-[12.5px] text-ink-3">{t("library.family.rule")}</p>
    </>
  );
}

function MemberCard({ member, today, single }: { member: FamilyLibraryMember; today: string; single: boolean }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const titleId = `library-member-${member.id}`;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={titleId} data-testid="family-library-member">
      <div className="card-head">
        <h2 id={titleId}>
          {single && !member.className ? t("library.family.yourBooks") : member.name}
          {member.className ? <span className="ml-2 text-[13px] font-normal text-ink-3">{member.className}</span> : null}
        </h2>
        {member.finesDuePaise > 0 ? (
          <Pill tone="warn">{t("library.family.finesDue", { amount: formatPaise(member.finesDuePaise) })}</Pill>
        ) : null}
      </div>

      {member.loans.length === 0 ? (
        <p className="text-[13.5px] text-ink-3" data-testid="family-library-empty">
          {t("library.family.noLoans")}
        </p>
      ) : (
        <ul className="rowcards" aria-label={t("library.family.onLoan")}>
          {member.loans.map((loan) => (
            <li key={loan.accessionNo} className="rowcard items-start" data-testid="family-library-loan">
              <span className="badge-ic" aria-hidden="true">
                <BookOpen size={18} />
              </span>
              <span className="min-w-0 flex-1">
                <b className="block">{loan.title}</b>
                {loan.authors ? <span className="block text-[12.5px] text-ink-3">{loan.authors}</span> : null}
                <span className="block text-[12.5px] text-ink-3">
                  {t("library.family.borrowed", { date: formatPlainDate(loan.issuedOn, locale) })}
                </span>
              </span>
              <span className="flex flex-col items-end gap-1 text-right">
                <Pill tone={loan.overdue ? "bad" : loan.dueOn === today ? "warn" : "neutral"}>{dueLine(loan, today, t, locale)}</Pill>
                {loan.overdue ? (
                  <span className="text-[12.5px] text-ink-2">
                    {t("library.family.dueWas", { date: formatPlainDate(loan.dueOn, locale) })}
                  </span>
                ) : null}
                {loan.fineSoFarPaise > 0 ? (
                  <span className="text-[12.5px] font-semibold text-bad">
                    {t("library.loan.fineSoFar", { amount: formatPaise(loan.fineSoFarPaise) })}
                  </span>
                ) : null}
              </span>
            </li>
          ))}
        </ul>
      )}

      {member.holds.length > 0 ? (
        <div className="flex flex-col gap-1.5">
          <h3 className="text-[14px] font-semibold">{t("library.family.holds")}</h3>
          <ul className="flex flex-col gap-1.5">
            {member.holds.map((hold, i) => (
              <li key={i} className="flex flex-wrap items-center justify-between gap-2 text-[13.5px]">
                <span>{hold.title ?? "—"}</span>
                <span className="flex items-center gap-2">
                  {hold.status === "READY" && hold.holdUntil ? (
                    <span className="text-[12.5px] text-ink-2">
                      {t("library.family.collectBy", { date: formatPlainDate(hold.holdUntil, locale) })}
                    </span>
                  ) : null}
                  <HoldStatusPill status={hold.status} />
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {member.fines.length > 0 ? (
        <div className="flex flex-col gap-1.5" data-testid="family-library-fines">
          <h3 className="text-[14px] font-semibold">{t("library.family.fines")}</h3>
          <ul className="flex flex-col gap-1.5">
            {member.fines.map((fine, i) => (
              <li key={i} className="flex flex-wrap items-center justify-between gap-2 text-[13.5px]">
                <span className="min-w-0">
                  {fine.title ?? "—"} · {fineKindLabel(t, fine.kind)}
                  <span className="block text-[12.5px] text-ink-3">
                    {[formatDate(fine.createdAt, locale), fine.receiptNo].filter(Boolean).join(" · ")}
                  </span>
                </span>
                <span className="flex items-center gap-2">
                  <b className="num">{formatPaise(fine.amountPaise)}</b>
                  <FineStatusPill status={fine.status} />
                </span>
              </li>
            ))}
          </ul>
          {member.finesDuePaise > 0 ? <p className="text-[12.5px] text-ink-3">{t("library.family.payAtLibrary")}</p> : null}
        </div>
      ) : null}
    </section>
  );
}
