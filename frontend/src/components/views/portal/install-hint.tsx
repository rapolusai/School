"use client";

import { Download, X } from "lucide-react";
import { useEffect, useState } from "react";
import { useI18n } from "@/lib/i18n";
import { dismissInstallHint, installHintDismissed, isPhoneWidth, isStandalone } from "@/lib/pwa";

/** The browser's install prompt (Chromium only; Safari has none). */
type InstallPromptEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: string }> };

/**
 * Phones only: a hint that the app can be added to the home screen, with an "Install app" button
 * where the browser offers its own prompt. Hidden once installed or dismissed on this device.
 */
export function InstallHint() {
  const { t } = useI18n();
  const [shown, setShown] = useState(() => isPhoneWidth() && !isStandalone() && !installHintDismissed());
  const [prompt, setPrompt] = useState<InstallPromptEvent | null>(null);

  useEffect(() => {
    if (!shown) return;
    const onPrompt = (event: Event) => {
      event.preventDefault();
      setPrompt(event as InstallPromptEvent);
    };
    const onInstalled = () => setShown(false);
    window.addEventListener("beforeinstallprompt", onPrompt);
    window.addEventListener("appinstalled", onInstalled);
    return () => {
      window.removeEventListener("beforeinstallprompt", onPrompt);
      window.removeEventListener("appinstalled", onInstalled);
    };
  }, [shown]);

  if (!shown) return null;

  const dismiss = () => {
    dismissInstallHint();
    setShown(false);
  };

  const install = async () => {
    if (!prompt) return;
    await prompt.prompt();
    const choice = await prompt.userChoice.catch(() => ({ outcome: "dismissed" }));
    setPrompt(null);
    if (choice.outcome === "accepted") setShown(false);
  };

  return (
    <aside
      className="card flex items-start gap-3"
      style={{ background: "var(--marigold-soft)", borderColor: "transparent" }}
      aria-labelledby="install-hint-title"
      data-testid="install-hint"
    >
      <span className="badge-ic flex-none" style={{ background: "var(--surface)", color: "var(--marigold-ink)" }}>
        <Download size={20} aria-hidden="true" />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <p id="install-hint-title" className="font-semibold text-ink">
          {t("portal.install.title")}
        </p>
        <p className="text-[13.5px] text-ink-2">{prompt ? t("portal.install.ready") : t("portal.install.how")}</p>
        {prompt ? (
          <button type="button" className="btn btn-primary self-start" onClick={() => void install()}>
            {t("portal.install.button")}
          </button>
        ) : null}
      </div>
      <button type="button" className="btn btn-sm btn-ghost flex-none" onClick={dismiss} aria-label={t("portal.install.dismiss")}>
        <X size={16} aria-hidden="true" />
      </button>
    </aside>
  );
}
