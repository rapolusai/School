"use client";

import { BellRing, Paperclip, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { homeworkApi } from "@/lib/homework-api";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { HOMEWORK_WHEN, type HomeworkWhen } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { HomeworkFormDialog } from "./homework-form-dialog";
import { countsText, dueLabel, dueTone } from "./homework-shared";

const PAGE_SIZE = 20;

const WHEN_LABEL: Record<HomeworkWhen, MessageKey> = {
  open: "homework.when.open",
  past: "homework.when.past",
  all: "homework.when.all",
};

/** Reminders to parents: off by default; only settings.manage switches them. */
function ReminderSettings() {
  const { t } = useI18n();
  const { toast } = useToast();
  const settings = useApiData("homework:settings", homeworkApi.settings);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [value, setValue] = useState<boolean | null>(null);
  const enabled = value ?? settings.data?.remindersEnabled ?? false;

  const toggle = async (next: boolean) => {
    setSaving(true);
    setError(null);
    try {
      const saved = await homeworkApi.saveSettings({ remindersEnabled: next });
      setValue(saved.remindersEnabled);
      toast(saved.remindersEnabled ? t("homework.reminders.on") : t("homework.reminders.off"));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setSaving(false);
    }
  };

  if (settings.error && !settings.data) return <ErrorState error={settings.error} onRetry={settings.reload} />;
  if (!settings.data) return null;
  return (
    <section className="card flex flex-col gap-2" aria-labelledby="hw-reminders">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="hw-reminders">{t("homework.reminders.title")}</h2>
        <BellRing size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      <label className="check">
        <input
          type="checkbox"
          name="remindersEnabled"
          checked={enabled}
          disabled={saving}
          onChange={(e) => void toggle(e.target.checked)}
        />
        <span>
          <b className="block font-semibold">{t("homework.reminders.enabled")}</b>
          <span className="text-[12.5px] text-ink-3">{t("homework.reminders.hint")}</span>
        </span>
      </label>
      <FormAlert message={error} />
    </section>
  );
}

/** Staff homework list: what the caller has set (or can see), with how submissions are going. */
export function HomeworkListView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const whenName = useId();
  const [when, setWhen] = useState<HomeworkWhen>("open");
  const [sectionId, setSectionId] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [dialogKey, setDialogKey] = useState(0);
  const options = useApiData("homework:options", homeworkApi.options);
  const query = { when, sectionId: sectionId || undefined, subjectId: subjectId || undefined, page, size: PAGE_SIZE };
  const list = useApiData(`homework:list:${JSON.stringify(query)}`, () => homeworkApi.list(query));
  const sections = options.data?.sections ?? [];
  const subjects = new Map<string, string>();
  for (const s of sections) {
    if (!sectionId || s.id === sectionId) for (const x of s.subjects) subjects.set(x.id, x.name);
  }
  const canSet = Boolean(options.data?.academicYear) && sections.length > 0;
  const data = list.data;
  const today = options.data?.today ?? "";
  const pages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <PageHead
        eyebrow={options.data?.academicYear?.name}
        title={t("homework.title")}
        actions={
          canSet ? (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                setDialogKey((k) => k + 1);
                setCreating(true);
              }}
            >
              <Plus size={18} aria-hidden="true" />
              {t("homework.set")}
            </button>
          ) : null
        }
      />

      <section className="card">
        <div className="toolbar">
          <div className="seg basis-full sm:basis-auto" role="radiogroup" aria-label={t("homework.when")}>
            {HOMEWORK_WHEN.map((w) => (
              <label key={w}>
                <input
                  type="radio"
                  name={whenName}
                  value={w}
                  checked={when === w}
                  onChange={() => {
                    setWhen(w);
                    setPage(0);
                  }}
                />
                {t(WHEN_LABEL[w])}
              </label>
            ))}
          </div>
          <label className="field min-w-[140px] flex-1 sm:max-w-[220px]">
            <span className="sr-only">{t("homework.section")}</span>
            <select
              className="input"
              name="section"
              value={sectionId}
              onChange={(e) => {
                setSectionId(e.target.value);
                setSubjectId("");
                setPage(0);
              }}
            >
              <option value="">{t("homework.allSections")}</option>
              {sections.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field min-w-[140px] flex-1 sm:max-w-[220px]">
            <span className="sr-only">{t("homework.subject")}</span>
            <select
              className="input"
              name="subject"
              value={subjectId}
              onChange={(e) => {
                setSubjectId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">{t("homework.allSubjects")}</option>
              {[...subjects].map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          </label>
        </div>

        {options.error && !options.data ? (
          <ErrorState error={options.error} onRetry={options.reload} />
        ) : options.data && !options.data.academicYear ? (
          <p className="empty">{t("homework.noYear")}</p>
        ) : options.data && sections.length === 0 && !hasPermission(me, PERMISSIONS.timetableManage) ? (
          <p className="empty" data-testid="homework-no-sections">
            {t("homework.noSections.teacher")}
          </p>
        ) : list.error && !data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : data.items.length === 0 ? (
          <p className="empty" data-testid="homework-empty">
            {when === "open" ? t("homework.empty.open") : t("homework.empty")}
          </p>
        ) : (
          <>
            <ul className="rowcards" aria-label={t("homework.title")} data-testid="homework-list">
              {data.items.map((hw) => (
                <li key={hw.id}>
                  <Link href={`/app/homework/${encodeURIComponent(hw.id)}`} className="rowcard hw-row">
                    <span className="min-w-0 flex-1">
                      <b className="block">{hw.title}</b>
                      <span className="block text-[13px] text-ink-2">
                        {[hw.subjectName, hw.sections.map((s) => s.label).join(", ")].join(" · ")}
                      </span>
                      <span className="block text-[12.5px] text-ink-3">{countsText(t, hw.counts)}</span>
                    </span>
                    <span className="hw-row-side">
                      <Pill tone={dueTone(hw.dueOn, today || hw.dueOn)}>{dueLabel(t, hw.dueOn, today || hw.dueOn, locale)}</Pill>
                      <span className="flex items-center gap-2 text-[12.5px] text-ink-3">
                        {hw.attachments > 0 ? (
                          <span className="inline-flex items-center gap-1" title={t("homework.attachments")}>
                            <Paperclip size={14} aria-hidden="true" />
                            <span className="sr-only">{t("homework.attachments")}</span>
                            {hw.attachments}
                          </span>
                        ) : null}
                        {hw.onlineSubmission ? null : <span>{t("homework.offline")}</span>}
                      </span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
            {data.total > PAGE_SIZE ? (
              <div className="pager">
                <span>{t("common.showing", { shown: data.items.length, total: data.total })}</span>
                <div className="flex gap-2">
                  <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                    {t("common.previous")}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    disabled={page + 1 >= pages}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    {t("common.next")}
                  </button>
                </div>
              </div>
            ) : (
              <p className="mt-2 text-[12.5px] text-ink-3">{plural(t, "homework.count", data.total)}</p>
            )}
          </>
        )}
      </section>

      {hasPermission(me, PERMISSIONS.settingsManage) ? <ReminderSettings /> : null}

      {options.data && creating ? (
        <HomeworkFormDialog
          key={dialogKey}
          open={creating}
          options={options.data}
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false);
            toast(t("homework.form.saved", { title: saved.title }));
            router.push(`/app/homework/${encodeURIComponent(saved.id)}`);
          }}
        />
      ) : null}
    </>
  );
}
