"use client";

import { ArrowRight, BellRing, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { fromIndiaInput, toIndiaInput } from "@/components/views/admissions/admission-time";
import { formatScore, YearSelect } from "@/components/views/exams/exams-shared";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { LESSONS_LIMITS, type LessonsOptions, type ShowAnswers, type TestDetail, type TestRequest } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isWholeNumberInRange } from "@/lib/validation";
import { classChoices, LessonsNav, TestStatePill, useLessonsOptions } from "./lessons-shared";

/** "17 Oct 2026, 09:00 – 18 Oct 2026, 17:00". */
export function testWindow(opensAt: string, closesAt: string, locale: string): string {
  return `${formatDateTime(opensAt, locale)} – ${formatDateTime(closesAt, locale)}`;
}

/** /app/lessons/tests: the year's online tests, with the switch for telling families about new tests. */
export function TestsListView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { me } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.lessonsManage);
  const [yearId, setYearId] = useState("");
  const [mine, setMine] = useState(canManage);
  const [creating, setCreating] = useState(false);
  const [dialogKey, setDialogKey] = useState(0);
  const options = useLessonsOptions(yearId);
  const list = useApiData(`lessons:tests:${yearId}:${mine}`, () => lessonsApi.tests(yearId || null, mine));
  const canCreate = Boolean(canManage && options.data?.yearId && options.data && classChoices(options.data).length > 0);
  const year = options.data?.years.find((y) => y.id === options.data?.yearId);

  return (
    <>
      <PageHead
        eyebrow={year?.name}
        title={t("lessons.tests.title")}
        actions={
          canCreate ? (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                setDialogKey((k) => k + 1);
                setCreating(true);
              }}
            >
              <Plus size={18} aria-hidden="true" />
              {t("lessons.tests.add")}
            </button>
          ) : null
        }
      />
      <LessonsNav />
      {hasPermission(me, PERMISSIONS.settingsManage) ? <NotificationSettings /> : null}
      <section className="card flex flex-col gap-3">
        <form method="post" className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label={t("lessons.filters")}>
          {options.data ? (
            <YearSelect years={options.data.years} value={yearId || options.data.yearId || ""} onChange={setYearId} />
          ) : null}
          {canManage ? (
            <label className="check self-end">
              <input type="checkbox" name="mine" checked={mine} onChange={(e) => setMine(e.target.checked)} />
              <span>{t("lessons.tests.mine")}</span>
            </label>
          ) : null}
        </form>
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={4} />
        ) : list.data.length === 0 ? (
          <p className="empty">{canCreate ? t("lessons.tests.emptyAdd") : t("lessons.tests.empty")}</p>
        ) : (
          <ul className="rowcards" data-testid="test-list">
            {list.data.map((test) => (
              <li key={test.id}>
                <Link href={`/app/lessons/tests/${encodeURIComponent(test.id)}`} className="rowcard" data-testid="test-row">
                  <span className="min-w-0 flex-1">
                    <b className="block font-semibold">{test.title}</b>
                    <span className="block text-[12.5px] text-ink-3">
                      {[
                        test.sections.map((s) => s.label).join(", ") || test.className,
                        test.subjectName,
                        plural(t, "lessons.tests.questionsCount", test.questions),
                        plural(t, "lessons.marksCount", test.totalMarks, { marks: formatScore(test.totalMarks) }),
                      ].join(" · ")}
                    </span>
                    <span className="block text-[12.5px] text-ink-3">{testWindow(test.opensAt, test.closesAt, locale)}</span>
                    {test.state !== "DRAFT" ? (
                      <span className="block text-[12.5px] text-ink-2">
                        {t("lessons.tests.progress", { submitted: test.submitted, students: test.students })}
                        {test.pendingMarking > 0 ? ` · ${plural(t, "lessons.tests.toMark", test.pendingMarking)}` : ""}
                      </span>
                    ) : null}
                  </span>
                  <span className="flex flex-col items-end gap-1">
                    <TestStatePill state={test.state} />
                    {test.released ? (
                      <Pill tone="good">{t("lessons.tests.released")}</Pill>
                    ) : null}
                  </span>
                  <ArrowRight size={18} aria-hidden="true" className="flex-none text-ink-3" />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      {creating && options.data ? (
        <TestSettingsDialog
          key={dialogKey}
          test={null}
          options={options.data}
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false);
            toast(t("lessons.tests.saved"));
            router.push(`/app/lessons/tests/${encodeURIComponent(saved.id)}`);
          }}
        />
      ) : null}
    </>
  );
}

/** Telling parents and students about each new online test: off by default; only settings.manage switches it. */
function NotificationSettings() {
  const { t } = useI18n();
  const { toast } = useToast();
  const settings = useApiData("lessons:settings", lessonsApi.settings);
  const [value, setValue] = useState<boolean | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const enabled = value ?? settings.data?.testNotificationsEnabled ?? false;

  const toggle = async (next: boolean) => {
    setSaving(true);
    setError(null);
    try {
      const saved = await lessonsApi.saveSettings({ testNotificationsEnabled: next });
      setValue(saved.testNotificationsEnabled);
      toast(saved.testNotificationsEnabled ? t("lessons.settings.on") : t("lessons.settings.off"));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setSaving(false);
    }
  };

  if (settings.error && !settings.data) return <ErrorState error={settings.error} onRetry={settings.reload} />;
  if (!settings.data) return null;
  return (
    <section className="card flex flex-col gap-2" aria-labelledby="lessons-notify">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="lessons-notify">{t("lessons.settings.title")}</h2>
        <BellRing size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      <label className="check">
        <input
          type="checkbox"
          name="testNotificationsEnabled"
          checked={enabled}
          disabled={saving}
          onChange={(e) => void toggle(e.target.checked)}
        />
        <span>
          <b className="block font-semibold">{t("lessons.settings.notify")}</b>
          <span className="text-[12.5px] text-ink-3">{t("lessons.settings.notifyHint")}</span>
        </span>
      </label>
      <FormAlert message={error} />
    </section>
  );
}

export type TestValues = {
  classId: string;
  subjectId: string;
  sectionIds: string[];
  title: string;
  instructions: string;
  opensAt: string;
  closesAt: string;
  durationMinutes: string;
  attemptsAllowed: string;
  shuffleQuestions: boolean;
  shuffleOptions: boolean;
  showAnswers: ShowAnswers;
};

const MAX_OPEN_DAYS = 90;

/** Client checks matching the API's. Pure, exported for tests. */
export function validateTest(values: TestValues): Problems {
  const problems: Problems = {};
  if (!values.classId) problems.classId = "lessons.v.class";
  if (!values.subjectId) problems.subjectId = "lessons.v.subject";
  if (values.sectionIds.length === 0) problems.sectionIds = "lessons.v.sections";
  if (!values.title.trim()) problems.title = "lessons.v.title";
  else if (values.title.trim().length > LESSONS_LIMITS.title) problems.title = "lessons.v.titleLong";
  if (values.instructions.trim().length > LESSONS_LIMITS.testInstructions) problems.instructions = "lessons.v.long2000";
  const opens = fromIndiaInput(values.opensAt);
  const closes = fromIndiaInput(values.closesAt);
  if (!opens) problems.opensAt = "lessons.v.dateTime";
  if (!closes) problems.closesAt = "lessons.v.dateTime";
  else if (opens && closes <= opens) problems.closesAt = "lessons.v.closeAfterOpen";
  else if (opens && Date.parse(closes) - Date.parse(opens) > MAX_OPEN_DAYS * 86_400_000) problems.closesAt = "lessons.v.openTooLong";
  if (!isWholeNumberInRange(values.durationMinutes, 1, LESSONS_LIMITS.duration)) problems.durationMinutes = "lessons.v.duration";
  if (!isWholeNumberInRange(values.attemptsAllowed, 1, LESSONS_LIMITS.attempts)) problems.attemptsAllowed = "lessons.v.attempts";
  return problems;
}

/** The request body for a test's values. Pure, exported for tests. */
export function testRequest(values: TestValues, yearId: string | null, version?: number): TestRequest {
  return {
    academicYearId: yearId,
    classId: values.classId,
    subjectId: values.subjectId,
    sectionIds: values.sectionIds,
    title: values.title.trim(),
    instructions: values.instructions.trim() || null,
    opensAt: fromIndiaInput(values.opensAt) ?? values.opensAt,
    closesAt: fromIndiaInput(values.closesAt) ?? values.closesAt,
    durationMinutes: Number(values.durationMinutes),
    attemptsAllowed: Number(values.attemptsAllowed),
    shuffleQuestions: values.shuffleQuestions,
    shuffleOptions: values.shuffleOptions,
    showAnswers: values.showAnswers,
    version: version ?? null,
  };
}

/** A test's settings: class, sections and subject, title, window, duration, attempts, shuffling, answers shown. */
export function TestSettingsDialog({
  test,
  options,
  onClose,
  onSaved,
}: {
  test: TestDetail | null;
  options: LessonsOptions;
  onClose: () => void;
  onSaved: (test: TestDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const form = useForm<TestValues>(
    test
      ? {
          classId: test.classId,
          subjectId: test.subjectId,
          sectionIds: test.sections.map((s) => s.id),
          title: test.title,
          instructions: test.instructions ?? "",
          opensAt: toIndiaInput(test.opensAt),
          closesAt: toIndiaInput(test.closesAt),
          durationMinutes: String(test.durationMinutes),
          attemptsAllowed: String(test.attemptsAllowed),
          shuffleQuestions: test.shuffleQuestions,
          shuffleOptions: test.shuffleOptions,
          showAnswers: test.showAnswers,
        }
      : {
          classId: "",
          subjectId: "",
          sectionIds: [],
          title: "",
          instructions: "",
          opensAt: `${today}T09:00`,
          closesAt: `${today}T17:00`,
          durationMinutes: "30",
          attemptsAllowed: "1",
          shuffleQuestions: false,
          shuffleOptions: false,
          showAnswers: "WITH_RESULTS",
        },
  );
  const { values, set, errors } = form;
  const classes = classChoices(options);
  const chosen = classes.find((c) => c.id === values.classId) ?? options.classes.find((c) => c.id === values.classId);
  const sections = (chosen?.sections ?? []).filter(
    (s) =>
      options.wholeSchool ||
      options.teaching.some((x) => x.sectionId === s.id && (!values.subjectId || x.subjectId === values.subjectId)),
  );
  const locked = Boolean(test && test.questions.length > 0);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateTest(values), t, event.currentTarget)) return;
    const body = testRequest(values, test?.yearId ?? options.yearId, test?.version);
    let saved: TestDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = test ? await lessonsApi.updateTest(test.id, body) : await lessonsApi.createTest(body);
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={test ? t("lessons.tests.edit") : t("lessons.tests.add")}
      description={t("lessons.tests.dialog")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="test-form">
        <FormAlert message={form.formError} />
        <div className="grid2">
          <SelectField
            label={t("lessons.class")}
            name="classId"
            value={values.classId}
            disabled={locked}
            onChange={(e) => {
              set("classId", e.target.value);
              set("subjectId", "");
              set("sectionIds", []);
            }}
            options={[{ value: "", label: t("lessons.pick") }, ...classes.map((c) => ({ value: c.id, label: c.name }))]}
            hint={locked ? t("lessons.tests.classLocked") : undefined}
            error={errors.classId}
            required
            data-autofocus
          />
          <SelectField
            label={t("lessons.subject")}
            name="subjectId"
            value={values.subjectId}
            disabled={locked || !values.classId}
            onChange={(e) => {
              set("subjectId", e.target.value);
              set("sectionIds", []);
            }}
            options={[{ value: "", label: t("lessons.pick") }, ...(chosen?.subjects ?? []).map((s) => ({ value: s.id, label: s.name }))]}
            error={errors.subjectId}
            required
          />
        </div>
        <fieldset className="fieldset">
          <legend>{t("lessons.tests.sections")}</legend>
          {sections.length === 0 ? (
            <p className="text-[13px] text-ink-3">{t("lessons.tests.pickClassFirst")}</p>
          ) : (
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {sections.map((s, i) => (
                <label key={s.id} className="check">
                  <input
                    type="checkbox"
                    name={i === 0 ? "sectionIds" : `section-${i}`}
                    checked={values.sectionIds.includes(s.id)}
                    onChange={(e) =>
                      set("sectionIds", e.target.checked ? [...values.sectionIds, s.id] : values.sectionIds.filter((x) => x !== s.id))
                    }
                  />
                  <span>{s.label}</span>
                </label>
              ))}
            </div>
          )}
          {errors.sectionIds ? <p className="field-error">{errors.sectionIds}</p> : null}
        </fieldset>
        <TextField
          label={t("lessons.tests.titleLabel")}
          name="title"
          value={values.title}
          maxLength={LESSONS_LIMITS.title}
          onChange={(e) => set("title", e.target.value)}
          error={errors.title}
          required
        />
        <TextAreaField
          label={t("lessons.tests.instructions")}
          name="instructions"
          rows={2}
          maxLength={LESSONS_LIMITS.testInstructions}
          value={values.instructions}
          onChange={(e) => set("instructions", e.target.value)}
          error={errors.instructions}
        />
        <div className="grid2">
          <TextField
            label={t("lessons.tests.opensAt")}
            name="opensAt"
            type="datetime-local"
            value={values.opensAt}
            onChange={(e) => set("opensAt", e.target.value)}
            error={errors.opensAt}
            required
          />
          <TextField
            label={t("lessons.tests.closesAt")}
            name="closesAt"
            type="datetime-local"
            value={values.closesAt}
            onChange={(e) => set("closesAt", e.target.value)}
            error={errors.closesAt}
            required
          />
        </div>
        <div className="grid2">
          <TextField
            label={t("lessons.tests.duration")}
            name="durationMinutes"
            type="number"
            inputMode="numeric"
            min={1}
            max={LESSONS_LIMITS.duration}
            value={values.durationMinutes}
            onChange={(e) => set("durationMinutes", e.target.value)}
            error={errors.durationMinutes}
            required
          />
          <TextField
            label={t("lessons.tests.attempts")}
            name="attemptsAllowed"
            type="number"
            inputMode="numeric"
            min={1}
            max={LESSONS_LIMITS.attempts}
            value={values.attemptsAllowed}
            onChange={(e) => set("attemptsAllowed", e.target.value)}
            error={errors.attemptsAllowed}
            required
          />
        </div>
        <SelectField
          label={t("lessons.tests.showAnswers")}
          name="showAnswers"
          value={values.showAnswers}
          onChange={(e) => set("showAnswers", e.target.value as ShowAnswers)}
          options={[
            { value: "WITH_RESULTS", label: t("lessons.tests.showAnswers.WITH_RESULTS") },
            { value: "NEVER", label: t("lessons.tests.showAnswers.NEVER") },
          ]}
        />
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <label className="check">
            <input
              type="checkbox"
              name="shuffleQuestions"
              checked={values.shuffleQuestions}
              onChange={(e) => set("shuffleQuestions", e.target.checked)}
            />
            <span>{t("lessons.tests.shuffleQuestions")}</span>
          </label>
          <label className="check">
            <input
              type="checkbox"
              name="shuffleOptions"
              checked={values.shuffleOptions}
              onChange={(e) => set("shuffleOptions", e.target.checked)}
            />
            <span>{t("lessons.tests.shuffleOptions")}</span>
          </label>
        </div>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
