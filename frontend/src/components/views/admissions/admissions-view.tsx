"use client";

import { CalendarClock, ChevronLeft, ChevronRight, ExternalLink, MapPin, Plus, Search, Video } from "lucide-react";
import Link from "next/link";
import { useRef, useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { admissionsApi } from "@/lib/admissions-api";
import { api, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import {
  APPLICATION_SOURCES,
  APPLICATION_STAGES,
  type AdmissionsBoard,
  type ApplicationPage,
  type ApplicationQuery,
  type ApplicationRow,
  type ApplicationSource,
  type ApplicationStage,
  type UpcomingSlot,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { kindLabel, modeLabel, sourceLabel, stageLabel, stageTone } from "./admission-labels";
import { ApplicationFormDialog } from "./application-form-dialog";
import { StageActions } from "./stage-actions";

export const PAGE_SIZE = 25;

export type AdmissionsFilters = {
  yearId: string;
  classId: string;
  source: "" | ApplicationSource;
};

const NO_FILTERS: AdmissionsFilters = { yearId: "", classId: "", source: "" };

/** The API query for the filters. Exported for tests. */
export function toApplicationQuery(
  filters: AdmissionsFilters,
  search: string,
  stage: "" | ApplicationStage,
  page: number,
): ApplicationQuery {
  return {
    yearId: filters.yearId || undefined,
    classId: filters.classId || undefined,
    source: filters.source || undefined,
    q: search.trim() || undefined,
    stage: stage || undefined,
    page,
    size: PAGE_SIZE,
  };
}

const OPEN_STAGES: ApplicationStage[] = ["ENQUIRY", "APPLICATION", "ASSESSMENT", "OFFERED"];

type View = "board" | "list";

export function AdmissionsView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.admissionsManage);
  const canSetup = hasPermission(me, PERMISSIONS.academicsRead);

  const [view, setView] = useState<View>("board");
  const [filters, setFilters] = useState<AdmissionsFilters>(NO_FILTERS);
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState("");
  const [stage, setStage] = useState<"" | ApplicationStage>("");
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const years = useApiData(canSetup ? "academics:years" : null, api.listYears);
  const classes = useApiData(canSetup ? "academics:classes" : null, api.listClasses);
  const query = toApplicationQuery(filters, search, stage, page);
  const boardQuery = { yearId: query.yearId, classId: query.classId, source: query.source, q: query.q };
  const board = useApiData(view === "board" ? `admissions:board:${JSON.stringify(boardQuery)}` : null, () =>
    admissionsApi.board(boardQuery),
  );
  const list = useApiData(view === "list" ? `admissions:list:${JSON.stringify(query)}` : null, () =>
    admissionsApi.list(query),
  );
  const upcoming = useApiData("admissions:upcoming", () => admissionsApi.upcomingSlots(14));

  const reload = () => {
    board.reload();
    list.reload();
    upcoming.reload();
  };

  const setFilter = <K extends keyof AdmissionsFilters>(key: K, value: AdmissionsFilters[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
    setPage(0);
  };

  const onSearch = (value: string) => {
    setSearchText(value);
    if (searchTimer.current) clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => {
      setSearch(value);
      setPage(0);
    }, 250);
  };

  /** "See all" under a board column opens the list filtered to that stage. */
  const showStage = (picked: ApplicationStage | "") => {
    setStage(picked);
    setPage(0);
    setView("list");
  };

  const openCount = board.data
    ? board.data.lanes.filter((l) => OPEN_STAGES.includes(l.stage)).reduce((sum, l) => sum + l.total, 0)
    : list.data
      ? OPEN_STAGES.reduce((sum, s) => sum + (list.data?.stageCounts[s] ?? 0), 0)
      : null;
  const filtered = Boolean(search.trim() || filters.yearId || filters.classId || filters.source);
  const schoolCode = me?.tenant?.code;

  return (
    <>
      <PageHead
        eyebrow={openCount === null ? t("common.loading") : plural(t, "admissions.eyebrow", openCount)}
        title={t("admissions.title")}
        actions={
          <>
            {schoolCode ? (
              <Link
                href={`/enquire/${schoolCode}`}
                className="btn"
                target="_blank"
                rel="noopener"
                data-testid="public-form-link"
              >
                <ExternalLink size={18} aria-hidden="true" />
                {t("admissions.publicForm")}
              </Link>
            ) : null}
            {canManage ? (
              <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
                <Plus size={18} aria-hidden="true" />
                {t("admissions.new")}
              </button>
            ) : null}
          </>
        }
      />

      <section className="card">
        <div className="tabs mb-3" role="tablist" aria-label={t("admissions.view")}>
          {(["board", "list"] as const).map((v) => (
            <button
              key={v}
              type="button"
              role="tab"
              id={`admissions-tab-${v}`}
              aria-selected={view === v}
              aria-controls="admissions-panel"
              onClick={() => setView(v)}
            >
              {t(v === "board" ? "admissions.view.board" : "admissions.view.list")}
            </button>
          ))}
        </div>

        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("admissions.search")}</span>
            <input
              type="search"
              name="q"
              value={searchText}
              onChange={(event) => onSearch(event.target.value)}
              placeholder={t("admissions.search")}
              maxLength={100}
            />
          </label>
          {canSetup ? (
            <>
              <label className="min-w-[120px] flex-1 sm:flex-none">
                <span className="sr-only">{t("admissions.filter.year")}</span>
                <select
                  className="input"
                  value={filters.yearId}
                  onChange={(e) => setFilter("yearId", e.target.value)}
                  aria-label={t("admissions.filter.year")}
                >
                  <option value="">{t("admissions.filter.allYears")}</option>
                  {(years.data ?? []).map((y) => (
                    <option key={y.id} value={y.id}>
                      {y.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="min-w-[120px] flex-1 sm:flex-none">
                <span className="sr-only">{t("admissions.filter.class")}</span>
                <select
                  className="input"
                  value={filters.classId}
                  onChange={(e) => setFilter("classId", e.target.value)}
                  aria-label={t("admissions.filter.class")}
                >
                  <option value="">{t("students.filter.allClasses")}</option>
                  {(classes.data ?? []).map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
            </>
          ) : null}
          <label className="min-w-[120px] flex-1 sm:flex-none">
            <span className="sr-only">{t("admissions.filter.source")}</span>
            <select
              className="input"
              value={filters.source}
              onChange={(e) => setFilter("source", e.target.value as AdmissionsFilters["source"])}
              aria-label={t("admissions.filter.source")}
            >
              <option value="">{t("admissions.filter.allSources")}</option>
              {APPLICATION_SOURCES.map((s) => (
                <option key={s} value={s}>
                  {sourceLabel(t, s)}
                </option>
              ))}
            </select>
          </label>
        </div>

        <div id="admissions-panel" role="tabpanel" aria-labelledby={`admissions-tab-${view}`}>
          {view === "board" ? (
            board.error && !board.data ? (
              <ErrorState error={board.error} onRetry={board.reload} />
            ) : !board.data ? (
              <LoadingRows rows={5} />
            ) : (
              <BoardPanel
                board={board.data}
                filtered={filtered}
                canManage={canManage}
                onChanged={reload}
                onShowStage={showStage}
                onAdd={() => setAdding(true)}
              />
            )
          ) : list.error && !list.data ? (
            <ErrorState error={list.error} onRetry={list.reload} />
          ) : !list.data ? (
            <LoadingRows rows={6} />
          ) : (
            <ListPanel
              data={list.data}
              stage={stage}
              onStage={(s) => {
                setStage(s);
                setPage(0);
              }}
              loading={list.loading}
              canManage={canManage}
              onChanged={reload}
              page={page}
              setPage={setPage}
            />
          )}
        </div>
      </section>

      <UpcomingSlots data={upcoming.data} error={upcoming.error} onRetry={upcoming.reload} />

      {canManage ? (
        <ApplicationFormDialog
          open={adding}
          onClose={() => setAdding(false)}
          onSaved={(created) => {
            setAdding(false);
            reload();
            toast(t("admissions.created", { name: created.childName }));
          }}
        />
      ) : null}
    </>
  );
}

/* ------------------------------------------------------------------ board */

function CardMeta({ row }: { row: ApplicationRow }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const today = todayInIndia();
  const due = row.followUpOn !== null && row.followUpOn <= today;
  return (
    <span className="kcard-meta">
      <span className="chip">
        {row.daysInStage === 0 ? t("admissions.card.today") : plural(t, "admissions.card.days", row.daysInStage)}
      </span>
      {row.followUpOn ? (
        due ? (
          <Pill tone="warn">{t("admissions.card.followUpDue", { date: formatPlainDate(row.followUpOn, locale) })}</Pill>
        ) : (
          <span className="chip">{t("admissions.card.followUp", { date: formatPlainDate(row.followUpOn, locale) })}</span>
        )
      ) : null}
      {row.nextSlotAt ? (
        <span className="chip">
          <CalendarClock size={13} aria-hidden="true" />
          {formatDateTime(row.nextSlotAt, locale)}
        </span>
      ) : null}
    </span>
  );
}

export function BoardPanel({
  board,
  filtered,
  canManage,
  onChanged,
  onShowStage,
  onAdd,
}: {
  board: AdmissionsBoard;
  filtered: boolean;
  canManage: boolean;
  onChanged: () => void;
  onShowStage: (stage: ApplicationStage | "") => void;
  onAdd: () => void;
}) {
  const { t } = useI18n();
  const total = board.lanes.reduce((sum, lane) => sum + lane.total, 0) + board.closed;

  if (total === 0) {
    return (
      <div className="empty flex flex-col items-center gap-3" data-testid="admissions-empty">
        <p>{filtered ? t("admissions.noMatch") : t("admissions.empty")}</p>
        {!filtered && canManage ? (
          <button type="button" className="btn btn-primary" onClick={onAdd}>
            {t("admissions.new")}
          </button>
        ) : null}
      </div>
    );
  }

  return (
    <>
      <div className="kanban" data-testid="admissions-board">
        {board.lanes.map((lane) => (
          <section
            key={lane.stage}
            className="lane"
            aria-labelledby={`lane-${lane.stage}`}
            data-testid={`lane-${lane.stage}`}
          >
            <h2 className="lane-h" id={`lane-${lane.stage}`}>
              {stageLabel(t, lane.stage)} <span>{lane.total}</span>
            </h2>
            {lane.cards.length === 0 ? <p className="lane-empty">{t("admissions.lane.empty")}</p> : null}
            {lane.cards.map((row) => (
              <article key={row.id} className="kcard" aria-label={row.childName}>
                <Link href={`/app/admissions/${row.id}`} className="kcard-title">
                  <b>{row.childName}</b>
                </Link>
                <span className="kcard-sub">
                  {[row.className, row.academicYearName].filter(Boolean).join(" · ")}
                </span>
                <CardMeta row={row} />
                {canManage && row.nextStages.length > 0 ? (
                  <div className="kcard-actions">
                    <StageActions
                      application={{
                        id: row.id,
                        childName: row.childName,
                        stage: row.stage,
                        classId: row.classId,
                        className: row.className,
                      }}
                      nextStages={row.nextStages}
                      onChanged={onChanged}
                    />
                  </div>
                ) : null}
              </article>
            ))}
            {lane.total > lane.cards.length ? (
              <button type="button" className="linkbtn text-[13px]" onClick={() => onShowStage(lane.stage)}>
                {t("admissions.lane.more", { count: lane.total - lane.cards.length })}
              </button>
            ) : null}
          </section>
        ))}
      </div>
      {board.closed > 0 ? (
        <p className="mt-3 text-[13px] text-ink-3">
          {plural(t, "admissions.closed", board.closed)}{" "}
          <button type="button" className="linkbtn" onClick={() => onShowStage("REJECTED")}>
            {t("admissions.closed.show")}
          </button>
        </p>
      ) : null}
    </>
  );
}

/* ------------------------------------------------------------------ list */

export function ListPanel({
  data,
  stage,
  onStage,
  loading,
  canManage,
  onChanged,
  page,
  setPage,
}: {
  data: ApplicationPage;
  stage: "" | ApplicationStage;
  onStage: (stage: "" | ApplicationStage) => void;
  loading: boolean;
  canManage: boolean;
  onChanged: () => void;
  page: number;
  setPage: (page: number) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const rows = data.items;
  const all = APPLICATION_STAGES.reduce((sum, s) => sum + (data.stageCounts[s] ?? 0), 0);
  const first = data.total > 0 ? data.page * data.size + 1 : 0;
  const last = Math.min(data.total, data.page * data.size + rows.length);
  const lastPage = Math.max(0, Math.ceil(data.total / data.size) - 1);

  return (
    <div aria-busy={loading}>
      <div className="stage-filter" role="group" aria-label={t("admissions.filter.stage")}>
        <button type="button" className="chip" aria-pressed={stage === ""} onClick={() => onStage("")}>
          {t("admissions.filter.allStages")} <b className="num">{all}</b>
        </button>
        {APPLICATION_STAGES.map((s) => (
          <button key={s} type="button" className="chip" aria-pressed={stage === s} onClick={() => onStage(s)}>
            {stageLabel(t, s)} <b className="num">{data.stageCounts[s] ?? 0}</b>
          </button>
        ))}
      </div>

      {rows.length === 0 ? (
        <p className="empty" data-testid="admissions-list-empty">
          {t("admissions.noMatch")}
        </p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table" data-testid="admissions-table">
              <thead>
                <tr>
                  <th scope="col">{t("admissions.col.child")}</th>
                  <th scope="col">{t("admissions.col.class")}</th>
                  <th scope="col">{t("admissions.col.stage")}</th>
                  <th scope="col">{t("admissions.col.followUp")}</th>
                  <th scope="col">{t("admissions.col.contact")}</th>
                  <th scope="col">{t("admissions.col.source")}</th>
                  {canManage ? (
                    <th scope="col">
                      <span className="sr-only">{t("common.actions")}</span>
                    </th>
                  ) : null}
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id}>
                    <td>
                      <Link href={`/app/admissions/${row.id}`} className="row-title">
                        <b>{row.childName}</b>
                      </Link>
                    </td>
                    <td className="whitespace-nowrap">
                      {row.className}
                      <span className="block text-[12.5px] text-ink-3">{row.academicYearName}</span>
                    </td>
                    <td>
                      <Pill tone={stageTone(row.stage)} dot>
                        {stageLabel(t, row.stage)}
                      </Pill>
                      <span className="block text-[12.5px] text-ink-3">
                        {row.daysInStage === 0
                          ? t("admissions.card.today")
                          : plural(t, "admissions.card.days", row.daysInStage)}
                      </span>
                    </td>
                    <td className="whitespace-nowrap">{formatPlainDate(row.followUpOn, locale) || "—"}</td>
                    <td>
                      {row.contactName ? (
                        <span className="block leading-tight">
                          {row.contactName}
                          <span className="block text-[12.5px] text-ink-3 num">{row.contactPhone}</span>
                        </span>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className="whitespace-nowrap">{sourceLabel(t, row.source)}</td>
                    {canManage ? (
                      <td className="r">
                        <StageActions
                          application={{
                            id: row.id,
                            childName: row.childName,
                            stage: row.stage,
                            classId: row.classId,
                            className: row.className,
                          }}
                          nextStages={row.nextStages}
                          onChanged={onChanged}
                        />
                      </td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="admissions-cards">
            {rows.map((row) => (
              <li key={row.id} className="rowcard">
                <span className="min-w-0 flex-1">
                  <Link href={`/app/admissions/${row.id}`} className="block truncate font-semibold">
                    {row.childName}
                  </Link>
                  <span className="block truncate text-[13px] text-ink-3">
                    {[row.className, row.academicYearName, sourceLabel(t, row.source)].join(" · ")}
                  </span>
                  {row.contactName ? (
                    <span className="block truncate text-[12.5px] text-ink-3">
                      {row.contactName} · <span className="num">{row.contactPhone}</span>
                    </span>
                  ) : null}
                </span>
                <Pill tone={stageTone(row.stage)}>{stageLabel(t, row.stage)}</Pill>
              </li>
            ))}
          </ul>
          <div className="pager">
            <span>{t("admissions.showing", { first, last, total: data.total })}</span>
            {data.total > data.size ? (
              <div className="flex gap-2">
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => setPage(Math.max(0, page - 1))}
                  disabled={page === 0}
                >
                  <ChevronLeft size={16} aria-hidden="true" />
                  {t("common.previous")}
                </button>
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => setPage(Math.min(lastPage, page + 1))}
                  disabled={page >= lastPage}
                >
                  {t("common.next")}
                  <ChevronRight size={16} aria-hidden="true" />
                </button>
              </div>
            ) : null}
          </div>
        </>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ upcoming tests and interviews */

function UpcomingSlots({
  data,
  error,
  onRetry,
}: {
  data: UpcomingSlot[] | undefined;
  error: ApiError | undefined;
  onRetry: () => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <section className="card" aria-labelledby="upcoming-heading">
      <div className="card-head">
        <h2 id="upcoming-heading">{t("admissions.upcoming.title")}</h2>
      </div>
      {error && !data ? (
        <ErrorState error={error} onRetry={onRetry} />
      ) : !data ? (
        <LoadingRows rows={2} />
      ) : data.length === 0 ? (
        <p className="text-sm text-ink-3">{t("admissions.upcoming.empty")}</p>
      ) : (
        <ul className="list" data-testid="upcoming-slots">
          {data.map((slot) => (
            <li key={slot.id} className="li items-center">
              <span className="badge-ic">
                {slot.mode === "ONLINE" ? (
                  <Video size={18} aria-hidden="true" />
                ) : (
                  <MapPin size={18} aria-hidden="true" />
                )}
              </span>
              <div className="min-w-0 flex-1">
                <p className="font-semibold">
                  <Link href={`/app/admissions/${slot.applicationId}`} className="link">
                    {slot.childName}
                  </Link>{" "}
                  <span className="font-normal text-ink-3">· {slot.className}</span>
                </p>
                <p className="text-[13px] text-ink-2">
                  {[
                    kindLabel(t, slot.kind),
                    slot.mode === "ONLINE" ? modeLabel(t, slot.mode) : slot.location,
                    slot.interviewer?.name,
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
              </div>
              <time className="whitespace-nowrap text-[13px] text-ink-2" dateTime={slot.scheduledAt}>
                {formatDateTime(slot.scheduledAt, locale)}
              </time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
