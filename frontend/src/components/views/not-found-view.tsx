"use client";

import Link from "next/link";
import { Brand } from "@/components/brand";
import { useI18n } from "@/lib/i18n";

export function NotFoundView() {
  const { t } = useI18n();
  return (
    <main className="grid min-h-dvh place-items-center px-4 py-12">
      <div className="card flex w-full max-w-md flex-col items-start gap-4">
        <Brand name={t("app.name")} />
        <div>
          <p className="eyebrow">{t("notFound.eyebrow")}</p>
          <h1 className="mt-1">{t("notFound.title")}</h1>
          <p className="mt-2 text-ink-2">{t("notFound.body")}</p>
        </div>
        <Link href="/app" className="btn btn-primary">
          {t("notFound.home")}
        </Link>
      </div>
    </main>
  );
}
