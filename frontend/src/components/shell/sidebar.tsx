"use client";

import { LogOut } from "lucide-react";
import { Brand } from "@/components/brand";
import { translateOr, useI18n, type MessageKey } from "@/lib/i18n";
import type { NavGroup, NavItem } from "@/lib/permissions";
import type { Me } from "@/lib/types";
import { isActivePath, SideNavLink } from "./nav-link";

const GROUP_LABELS: Record<NavGroup, MessageKey> = {
  overview: "nav.group.overview",
  administration: "nav.group.administration",
  platform: "nav.group.platform",
};

export function Sidebar({
  me,
  items,
  pathname,
  onSignOut,
}: {
  me: Me;
  items: NavItem[];
  pathname: string;
  onSignOut: () => void;
}) {
  const { t } = useI18n();
  const groups = (Object.keys(GROUP_LABELS) as NavGroup[])
    .map((group) => ({ group, items: items.filter((item) => item.group === group) }))
    .filter((g) => g.items.length > 0);

  const tenant = me.tenant;
  const scopeName = tenant ? tenant.name : t("app.fullName");
  const scopeSub = tenant
    ? [translateOr(t, `board.${tenant.board}`, tenant.board), tenant.city].filter(Boolean).join(" · ")
    : t("shell.platformOwner");

  return (
    <aside className="side" data-testid="sidebar">
      <Brand name={t("app.name")} />
      <div className="tenant">
        <span className="crest" aria-hidden="true">
          {Array.from(scopeName)[0]?.toUpperCase()}
        </span>
        <div className="min-w-0">
          <b>{scopeName}</b>
          <span>{scopeSub}</span>
        </div>
      </div>
      <nav className="nav" aria-label={t("shell.mainNav")}>
        {groups.map(({ group, items: groupItems }) => (
          <div key={group} className="flex flex-col gap-0.5">
            <p className="nav-group">{t(GROUP_LABELS[group])}</p>
            {groupItems.map((item) => (
              <SideNavLink
                key={item.key}
                item={item}
                label={t(item.labelKey)}
                active={isActivePath(pathname, item.href)}
              />
            ))}
          </div>
        ))}
      </nav>
      <div className="side-foot w-full">
        <button type="button" className="nav-link" data-tip={t("shell.signOut")} onClick={onSignOut}>
          <LogOut size={20} strokeWidth={1.8} aria-hidden="true" />
          <span className="lbl">{t("shell.signOut")}</span>
        </button>
      </div>
    </aside>
  );
}
