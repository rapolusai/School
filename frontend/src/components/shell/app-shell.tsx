"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef } from "react";
import { BrandMark } from "@/components/brand";
import { LanguageSelect, ThemeToggle } from "@/components/preferences";
import { useAuth } from "@/lib/auth";
import { roleLabel, useI18n } from "@/lib/i18n";
import { navFor } from "@/lib/permissions";
import { BottomBar } from "./bottom-bar";
import { Sidebar } from "./sidebar";
import { UserMenu } from "./user-menu";

export function Splash() {
  const { t } = useI18n();
  return (
    <div className="grid min-h-dvh place-items-center" aria-busy="true">
      <div className="flex flex-col items-center gap-3 text-ink-2">
        <BrandMark size={44} />
        <p role="status">{t("common.loading")}</p>
      </div>
    </div>
  );
}

/**
 * Signed-in layout: full sidebar ≥1024px, 76px icon rail 640–1023px, bottom tab bar below
 * 640px. Signed-out visitors are sent to /login (with a safe `next` back to this page).
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const { t } = useI18n();
  const signingOut = useRef(false);

  useEffect(() => {
    if (auth.status !== "anonymous") return;
    if (signingOut.current) {
      router.replace("/login");
    } else {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [auth.status, pathname, router]);

  if (auth.status !== "authenticated") return <Splash />;

  const me = auth.me;
  const items = navFor(me);
  const signOut = () => {
    signingOut.current = true;
    void auth.logout();
  };
  const primaryRole = me.platformAdmin
    ? t("shell.superAdmin")
    : me.roles.length
      ? roleLabel(t, me.roles[0])
      : "";

  return (
    <div className="shell">
      <a href="#main" className="skip-link">
        {t("common.skipToContent")}
      </a>
      <Sidebar me={me} items={items} pathname={pathname} onSignOut={signOut} />
      <div className="main">
        <header className="top">
          <span className="mobile-only">
            <BrandMark size={30} />
          </span>
          <p className="top-title" data-testid="tenant-name">
            {me.tenant ? me.tenant.name : t("app.fullName")}
          </p>
          <LanguageSelect />
          <ThemeToggle />
          <UserMenu me={me} roleLabel={primaryRole} onSignOut={signOut} />
        </header>
        <main id="main" className="content" tabIndex={-1}>
          {children}
        </main>
      </div>
      <BottomBar items={items} pathname={pathname} onSignOut={signOut} />
    </div>
  );
}
