"use client";

import { Plus, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { Pill, statusTone } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, initials } from "@/lib/format";
import { localeFor, plural, roleLabel, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { Role, UserSummary } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AddUserDialog } from "./add-user-dialog";

export function filterUsers(
  users: UserSummary[],
  query: string,
  labelFor: (code: string) => string,
): UserSummary[] {
  const q = query.trim().toLowerCase();
  if (!q) return users;
  return users.filter((user) =>
    [user.name, user.email, ...user.roles, ...user.roles.map(labelFor)]
      .join(" ")
      .toLowerCase()
      .includes(q),
  );
}

export function UsersView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const [query, setQuery] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);

  const canManage = hasPermission(me, PERMISSIONS.usersManage);
  const canRoles = hasPermission(me, PERMISSIONS.rolesRead);
  const users = useApiData("users", api.listUsers);
  const roles = useApiData<Role[]>(canRoles ? "roles" : null, api.listRoles);

  const roleNames = useMemo(
    () => new Map((roles.data ?? []).map((role) => [role.code, role.name])),
    [roles.data],
  );
  const labelFor = (code: string) => roleLabel(t, code, roleNames.get(code));
  const list = users.data ?? [];
  const rows = filterUsers(list, query, labelFor);
  const locale = localeFor(lang);

  const rolePills = (user: UserSummary) => (
    <div className="flex flex-wrap gap-1">
      {user.roles.map((code) => (
        <Pill key={code} tone="accent">
          {labelFor(code)}
        </Pill>
      ))}
    </div>
  );

  return (
    <>
      <PageHead
        eyebrow={users.data ? plural(t, "users.eyebrow", list.length) : t("common.loading")}
        title={t("users.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setDialogOpen(true)}>
              <Plus size={18} aria-hidden="true" />
              {t("users.add")}
            </button>
          ) : null
        }
      />

      <section className="card">
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("users.search")}</span>
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={t("users.search")}
            />
          </label>
        </div>

        {users.error && !users.data ? (
          <ErrorState error={users.error} onRetry={users.reload} />
        ) : users.loading && !users.data ? (
          <LoadingRows rows={4} />
        ) : (
          <>
            <div className="table-wrap">
              <table className="table" data-testid="users-table">
                <thead>
                  <tr>
                    <th scope="col">{t("users.col.name")}</th>
                    <th scope="col" className="hidden md:table-cell">
                      {t("users.col.roles")}
                    </th>
                    <th scope="col">{t("users.col.status")}</th>
                    <th scope="col" className="hidden lg:table-cell">
                      {t("users.col.lastLogin")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((user) => (
                    <tr key={user.id}>
                      <td className="max-w-0 w-full md:w-auto md:max-w-none">
                        <div className="person">
                          <span className="avatar" aria-hidden="true">
                            {initials(user.name)}
                          </span>
                          <div className="min-w-0">
                            <b className="truncate">{user.name}</b>
                            <span className="sub">{user.email}</span>
                            <div className="mt-1 md:hidden">{rolePills(user)}</div>
                          </div>
                        </div>
                      </td>
                      <td className="hidden md:table-cell">{rolePills(user)}</td>
                      <td>
                        <Pill tone={statusTone(user.status)} dot>
                          {translateOr(t, `status.${user.status}`, user.status)}
                        </Pill>
                      </td>
                      <td className="hidden whitespace-nowrap text-ink-2 lg:table-cell">
                        {user.lastLoginAt ? (
                          <time dateTime={user.lastLoginAt}>
                            {formatDateTime(user.lastLoginAt, locale)}
                          </time>
                        ) : (
                          <span className="text-ink-3">{t("common.never")}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                  {rows.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="empty">
                        {query.trim() ? t("users.noMatch", { query: query.trim() }) : t("users.empty")}
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

      {canManage ? (
        <AddUserDialog
          open={dialogOpen}
          onClose={() => setDialogOpen(false)}
          roles={roles.data}
          rolesError={Boolean(roles.error) || !canRoles}
          rolesLoading={roles.loading}
          onCreated={(user) => {
            setDialogOpen(false);
            users.reload();
            toast(t("users.added", { name: user.name }));
          }}
        />
      ) : null}
    </>
  );
}
