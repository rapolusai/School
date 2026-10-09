"use client";

import { ArrowRight, CalendarClock, History, KeyRound, School, ShieldCheck, Users } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import {
  daysUntil,
  firstName,
  formatDate,
  formatDateTime,
  formatLongDate,
  hourInIndia,
} from "@/lib/format";
import {
  auditActionLabel,
  auditEntityLabel,
  localeFor,
  plural,
  roleLabel,
  translateOr,
  useI18n,
  type MessageKey,
} from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { Tenant } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AdmissionsCard } from "./admissions/admissions-card";
import { MyChildren, MyClass } from "./my-children";

function greetingKey(hour: number): MessageKey {
  if (hour < 12) return "dashboard.greeting.morning";
  if (hour < 17) return "dashboard.greeting.afternoon";
  return "dashboard.greeting.evening";
}

function TrialBanner({ tenant, now }: { tenant: Tenant; now: Date }) {
  const { t, lang } = useI18n();
  if (tenant.status !== "TRIAL" || !tenant.trialEndsAt) return null;
  const date = formatDate(tenant.trialEndsAt, localeFor(lang));
  const days = daysUntil(tenant.trialEndsAt, now);
  const ended = days < 0;
  return (
    <section
      className="card flex flex-wrap items-center gap-3"
      style={{ background: "var(--marigold-soft)", borderColor: "transparent" }}
      data-testid="trial-banner"
    >
      <span className="badge-ic" style={{ background: "var(--surface)", color: "var(--marigold-ink)" }}>
        <CalendarClock size={20} aria-hidden="true" />
      </span>
      <p className="min-w-0 flex-1 font-semibold text-ink">
        {ended ? t("dashboard.trial.ended", { date }) : t("dashboard.trial.endsOn", { date })}
      </p>
      {!ended ? (
        <span className="pill" style={{ background: "var(--surface)", color: "var(--marigold-ink)" }}>
          {plural(t, "dashboard.trial.daysLeft", days)}
        </span>
      ) : null}
    </section>
  );
}

function StatCard({
  icon: Icon,
  label,
  value,
  sub,
  href,
  linkLabel,
  loading,
}: {
  icon: typeof Users;
  label: string;
  value: React.ReactNode;
  sub?: React.ReactNode;
  href?: string;
  linkLabel?: string;
  loading?: boolean;
}) {
  return (
    <section className="card flex flex-col gap-1.5">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-[13px] font-medium text-ink-2 font-body">{label}</h2>
        <Icon size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {loading ? (
        <div className="skeleton h-8 w-16" aria-hidden="true" />
      ) : (
        <span className="kpi-value">{value}</span>
      )}
      {sub ? <span className="text-[12.5px] font-semibold text-ink-3">{sub}</span> : null}
      {href && linkLabel ? (
        <Link href={href} className="link mt-auto inline-flex items-center gap-1 pt-2 text-[13.5px]">
          {linkLabel}
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
      ) : null}
    </section>
  );
}

export function DashboardView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const [now] = useState(() => new Date());

  const canUsers = hasPermission(me, PERMISSIONS.usersRead);
  const canRoles = hasPermission(me, PERMISSIONS.rolesRead);
  const canAudit = hasPermission(me, PERMISSIONS.auditRead);
  // Admins hold child.view too (every school permission); staff find students on the Students page instead.
  const canChildren =
    hasPermission(me, PERMISSIONS.childView) &&
    (Boolean(me?.roles.includes("PARENT")) || !hasPermission(me, PERMISSIONS.studentsRead));
  const isStudent = me?.roles.includes("STUDENT") ?? false;

  const users = useApiData(canUsers ? "users" : null, api.listUsers);
  const roles = useApiData(canRoles ? "roles" : null, api.listRoles);
  const audit = useApiData(canAudit ? "audit:5" : null, () => api.listAuditEvents(5));

  if (!me) return null;
  const tenant = me.tenant;
  const locale = localeFor(lang);
  const activeUsers = users.data?.filter((u) => u.status === "ACTIVE").length ?? 0;
  const latest = [...(audit.data ?? [])].sort((a, b) => b.at.localeCompare(a.at)).slice(0, 5);

  return (
    <>
      <PageHead
        eyebrow={[formatLongDate(now, locale), tenant?.name].filter(Boolean).join(" · ")}
        title={t(greetingKey(hourInIndia(now)), { name: firstName(me.name) })}
      />

      {tenant ? <TrialBanner tenant={tenant} now={now} /> : null}

      {canChildren ? <MyChildren /> : null}
      {isStudent ? <MyClass /> : null}

      <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2 xl:grid-cols-4">
        {canUsers ? (
          <StatCard
            icon={Users}
            label={t("dashboard.users.title")}
            value={users.data?.length ?? "—"}
            sub={users.data ? t("dashboard.users.active", { count: activeUsers }) : undefined}
            href="/app/users"
            linkLabel={t("dashboard.users.link")}
            loading={users.loading && !users.data}
          />
        ) : null}
        {canRoles ? (
          <StatCard
            icon={ShieldCheck}
            label={t("dashboard.roles.title")}
            value={roles.data?.length ?? "—"}
            sub={t("dashboard.roles.sub")}
            href="/app/roles"
            linkLabel={t("dashboard.roles.link")}
            loading={roles.loading && !roles.data}
          />
        ) : null}
        {tenant ? (
          <section className="card flex flex-col gap-3">
            <div className="flex items-center justify-between gap-2">
              <h2 className="text-[13px] font-medium text-ink-2 font-body">
                {t("dashboard.school.title")}
              </h2>
              <School size={18} className="text-ink-3" aria-hidden="true" />
            </div>
            <p className="font-display text-lg font-bold leading-tight">{tenant.name}</p>
            <dl className="kv text-[13.5px]">
              <dt>{t("dashboard.school.code")}</dt>
              <dd className="mono">{tenant.code}</dd>
              <dt>{t("dashboard.school.board")}</dt>
              <dd>{translateOr(t, `board.${tenant.board}`, tenant.board)}</dd>
              {tenant.city ? (
                <>
                  <dt>{t("dashboard.school.city")}</dt>
                  <dd>{tenant.city}</dd>
                </>
              ) : null}
              <dt>{t("dashboard.school.plan")}</dt>
              <dd>
                {translateOr(t, `plan.${tenant.plan}`, tenant.plan)}{" "}
                <Pill tone={tenant.status === "TRIAL" ? "info" : "good"}>
                  {translateOr(t, `status.${tenant.status}`, tenant.status)}
                </Pill>
              </dd>
            </dl>
          </section>
        ) : null}
        <section className="card flex flex-col gap-3">
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-[13px] font-medium text-ink-2 font-body">{t("dashboard.access.title")}</h2>
            <KeyRound size={18} className="text-ink-3" aria-hidden="true" />
          </div>
          <div className="flex flex-wrap gap-1.5">
            {me.roles.map((code) => (
              <Pill key={code} tone="accent">
                {roleLabel(t, code)}
              </Pill>
            ))}
          </div>
          <p className="text-[12.5px] font-semibold text-ink-3">
            {plural(t, "dashboard.access.permissions", me.permissions.length)}
          </p>
        </section>
      </div>

      {hasPermission(me, PERMISSIONS.admissionsRead) ? <AdmissionsCard /> : null}

      {canAudit ? (
        <section className="card">
          <div className="card-head">
            <h2>{t("dashboard.activity.title")}</h2>
            <Link href="/app/audit" className="link text-[13.5px]">
              {t("dashboard.activity.link")}
            </Link>
          </div>
          {audit.error && !audit.data ? (
            <ErrorState error={audit.error} onRetry={audit.reload} />
          ) : audit.loading && !audit.data ? (
            <LoadingRows rows={3} />
          ) : latest.length === 0 ? (
            <p className="empty">{t("dashboard.activity.empty")}</p>
          ) : (
            <ul className="list" aria-label={t("dashboard.activity.title")}>
              {latest.map((event) => (
                <li key={event.id} className="li items-center">
                  <span className="badge-ic">
                    <History size={18} aria-hidden="true" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="break-words text-ink" title={event.action}>
                      {auditActionLabel(t, event.action)}
                    </p>
                    <p className="text-[13px] text-ink-3">
                      {event.actorName ?? t("common.system")}
                      {event.entityType ? ` · ${auditEntityLabel(t, event.entityType)}` : ""}
                    </p>
                  </div>
                  <time className="text-xs text-ink-3 whitespace-nowrap" dateTime={event.at}>
                    {formatDateTime(event.at, locale)}
                  </time>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}
    </>
  );
}
