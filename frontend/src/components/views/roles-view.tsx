"use client";

import { ShieldCheck } from "lucide-react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { api } from "@/lib/api";
import { plural, roleLabel, translateOr, useI18n } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";

export function RolesView() {
  const { t } = useI18n();
  const roles = useApiData("roles", api.listRoles);
  const list = roles.data ?? [];

  return (
    <>
      <PageHead
        eyebrow={roles.data ? plural(t, "roles.eyebrow", list.length) : t("common.loading")}
        title={t("roles.title")}
      />
      {roles.error && !roles.data ? (
        <section className="card">
          <ErrorState error={roles.error} onRetry={roles.reload} />
        </section>
      ) : roles.loading && !roles.data ? (
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      ) : list.length === 0 ? (
        <section className="card">
          <p className="empty">{t("roles.empty")}</p>
        </section>
      ) : (
        <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
          {list.map((role) => (
            <section key={role.code} className="card flex flex-col gap-3" aria-labelledby={`role-${role.code}`}>
              <div className="flex items-start gap-3">
                <span className="badge-ic">
                  <ShieldCheck size={20} aria-hidden="true" />
                </span>
                <div className="min-w-0 flex-1">
                  <h2 id={`role-${role.code}`}>{roleLabel(t, role.code, role.name)}</h2>
                  <p className="mono text-ink-3 break-all">{role.code}</p>
                </div>
                <span className="pill pill-neutral">
                  {plural(t, "roles.permissions", role.permissions.length)}
                </span>
              </div>
              <ul className="flex flex-wrap gap-1.5" aria-label={plural(t, "roles.permissions", role.permissions.length)}>
                {role.permissions.map((permission) => (
                  <li key={permission} className="chip" title={permission}>
                    {translateOr(t, `perm.${permission}`, permission)}
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </>
  );
}
