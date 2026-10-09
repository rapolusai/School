import { useCallback, useSyncExternalStore } from "react";
import { en, type MessageKey } from "./en";
import { hi } from "./hi";

export type { MessageKey } from "./en";

export const LANGUAGES = [
  { code: "en", label: "EN", htmlLang: "en" },
  { code: "hi", label: "हिन्दी", htmlLang: "hi" },
] as const;

export type Lang = (typeof LANGUAGES)[number]["code"];

export const dictionaries: Record<Lang, Record<MessageKey, string>> = { en, hi };

export const LANG_STORAGE_KEY = "akshara.lang";

export type TranslateVars = Record<string, string | number>;

/** Pure lookup + {placeholder} interpolation. Falls back to English, then to the key. */
export function translate(lang: Lang, key: MessageKey, vars?: TranslateVars): string {
  const template = dictionaries[lang][key] ?? en[key] ?? key;
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match,
  );
}

function isLang(value: unknown): value is Lang {
  return LANGUAGES.some((l) => l.code === value);
}

/* A tiny external store so every component reads the same language without a provider. */

let current: Lang | null = null;
const listeners = new Set<() => void>();

function readLang(): Lang {
  if (current) return current;
  let stored: string | null = null;
  try {
    stored = window.localStorage.getItem(LANG_STORAGE_KEY);
  } catch {
    stored = null;
  }
  current = isLang(stored) ? stored : "en";
  return current;
}

export function setLang(lang: Lang): void {
  current = lang;
  try {
    window.localStorage.setItem(LANG_STORAGE_KEY, lang);
  } catch {
    // Storage blocked (private mode etc.): the choice still applies for this page view.
  }
  document.documentElement.lang = lang;
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

const serverLang = (): Lang => "en";

export function useLang(): Lang {
  return useSyncExternalStore(subscribe, readLang, serverLang);
}

export type Translate = (key: MessageKey, vars?: TranslateVars) => string;

export function useI18n(): { lang: Lang; t: Translate; setLang: (lang: Lang) => void } {
  const lang = useLang();
  const t = useCallback<Translate>((key, vars) => translate(lang, key, vars), [lang]);
  return { lang, t, setLang };
}

/** BCP 47 locale for Intl formatting in the current language. */
export function localeFor(lang: Lang): string {
  return lang === "hi" ? "hi-IN" : "en-IN";
}

export function isMessageKey(key: string): key is MessageKey {
  return Object.prototype.hasOwnProperty.call(en, key);
}

/** Translate a dynamic key such as `board.${code}`, falling back to `fallback` if unknown. */
export function translateOr(t: Translate, key: string, fallback: string): string {
  return isMessageKey(key) ? t(key) : fallback;
}

/** Display name for a role code: dictionary first, then the server's name, then the code. */
export function roleLabel(t: Translate, code: string, serverName?: string): string {
  return translateOr(t, `role.${code}`, serverName ?? code);
}

/** Message bases that have ".one" and ".other" variants. */
export type PluralBase = {
  [K in MessageKey]: K extends `${infer Base}.one` ? Base : never;
}[MessageKey];

/** Pick the singular or plural variant and interpolate {count}. */
export function plural(t: Translate, base: PluralBase, count: number, vars?: TranslateVars): string {
  const key = `${base}.${count === 1 ? "one" : "other"}` as MessageKey;
  return t(key, { count, ...vars });
}
