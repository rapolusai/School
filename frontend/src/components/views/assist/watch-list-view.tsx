"use client";

import { Play } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { Figure } from "@/components/views/reports/report-frame";
import { api, toApiError } from "@/lib/api";
import { assistApi } from "@/lib/assist-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import { WATCH_STATUSES, type AssistPerson, type WatchRow, type WatchStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AssistNav, AssistOffNotice, RiskPill, WatchStatusPill, assistError, useAssistSettings } from "./assist-shared";

/**
 * The early warning watch list (docs/api/phase-3-assist.md): students the rule-based score lists, with every reason,
 * who is following them up, notes and a simulated message to the parent. Staff only: nothing is shown to families.
 */
export function WatchListView() {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const settings = useAssistSettings();
  const classes = useApiData("assist:classes", () => api.listClasses());
  const [classId, setClassId] = useState("");
  const [status, setStatus] = useState<WatchStatus | "">("");
  const [running, setRunning] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const list = useApiData(`assist:watch:${classId}:${status}`, () => assistApi.watchList(classId || null, status || null));
  const data = list.data;

  const runNow = async () => {
    setRunning(true);
    setProblem(null);
    try {
      const result = await assistApi.runWatchList();
      toast(t("assist.watch.ran", { students: result.students, listed: result.listed }));
      list.reload();
    } catch (error) {
      setProblem(assistError(toApiError(error), t));
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHead
        eyebrow={t("assist.eyebrow")}
        title={t("assist.watch.title")}
        actions={
          <button type="button" className="btn btn-primary" disabled={running} onClick={() => void runNow()} data-testid="assist-run-now">
            <Play size={16} aria-hidden="true" />
            {running ? t("assist.watch.running") : t("assist.watch.runNow")}
          </button>
        }
      />
      <AssistNav />
      {settings.data ? <AssistOffNotice settings={settings.data} feature="earlyWarningEnabled" /> : null}
      <p className="text-[13px] text-ink-3">{t("assist.watch.explain")}</p>
      <FormAlert message={problem} />
      <div className="card flex flex-wrap items-end gap-3">
        <label className="field w-[calc(50%-6px)] sm:w-auto sm:min-w-40">
          <span className="field-label">{t("reports.filter.class")}</span>
          <select className="input" name="classId" value={classId} onChange={(e) => setClassId(e.target.value)}>
            <option value="">{t("reports.filter.allClasses")}</option>
            {(classes.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field w-[calc(50%-6px)] sm:w-auto sm:min-w-40">
          <span className="field-label">{t("assist.watch.status")}</span>
          <select className="input" name="status" value={status} onChange={(e) => setStatus(e.target.value as WatchStatus | "")}>
            <option value="">{t("assist.watch.allStatuses")}</option>
            {WATCH_STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`assist.status.${s}` as MessageKey)}
              </option>
            ))}
          </select>
        </label>
      </div>
      {list.error && !data ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("assist.level.HIGH")} value={data.high} testId="watch-high" />
            <Figure label={t("assist.level.MEDIUM")} value={data.medium} />
            <Figure label={t("assist.level.LOW")} value={data.low} />
            <Figure label={t("assist.watch.lastRun")} value={data.lastRunAt ? formatDateTime(data.lastRunAt, locale) : t("common.never")} />
          </div>
          {data.entries.length === 0 ? (
            <p className="empty">{data.lastRunAt ? t("assist.watch.empty") : t("assist.watch.neverRun")}</p>
          ) : (
            <ul className="flex flex-col gap-3" data-testid="watch-list">
              {data.entries.map((entry) => (
                <li key={entry.id} className="card flex flex-col gap-2" data-testid="watch-row" data-student={entry.studentName}>
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="min-w-0">
                      <h2 className="text-[15px] font-semibold">{entry.studentName}</h2>
                      <p className="text-[13px] text-ink-3">
                        {entry.classLabel} · {t("assist.watch.score", { score: entry.score })}
                      </p>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <RiskPill level={entry.level} />
                      <WatchStatusPill status={entry.status} />
                    </div>
                  </div>
                  <Reasons entry={entry} />
                  <div className="flex flex-wrap items-center justify-between gap-2 text-[13px] text-ink-3">
                    <span>
                      {entry.assignedToName ? t("assist.watch.assignedTo", { name: entry.assignedToName }) : t("assist.watch.unassigned")}
                      {entry.notes > 0 ? ` · ${plural(t, "assist.watch.notes", entry.notes)}` : ""}
                      {entry.parentInformedAt ? ` · ${t("assist.watch.parentInformed")}` : ""}
                    </span>
                    <button type="button" className="btn btn-sm" onClick={() => setOpenId(entry.id)} aria-label={t("assist.watch.openFor", { name: entry.studentName })}>
                      {t("assist.watch.open")}
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
          {openId ? (
            <WatchDialog
              entryId={openId}
              assignees={data.assignees}
              onClose={() => {
                setOpenId(null);
                list.reload();
              }}
            />
          ) : null}
        </>
      )}
    </div>
  );
}

function Reasons({ entry }: { entry: WatchRow }) {
  const { t } = useI18n();
  return (
    <ul className="flex flex-col gap-1 text-[14px]" aria-label={t("assist.watch.reasons")}>
      {entry.reasons.map((reason) => (
        <li key={reason.signal} className="flex justify-between gap-3">
          <span>{reason.detail}</span>
          <span className="num shrink-0 text-ink-3">{t("assist.watch.points", { points: reason.points })}</span>
        </li>
      ))}
    </ul>
  );
}

/** One student on the list: reasons, assignment, status, notes and "parent informed". */
function WatchDialog({ entryId, assignees, onClose }: { entryId: string; assignees: AssistPerson[]; onClose: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const detail = useApiData(`assist:watch-entry:${entryId}`, () => assistApi.watchEntry(entryId));
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const entry = detail.data?.entry;

  const run = async (work: () => Promise<unknown>) => {
    setBusy(true);
    setProblem(null);
    try {
      await work();
      detail.reload();
      return true;
    } catch (error) {
      setProblem(assistError(toApiError(error), t));
      return false;
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} title={entry ? entry.studentName : t("assist.watch.title")} closeLabel={t("common.close")}>
      {detail.error && !detail.data ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !entry ? (
        <LoadingRows rows={3} />
      ) : (
        <div className="flex flex-col gap-3" data-testid="watch-detail">
          <p className="text-[13px] text-ink-3">
            {entry.classLabel} · {t("assist.watch.score", { score: entry.score })} · {formatDateTime(entry.computedAt, locale)}
          </p>
          <Reasons entry={entry} />
          <label className="field">
            <span className="field-label">{t("assist.watch.assign")}</span>
            <select
              className="input"
              name="assignedToId"
              value={entry.assignedToId ?? ""}
              disabled={busy}
              onChange={(e) => void run(() => assistApi.assign(entry.id, e.target.value || null))}
            >
              <option value="">{t("assist.watch.unassigned")}</option>
              {assignees.map((person) => (
                <option key={person.id} value={person.id}>
                  {person.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">{t("assist.watch.status")}</span>
            <select
              className="input"
              name="status"
              value={entry.status}
              disabled={busy}
              onChange={(e) => void run(() => assistApi.setStatus(entry.id, e.target.value as WatchStatus))}
            >
              {WATCH_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {t(`assist.status.${s}` as MessageKey)}
                </option>
              ))}
            </select>
          </label>
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" className="btn btn-sm" disabled={busy} onClick={() => void run(() => assistApi.informParent(entry.id))}>
              {t("assist.watch.informParent")}
            </button>
            {entry.parentInformedAt ? (
              <span className="text-[13px] text-ink-3">{t("assist.watch.informedOn", { when: formatDateTime(entry.parentInformedAt, locale) })}</span>
            ) : null}
          </div>
          <p className="text-[13px] text-ink-3">{t("assist.watch.informHint")}</p>
          <FormAlert message={problem} />
          <form
            method="post"
            className="flex flex-col gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              void run(() => assistApi.addNote(entry.id, note)).then((ok) => {
                if (ok) setNote("");
              });
            }}
          >
            <label className="field">
              <span className="field-label">{t("assist.watch.note")}</span>
              <textarea className="input" name="note" rows={2} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />
            </label>
            <button type="submit" className="btn btn-sm self-start" disabled={busy || !note.trim()}>
              {t("assist.watch.addNote")}
            </button>
          </form>
          {detail.data && detail.data.notes.length > 0 ? (
            <ul className="flex flex-col gap-2 text-[14px]" data-testid="watch-notes">
              {detail.data.notes.map((n) => (
                <li key={n.id}>
                  <p>{n.body}</p>
                  <p className="text-[12px] text-ink-3">
                    {n.authorName} · {formatDateTime(n.createdAt, locale)}
                  </p>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      )}
    </Dialog>
  );
}
