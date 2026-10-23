"use client";

import { ArrowLeft, Download, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead, pendingTitle } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage, fieldErrorMessage } from "@/lib/error-message";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { libraryApi, libraryReportPaths } from "@/lib/library-api";
import { downloadXlsx } from "@/lib/reports-api";
import type { ScanResult, StockCheckDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { copyStatusLabel, CopyStatusPill, LibraryNav } from "./library-shared";
import { ScanField } from "./scan-field";

/** /app/library/stock: stock verification rounds, the open one first. */
export function StockChecksView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const router = useRouter();
  const checks = useApiData("library:stock-checks", libraryApi.stockChecks);
  const [starting, setStarting] = useState(false);
  const list = checks.data ?? [];
  const open = list.find((c) => c.status === "OPEN");

  return (
    <>
      <PageHead
        eyebrow={t("nav.library")}
        title={t("library.stock.title")}
        actions={
          open ? (
            <Link href={`/app/library/stock/${open.id}`} className="btn btn-primary">
              {t("library.stock.continue")}
            </Link>
          ) : (
            <button type="button" className="btn btn-primary" onClick={() => setStarting(true)} disabled={!checks.data}>
              <Plus size={18} aria-hidden="true" />
              {t("library.stock.start")}
            </button>
          )
        }
      />
      <LibraryNav />
      <section className="card flex flex-col gap-3">
        <p className="text-[13.5px] text-ink-2">{t("library.stock.intro")}</p>
        {checks.error && !checks.data ? (
          <ErrorState error={checks.error} onRetry={checks.reload} />
        ) : !checks.data ? (
          <LoadingRows rows={3} />
        ) : list.length === 0 ? (
          <p className="empty">{t("library.stock.none")}</p>
        ) : (
          <ul className="rowcards" data-testid="stock-checks">
            {list.map((check) => (
              <li key={check.id}>
                <Link href={`/app/library/stock/${check.id}`} className="rowcard">
                  <span className="min-w-0 flex-1">
                    <b className="block">
                      {t("library.stock.startedAt", { date: formatDateTime(check.startedAt, locale) })}
                    </b>
                    <span className="block text-[12.5px] text-ink-3">
                      {[check.note, check.startedByName, t("library.stock.counts", { seen: check.seen, expected: check.expected })]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </span>
                  {check.status === "OPEN" ? (
                    <Pill tone="info">{t("library.stock.open")}</Pill>
                  ) : (
                    <Pill tone={check.missing > 0 ? "warn" : "good"}>{plural(t, "library.stock.missingCount", check.missing)}</Pill>
                  )}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      {starting ? (
        <StartDialog
          onClose={() => setStarting(false)}
          onStarted={(id) => {
            setStarting(false);
            router.push(`/app/library/stock/${id}`);
          }}
        />
      ) : null}
    </>
  );
}

function StartDialog({ onClose, onStarted }: { onClose: () => void; onStarted: (id: string) => void }) {
  const { t } = useI18n();
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const tooLong = note.trim().length > 200;

  const start = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy || tooLong) return;
    setBusy(true);
    setError(null);
    try {
      const check = await libraryApi.startStockCheck(note.trim() || null);
      onStarted(check.id);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} title={t("library.stock.start")} description={t("library.stock.startDescription")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={start} noValidate>
        <FormAlert message={error} />
        <TextField
          label={t("library.field.noteOptional")}
          name="note"
          value={note}
          onChange={(e) => setNote(e.target.value)}
          error={tooLong ? t("validation.tooLong") : undefined}
          maxLength={200}
          placeholder={t("library.stock.notePlaceholder")}
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? t("common.working") : t("library.stock.startSubmit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** /app/library/stock/[id]: scan every copy on the shelves; what was not seen is listed, to print or download. */
export function StockCheckView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const detail = useApiData(`library:stock-check:${id}`, () => libraryApi.stockCheck(id));
  const [last, setLast] = useState<ScanResult | null>(null);
  const [closing, setClosing] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const data: StockCheckDetail | undefined = detail.data;
  const open = data?.check.status === "OPEN";

  const scan = async (accessionNo: string): Promise<string | null> => {
    try {
      const result = await libraryApi.scan(id, accessionNo);
      setLast(result);
      detail.reload();
      return null;
    } catch (caught) {
      const error = toApiError(caught);
      return error.errors?.accessionNo ? fieldErrorMessage(error.errors.accessionNo, t) : errorMessage(error, t);
    }
  };

  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      const name = await downloadXlsx(libraryReportPaths.missingCsv(id), "library-missing.csv");
      toast(t("reports.downloaded", { name }));
    } catch (caught) {
      setDownloadError(errorMessage(toApiError(caught), t));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <>
      <PageHead
        eyebrow={t("library.stock.title")}
        title={data ? t("library.stock.startedAt", { date: formatDateTime(data.check.startedAt, locale) }) : pendingTitle(t, detail.error)}
        actions={
          <>
            <Link href="/app/library/stock" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("library.stock.back")}
            </Link>
            <button type="button" className="btn" onClick={download} disabled={!data || downloading}>
              <Download size={18} aria-hidden="true" />
              {downloading ? t("common.working") : t("library.stock.download")}
            </button>
            {open ? (
              <button type="button" className="btn btn-primary" onClick={() => setClosing(true)}>
                {t("library.stock.close")}
              </button>
            ) : null}
          </>
        }
      />
      <LibraryNav />
      <FormAlert message={downloadError} />
      {detail.error?.status === 404 ? (
        <p className="empty">{t("library.stock.notFound")}</p>
      ) : detail.error && !data ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <section className="card flex flex-col gap-3.5" aria-label={t("library.stock.progress")}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="font-display text-2xl font-bold num" data-testid="stock-progress">
                {t("library.stock.counts", { seen: data.check.seen, expected: data.check.expected })}
              </p>
              {open ? <Pill tone="info">{t("library.stock.open")}</Pill> : <Pill tone="good">{t("library.stock.closed")}</Pill>}
            </div>
            <div
              className="meter"
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={data.check.expected}
              aria-valuenow={data.check.seen}
              aria-label={t("library.stock.progress")}
            >
              <span style={{ width: `${data.check.expected ? Math.min(100, (100 * data.check.seen) / data.check.expected) : 0}%` }} />
            </div>
            {open ? (
              <ScanField
                label={t("library.stock.scan")}
                name="accessionNo"
                action={t("library.stock.scanButton")}
                onScan={scan}
                hint={t("library.stock.scanHint")}
                testId="stock-scan"
              />
            ) : (
              <p className="text-[13px] text-ink-2">
                {t("library.stock.closedBy", {
                  name: data.check.closedByName ?? "—",
                  date: formatDateTime(data.check.closedAt, locale),
                })}
              </p>
            )}
            {last ? (
              <p className={`alert ${last.warning || last.alreadySeen ? "alert-warn" : "alert-good"}`} role="status" data-testid="stock-last">
                <span>
                  <b className="mono">{last.accessionNo}</b> {last.title}
                  {last.alreadySeen ? ` · ${t("library.stock.alreadySeen")}` : ""}
                  {last.warning ? ` · ${t("library.stock.warning", { status: copyStatusLabel(t, last.status) })}` : ""}
                </span>
              </p>
            ) : null}
          </section>

          <section className="card flex flex-col gap-3" aria-labelledby="stock-missing">
            <div className="card-head">
              <h2 id="stock-missing">{plural(t, "library.stock.notSeen", data.missing.length)}</h2>
            </div>
            {data.missing.length === 0 ? (
              <p className="text-[13px] text-ink-3">{t("library.stock.allSeen")}</p>
            ) : (
              <>
                <div className="table-wrap hidden md:block">
                  <table className="table" data-testid="stock-missing-table">
                    <thead>
                      <tr>
                        <th scope="col">{t("library.col.accession")}</th>
                        <th scope="col">{t("library.col.title")}</th>
                        <th scope="col">{t("library.col.callNumber")}</th>
                        <th scope="col">{t("library.col.status")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.missing.map((copy) => (
                        <tr key={copy.copyId}>
                          <td className="mono font-semibold">{copy.accessionNo}</td>
                          <td>
                            <Link className="link" href={`/app/library/titles/${copy.titleId}`}>
                              {copy.title ?? "—"}
                            </Link>
                          </td>
                          <td>
                            <span className="mono">{copy.callNumber ?? "—"}</span>
                            {copy.shelfLocation ? <span className="block text-[12.5px] text-ink-3">{copy.shelfLocation}</span> : null}
                          </td>
                          <td>
                            <CopyStatusPill status={copy.status} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <ul className="rowcards md:hidden">
                  {data.missing.map((copy) => (
                    <li key={copy.copyId} className="rowcard">
                      <span className="min-w-0 flex-1">
                        <b className="mono block">{copy.accessionNo}</b>
                        <span className="block truncate text-[13px]">{copy.title ?? "—"}</span>
                        <span className="block text-[12.5px] text-ink-3">
                          {[copy.callNumber, copy.shelfLocation].filter(Boolean).join(" · ") || "—"}
                        </span>
                      </span>
                      <CopyStatusPill status={copy.status} />
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>

          {data.latest.length > 0 ? (
            <section className="card flex flex-col gap-2" aria-labelledby="stock-latest">
              <h2 id="stock-latest">{t("library.stock.latest")}</h2>
              <ul className="flex flex-col gap-1 text-[13px] text-ink-2">
                {data.latest.map((seen, i) => (
                  <li key={`${seen.accessionNo}-${i}`}>
                    <span className="mono font-semibold">{seen.accessionNo}</span> {seen.title} ·{" "}
                    {formatDateTime(seen.seenAt, locale)}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          <ConfirmDialog
            open={closing}
            title={t("library.stock.closeTitle")}
            body={plural(t, "library.stock.closeBody", data.missing.length)}
            confirmLabel={t("library.stock.close")}
            danger={false}
            onClose={() => setClosing(false)}
            onConfirm={async () => {
              await libraryApi.closeStockCheck(id);
              setClosing(false);
              toast(t("library.stock.closedToast"));
              detail.reload();
            }}
          />
        </>
      )}
    </>
  );
}
