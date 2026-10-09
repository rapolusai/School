"use client";

import { ShieldAlert } from "lucide-react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import { hasPermission, landingPath } from "@/lib/permissions";

export function AccessDenied() {
  const { t } = useI18n();
  const { me } = useAuth();
  return (
    <section className="card flex max-w-xl flex-col items-start gap-3" data-testid="access-denied">
      <span className="badge-ic" style={{ background: "var(--warn-soft)", color: "var(--warn)" }}>
        <ShieldAlert size={20} aria-hidden="true" />
      </span>
      <h1>{t("denied.title")}</h1>
      <p className="text-ink-2">{t("denied.body")}</p>
      <Link href={landingPath(me)} className="btn">
        {t("denied.back")}
      </Link>
    </section>
  );
}

/** Renders children only when the signed-in user holds `permission`. */
export function RequirePermission({
  permission,
  children,
}: {
  permission: string;
  children: React.ReactNode;
}) {
  const { me } = useAuth();
  if (!hasPermission(me, permission)) return <AccessDenied />;
  return <>{children}</>;
}
