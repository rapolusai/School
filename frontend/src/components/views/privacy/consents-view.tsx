"use client";

import { ChevronLeft, ChevronRight, Search } from "lucide-react";
import { useRef, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { classLabel, formatDateTime, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { privacyApi } from "@/lib/privacy-api";
import { PRIVACY_PURPOSES, type ConsentEntry, type ConsentRow, type PurposeState } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";
import { ConsentPill, PrivacyNav, purposeLabel } from "./privacy-ui";

export const CONSENTS_PAGE_SIZE = 25;

type PaperValues = { givenByName: string; signedOn: string; paperReference: string; photos: boolean; whatsapp: boolean };

/** Client-side checks for a paper consent form, mirroring the API. Pure; exported for tests. */
export function paperProblems(values: PaperValues, today: string): Problems {
  const problems: Problems = {};
  if (!values.givenByName.trim()) problems.givenByName = "validation.required";
  else if (values.givenByName.trim().length > 200) problems.givenByName = "validation.tooLong";
  if (!isPlainDate(values.signedOn)) problems.signedOn = "validation.date";
  else if (values.signedOn > today) problems.signedOn = "privacy.paper.v.future";
  if (values.paperReference.trim().length > 100) problems.paperReference = "validation.tooLong";
  return problems;
}

/** The state of one purpose in a list of states (NONE when it is missing). */
function stateOf(states: PurposeState[], purpose: PurposeState["purpose"]): PurposeState {
  return (
    states.find((s) => s.purpose === purpose) ?? {
      purpose,
      status: "NONE",
      at: null,
      noticeVersion: null,
      method: null,
      current: false,
    }
  );
}

/** /app/privacy/consents: every student's consent for the current notice, and paper forms. */
export function ConsentsView() {
  const { t } = useI18n();
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [paperFor, setPaperFor] = useState<ConsentRow | null>(null);
  const [historyFor, setHistoryFor] = useState<ConsentRow | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const query = { q: search.trim() || undefined, page, size: CONSENTS_PAGE_SIZE };
  const consents = useApiData(`privacy:consents:${JSON.stringify(query)}`, () => privacyApi.consents(query));
  const data = consents.data;
  const rows = data?.items ?? [];

  const onSearch = (value: string) => {
    setSearchText(value);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      setSearch(value);
      setPage(0);
    }, 250);
  };

  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;
  const percent = data && data.activeStudents > 0 ? Math.round((data.essentialGiven * 100) / data.activeStudents) : 0;
  const actions = (row: ConsentRow) => (
    <span className="flex flex-wrap justify-end gap-2">
      <button
        type="button"
        className="btn btn-sm"
        onClick={() => setPaperFor(row)}
        disabled={!data?.noticeVersion}
        aria-label={`${t("privacy.consents.record")}: ${row.fullName}`}
      >
        {t("privacy.consents.record")}
      </button>
      <button
        type="button"
        className="btn btn-sm btn-ghost"
        onClick={() => setHistoryFor(row)}
        aria-label={`${t("privacy.consents.history")}: ${row.fullName}`}
      >
        {t("privacy.consents.history")}
      </button>
    </span>
  );

  return (
    <>
      <PageHead eyebrow={t("privacy.eyebrow")} title={t("privacy.consents.title")} />
      <PrivacyNav />

      {data ? (
        <section className="card flex flex-col gap-2" data-testid="consent-coverage">
          {data.noticeVersion ? (
            <>
              <p>
                {t("privacy.consents.coverage", {
                  given: data.essentialGiven,
                  active: data.activeStudents,
                  version: data.noticeVersion,
                })}
              </p>
              <span
                className="meter"
                role="meter"
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={percent}
                aria-label={t("privacy.consents.coverageLabel")}
              >
                <span style={{ width: `${percent}%` }} />
              </span>
            </>
          ) : (
            <p className="text-ink-2">{t("privacy.consents.noNotice")}</p>
          )}
        </section>
      ) : null}

      <section className="card">
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("privacy.consents.search")}</span>
            <input
              type="search"
              name="q"
              value={searchText}
              onChange={(e) => onSearch(e.target.value)}
              placeholder={t("privacy.consents.search")}
              maxLength={100}
            />
          </label>
        </div>
        {consents.error && !data ? (
          <ErrorState error={consents.error} onRetry={consents.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : rows.length === 0 ? (
          <p className="empty" data-testid="consents-empty">
            {search.trim() ? t("privacy.consents.noMatch") : t("privacy.consents.empty")}
          </p>
        ) : (
          <div aria-busy={consents.loading}>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="consents-table">
                <thead>
                  <tr>
                    <th scope="col">{t("privacy.consents.col.student")}</th>
                    <th scope="col">{t("privacy.consents.col.class")}</th>
                    {PRIVACY_PURPOSES.map((purpose) => (
                      <th key={purpose} scope="col">
                        {translateOr(t, `privacy.purposeShort.${purpose}`, purpose)}
                      </th>
                    ))}
                    <th scope="col" className="r">
                      <span className="sr-only">{t("common.actions")}</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.studentId}>
                      <td>
                        <span className="block leading-tight">
                          {row.fullName}
                          <span className="mono block text-[12.5px] text-ink-3">{row.admissionNo}</span>
                        </span>
                      </td>
                      <td>{classLabel(row.className, row.sectionName)}</td>
                      {PRIVACY_PURPOSES.map((purpose) => (
                        <td key={purpose}>
                          <ConsentPill state={stateOf(row.purposes, purpose)} />
                        </td>
                      ))}
                      <td className="r">{actions(row)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden" data-testid="consents-cards">
              {rows.map((row) => (
                <li key={row.studentId} className="rowcard flex-col items-stretch">
                  <span className="min-w-0">
                    <b className="block truncate font-semibold">{row.fullName}</b>
                    <span className="block truncate text-[12.5px] text-ink-3">
                      {[row.admissionNo, classLabel(row.className, row.sectionName)].filter(Boolean).join(" · ")}
                    </span>
                  </span>
                  <span className="flex flex-wrap gap-2 text-[12.5px]">
                    {PRIVACY_PURPOSES.map((purpose) => (
                      <span key={purpose} className="flex items-center gap-1">
                        {translateOr(t, `privacy.purposeShort.${purpose}`, purpose)}:
                        <ConsentPill state={stateOf(row.purposes, purpose)} />
                      </span>
                    ))}
                  </span>
                  {actions(row)}
                </li>
              ))}
            </ul>
            <div className="pager">
              <span>
                {t("privacy.requests.showing", {
                  first: data.total > 0 ? data.page * data.size + 1 : 0,
                  last: Math.min(data.total, data.page * data.size + rows.length),
                  total: data.total,
                })}
              </span>
              {data.total > data.size ? (
                <div className="flex gap-2">
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={page === 0}
                  >
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

      {paperFor && data?.noticeVersion ? (
        <PaperConsentDialog
          row={paperFor}
          version={data.noticeVersion}
          onClose={() => setPaperFor(null)}
          onSaved={() => {
            setPaperFor(null);
            consents.reload();
          }}
        />
      ) : null}
      {historyFor ? <HistoryDialog row={historyFor} onClose={() => setHistoryFor(null)} /> : null}
    </>
  );
}

function PaperConsentDialog({
  row,
  version,
  onClose,
  onSaved,
}: {
  row: ConsentRow;
  version: number;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [today] = useState(() => todayInIndia());
  const form = useForm<PaperValues>({ givenByName: "", signedOn: today, paperReference: "", photos: false, whatsapp: false });
  const v = form.values;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(paperProblems(v, today), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await privacyApi.recordPaperConsent(row.studentId, {
        givenByName: v.givenByName.trim(),
        signedOn: v.signedOn,
        paperReference: v.paperReference.trim() || null,
        photos: v.photos,
        whatsapp: v.whatsapp,
      });
    });
    if (ok) {
      toast(t("privacy.paper.done", { name: row.fullName }));
      onSaved();
    }
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={t("privacy.paper.title")}
      description={t("privacy.paper.sub", { name: row.fullName, version })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("privacy.paper.givenBy")}
          name="givenByName"
          maxLength={200}
          value={v.givenByName}
          onChange={(e) => form.set("givenByName", e.target.value)}
          error={form.errors.givenByName}
          data-autofocus
        />
        <div className="grid2">
          <TextField
            label={t("privacy.paper.signedOn")}
            name="signedOn"
            type="date"
            max={today}
            value={v.signedOn}
            onChange={(e) => form.set("signedOn", e.target.value)}
            error={form.errors.signedOn}
          />
          <TextField
            label={t("privacy.paper.reference")}
            hint={t("privacy.paper.referenceHint")}
            name="paperReference"
            maxLength={100}
            value={v.paperReference}
            onChange={(e) => form.set("paperReference", e.target.value)}
            error={form.errors.paperReference}
          />
        </div>
        <p className="text-[13.5px] text-ink-2">{t("privacy.paper.essential")}</p>
        <label className="check">
          <input type="checkbox" name="photos" checked={v.photos} onChange={(e) => form.set("photos", e.target.checked)} />
          <span>{purposeLabel(t, "PHOTOS")}</span>
        </label>
        <label className="check">
          <input
            type="checkbox"
            name="whatsapp"
            checked={v.whatsapp}
            onChange={(e) => form.set("whatsapp", e.target.checked)}
          />
          <span>{purposeLabel(t, "WHATSAPP")}</span>
        </label>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("privacy.paper.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** One consent decision as a line: what, how, against which version, by whom. */
export function ConsentHistory({ entries }: { entries: ConsentEntry[] }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  if (entries.length === 0) return <p className="empty">{t("privacy.history.empty")}</p>;
  return (
    <ul className="list" data-testid="consent-history">
      {entries.map((e) => (
        <li key={e.id} className="li flex-col gap-0.5">
          <b className="font-semibold">
            {purposeLabel(t, e.purpose)}: {translateOr(t, `privacy.consent.${e.action}`, e.action)}
          </b>
          <span className="text-[12.5px] text-ink-3">
            {[
              translateOr(t, `privacy.method.${e.method}`, e.method),
              t("privacy.history.version", { version: e.noticeVersion }),
              t("privacy.history.by", { name: e.givenByName }),
              e.recordedByName && e.method === "PAPER" ? t("privacy.history.recordedBy", { name: e.recordedByName }) : null,
              e.signedOn ? t("privacy.history.signedOn", { date: formatPlainDate(e.signedOn, locale) }) : null,
              e.paperReference,
              formatDateTime(e.at, locale),
            ]
              .filter(Boolean)
              .join(" · ")}
          </span>
        </li>
      ))}
    </ul>
  );
}

function HistoryDialog({ row, onClose }: { row: ConsentRow; onClose: () => void }) {
  const { t } = useI18n();
  const history = useApiData(`privacy:consents:${row.studentId}`, () => privacyApi.studentConsents(row.studentId));
  return (
    <Dialog open onClose={onClose} title={t("privacy.history.title", { name: row.fullName })} closeLabel={t("common.close")}>
      {history.error && !history.data ? (
        <ErrorState error={history.error} onRetry={history.reload} />
      ) : !history.data ? (
        <LoadingRows rows={4} />
      ) : (
        <div className="max-h-[60vh] overflow-y-auto">
          <ConsentHistory entries={history.data.history} />
        </div>
      )}
    </Dialog>
  );
}
