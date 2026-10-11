"use client";

import { ChevronLeft, ChevronRight, Plus, Search, Upload } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPaise } from "@/lib/format";
import { plural, useI18n } from "@/lib/i18n";
import { libraryApi, type TitleQuery } from "@/lib/library-api";
import type { LibrarySummary, TitleRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { LibraryNav, useCanManageLibrary } from "./library-shared";
import { TitleDialog } from "./title-dialogs";

export const CATALOGUE_PAGE_SIZE = 25;

/** /app/library: search the catalogue (every member of staff); add titles and see the day at a glance (library.manage). */
export function CatalogueView() {
  const { t } = useI18n();
  const router = useRouter();
  const { toast } = useToast();
  const canManage = useCanManageLibrary();
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState("");
  const [subject, setSubject] = useState("");
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const query: TitleQuery = { q: search.trim() || undefined, subject: subject || undefined, page, size: CATALOGUE_PAGE_SIZE };
  const titles = useApiData(`library:titles:${JSON.stringify(query)}`, () => libraryApi.titles(query));
  const subjects = useApiData("library:subjects", libraryApi.subjects);
  const summary = useApiData(canManage ? "library:summary" : null, libraryApi.summary);

  const onSearch = (value: string) => {
    setSearchText(value);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      setSearch(value);
      setPage(0);
    }, 250);
  };

  const data = titles.data;
  const rows = data?.items ?? [];
  const filtered = Boolean(search.trim() || subject);
  const first = data && data.total > 0 ? data.page * data.size + 1 : 0;
  const last = data ? Math.min(data.total, data.page * data.size + rows.length) : 0;
  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;
  const stats = data?.stats;

  return (
    <>
      <PageHead
        eyebrow={
          stats
            ? `${plural(t, "library.stats.titles", stats.titles)} · ${plural(t, "library.stats.copies", stats.copies)}`
            : t("common.loading")
        }
        title={t("nav.library")}
        actions={
          canManage ? (
            <>
              <Link href="/app/library/import" className="btn">
                <Upload size={18} aria-hidden="true" />
                {t("library.import.short")}
              </Link>
              <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
                <Plus size={18} aria-hidden="true" />
                {t("library.title.add")}
              </button>
            </>
          ) : null
        }
      />
      <LibraryNav />

      {canManage && summary.data ? <SummaryStrip summary={summary.data} /> : null}

      <section className="card">
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("library.search")}</span>
            <input
              type="search"
              name="q"
              value={searchText}
              onChange={(event) => onSearch(event.target.value)}
              placeholder={t("library.search")}
              maxLength={100}
            />
          </label>
          <label className="min-w-[140px] flex-1 sm:flex-none">
            <span className="sr-only">{t("library.filter.subject")}</span>
            <select
              className="input"
              name="subject"
              value={subject}
              onChange={(e) => {
                setSubject(e.target.value);
                setPage(0);
              }}
              aria-label={t("library.filter.subject")}
            >
              <option value="">{t("library.filter.allSubjects")}</option>
              {(subjects.data ?? []).map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
        </div>

        {titles.error && !data ? (
          <ErrorState error={titles.error} onRetry={titles.reload} />
        ) : !data ? (
          <LoadingRows rows={6} />
        ) : rows.length === 0 ? (
          <div className="empty flex flex-col items-center gap-3" data-testid="catalogue-empty">
            <p>{filtered ? t("library.noMatch") : t("library.empty")}</p>
            {!filtered && canManage ? (
              <div className="flex flex-wrap justify-center gap-2">
                <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
                  {t("library.title.add")}
                </button>
                <Link href="/app/library/import" className="btn">
                  {t("library.import.short")}
                </Link>
              </div>
            ) : null}
          </div>
        ) : (
          <div aria-busy={titles.loading}>
            <TitleTable rows={rows} />
            <TitleCards rows={rows} />
            <div className="pager">
              <span>{t("common.showing", { shown: `${first}–${last}`, total: data.total })}</span>
              {data.total > data.size ? (
                <div className="flex gap-2">
                  <button type="button" className="btn btn-sm" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>
                    <ChevronLeft size={16} aria-hidden="true" />
                    {t("common.previous")}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setPage((p) => Math.min(lastPage, p + 1))}
                    disabled={page >= lastPage}
                  >
                    {t("common.next")}
                    <ChevronRight size={16} aria-hidden="true" />
                  </button>
                </div>
              ) : null}
            </div>
          </div>
        )}
      </section>

      {canManage && adding ? (
        <TitleDialog
          open
          title={null}
          onClose={() => setAdding(false)}
          onSaved={(title) => {
            setAdding(false);
            toast(plural(t, "library.title.added", title.copiesTotal, { title: title.title }));
            router.push(`/app/library/titles/${title.id}`);
          }}
        />
      ) : null}
    </>
  );
}

function SummaryStrip({ summary }: { summary: LibrarySummary }) {
  const { t } = useI18n();
  const items: { label: string; value: string; href: string; tone?: "bad" }[] = [
    { label: t("library.summary.issued"), value: String(summary.issued), href: "/app/reports/library-issued" },
    { label: t("library.summary.overdue"), value: String(summary.overdue), href: "/app/reports/library-overdue", tone: summary.overdue ? "bad" : undefined },
    { label: t("library.summary.dueToday"), value: String(summary.dueToday), href: "/app/library/desk" },
    { label: t("library.summary.holdsReady"), value: String(summary.holdsReady), href: "/app/library/holds" },
    { label: t("library.summary.finesDue"), value: formatPaise(summary.finesDuePaise), href: "/app/library/fines" },
  ];
  return (
    <ul className="library-summary" aria-label={t("library.summary.label")} data-testid="library-summary">
      {items.map((item) => (
        <li key={item.label}>
          <Link href={item.href} className="amount">
            <span>{item.label}</span>
            <b className={`num${item.tone === "bad" ? " text-bad" : ""}`}>{item.value}</b>
          </Link>
        </li>
      ))}
    </ul>
  );
}

function Availability({ row }: { row: TitleRow }) {
  const { t } = useI18n();
  if (row.copies === 0) return <span className="text-ink-3">{t("library.noCopies")}</span>;
  return (
    <span className={row.available === 0 ? "text-warn" : undefined}>
      {t("library.available", { available: row.available, copies: row.copies })}
    </span>
  );
}

function TitleTable({ rows }: { rows: TitleRow[] }) {
  const { t } = useI18n();
  return (
    <div className="table-wrap hidden md:block">
      <table className="table" data-testid="catalogue-table">
        <thead>
          <tr>
            <th scope="col">{t("library.col.title")}</th>
            <th scope="col">{t("library.col.subject")}</th>
            <th scope="col">{t("library.col.callNumber")}</th>
            <th scope="col">{t("library.col.isbn")}</th>
            <th scope="col" className="r">
              {t("library.col.available")}
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id}>
              <td>
                <Link href={`/app/library/titles/${row.id}`} className="row-title">
                  <b>{row.title}</b>
                  <span className="sub">{[row.subtitle, row.authors].filter(Boolean).join(" · ") || "—"}</span>
                </Link>
              </td>
              <td>{row.subject ?? "—"}</td>
              <td>
                <span className="mono">{row.callNumber ?? "—"}</span>
                {row.shelfLocation ? <span className="block text-[12.5px] text-ink-3">{row.shelfLocation}</span> : null}
              </td>
              <td className="mono whitespace-nowrap">{row.isbn13 ?? "—"}</td>
              <td className="r num whitespace-nowrap">
                <Availability row={row} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TitleCards({ rows }: { rows: TitleRow[] }) {
  return (
    <ul className="rowcards md:hidden" data-testid="catalogue-cards">
      {rows.map((row) => (
        <li key={row.id}>
          <Link href={`/app/library/titles/${row.id}`} className="rowcard">
            <span className="min-w-0 flex-1">
              <b className="block font-semibold">{row.title}</b>
              <span className="block truncate text-[13px] text-ink-3">
                {[row.authors, row.subject, row.callNumber].filter(Boolean).join(" · ") || "—"}
              </span>
              <span className="block text-[12.5px]">
                <Availability row={row} />
              </span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
