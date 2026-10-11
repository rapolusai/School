"use client";

import { ArrowRight, ChevronLeft, ChevronRight, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { YearSelect } from "@/components/views/exams/exams-shared";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { PLAN_STATUSES, type PlanRow, type PlanStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { LessonsNav, mondayOf, PlanStatusPill, planChoices, plusDays, useLessonsOptions, weekLabel } from "./lessons-shared";
import { PlanDialog } from "./plan-editor";

/** /app/lessons: lesson plans week by week; teachers see their own by default, readers everyone's. */
export function LessonsHomeView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { me } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const whoName = useId();
  const canManage = hasPermission(me, PERMISSIONS.lessonsManage);
  const canApprove = hasPermission(me, PERMISSIONS.lessonsApprove);
  const [today] = useState(() => todayInIndia());
  const [yearId, setYearId] = useState("");
  const [week, setWeek] = useState(() => mondayOf(today));
  const [allWeeks, setAllWeeks] = useState(false);
  const [status, setStatus] = useState<PlanStatus | "">("");
  const [mine, setMine] = useState(canManage);
  const [creating, setCreating] = useState(false);
  const [dialogKey, setDialogKey] = useState(0);
  const options = useLessonsOptions(yearId);
  const year = options.data?.years.find((y) => y.id === options.data?.yearId) ?? null;
  const query = {
    yearId: yearId || undefined,
    mine,
    status: status || undefined,
    from: allWeeks ? undefined : week,
    to: allWeeks ? undefined : plusDays(week, 6),
  };
  const plans = useApiData(`lessons:plans:${JSON.stringify(query)}`, () => lessonsApi.plans(query));
  const queue = useApiData(canApprove ? `lessons:queue:${yearId}` : null, () => lessonsApi.queue(yearId || null));
  const canPlan = Boolean(options.data?.canManage && year && planChoices(options.data).length > 0);

  return (
    <>
      <PageHead
        eyebrow={year?.name}
        title={t("lessons.plans.title")}
        actions={
          canPlan ? (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                setDialogKey((k) => k + 1);
                setCreating(true);
              }}
            >
              <Plus size={18} aria-hidden="true" />
              {t("lessons.plan.add")}
            </button>
          ) : null
        }
      />
      <LessonsNav />

      {canApprove && queue.data && queue.data.length > 0 ? (
        <Link href="/app/lessons/approvals" className="alert alert-info items-center" data-testid="plans-waiting">
          <span className="flex-1">{plural(t, "lessons.queue.waiting", queue.data.length)}</span>
          <ArrowRight size={18} aria-hidden="true" />
        </Link>
      ) : null}

      <section className="card flex flex-col gap-3">
        <form method="post" className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label={t("lessons.filters")}>
          {options.data ? (
            <YearSelect years={options.data.years} value={yearId || options.data.yearId || ""} onChange={setYearId} />
          ) : null}
          {canManage ? (
            <div className="seg basis-full sm:basis-auto" role="radiogroup" aria-label={t("lessons.plans.whose")}>
              <label>
                <input type="radio" name={whoName} checked={mine} onChange={() => setMine(true)} />
                {t("lessons.plans.mine")}
              </label>
              <label>
                <input type="radio" name={whoName} checked={!mine} onChange={() => setMine(false)} />
                {t("lessons.plans.everyone")}
              </label>
            </div>
          ) : null}
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("lessons.plans.status")}</span>
            <select
              className="input"
              name="status"
              value={status}
              onChange={(e) => setStatus(e.target.value as PlanStatus | "")}
            >
              <option value="">{t("lessons.plans.anyStatus")}</option>
              {PLAN_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {translateOr(t, `lessons.plan.status.${s}`, s)}
                </option>
              ))}
            </select>
          </label>
          <label className="check self-end">
            <input type="checkbox" name="allWeeks" checked={allWeeks} onChange={(e) => setAllWeeks(e.target.checked)} />
            <span>{t("lessons.plans.allWeeks")}</span>
          </label>
        </form>
        {allWeeks ? null : (
          <div className="flex flex-wrap items-center gap-2" data-testid="plans-week">
            <button
              type="button"
              className="iconbtn"
              onClick={() => setWeek(plusDays(week, -7))}
              aria-label={t("lessons.plans.previousWeek")}
            >
              <ChevronLeft size={18} aria-hidden="true" />
            </button>
            <b className="min-w-0 flex-1 text-center sm:flex-none">{weekLabel(t, week, locale)}</b>
            <button
              type="button"
              className="iconbtn"
              onClick={() => setWeek(plusDays(week, 7))}
              aria-label={t("lessons.plans.nextWeek")}
            >
              <ChevronRight size={18} aria-hidden="true" />
            </button>
            {week !== mondayOf(today) ? (
              <button type="button" className="btn btn-sm" onClick={() => setWeek(mondayOf(today))}>
                {t("lessons.plans.thisWeek")}
              </button>
            ) : null}
          </div>
        )}

        {plans.error && !plans.data ? (
          <ErrorState error={plans.error} onRetry={plans.reload} />
        ) : !plans.data ? (
          <LoadingRows rows={4} />
        ) : options.data && !options.data.yearId ? (
          <p className="empty">{t("lessons.noYear")}</p>
        ) : plans.data.length === 0 ? (
          <p className="empty" data-testid="plans-empty">
            {canPlan && mine ? t("lessons.plans.emptyMine") : t("lessons.plans.empty")}
          </p>
        ) : (
          <PlanRows rows={plans.data} showTeacher={!mine} />
        )}
      </section>

      {options.data && creating ? (
        <PlanDialog
          key={dialogKey}
          open
          plan={null}
          options={options.data}
          week={week}
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false);
            toast(t("lessons.plan.saved"));
            router.push(`/app/lessons/plans/${encodeURIComponent(saved.id)}`);
          }}
        />
      ) : null}
    </>
  );
}

/** Plans as row cards linking to each plan. */
export function PlanRows({ rows, showTeacher }: { rows: PlanRow[]; showTeacher: boolean }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <ul className="rowcards" data-testid="plan-rows">
      {rows.map((p) => (
        <li key={p.id}>
          <Link href={`/app/lessons/plans/${encodeURIComponent(p.id)}`} className="rowcard" data-testid="plan-row">
            <span className="min-w-0 flex-1">
              <b className="block font-semibold">{p.title}</b>
              <span className="block text-[12.5px] text-ink-3">
                {[
                  p.sectionLabel,
                  p.subjectName,
                  p.planDate ? formatPlainDate(p.planDate, locale) : weekLabel(t, p.weekStart, locale),
                  plural(t, "lessons.plan.periodsCount", p.periods),
                  showTeacher ? p.teacherName : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
              {p.status === "RETURNED" && p.reviewComment ? (
                <span className="mt-1 block text-[12.5px] text-warn">{p.reviewComment}</span>
              ) : null}
              {p.status === "SUBMITTED" && p.submittedAt ? (
                <span className="block text-[12px] text-ink-3">
                  {t("lessons.plan.submittedOn", { date: formatDateTime(p.submittedAt, locale) })}
                </span>
              ) : null}
            </span>
            <PlanStatusPill status={p.status} />
            <ArrowRight size={18} aria-hidden="true" className="flex-none text-ink-3" />
          </Link>
        </li>
      ))}
    </ul>
  );
}

/** /app/lessons/approvals: other teachers' submitted plans, oldest first (lessons.approve). */
export function ApprovalsView() {
  const { t } = useI18n();
  const queue = useApiData("lessons:queue:", () => lessonsApi.queue(null));
  return (
    <>
      <PageHead title={t("lessons.queue.title")} />
      <LessonsNav />
      <section className="card flex flex-col gap-3" data-testid="approval-queue">
        <p className="text-[13.5px] text-ink-2">{t("lessons.queue.intro")}</p>
        {queue.error && !queue.data ? (
          <ErrorState error={queue.error} onRetry={queue.reload} />
        ) : !queue.data ? (
          <LoadingRows rows={4} />
        ) : queue.data.length === 0 ? (
          <p className="empty">{t("lessons.queue.empty")}</p>
        ) : (
          <PlanRows rows={queue.data} showTeacher />
        )}
      </section>
    </>
  );
}
