"use client";

import { ArrowRight, CalendarDays, CalendarOff, ClipboardCheck, GraduationCap, Newspaper, NotebookPen } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { CountsBar, formatPercent } from "@/components/views/attendance/attendance-shared";
import { UpcomingCard } from "@/components/views/communication/dashboard-cards";
import { NoticeMeta, NoticeReader } from "@/components/views/communication/notice-reader";
import { nextDue } from "@/components/views/fees/child-fees";
import { isToDo, LearnerHomeworkRows } from "@/components/views/homework/learner-homework";
import { studentStatusTone } from "@/components/views/students/student-status";
import { DayPeriods } from "@/components/views/timetable/timetable-shared";
import { api } from "@/lib/api";
import { attendanceApi } from "@/lib/attendance-api";
import { useAuth } from "@/lib/auth";
import { boardApi } from "@/lib/communication-api";
import { isParent } from "@/lib/family";
import { feesApi } from "@/lib/fees-api";
import { classLabel, firstName, formatLongDate, formatPaise, formatPlainDate, hourInIndia, initials } from "@/lib/format";
import { homeworkApi } from "@/lib/homework-api";
import { localeFor, plural, translateOr, useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { portalApi } from "@/lib/portal-api";
import { timetableApi } from "@/lib/timetable-api";
import type { BoardItem, Child, ChildAttendance, FamilyLeave, FamilyTimetable, StudentFees, StudentHomework } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { ChildSwitcher, leaveDates, LeaveStatusPill, TodayPill, useFamilyChildren } from "./family-shared";
import { InstallHint } from "./install-hint";

export { isFamilyMember } from "@/lib/family";

/** How many rows each home card lists before its "all" link. */
const CARD_ROWS = 3;
/** Notices fetched for the home card, of which the unread come first. */
const NOTICES_FETCHED = 10;

function greetingKey(hour: number): MessageKey {
  if (hour < 12) return "dashboard.greeting.morning";
  if (hour < 17) return "dashboard.greeting.afternoon";
  return "dashboard.greeting.evening";
}

/**
 * The parent and student app's home (the dashboard for someone whose roles are all PARENT or
 * STUDENT): one child at a time with today, this month's attendance, homework, fees, leave,
 * notices and what is coming up.
 */
export function FamilyHome() {
  const { me } = useAuth();
  return isParent(me) ? <ParentHome /> : <StudentHome />;
}

function Greeting() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const [now] = useState(() => new Date());
  if (!me) return null;
  return (
    <PageHead
      eyebrow={[formatLongDate(now, localeFor(lang)), me.tenant?.name].filter(Boolean).join(" · ")}
      title={t(greetingKey(hourInIndia(now)), { name: firstName(me.name) })}
    />
  );
}

function ParentHome() {
  const { t } = useI18n();
  const { me } = useAuth();
  const { children, list, child, choose } = useFamilyChildren();
  const canNotices = hasPermission(me, PERMISSIONS.noticesRead);
  return (
    <>
      <Greeting />
      <InstallHint />
      <section aria-labelledby="my-children" className="flex flex-col gap-3.5">
        <h2 id="my-children" className="flex items-center gap-2">
          <GraduationCap size={20} aria-hidden="true" className="text-ink-3" />
          {t("children.title")}
        </h2>
        {children.error && !children.data ? (
          <section className="card">
            <ErrorState error={children.error} onRetry={children.reload} />
          </section>
        ) : !children.data ? (
          <section className="card">
            <LoadingRows rows={3} />
          </section>
        ) : !child ? (
          <section className="card">
            <p className="empty">{t("children.empty")}</p>
          </section>
        ) : (
          <>
            <ChildSwitcher list={list} value={child.id} onChange={choose} />
            <ChildPanel key={child.id} child={child} self={false} />
          </>
        )}
      </section>
      {canNotices ? <NoticesAndUpcoming /> : null}
    </>
  );
}

function StudentHome() {
  const { t } = useI18n();
  const { me } = useAuth();
  const record = useApiData("me:student", api.myStudentRecord);
  const canNotices = hasPermission(me, PERMISSIONS.noticesRead);
  return (
    <>
      <Greeting />
      <InstallHint />
      {record.error?.status === 404 ? (
        <section className="card">
          <p className="text-sm text-ink-2">{t("children.notLinked")}</p>
        </section>
      ) : record.error && !record.data ? (
        <section className="card">
          <ErrorState error={record.error} onRetry={record.reload} />
        </section>
      ) : !record.data ? (
        <section className="card">
          <LoadingRows rows={3} />
        </section>
      ) : (
        <section aria-labelledby="my-class" className="flex flex-col gap-3.5">
          <h2 id="my-class">{t("children.myClass")}</h2>
          <ChildPanel child={record.data} self />
        </section>
      )}
      {canNotices ? <NoticesAndUpcoming /> : null}
    </>
  );
}

function NoticesAndUpcoming() {
  return (
    <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
      <FamilyNoticesCard />
      <UpcomingCard />
    </div>
  );
}

/* ------------------------------------------------------------------ one child */

/**
 * Everything about one child (or the student themselves): their card, then today, this month,
 * homework and leave. Each piece loads once here and is shared by the cards that need it.
 */
function ChildPanel({ child, self }: { child: Child; self: boolean }) {
  const id = child.id;
  const attendance = useApiData(self ? "portal:attendance:me" : `attendance:child:${id}`, () =>
    self ? portalApi.myAttendance() : attendanceApi.child(id),
  );
  const timetable = useApiData(self ? "timetable:family:me" : `timetable:family:${id}`, () =>
    self ? timetableApi.myTimetable() : timetableApi.childTimetable(id),
  );
  const homework = useApiData(self ? "homework:mine" : `homework:child:${id}`, () =>
    self ? homeworkApi.mine() : homeworkApi.child(id),
  );
  const leave = useApiData(self ? "portal:leave:me" : `portal:leave:${id}`, () =>
    self ? portalApi.myLeave() : portalApi.childLeave(id),
  );
  const fees = useApiData(self ? null : `me:child-fees:${id}`, () => feesApi.childFees(id));
  const query = self ? "" : `?child=${encodeURIComponent(id)}`;
  return (
    <>
      <ChildCard child={child} attendance={attendance.data} fees={self ? undefined : fees} />
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        <TodayCard attendance={attendance} timetable={timetable} timetableHref={`/app/timetable${query}`} />
        <MonthCard attendance={attendance} href={`/app/family/attendance${query}`} />
        <HomeworkCard
          data={homework}
          self={self}
          allHref={`/app/homework${query}`}
          hrefFor={(homeworkId) => `/app/homework/${encodeURIComponent(homeworkId)}${query}`}
        />
        <LeaveCard data={leave} self={self} href={`/app/family/leave${query}`} />
      </div>
    </>
  );
}

function ChildCard({
  child,
  attendance,
  fees,
}: {
  child: Child;
  attendance: ChildAttendance | undefined;
  fees?: ApiData<StudentFees>;
}) {
  const { t } = useI18n();
  const place = classLabel(child.className, child.sectionName);
  return (
    <article className="card flex flex-col gap-3" aria-label={child.fullName} data-testid="child-card">
      <div className="person">
        <span className="avatar h-11 w-11 text-[15px]" aria-hidden="true">
          {initials(child.fullName)}
        </span>
        <div className="min-w-0">
          <h3 className="font-display text-base font-bold leading-tight">{child.fullName}</h3>
          <span className="sub">{place || translateOr(t, `studentStatus.${child.status}`, child.status)}</span>
        </div>
        <span className="ml-auto flex flex-wrap justify-end gap-1.5" data-testid="child-today">
          {child.status !== "ACTIVE" ? (
            <Pill tone={studentStatusTone(child.status)}>{translateOr(t, `studentStatus.${child.status}`, child.status)}</Pill>
          ) : attendance ? (
            <TodayPill attendance={attendance} />
          ) : null}
        </span>
      </div>
      <dl className="kv text-[13.5px]">
        {child.rollNo ? (
          <>
            <dt>{t("children.roll")}</dt>
            <dd className="num">{child.rollNo}</dd>
          </>
        ) : null}
        <dt>{t("children.classTeacher")}</dt>
        <dd>{child.classTeacherName ?? "—"}</dd>
      </dl>
      {fees ? <FeesDue childId={child.id} fees={fees} /> : null}
    </article>
  );
}

/** Fees at a glance with the way to pay (the school's existing online payment). */
function FeesDue({ childId, fees }: { childId: string; fees: ApiData<StudentFees> }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const data = fees.data;
  const next = data ? nextDue(data) : undefined;
  const href = `/app/children/${encodeURIComponent(childId)}/fees`;
  return (
    <div className="flex flex-col gap-1.5 border-t border-line pt-3" data-testid="child-fees">
      <p className="text-[12px] font-semibold uppercase tracking-wide text-ink-3">{t("fees.child.title")}</p>
      {fees.error && !data ? (
        <p className="text-[13px] text-ink-3">{t("fees.child.unavailable")}</p>
      ) : !data ? (
        <div className="skeleton h-5 w-40" aria-hidden="true" />
      ) : data.instalments.length === 0 ? (
        <p className="text-[13px] text-ink-3">{t("fees.child.none")}</p>
      ) : (
        <>
          {data.totals.overduePaise > 0 ? (
            <p className="text-[13.5px] font-semibold text-bad">
              {t("fees.child.overdue", { amount: formatPaise(data.totals.overduePaise + data.totals.lateFeePaise) })}
            </p>
          ) : null}
          {next ? (
            <p className="text-[13.5px]">
              {t("fees.child.next", {
                label: next.label,
                amount: formatPaise(next.balancePaise),
                date: formatPlainDate(next.dueDate, locale),
              })}
            </p>
          ) : data.totals.balancePaise === 0 ? (
            <p className="text-[13.5px] text-good">{t("fees.child.allPaid")}</p>
          ) : null}
          {data.totals.payableNowPaise > 0 ? (
            <Link href={href} className="btn btn-primary mt-1 self-start">
              {t("fees.child.payLink")}
              <ArrowRight size={16} aria-hidden="true" />
            </Link>
          ) : (
            <Link href={href} className="link inline-flex items-center gap-1 text-[13.5px]">
              {t("fees.child.viewLink")}
              <ArrowRight size={16} aria-hidden="true" />
            </Link>
          )}
        </>
      )}
    </div>
  );
}

function HomeCard({
  id,
  title,
  icon: Icon,
  testId,
  sub,
  links,
  children,
}: {
  id: string;
  title: string;
  icon: typeof CalendarDays;
  testId: string;
  sub?: React.ReactNode;
  links: { href: string; label: string }[];
  children: React.ReactNode;
}) {
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={id} data-testid={testId}>
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h2 id={id}>{title}</h2>
          {sub ? <p className="mt-1 text-[13px] text-ink-3">{sub}</p> : null}
        </div>
        <Icon size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {children}
      <div className="mt-auto flex flex-wrap gap-x-5 gap-y-1">
        {links.map((link) => (
          <Link key={link.href} href={link.href} className="link inline-flex items-center gap-1 text-[13.5px]">
            {link.label}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        ))}
      </div>
    </section>
  );
}

function TodayCard({
  attendance,
  timetable,
  timetableHref,
}: {
  attendance: ApiData<ChildAttendance>;
  timetable: ApiData<FamilyTimetable>;
  timetableHref: string;
}) {
  const { t } = useI18n();
  const view = timetable.data;
  return (
    <HomeCard
      id="family-today"
      title={t("portal.home.today")}
      icon={CalendarDays}
      testId="family-today"
      sub={attendance.data ? <TodayPill attendance={attendance.data} /> : undefined}
      links={[{ href: timetableHref, label: t("timetable.open") }]}
    >
      {timetable.error?.status === 404 ? (
        <p className="text-sm text-ink-2">{t("timetable.family.noSection")}</p>
      ) : timetable.error && !view ? (
        <ErrorState error={timetable.error} onRetry={timetable.reload} />
      ) : !view ? (
        <LoadingRows rows={3} />
      ) : !view.sectionId ? (
        <p className="text-sm text-ink-2">{t("timetable.family.noSection")}</p>
      ) : !view.workingDay || attendance.data?.todayHoliday ? (
        <p className="text-sm text-ink-2">{t("timetable.closedToday")}</p>
      ) : (
        <DayPeriods
          periods={view.todayPeriods}
          showBreaks={false}
          showSection={false}
          emptyLabel={t("timetable.family.noClasses")}
        />
      )}
    </HomeCard>
  );
}

function MonthCard({ attendance, href }: { attendance: ApiData<ChildAttendance>; href: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const data = attendance.data;
  const absences = data?.days.filter((d) => d.status === "ABSENT").map((d) => d.date) ?? [];
  return (
    <HomeCard
      id="family-month"
      title={t("attendance.child.thisMonth")}
      icon={ClipboardCheck}
      testId="child-attendance"
      links={[{ href, label: t("portal.home.attendanceLink") }]}
    >
      {attendance.error?.status === 404 ? (
        <p className="text-sm text-ink-2">{t("children.notLinked")}</p>
      ) : attendance.error && !data ? (
        <ErrorState error={attendance.error} onRetry={attendance.reload} />
      ) : !data ? (
        <LoadingRows rows={2} />
      ) : (
        <>
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-[13px] text-ink-2">
              {data.daysMarked > 0
                ? t("attendance.child.days", { present: data.counts.present + data.counts.late, total: data.daysMarked })
                : t("attendance.child.noneYet")}
            </span>
            <span className="font-display text-2xl font-bold num">{formatPercent(data.presentPercent)}</span>
          </div>
          {data.daysMarked > 0 ? <CountsBar counts={data.counts} /> : null}
          <p className="text-[13px] text-ink-3">
            {absences.length === 0
              ? t("portal.home.noAbsencesMonth")
              : t("portal.home.absencesMonth", {
                  dates: absences.map((d) => formatPlainDate(d, locale)).join(", "),
                })}
          </p>
        </>
      )}
    </HomeCard>
  );
}

function HomeworkCard({
  data,
  self,
  allHref,
  hrefFor,
}: {
  data: ApiData<StudentHomework>;
  self: boolean;
  allHref: string;
  hrefFor: (homeworkId: string) => string;
}) {
  const { t } = useI18n();
  const view = data.data;
  const todo = view ? [...view.items].filter(isToDo).sort((a, b) => a.dueOn.localeCompare(b.dueOn)) : [];
  const reviewed = view
    ? view.items
        .filter((row) => row.status === "REVIEWED")
        .sort((a, b) => (b.submittedAt ?? b.dueOn).localeCompare(a.submittedAt ?? a.dueOn))
        .slice(0, 2)
    : [];
  return (
    <HomeCard
      id="family-homework"
      title={t("homework.card.title")}
      icon={NotebookPen}
      testId={self ? "student-homework-due" : "child-homework-due"}
      sub={view ? plural(t, "homework.card.due", todo.length) : undefined}
      links={[{ href: allHref, label: t("homework.card.all") }]}
    >
      {data.error?.status === 404 ? (
        <p className="text-sm text-ink-2">{t("timetable.family.noSection")}</p>
      ) : data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={3} />
      ) : (
        <>
          {todo.length === 0 ? (
            <p className="text-sm text-ink-2">{t("homework.card.nothingDue")}</p>
          ) : (
            <LearnerHomeworkRows items={todo.slice(0, CARD_ROWS)} today={view.today} hrefFor={(row) => hrefFor(row.id)} />
          )}
          {reviewed.length > 0 ? (
            <div className="flex flex-col gap-2" data-testid="homework-reviewed">
              <p className="text-[12px] font-semibold uppercase tracking-wide text-ink-3">{t("portal.home.reviewed")}</p>
              <LearnerHomeworkRows items={reviewed} today={view.today} hrefFor={(row) => hrefFor(row.id)} />
            </div>
          ) : null}
        </>
      )}
    </HomeCard>
  );
}

function LeaveCard({ data, self, href }: { data: ApiData<FamilyLeave>; self: boolean; href: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const view = data.data;
  const latest = view?.requests.slice(0, 2) ?? [];
  const waiting = view?.requests.filter((r) => r.status === "PENDING").length ?? 0;
  return (
    <HomeCard
      id="family-leave"
      title={t("portal.leave.title")}
      icon={CalendarOff}
      testId="family-leave-card"
      sub={waiting > 0 ? plural(t, "portal.leave.waiting", waiting) : undefined}
      links={[
        {
          href,
          label: !self && view?.canApply ? t("portal.leave.apply") : t("portal.home.leaveLink"),
        },
      ]}
    >
      {data.error?.status === 404 ? (
        <p className="text-sm text-ink-2">{t("children.notLinked")}</p>
      ) : data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={2} />
      ) : latest.length === 0 ? (
        <p className="text-sm text-ink-2">{self ? t("portal.leave.noneStudent") : t("portal.leave.none")}</p>
      ) : (
        <ul className="list">
          {latest.map((request) => (
            <li key={request.id} className="li items-center">
              <div className="min-w-0 flex-1">
                <p className="font-semibold leading-tight text-ink">{leaveDates(t, request, locale)}</p>
                <p className="truncate text-[13px] text-ink-3">{request.reason}</p>
              </div>
              <LeaveStatusPill status={request.status} />
            </li>
          ))}
        </ul>
      )}
    </HomeCard>
  );
}

/** The latest notices, unread first, opened in place (marking them read). */
function FamilyNoticesCard() {
  const { t } = useI18n();
  const [open, setOpen] = useState<BoardItem | null>(null);
  const board = useApiData("board:family", () => boardApi.page({ size: NOTICES_FETCHED }));
  const data = board.data;
  const items = data ? [...data.items].sort((a, b) => Number(a.read) - Number(b.read)).slice(0, CARD_ROWS) : [];
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="notices-card-heading" data-testid="notices-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="notices-card-heading" className="flex items-center gap-2">
          <Newspaper size={18} className="text-ink-3" aria-hidden="true" />
          {t("noticeBoard.card.title")}
        </h2>
        {data && data.unread > 0 ? <Pill tone="accent">{plural(t, "noticeBoard.unreadCount", data.unread)}</Pill> : null}
      </div>
      {board.error && !data ? (
        <ErrorState error={board.error} onRetry={board.reload} />
      ) : !data ? (
        <LoadingRows rows={2} />
      ) : items.length === 0 ? (
        <p className="text-sm text-ink-2">{t("portal.home.noNotices")}</p>
      ) : (
        <ul className="list">
          {items.map((item) => (
            <li key={item.id} className="li flex-col gap-1.5" data-unread={item.read ? undefined : "true"}>
              <button type="button" className="notice-open text-left font-semibold" onClick={() => setOpen(item)}>
                {item.read ? null : <span className="sr-only">{t("portal.home.unread")} </span>}
                {item.title}
              </button>
              <NoticeMeta item={item} />
            </li>
          ))}
        </ul>
      )}
      <Link href="/app/board" className="link mt-auto inline-flex items-center gap-1 text-[13.5px]">
        {t("noticeBoard.card.link")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
      <NoticeReader item={open} onClose={() => setOpen(null)} onRead={() => board.reload()} />
    </section>
  );
}
