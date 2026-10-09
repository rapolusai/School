"use client";

import { Globe, Moon, Sun } from "lucide-react";
import { useId } from "react";
import { LANGUAGES, useI18n, type Lang } from "@/lib/i18n";
import { toggleTheme, useTheme } from "@/lib/theme";

/** EN / हिन्दी selector. The choice is stored in localStorage (when available). */
export function LanguageSelect() {
  const { lang, setLang, t } = useI18n();
  const id = useId();
  return (
    <div className="lang">
      <Globe size={18} aria-hidden="true" />
      <label htmlFor={id} className="sr-only">
        {t("shell.language")}
      </label>
      <select id={id} value={lang} onChange={(event) => setLang(event.target.value as Lang)}>
        {LANGUAGES.map((language) => (
          <option key={language.code} value={language.code} lang={language.htmlLang}>
            {language.label}
          </option>
        ))}
      </select>
    </div>
  );
}

export function ThemeToggle({ withLabel = false }: { withLabel?: boolean }) {
  const theme = useTheme();
  const { t } = useI18n();
  const label = theme === "dark" ? t("shell.theme.toLight") : t("shell.theme.toDark");
  const Icon = theme === "dark" ? Sun : Moon;
  if (withLabel) {
    return (
      <button type="button" className="btn btn-ghost btn-sm" onClick={toggleTheme}>
        <Icon size={18} aria-hidden="true" />
        {label}
      </button>
    );
  }
  return (
    <button type="button" className="iconbtn" onClick={toggleTheme} aria-label={label} title={label}>
      <Icon size={20} aria-hidden="true" />
    </button>
  );
}
