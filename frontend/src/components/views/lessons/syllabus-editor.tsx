"use client";

import { ArrowDown, ArrowLeft, ArrowUp, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { LESSONS_LIMITS, type LessonsYear, type SyllabusDetail, type SyllabusRequest } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { isWholeNumberInRange } from "@/lib/validation";
import { LessonsNav, monthLabel, useLessonsOptions } from "./lessons-shared";

export type EditTopic = { key: string; id: string | null; title: string; periods: string; month: string; hasPlans: boolean };
export type EditUnit = { key: string; id: string | null; title: string; topics: EditTopic[] };

/** The most topics a syllabus can have (the API's limit). */
const MAX_TOPICS = 600;

let nextKey = 0;
const newKey = () => `new-${++nextKey}`;

export function editUnits(detail: SyllabusDetail | null): EditUnit[] {
  if (!detail) return [];
  return detail.units.map((u) => ({
    key: u.id,
    id: u.id,
    title: u.title,
    topics: u.topics.map((topic) => ({
      key: topic.id,
      id: topic.id,
      title: topic.title,
      periods: String(topic.plannedPeriods),
      month: topic.targetMonth ?? "",
      hasPlans: topic.hasPlans,
    })),
  }));
}

/**
 * Client checks matching the API's: every unit and topic named (at most 200 characters), planned periods from 1 to
 * 200, target months within the year, at most 40 units, 60 topics a unit and 600 in all. Keys: "units", "u:<key>",
 * "t:<key>:title", "t:<key>:periods" and "t:<key>:month". Pure, exported for tests.
 */
export function syllabusProblems(units: EditUnit[], year?: Pick<LessonsYear, "startsOn" | "endsOn"> | null) {
  const problems: Record<string, MessageKey> = {};
  if (units.length > LESSONS_LIMITS.units) problems.units = "lessons.v.tooManyUnits";
  if (units.reduce((n, u) => n + u.topics.length, 0) > MAX_TOPICS) problems.units = "lessons.v.tooManyTopics";
  const first = year?.startsOn.slice(0, 7);
  const last = year?.endsOn.slice(0, 7);
  for (const unit of units) {
    if (!unit.title.trim()) problems[`u:${unit.key}`] = "lessons.v.unitTitle";
    else if (unit.title.trim().length > LESSONS_LIMITS.title) problems[`u:${unit.key}`] = "lessons.v.titleLong";
    if (unit.topics.length > LESSONS_LIMITS.topicsPerUnit) problems[`u:${unit.key}`] = "lessons.v.tooManyTopicsUnit";
    for (const topic of unit.topics) {
      if (!topic.title.trim()) problems[`t:${topic.key}:title`] = "lessons.v.topicTitle";
      else if (topic.title.trim().length > LESSONS_LIMITS.title) problems[`t:${topic.key}:title`] = "lessons.v.titleLong";
      if (!isWholeNumberInRange(topic.periods, 1, LESSONS_LIMITS.plannedPeriods)) {
        problems[`t:${topic.key}:periods`] = "lessons.v.plannedPeriods";
      }
      if (topic.month) {
        if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(topic.month)) problems[`t:${topic.key}:month`] = "lessons.v.month";
        else if (first && last && (topic.month < first || topic.month > last)) {
          problems[`t:${topic.key}:month`] = "lessons.v.monthInYear";
        }
      }
    }
  }
  return problems;
}

/** The request body for the edited units. Pure, exported for tests. */
export function syllabusRequest(
  units: EditUnit[],
  base: { yearId: string | null; classId: string; subjectId: string; version: number | null },
): SyllabusRequest {
  return {
    academicYearId: base.yearId,
    classId: base.classId,
    subjectId: base.subjectId,
    version: base.version,
    units: units.map((u) => ({
      id: u.id,
      title: u.title.trim(),
      topics: u.topics.map((topic) => ({
        id: topic.id,
        title: topic.title.trim(),
        plannedPeriods: Number(topic.periods),
        targetMonth: topic.month || null,
      })),
    })),
  };
}

function move<T>(list: T[], index: number, delta: number): T[] {
  const target = index + delta;
  if (target < 0 || target >= list.length) return list;
  const next = [...list];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

/**
 * /app/lessons/syllabus/[syllabusId]: a syllabus's units and topics with planned periods and target months. "new"
 * starts one for the class and subject in the query.
 */
export function SyllabusEditorView({
  syllabusId,
  yearId,
  classId,
  subjectId,
}: {
  syllabusId: string;
  yearId?: string;
  classId?: string;
  subjectId?: string;
}) {
  const { t } = useI18n();
  const router = useRouter();
  const creating = syllabusId === "new";
  const loaded = useApiData(creating ? null : `lessons:syllabus:${syllabusId}`, () => lessonsApi.syllabus(syllabusId));
  const [current, setCurrent] = useState<SyllabusDetail | null>(null);
  const detail = { ...loaded, data: current && current.id === syllabusId ? current : loaded.data };
  const options = useLessonsOptions(creating ? (yearId ?? "") : (detail.data?.yearId ?? ""));
  const head = <PageHead title={t("lessons.syllabus.title")} />;

  if (!creating && detail.error && !detail.data) {
    return (
      <>
        {head}
        <section className="card">
          <ErrorState error={detail.error} onRetry={detail.reload} />
        </section>
      </>
    );
  }
  if ((!creating && !detail.data) || !options.data) {
    return (
      <>
        {head}
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }
  const year = options.data.years.find((y) => y.id === (detail.data?.yearId ?? options.data?.yearId)) ?? null;
  const klass = options.data.classes.find((c) => c.id === (detail.data?.classId ?? classId));
  const subject = klass?.subjects.find((s) => s.id === (detail.data?.subjectId ?? subjectId));
  if (creating && (!klass || !subject || !year)) {
    return (
      <>
        {head}
        <LessonsNav />
        <section className="card">
          <p className="empty">{t("lessons.syllabus.pickFirst")}</p>
        </section>
      </>
    );
  }
  return (
    <SyllabusForm
      key={detail.data ? `${detail.data.id}:${detail.data.version}` : "new"}
      detail={detail.data ?? null}
      year={year}
      className={detail.data?.className ?? klass?.name ?? ""}
      subjectName={detail.data?.subjectName ?? subject?.name ?? ""}
      classId={detail.data?.classId ?? classId ?? ""}
      subjectId={detail.data?.subjectId ?? subjectId ?? ""}
      canEdit={detail.data ? detail.data.canEdit : options.data.canApprove || options.data.canManage}
      onSaved={(saved) => {
        if (creating) router.replace(`/app/lessons/syllabus/${encodeURIComponent(saved.id)}`);
        else setCurrent(saved);
      }}
    />
  );
}

function SyllabusForm({
  detail,
  year,
  className,
  subjectName,
  classId,
  subjectId,
  canEdit,
  onSaved,
}: {
  detail: SyllabusDetail | null;
  year: LessonsYear | null;
  className: string;
  subjectName: string;
  classId: string;
  subjectId: string;
  canEdit: boolean;
  onSaved: (saved: SyllabusDetail) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const router = useRouter();
  const { toast } = useToast();
  const [units, setUnits] = useState<EditUnit[]>(() => editUnits(detail));
  const [problems, setProblems] = useState<Record<string, MessageKey>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [dirty, setDirty] = useState(false);
  const topicCount = units.reduce((n, u) => n + u.topics.length, 0);
  const periodCount = units.reduce((n, u) => n + u.topics.reduce((m, topic) => m + (Number(topic.periods) || 0), 0), 0);

  const change = (next: EditUnit[]) => {
    setUnits(next);
    setDirty(true);
  };
  const updateUnit = (key: string, patch: Partial<EditUnit>) =>
    change(units.map((u) => (u.key === key ? { ...u, ...patch } : u)));
  const updateTopic = (unitKey: string, topicKey: string, patch: Partial<EditTopic>) =>
    change(
      units.map((u) =>
        u.key === unitKey ? { ...u, topics: u.topics.map((x) => (x.key === topicKey ? { ...x, ...patch } : x)) } : u,
      ),
    );
  const addTopic = (unitKey: string) =>
    change(
      units.map((u) =>
        u.key === unitKey
          ? { ...u, topics: [...u.topics, { key: newKey(), id: null, title: "", periods: "1", month: "", hasPlans: false }] }
          : u,
      ),
    );

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = syllabusProblems(units, year);
    setProblems(found);
    setFormError(null);
    if (Object.keys(found).length > 0) {
      setFormError(t(found.units ?? "validation.fixErrors"));
      const firstKey = Object.keys(found).find((k) => k !== "units");
      if (firstKey) event.currentTarget.querySelector<HTMLElement>(`[data-key="${firstKey}"]`)?.focus();
      return;
    }
    setSaving(true);
    try {
      const body = syllabusRequest(units, { yearId: year?.id ?? null, classId, subjectId, version: detail?.version ?? null });
      const saved = detail ? await lessonsApi.updateSyllabus(detail.id, body) : await lessonsApi.createSyllabus(body);
      toast(t("lessons.syllabus.saved"));
      setDirty(false);
      onSaved(saved);
    } catch (caught) {
      setFormError(errorMessage(toApiError(caught), t));
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <PageHead
        eyebrow={year?.name}
        title={`${className} · ${subjectName}`}
        actions={
          <Link href="/app/lessons/syllabus" className="btn">
            <ArrowLeft size={18} aria-hidden="true" />
            {t("lessons.syllabus.back")}
          </Link>
        }
      />
      <LessonsNav />
      <form method="post" className="card flex flex-col gap-4" onSubmit={save} noValidate data-testid="syllabus-editor">
        <p className="text-[13.5px] text-ink-2" data-testid="syllabus-totals">
          {[
            plural(t, "lessons.syllabus.units", units.length),
            plural(t, "lessons.syllabus.topics", topicCount),
            plural(t, "lessons.plan.periodsCount", periodCount),
          ].join(" · ")}
          {detail?.updatedByName
            ? ` · ${t("lessons.updatedBy", { date: formatDateTime(detail.updatedAt, locale), name: detail.updatedByName })}`
            : ""}
        </p>
        <FormAlert message={formError} />
        {units.length === 0 ? (
          <p className="empty">{canEdit ? t("lessons.syllabus.noUnitsEdit") : t("lessons.syllabus.noUnits")}</p>
        ) : null}
        <ol className="flex flex-col gap-3">
          {units.map((unit, ui) => (
            <li key={unit.key} className="rounded-[10px] border border-line p-3" data-testid="syllabus-unit">
              {canEdit ? (
                <div className="flex flex-wrap items-start gap-2">
                  <label className="field min-w-0 flex-1">
                    <span className="field-label">{t("lessons.syllabus.unitN", { n: ui + 1 })}</span>
                    <input
                      className="input"
                      name={`unit-${ui}`}
                      data-key={`u:${unit.key}`}
                      value={unit.title}
                      maxLength={LESSONS_LIMITS.title}
                      aria-invalid={problems[`u:${unit.key}`] ? true : undefined}
                      onChange={(e) => updateUnit(unit.key, { title: e.target.value })}
                    />
                    {problems[`u:${unit.key}`] ? <span className="field-error">{t(problems[`u:${unit.key}`])}</span> : null}
                  </label>
                  <div className="flex gap-1 pt-6">
                    <button
                      type="button"
                      className="iconbtn"
                      disabled={ui === 0}
                      onClick={() => change(move(units, ui, -1))}
                      aria-label={t("lessons.syllabus.unitUp", { n: ui + 1 })}
                    >
                      <ArrowUp size={16} aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="iconbtn"
                      disabled={ui === units.length - 1}
                      onClick={() => change(move(units, ui, 1))}
                      aria-label={t("lessons.syllabus.unitDown", { n: ui + 1 })}
                    >
                      <ArrowDown size={16} aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="iconbtn"
                      disabled={unit.topics.some((x) => x.hasPlans)}
                      title={unit.topics.some((x) => x.hasPlans) ? t("lessons.syllabus.inUse") : undefined}
                      onClick={() => change(units.filter((u) => u.key !== unit.key))}
                      aria-label={t("lessons.syllabus.removeUnit", { n: ui + 1 })}
                    >
                      <Trash2 size={16} aria-hidden="true" />
                    </button>
                  </div>
                </div>
              ) : (
                <h2 className="text-base">{t("lessons.syllabus.unitTitled", { n: ui + 1, title: unit.title })}</h2>
              )}
              <ol className="mt-2 flex flex-col gap-2">
                {unit.topics.map((topic, ti) =>
                  canEdit ? (
                    <li key={topic.key} className="flex flex-wrap items-start gap-2 border-t border-line pt-2" data-testid="syllabus-topic">
                      <label className="field min-w-0 flex-[1_1_14rem]">
                        <span className="field-label">{t("lessons.syllabus.topicN", { n: ti + 1 })}</span>
                        <input
                          className="input"
                          name={`topic-${ui}-${ti}`}
                          data-key={`t:${topic.key}:title`}
                          value={topic.title}
                          maxLength={LESSONS_LIMITS.title}
                          aria-invalid={problems[`t:${topic.key}:title`] ? true : undefined}
                          onChange={(e) => updateTopic(unit.key, topic.key, { title: e.target.value })}
                        />
                        {problems[`t:${topic.key}:title`] ? (
                          <span className="field-error">{t(problems[`t:${topic.key}:title`])}</span>
                        ) : null}
                      </label>
                      <label className="field w-24">
                        <span className="field-label">{t("lessons.plan.periods")}</span>
                        <input
                          className="input"
                          type="number"
                          inputMode="numeric"
                          min={1}
                          max={LESSONS_LIMITS.plannedPeriods}
                          name={`periods-${ui}-${ti}`}
                          data-key={`t:${topic.key}:periods`}
                          value={topic.periods}
                          aria-invalid={problems[`t:${topic.key}:periods`] ? true : undefined}
                          onChange={(e) => updateTopic(unit.key, topic.key, { periods: e.target.value })}
                        />
                        {problems[`t:${topic.key}:periods`] ? (
                          <span className="field-error">{t(problems[`t:${topic.key}:periods`])}</span>
                        ) : null}
                      </label>
                      <label className="field w-40">
                        <span className="field-label">{t("lessons.syllabus.month")}</span>
                        <input
                          className="input"
                          type="month"
                          name={`month-${ui}-${ti}`}
                          data-key={`t:${topic.key}:month`}
                          placeholder="2026-07"
                          min={year?.startsOn.slice(0, 7)}
                          max={year?.endsOn.slice(0, 7)}
                          value={topic.month}
                          aria-invalid={problems[`t:${topic.key}:month`] ? true : undefined}
                          onChange={(e) => updateTopic(unit.key, topic.key, { month: e.target.value })}
                        />
                        {problems[`t:${topic.key}:month`] ? (
                          <span className="field-error">{t(problems[`t:${topic.key}:month`])}</span>
                        ) : null}
                      </label>
                      <button
                        type="button"
                        className="iconbtn mt-6"
                        disabled={topic.hasPlans}
                        title={topic.hasPlans ? t("lessons.syllabus.inUse") : undefined}
                        onClick={() => updateUnit(unit.key, { topics: unit.topics.filter((x) => x.key !== topic.key) })}
                        aria-label={t("lessons.syllabus.removeTopic", { title: topic.title || String(ti + 1) })}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </button>
                    </li>
                  ) : (
                    <li key={topic.key} className="flex flex-wrap items-baseline gap-x-3 border-t border-line pt-2 text-[14px]">
                      <span className="min-w-0 flex-1">{topic.title}</span>
                      <span className="text-[12.5px] text-ink-3">
                        {[plural(t, "lessons.plan.periodsCount", Number(topic.periods)), monthLabel(topic.month, locale)]
                          .filter(Boolean)
                          .join(" · ")}
                      </span>
                    </li>
                  ),
                )}
              </ol>
              {canEdit ? (
                <button type="button" className="btn btn-sm mt-2" onClick={() => addTopic(unit.key)}>
                  <Plus size={16} aria-hidden="true" />
                  {t("lessons.syllabus.addTopic")}
                </button>
              ) : null}
            </li>
          ))}
        </ol>
        {canEdit ? (
          <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3">
            <button
              type="button"
              className="btn"
              onClick={() =>
                change([
                  ...units,
                  {
                    key: newKey(),
                    id: null,
                    title: "",
                    topics: [{ key: newKey(), id: null, title: "", periods: "1", month: "", hasPlans: false }],
                  },
                ])
              }
            >
              <Plus size={16} aria-hidden="true" />
              {t("lessons.syllabus.addUnit")}
            </button>
            <span className="flex-1" />
            {detail ? (
              <button type="button" className="btn btn-ghost" onClick={() => setDeleting(true)}>
                <Trash2 size={16} aria-hidden="true" />
                {t("lessons.syllabus.delete")}
              </button>
            ) : null}
            <button type="submit" className="btn btn-primary" disabled={saving || (!dirty && Boolean(detail))}>
              {saving ? t("common.saving") : t("lessons.syllabus.save")}
            </button>
          </div>
        ) : null}
      </form>
      {detail ? (
        <ConfirmDialog
          open={deleting}
          title={t("lessons.syllabus.deleteTitle", { name: `${className} · ${subjectName}` })}
          body={t("lessons.syllabus.deleteBody")}
          confirmLabel={t("common.delete")}
          onClose={() => setDeleting(false)}
          onConfirm={async () => {
            await lessonsApi.deleteSyllabus(detail.id);
            setDeleting(false);
            toast(t("common.deleted"));
            router.push("/app/lessons/syllabus");
          }}
        />
      ) : null}
    </>
  );
}
