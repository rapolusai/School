"use client";

import { Printer, Search } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { TextField } from "@/components/ui/field";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { plural, useI18n } from "@/lib/i18n";
import { libraryApi, type LabelQuery } from "@/lib/library-api";
import { useApiData } from "@/lib/use-api-data";
import { LabelSheet, labelsPerSheet, type LabelKind } from "./label-sheet";
import { CopyStatusPill, LibraryNav } from "./library-shared";

/** /app/library/labels: choose copies (a title's, an accession number range or a search) and print their labels. */
export function LabelsView({ initialTitleId }: { initialTitleId?: string }) {
  const { t } = useI18n();
  const [fields, setFields] = useState({ from: "", to: "", q: "" });
  const [query, setQuery] = useState<LabelQuery>(initialTitleId ? { titleId: initialTitleId } : {});
  const [kind, setKind] = useState<LabelKind>("barcode");
  const [startAt, setStartAt] = useState("1");
  const [left, setLeft] = useState<Set<string>>(new Set());
  const key = `library:labels:${JSON.stringify(query)}`;
  const copies = useApiData(key, () => libraryApi.labels(query));

  const list = copies.data ?? [];
  const chosen = list.filter((copy) => !left.has(copy.id));
  const perSheet = labelsPerSheet(kind);
  const start = Number(startAt);
  const startProblem = !Number.isInteger(start) || start < 1 || start > perSheet;
  const skip = startProblem ? 0 : start - 1;

  const find = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLeft(new Set());
    setQuery({
      from: fields.from.trim() || undefined,
      to: fields.to.trim() || undefined,
      q: fields.q.trim() || undefined,
    });
  };

  const toggle = (id: string) =>
    setLeft((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <>
      <PageHead
        eyebrow={t("nav.library")}
        title={t("library.labels.title")}
        actions={
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => window.print()}
            disabled={chosen.length === 0 || startProblem}
          >
            <Printer size={18} aria-hidden="true" />
            {t("library.labels.print")}
          </button>
        }
      />
      <LibraryNav />

      <section className="card flex flex-col gap-3.5 no-print">
        <form method="post" className="flex flex-col gap-3" onSubmit={find} aria-label={t("library.labels.find")}>
          {query.titleId ? (
            <p className="alert alert-info">
              <span>
                {t("library.labels.oneTitle")}{" "}
                <Link href="/app/library/labels" className="link" onClick={() => setQuery({})}>
                  {t("library.labels.allCopies")}
                </Link>
              </span>
            </p>
          ) : null}
          <div className="grid gap-3 sm:grid-cols-3">
            <TextField
              label={t("library.labels.from")}
              name="from"
              value={fields.from}
              onChange={(e) => setFields((f) => ({ ...f, from: e.target.value }))}
              maxLength={30}
              placeholder="LIB00001"
              autoComplete="off"
            />
            <TextField
              label={t("library.labels.to")}
              name="to"
              value={fields.to}
              onChange={(e) => setFields((f) => ({ ...f, to: e.target.value }))}
              maxLength={30}
              placeholder="LIB00060"
              autoComplete="off"
            />
            <TextField
              label={t("library.labels.text")}
              name="q"
              value={fields.q}
              onChange={(e) => setFields((f) => ({ ...f, q: e.target.value }))}
              maxLength={100}
              autoComplete="off"
            />
          </div>
          <p className="field-hint">{t("library.labels.rangeHint")}</p>
          <div className="flex flex-wrap items-end gap-3">
            <button type="submit" className="btn">
              <Search size={18} aria-hidden="true" />
              {t("library.labels.find")}
            </button>
            <div className="seg" role="radiogroup" aria-label={t("library.labels.kind")}>
              {(["barcode", "spine"] as const).map((k) => (
                <label key={k}>
                  <input type="radio" name="kind" value={k} checked={kind === k} onChange={() => setKind(k)} />
                  {t(k === "barcode" ? "library.labels.kind.barcode" : "library.labels.kind.spine")}
                </label>
              ))}
            </div>
            <TextField
              label={t("library.labels.startAt", { max: perSheet })}
              name="startAt"
              type="number"
              inputMode="numeric"
              min={1}
              max={perSheet}
              value={startAt}
              onChange={(e) => setStartAt(e.target.value)}
              error={startProblem ? t("library.labels.v.startAt", { max: perSheet }) : undefined}
              className="w-40"
            />
          </div>
        </form>

        {copies.error && !copies.data ? (
          <ErrorState error={copies.error} onRetry={copies.reload} />
        ) : !copies.data ? (
          <LoadingRows rows={3} />
        ) : list.length === 0 ? (
          <p className="empty">{t("library.labels.none")}</p>
        ) : (
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-[13px] text-ink-2" role="status" data-testid="labels-count">
                {t("library.labels.chosen", { chosen: chosen.length, total: list.length })} ·{" "}
                {plural(t, "library.labels.sheets", Math.ceil((chosen.length + skip) / perSheet))}
              </p>
              <div className="flex gap-2">
                <button type="button" className="btn btn-sm" onClick={() => setLeft(new Set())}>
                  {t("library.labels.all")}
                </button>
                <button type="button" className="btn btn-sm" onClick={() => setLeft(new Set(list.map((c) => c.id)))}>
                  {t("library.labels.clear")}
                </button>
              </div>
            </div>
            <ul className="label-choices" aria-label={t("library.labels.copies")}>
              {list.map((copy) => (
                <li key={copy.id}>
                  <label className="check">
                    <input type="checkbox" checked={!left.has(copy.id)} onChange={() => toggle(copy.id)} />
                    <span className="min-w-0 flex-1">
                      <b className="mono">{copy.accessionNo}</b> <span className="text-ink-2">{copy.title}</span>
                    </span>
                    {copy.status !== "AVAILABLE" ? <CopyStatusPill status={copy.status} /> : null}
                  </label>
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>

      {chosen.length > 0 ? (
        <section className="label-preview" aria-label={t("library.labels.preview")}>
          <p className="field-hint no-print">{t("library.labels.printHint")}</p>
          <LabelSheet copies={chosen} kind={kind} skip={skip} />
        </section>
      ) : null}
    </>
  );
}
