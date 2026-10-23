"use client";

import { Copy, Wand2 } from "lucide-react";
import { useState } from "react";
import { FormAlert, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { assistApi } from "@/lib/assist-api";
import { useI18n } from "@/lib/i18n";
import type { WritingResult } from "@/lib/types";
import { AssistNav, AssistOffNotice, SimulatedLabel, assistError, useAssistSettings } from "./assist-shared";

/** "Improve this circular": a tidier version of a draft, counted against the monthly limit. */
export function WritingView() {
  const { t } = useI18n();
  const { toast } = useToast();
  const settings = useAssistSettings();
  const [text, setText] = useState("");
  const [result, setResult] = useState<WritingResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const improve = async () => {
    setBusy(true);
    setProblem(null);
    try {
      setResult(await assistApi.improve(text));
      settings.reload();
    } catch (error) {
      setProblem(assistError(toApiError(error), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHead eyebrow={t("assist.eyebrow")} title={t("assist.writing.title")} />
      <AssistNav />
      {settings.data ? <AssistOffNotice settings={settings.data} feature="writingEnabled" /> : null}
      <form
        method="post"
        className="card flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          void improve();
        }}
      >
        <label className="field">
          <span className="field-label">{t("assist.writing.text")}</span>
          <textarea className="input" name="text" rows={8} maxLength={4000} required value={text} onChange={(e) => setText(e.target.value)} />
        </label>
        <p className="text-[13px] text-ink-3">{t("assist.writing.hint")}</p>
        <FormAlert message={problem} />
        <button type="submit" className="btn btn-primary self-start" disabled={busy || !text.trim()}>
          <Wand2 size={16} aria-hidden="true" />
          {t("assist.writing.improve")}
        </button>
      </form>
      {result ? (
        <section className="card flex flex-col gap-3" data-testid="writing-result">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="font-semibold">{t("assist.writing.result")}</h2>
            <SimulatedLabel simulated={result.simulated} />
          </div>
          <textarea className="input" rows={8} readOnly value={result.text} aria-label={t("assist.writing.result")} />
          <button
            type="button"
            className="btn btn-sm self-start"
            onClick={() => {
              void navigator.clipboard?.writeText(result.text).then(() => toast(t("assist.writing.copied")));
            }}
          >
            <Copy size={14} aria-hidden="true" />
            {t("assist.writing.copy")}
          </button>
        </section>
      ) : null}
    </div>
  );
}
