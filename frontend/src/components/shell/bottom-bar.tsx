"use client";

import { LayoutGrid, LogOut } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { useI18n } from "@/lib/i18n";
import type { NavItem } from "@/lib/permissions";
import { isActivePath } from "./nav-link";

const PRIMARY_TABS = 4;

/** Phone navigation: the first four items plus a "More" sheet with everything. */
export function BottomBar({
  items,
  pathname,
  onSignOut,
}: {
  items: NavItem[];
  pathname: string;
  onSignOut: () => void;
}) {
  const { t } = useI18n();
  const [moreOpen, setMoreOpen] = useState(false);
  const primary = items.slice(0, PRIMARY_TABS);
  const moreActive = items.slice(PRIMARY_TABS).some((item) => isActivePath(pathname, item.href));

  return (
    <>
      <nav
        className="bottombar"
        aria-label={t("shell.mainNav")}
        data-testid="bottom-bar"
        style={{ "--tabs": primary.length + 1 } as React.CSSProperties}
      >
        {primary.map((item) => {
          const Icon = item.icon;
          return (
            <Link
              key={item.key}
              href={item.href}
              aria-current={isActivePath(pathname, item.href) ? "page" : undefined}
            >
              <Icon size={20} strokeWidth={1.8} aria-hidden="true" />
              <span>{t(item.shortLabelKey)}</span>
            </Link>
          );
        })}
        <button
          type="button"
          aria-haspopup="dialog"
          aria-expanded={moreOpen}
          onClick={() => setMoreOpen(true)}
          style={moreActive ? { color: "var(--accent)" } : undefined}
        >
          <LayoutGrid size={20} strokeWidth={1.8} aria-hidden="true" />
          <span>{t("shell.more")}</span>
        </button>
      </nav>
      <Dialog
        open={moreOpen}
        onClose={() => setMoreOpen(false)}
        title={t("shell.allSections")}
        variant="sheet"
        closeLabel={t("common.close")}
      >
        <div className="sheet-grid">
          {items.map((item) => {
            const Icon = item.icon;
            return (
              <Link
                key={item.key}
                href={item.href}
                aria-current={isActivePath(pathname, item.href) ? "page" : undefined}
                onClick={() => setMoreOpen(false)}
              >
                <Icon size={20} strokeWidth={1.8} aria-hidden="true" />
                {t(item.labelKey)}
              </Link>
            );
          })}
          <button
            type="button"
            onClick={() => {
              setMoreOpen(false);
              onSignOut();
            }}
          >
            <LogOut size={20} strokeWidth={1.8} aria-hidden="true" />
            {t("shell.signOut")}
          </button>
        </div>
      </Dialog>
    </>
  );
}
