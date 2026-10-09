import { useSyncExternalStore } from "react";
import { THEME_STORAGE_KEY } from "./theme-script";

export type Theme = "light" | "dark";

const listeners = new Set<() => void>();

function systemPrefersDark(): boolean {
  try {
    return window.matchMedia("(prefers-color-scheme: dark)").matches;
  } catch {
    return false;
  }
}

function readTheme(): Theme {
  const explicit = document.documentElement.getAttribute("data-theme");
  if (explicit === "light" || explicit === "dark") return explicit;
  return systemPrefersDark() ? "dark" : "light";
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  let media: MediaQueryList | null = null;
  try {
    media = window.matchMedia("(prefers-color-scheme: dark)");
    media.addEventListener("change", listener);
  } catch {
    media = null;
  }
  return () => {
    listeners.delete(listener);
    media?.removeEventListener("change", listener);
  };
}

export function setTheme(theme: Theme): void {
  document.documentElement.setAttribute("data-theme", theme);
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // Storage unavailable: the theme still applies until the next reload.
  }
  for (const listener of listeners) listener();
}

export function toggleTheme(): void {
  setTheme(readTheme() === "dark" ? "light" : "dark");
}

/** The effective theme (explicit choice, else the OS preference). Server render: light. */
export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, readTheme, () => "light");
}
