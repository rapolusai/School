"use client";

import { Plus, Search } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill, statusTone } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api } from "@/lib/api";
import { formatDate, initials } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import type { TenantSummary } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AddSchoolDialog } from "./add-school-dialog";

export function filterTenants(tenants: TenantSummary[], query: string): TenantSummary[] {
  const q = query.trim().toLowerCase();
  if (!q) return tenants;
  return tenants.filter((tenant) =>
    [tenant.name, tenant.code, tenant.city ?? ""].join(" ").toLowerCase().includes(q),
  );
}

export function SchoolsView() {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const [query, setQuery] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const tenants = useApiData("tenants", api.listTenants);
  const list = tenants.data ?? [];
  const rows = filterTenants(list, query);
  const locale = localeFor(lang);

  return (
    <>
      <PageHead
        eyebrow={tenants.data ? plural(t, "schools.eyebrow", list.length) : t("common.loading")}
        title={t("schools.title")}
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setDialogOpen(true)}>
            <Plus size={18} aria-hidden="true" />
            {t("schools.add")}
          </button>
        }
      />
      <section className="card">
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("schools.search")}</span>
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={t("schools.search")}
            />
          </label>
        </div>
        {tenants.error && !tenants.data ? (
          <ErrorState error={tenants.error} onRetry={tenants.reload} />
        ) : tenants.loading && !tenants.data ? (
          <LoadingRows rows={4} />
        ) : (
          <>
            <div className="table-wrap">
              <table className="table" data-testid="schools-table">
                <thead>
                  <tr>
                    <th scope="col">{t("schools.col.school")}</th>
                    <th scope="col" className="hidden sm:table-cell">
                      {t("schools.col.plan")}
                    </th>
                    <th scope="col">{t("schools.col.status")}</th>
                    <th scope="col" className="hidden lg:table-cell">
                      {t("schools.col.board")}
                    </th>
                    <th scope="col" className="r hidden md:table-cell">
                      {t("schools.col.users")}
                    </th>
                    <th scope="col" className="hidden lg:table-cell">
                      {t("schools.col.created")}
                    </th>
                    <th scope="col" className="hidden xl:table-cell">
                      {t("schools.col.trialEnds")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((tenant) => (
                    <tr key={tenant.id}>
                      <td className="max-w-0 w-full sm:w-auto sm:max-w-none">
                        <div className="person">
                          <span className="avatar avatar-school" aria-hidden="true">
                            {initials(tenant.name)}
                          </span>
                          <div className="min-w-0">
                            <Link
                              href={`/app/platform/schools/${encodeURIComponent(tenant.id)}`}
                              className="link block truncate font-bold"
                            >
                              {tenant.name}
                            </Link>
                            <span className="sub">
                              <span className="mono">{tenant.code}</span>
                              {tenant.city ? ` · ${tenant.city}` : ""}
                            </span>
                          </div>
                        </div>
                      </td>
                      <td className="hidden sm:table-cell">
                        {translateOr(t, `plan.${tenant.plan}`, tenant.plan)}
                      </td>
                      <td>
                        <Pill tone={statusTone(tenant.status)} dot>
                          {translateOr(t, `status.${tenant.status}`, tenant.status)}
                        </Pill>
                      </td>
                      <td className="hidden lg:table-cell">
                        {translateOr(t, `board.${tenant.board}`, tenant.board)}
                      </td>
                      <td className="r num hidden md:table-cell">
                        {tenant.userCount.toLocaleString("en-IN")}
                      </td>
                      <td className="hidden whitespace-nowrap text-ink-2 lg:table-cell">
                        {formatDate(tenant.createdAt, locale)}
                      </td>
                      <td className="hidden whitespace-nowrap text-ink-2 xl:table-cell">
                        {tenant.trialEndsAt ? formatDate(tenant.trialEndsAt, locale) : "—"}
                      </td>
                    </tr>
                  ))}
                  {rows.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="empty">
                        {query.trim() ? t("schools.noMatch", { query: query.trim() }) : t("schools.empty")}
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
            <p className="mt-2.5 text-[13px] text-ink-3">
              {t("common.showing", { shown: rows.length, total: list.length })}
            </p>
          </>
        )}
      </section>
      <AddSchoolDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onCreated={(tenant) => {
          setDialogOpen(false);
          tenants.reload();
          toast(t("schools.added", { name: tenant.name }));
        }}
      />
    </>
  );
}
