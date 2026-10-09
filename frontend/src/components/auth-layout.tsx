"use client";

import { BrandMark } from "@/components/brand";
import { LanguageSelect, ThemeToggle } from "@/components/preferences";
import { useI18n } from "@/lib/i18n";

/** The prototype's login split: green ruled "register" panel + form column. */
export function AuthLayout({
  title,
  body,
  children,
}: {
  title: string;
  body: string;
  children: React.ReactNode;
}) {
  const { t } = useI18n();
  return (
    <div className="auth">
      <section className="auth-art" aria-label={t("app.fullName")}>
        <div className="ruled" aria-hidden="true" />
        <div className="margin" aria-hidden="true" />
        <div className="brand relative">
          <BrandMark />
          {t("app.fullName")}
        </div>
        <div className="relative flex flex-col gap-4">
          <p className="auth-headline">{title}</p>
          <p>{body}</p>
        </div>
        <dl className="auth-stats">
          {(
            [
              ["login.stat.trial.value", "login.stat.trial.label"],
              ["login.stat.languages.value", "login.stat.languages.label"],
              ["login.stat.roles.value", "login.stat.roles.label"],
            ] as const
          ).map(([value, label]) => (
            <div key={value} className="flex flex-col-reverse">
              <dt>
                <span>{t(label)}</span>
              </dt>
              <dd className="m-0">
                <b>{t(value)}</b>
              </dd>
            </div>
          ))}
        </dl>
      </section>
      <main className="auth-form">
        {children}
        <div className="flex flex-wrap items-center justify-center gap-2">
          <LanguageSelect />
          <ThemeToggle withLabel />
        </div>
      </main>
    </div>
  );
}
