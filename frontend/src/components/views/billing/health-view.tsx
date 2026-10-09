"use client";

import { Building2, Database, GraduationCap, Inbox, RefreshCw, Server, Users } from "lucide-react";
import { Pill, statusTone } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { platformBillingApi } from "@/lib/billing-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, translateOr, useI18n, type Translate } from "@/lib/i18n";
import type { TenantStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

const STATUSES: TenantStatus[] = ["TRIAL", "ACTIVE", "PAST_DUE", "SUSPENDED"];

/** 93784 seconds → "1 d 2 h 3 min"; under a minute → "less than a minute". */
export function uptimeLabel(t: Translate, seconds: number): string {
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  if (days === 0 && hours === 0 && minutes === 0) return t("billing.health.uptimeShort");
  return [
    days ? t("billing.health.days", { count: days }) : null,
    days || hours ? t("billing.health.hours", { count: hours }) : null,
    t("billing.health.minutes", { count: minutes }),
  ]
    .filter(Boolean)
    .join(" ");
}

function Tile({
  icon: Icon,
  label,
  value,
  children,
  testId,
}: {
  icon: typeof Users;
  label: string;
  value: React.ReactNode;
  children?: React.ReactNode;
  testId?: string;
}) {
  return (
    <section className="card flex flex-col gap-1.5" data-testid={testId}>
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-[13px] font-medium text-ink-2 font-body">{label}</h2>
        <Icon size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      <span className="kpi-value">{value}</span>
      {children}
    </section>
  );
}

const count = (value: number | null | undefined) => (value === null || value === undefined ? "—" : value.toLocaleString("en-IN"));

/** /app/platform/health: schools by status, people and students, the outbox, the database and the app. */
export function HealthView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const health = useApiData("platform:health", platformBillingApi.health);
  const data = health.data;

  return (
    <>
      <PageHead
        eyebrow={t("billing.platform.eyebrow")}
        title={t("billing.health.title")}
        actions={
          <button type="button" className="btn" onClick={health.reload} disabled={health.loading}>
            <RefreshCw size={18} aria-hidden="true" />
            {health.loading ? t("common.loading") : t("billing.health.refresh")}
          </button>
        }
      />
      {health.error && !data ? (
        <section className="card">
          <ErrorState error={health.error} onRetry={health.reload} />
        </section>
      ) : !data ? (
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      ) : (
        <>
          {!data.database.reachable ? (
            <div className="alert alert-bad" role="alert" data-testid="health-db-down">
              <span>{t("billing.health.dbDown")}</span>
            </div>
          ) : null}
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" aria-busy={health.loading}>
            <Tile icon={Building2} label={t("billing.health.schools")} value={count(data.schools?.total)} testId="health-schools">
              {data.schools ? (
                <ul className="flex flex-wrap gap-1.5 pt-1">
                  {STATUSES.map((status) => (
                    <li key={status}>
                      <Pill tone={statusTone(status)}>
                        {translateOr(t, `status.${status}`, status)}: {count(data.schools?.byStatus[status] ?? 0)}
                      </Pill>
                    </li>
                  ))}
                </ul>
              ) : null}
            </Tile>
            <Tile icon={Users} label={t("billing.health.users")} value={count(data.users)} testId="health-users" />
            <Tile
              icon={GraduationCap}
              label={t("billing.health.students")}
              value={count(data.activeStudents)}
              testId="health-students"
            />
            <Tile icon={Inbox} label={t("billing.health.outbox")} value={count(data.outbox?.queued)} testId="health-outbox">
              <span className="text-[12.5px] font-semibold text-ink-3">
                {t("billing.health.outboxDetail", {
                  queued: count(data.outbox?.queued),
                  failed: count(data.outbox?.failed),
                })}
              </span>
              {data.outbox && data.outbox.failed > 0 ? (
                <span>
                  <Pill tone="bad" dot>
                    {t("billing.health.failed", { count: count(data.outbox.failed) })}
                  </Pill>
                </span>
              ) : null}
            </Tile>
            <Tile
              icon={Database}
              label={t("billing.health.database")}
              value={data.database.reachable ? t("billing.health.reachable") : t("billing.health.unreachable")}
              testId="health-database"
            >
              {data.database.latencyMs !== null ? (
                <span className="text-[12.5px] font-semibold text-ink-3">
                  {t("billing.health.latency", { ms: data.database.latencyMs })}
                </span>
              ) : null}
            </Tile>
            <Tile icon={Server} label={t("billing.health.app")} value={<span className="mono text-[20px]">{data.app.version}</span>} testId="health-app">
              <span className="text-[12.5px] font-semibold text-ink-3">
                {t("billing.health.uptime", {
                  uptime: uptimeLabel(t, data.app.uptimeSeconds),
                  since: formatDateTime(data.app.startedAt, locale),
                })}
              </span>
            </Tile>
          </div>
          <p className="text-[13px] text-ink-3">
            {t("billing.health.checkedAt", { time: formatDateTime(data.checkedAt, locale) })}
          </p>
        </>
      )}
    </>
  );
}
