"use client";

import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { FineStatusPill, fineKindLabel, MemberPicker, memberName, memberSub, memberTypeLabel } from "@/components/views/library/library-shared";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { libraryApi, libraryReportPaths, type OverdueParams, type PopularParams } from "@/lib/library-api";
import { MEMBER_TYPES, type LibraryMember, type LoanView, type MemberType } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { addDays, ClassFilter, DateFilter, Figure, nameOf, rangeProblem, ReportFrame, useClasses } from "./report-frame";

/* The library's reports in the reports hub (docs/api/phase-2-library.md, "Reports"). Downloads are CSV files that
   open in Excel. */

const DATE = /^\d{4}-\d{2}-\d{2}$/;

function MemberTypeFilter({ value, onChange }: { value: MemberType | ""; onChange: (value: MemberType | "") => void }) {
  const { t } = useI18n();
  return (
    <label className="field w-full sm:w-auto sm:min-w-40">
      <span className="field-label">{t("library.report.memberType")}</span>
      <select className="input" name="memberType" value={value} onChange={(e) => onChange(e.target.value as MemberType | "")}>
        <option value="">{t("library.report.allMembers")}</option>
        {MEMBER_TYPES.map((type) => (
          <option key={type} value={type}>
            {memberTypeLabel(t, type)}
          </option>
        ))}
      </select>
    </label>
  );
}

/** The member and their class (students) or designation (staff), for a report row. */
function MemberCell({ loan }: { loan: LoanView }) {
  const { t } = useI18n();
  return (
    <>
      <span className="block font-semibold">{memberName(t, loan.member)}</span>
      <span className="block text-[12.5px] font-normal text-ink-3">{memberSub(loan.member)}</span>
    </>
  );
}

function BookCell({ loan }: { loan: LoanView }) {
  return (
    <>
      <span className="block">{loan.title}</span>
      <span className="mono block text-[12.5px] text-ink-3">{loan.accessionNo}</span>
    </>
  );
}

/* ------------------------------------------------------------------ overdue */

/** Books past their due date today, with the fine so far, by member type and class. */
export function LibraryOverdueReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [memberType, setMemberType] = useState<MemberType | "">("");
  const [classId, setClassId] = useState("");
  const classes = useClasses();
  const params: OverdueParams = { memberType: memberType || undefined, classId: memberType === "STAFF" ? undefined : classId || undefined };
  const report = useApiData(`library:report:overdue:${JSON.stringify(params)}`, () => libraryApi.overdue(params));
  const data = report.loading ? undefined : report.data;
  const className = nameOf(classes, params.classId ?? "");
  const summary = [
    memberType ? memberTypeLabel(t, memberType) : t("library.report.allMembers"),
    className ? t("reports.filter.classIs", { name: className }) : null,
    data ? t("library.report.asOf", { date: formatPlainDate(data.asOf, locale) }) : null,
  ].filter((s): s is string => Boolean(s));

  return (
    <ReportFrame
      title={t("library.report.overdue.title")}
      testId="report-library-overdue"
      filterSummary={summary}
      problem={null}
      excel={{ path: libraryReportPaths.overdueCsv(params), fileName: "library-overdue.csv" }}
      ready={Boolean(data)}
      filters={
        <>
          <MemberTypeFilter
            value={memberType}
            onChange={(value) => {
              setMemberType(value);
              if (value === "STAFF") setClassId("");
            }}
          />
          {memberType === "STAFF" ? null : <ClassFilter classes={classes} value={classId} onChange={setClassId} />}
        </>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("library.report.overdue.books")} value={data.rows.length} testId="library-overdue-count" />
            <Figure label={t("library.report.overdue.fines")} value={formatPaise(data.totalFinePaise)} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("library.report.overdue.empty")}</p>
          ) : (
            <>
              <div className="table-wrap hidden md:block">
                <table className="table report-table" data-testid="library-overdue-table">
                  <caption className="sr-only">{t("library.report.overdue.title")}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t("library.col.member")}</th>
                      <th scope="col">{t("library.col.book")}</th>
                      <th scope="col">{t("library.col.issued")}</th>
                      <th scope="col">{t("library.col.due")}</th>
                      <th scope="col" className="r">
                        {t("library.col.daysOverdue")}
                      </th>
                      <th scope="col" className="r">
                        {t("library.col.fineSoFar")}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((loan) => (
                      <tr key={loan.id}>
                        <th scope="row">
                          <MemberCell loan={loan} />
                        </th>
                        <td>
                          <BookCell loan={loan} />
                        </td>
                        <td className="whitespace-nowrap">{formatPlainDate(loan.issuedOn, locale)}</td>
                        <td className="whitespace-nowrap">{formatPlainDate(loan.dueOn, locale)}</td>
                        <td className="r num font-semibold">{loan.daysOverdue}</td>
                        <td className="r num">{formatPaise(loan.fineSoFarPaise)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <ul className="rowcards md:hidden" data-testid="library-overdue-cards">
                {data.rows.map((loan) => (
                  <li key={loan.id} className="rowcard items-start">
                    <span className="min-w-0 flex-1">
                      <MemberCell loan={loan} />
                      <span className="block text-[13px]">{loan.title}</span>
                      <span className="block text-[12.5px] text-ink-3">
                        {t("library.loan.due", { date: formatPlainDate(loan.dueOn, locale) })}
                      </span>
                    </span>
                    <span className="flex flex-col items-end gap-1">
                      <Pill tone="bad">{plural(t, "library.loan.daysOverdue", loan.daysOverdue)}</Pill>
                      <b className="num">{formatPaise(loan.fineSoFarPaise)}</b>
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ issued on a day */

/** Every book lent on one day (today unless chosen). */
export function LibraryIssuedReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [today] = useState(() => todayInIndia());
  const [date, setDate] = useState(today);
  const problem = !DATE.test(date) ? t("reports.v.date") : date > today ? t("reports.v.future") : null;
  const report = useApiData(problem ? null : `library:report:issued:${date}`, () => libraryApi.issued({ date }));
  const data = report.data?.date === date ? report.data : undefined;

  return (
    <ReportFrame
      title={t("library.report.issued.title")}
      testId="report-library-issued"
      filterSummary={[t("reports.filter.dateIs", { date: formatPlainDate(date, locale) })]}
      problem={problem}
      excel={{ path: libraryReportPaths.issuedCsv({ date }), fileName: `library-issued-${date}.csv` }}
      ready={Boolean(data)}
      filters={
        <DateFilter name="date" label={t("reports.filter.date")} value={date} max={today} invalid={problem !== null} onChange={setDate} />
      }
    >
      {problem ? null : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("library.report.issued.books")} value={data.rows.length} testId="library-issued-count" />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("library.report.issued.empty")}</p>
          ) : (
            <>
              <div className="table-wrap hidden md:block">
                <table className="table report-table" data-testid="library-issued-table">
                  <caption className="sr-only">{t("library.report.issued.title")}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t("library.col.member")}</th>
                      <th scope="col">{t("library.col.book")}</th>
                      <th scope="col">{t("library.col.due")}</th>
                      <th scope="col">{t("library.col.status")}</th>
                      <th scope="col">{t("library.col.issuedBy")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((loan) => (
                      <tr key={loan.id}>
                        <th scope="row">
                          <MemberCell loan={loan} />
                        </th>
                        <td>
                          <BookCell loan={loan} />
                        </td>
                        <td className="whitespace-nowrap">{formatPlainDate(loan.dueOn, locale)}</td>
                        <td>{t(`library.loan.status.${loan.status}`)}</td>
                        <td>{loan.issuedByName ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <ul className="rowcards md:hidden" data-testid="library-issued-cards">
                {data.rows.map((loan) => (
                  <li key={loan.id} className="rowcard items-start">
                    <span className="min-w-0 flex-1">
                      <MemberCell loan={loan} />
                      <span className="block text-[13px]">{loan.title}</span>
                    </span>
                    <span className="text-[12.5px] text-ink-3">{t("library.loan.due", { date: formatPlainDate(loan.dueOn, locale) })}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ most borrowed */

/** The titles lent most often in a date range (the last 30 days unless chosen). */
export function LibraryPopularReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [today] = useState(() => todayInIndia());
  const [from, setFrom] = useState(() => addDays(today, -29));
  const [to, setTo] = useState(today);
  const problemKey = rangeProblem(from, to);
  const problem = problemKey ? t(problemKey) : to > today ? t("reports.v.future") : null;
  const params: PopularParams = { from, to };
  const report = useApiData(problem ? null : `library:report:popular:${from}:${to}`, () => libraryApi.popular(params));
  const data = report.data && report.data.from === from && report.data.to === to ? report.data : undefined;

  return (
    <ReportFrame
      title={t("library.report.popular.title")}
      testId="report-library-popular"
      filterSummary={[t("reports.filter.rangeIs", { from: formatPlainDate(from, locale), to: formatPlainDate(to, locale) })]}
      problem={problem}
      excel={{ path: libraryReportPaths.popularCsv(params), fileName: "library-most-borrowed.csv" }}
      ready={Boolean(data)}
      filters={
        <>
          <DateFilter name="from" label={t("reports.filter.from")} value={from} max={today} invalid={problem !== null} onChange={setFrom} />
          <DateFilter name="to" label={t("reports.filter.to")} value={to} max={today} invalid={problem !== null} onChange={setTo} />
        </>
      }
    >
      {problem ? null : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : data.rows.length === 0 ? (
        <p className="empty">{t("library.report.popular.empty")}</p>
      ) : (
        <div className="table-wrap" data-testid="library-popular-table">
          <table className="table report-table">
            <caption className="sr-only">{t("library.report.popular.title")}</caption>
            <thead>
              <tr>
                <th scope="col" className="r">
                  #
                </th>
                <th scope="col">{t("library.col.title")}</th>
                <th scope="col" className="r">
                  {t("library.col.loans")}
                </th>
                <th scope="col" className="r">
                  {t("library.col.copies")}
                </th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((row, i) => (
                <tr key={row.titleId}>
                  <td className="r num">{i + 1}</td>
                  <th scope="row">
                    <span className="block font-semibold">{row.title ?? "—"}</span>
                    <span className="block text-[12.5px] font-normal text-ink-3">{row.authors ?? ""}</span>
                  </th>
                  <td className="r num font-semibold">{row.loans}</td>
                  <td className="r num">{row.copies}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ member history */

/** Everything one student or member of staff has borrowed, and their fines. */
export function LibraryMemberHistoryView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [member, setMember] = useState<LibraryMember | null>(null);
  const params = member ? { memberType: member.type, memberId: member.id } : null;
  const report = useApiData(params ? `library:report:member:${params.memberType}:${params.memberId}` : null, () =>
    libraryApi.memberHistory(params!),
  );
  const data = member && report.data?.member.id === member.id ? report.data : undefined;

  return (
    <ReportFrame
      title={t("library.report.member.title")}
      testId="report-library-member"
      filterSummary={member ? [memberName(t, member), memberSub(member)].filter(Boolean) : []}
      problem={null}
      excel={params ? { path: libraryReportPaths.memberHistoryCsv(params), fileName: "library-member-history.csv" } : null}
      ready={Boolean(data)}
      filters={
        member ? (
          <div className="flex w-full flex-wrap items-center justify-between gap-2">
            <span>
              <b>{memberName(t, member)}</b> <span className="text-ink-3">{memberSub(member)}</span>
            </span>
            <button type="button" className="btn btn-sm" onClick={() => setMember(null)}>
              {t("library.desk.changeMember")}
            </button>
          </div>
        ) : (
          <div className="w-full">
            <MemberPicker onPick={setMember} />
          </div>
        )
      }
    >
      {!member ? (
        <p className="empty">{t("library.report.member.pick")}</p>
      ) : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("library.report.member.loans")} value={data.loans.length} testId="library-member-loans" />
            <Figure label={t("library.report.member.finesDue")} value={formatPaise(data.finesDuePaise)} />
            <Figure label={t("library.report.member.finesPaid")} value={formatPaise(data.finesPaidPaise)} />
          </div>
          {data.loans.length === 0 ? (
            <p className="empty">{t("library.report.member.empty")}</p>
          ) : (
            <div className="table-wrap" data-testid="library-member-table">
              <table className="table report-table">
                <caption className="sr-only">{t("library.report.member.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("library.col.book")}</th>
                    <th scope="col">{t("library.col.issued")}</th>
                    <th scope="col">{t("library.col.due")}</th>
                    <th scope="col">{t("library.col.returned")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.loans.map((loan) => (
                    <tr key={loan.id}>
                      <th scope="row" className="font-normal">
                        <BookCell loan={loan} />
                      </th>
                      <td className="whitespace-nowrap">{formatPlainDate(loan.issuedOn, locale)}</td>
                      <td className="whitespace-nowrap">
                        {formatPlainDate(loan.dueOn, locale)}
                        {loan.overdue ? (
                          <span className="block">
                            <Pill tone="bad">{plural(t, "library.loan.daysOverdue", loan.daysOverdue)}</Pill>
                          </span>
                        ) : null}
                      </td>
                      <td className="whitespace-nowrap">
                        {loan.returnedOn ? formatPlainDate(loan.returnedOn, locale) : t(`library.loan.status.${loan.status}`)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.fines.length > 0 ? (
            <div className="table-wrap" data-testid="library-member-fines">
              <table className="table report-table">
                <caption className="text-left font-semibold">{t("library.report.member.fines")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("library.col.book")}</th>
                    <th scope="col" className="r">
                      {t("library.col.amount")}
                    </th>
                    <th scope="col">{t("library.col.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.fines.map((fine) => (
                    <tr key={fine.id}>
                      <td>
                        {fine.title ?? "—"}
                        <span className="block text-[12.5px] text-ink-3">{fineKindLabel(t, fine.kind)}</span>
                      </td>
                      <td className="r num">{formatPaise(fine.amountPaise)}</td>
                      <td>
                        <FineStatusPill status={fine.status} />
                        {fine.receiptNo ? <span className="mono block text-[12.5px] text-ink-3">{fine.receiptNo}</span> : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </>
      )}
    </ReportFrame>
  );
}
