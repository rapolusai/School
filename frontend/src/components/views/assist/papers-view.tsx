"use client";

import { Plus, Printer, Trash2, Wand2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, toApiError } from "@/lib/api";
import { assistApi } from "@/lib/assist-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { BLOOM_LEVELS, type BloomLevel, type Blueprint, type BlueprintSection, type PaperView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AssistNav, AssistOffNotice, SimulatedLabel, assistError, useAssistSettings } from "./assist-shared";

export const DEFAULT_SECTIONS: BlueprintSection[] = [
  { name: "Section A", questions: 10, marksEach: 1 },
  { name: "Section B", questions: 5, marksEach: 3 },
];

/** Marks the blueprint asks for. Pure, exported for tests. */
export function blueprintMarks(sections: readonly BlueprintSection[]): number {
  return sections.reduce((sum, s) => sum + (Number(s.questions) || 0) * (Number(s.marksEach) || 0), 0);
}

/** A problem with the blueprint before it is sent, as a message key; null when it can go. Pure, exported for tests. */
export function blueprintProblem(blueprint: Blueprint): MessageKey | null {
  if (blueprint.sections.length === 0) return "assist.papers.v.sections";
  if (blueprint.sections.some((s) => !s.name.trim() || s.questions < 1 || s.questions > 50 || !(s.marksEach > 0))) {
    return "assist.papers.v.section";
  }
  const mix = blueprint.easyPercent + blueprint.mediumPercent + blueprint.hardPercent;
  if (mix !== 0 && mix !== 100) return "assist.papers.v.mix";
  return null;
}

/** Saved draft papers, newest first, with a link to build a new one. */
export function PapersView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const papers = useApiData("assist:papers", () => assistApi.papers());
  return (
    <div className="flex flex-col gap-4">
      <PageHead
        eyebrow={t("assist.eyebrow")}
        title={t("assist.papers.title")}
        actions={
          <Link className="btn btn-primary" href="/app/assist/papers/new">
            <Plus size={16} aria-hidden="true" />
            {t("assist.papers.new")}
          </Link>
        }
      />
      <AssistNav />
      <p className="text-[13px] text-ink-3">{t("assist.papers.explain")}</p>
      {papers.error && !papers.data ? (
        <ErrorState error={papers.error} onRetry={papers.reload} />
      ) : !papers.data ? (
        <LoadingRows rows={4} />
      ) : papers.data.length === 0 ? (
        <p className="empty">{t("assist.papers.empty")}</p>
      ) : (
        <ul className="rowcards" data-testid="paper-list">
          {papers.data.map((p) => (
            <li key={p.id}>
              <Link className="rowcard" href={`/app/assist/papers/${p.id}`}>
                <span className="min-w-0">
                  <b>{p.title}</b>
                  <span className="block text-[13px] text-ink-3">
                    {p.className} · {p.subjectName} · {t("assist.papers.marksMinutes", { marks: p.totalMarks, minutes: p.durationMinutes })}
                  </span>
                </span>
                <span className="text-[12px] text-ink-3">
                  {p.createdByName ?? ""} {p.updatedAt ? formatDateTime(p.updatedAt, locale) : ""}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * The paper builder: class, subject, a blueprint (sections with marks, the difficulty mix, Bloom levels, chapters),
 * then a draft from the question bank to look over and save.
 */
export function PaperBuilderView() {
  const { t } = useI18n();
  const router = useRouter();
  const { toast } = useToast();
  const settings = useAssistSettings();
  const classes = useApiData("assist:classes", () => api.listClasses());
  const [classId, setClassId] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [title, setTitle] = useState("");
  const [duration, setDuration] = useState(60);
  const [sections, setSections] = useState<BlueprintSection[]>(DEFAULT_SECTIONS);
  const [mix, setMix] = useState({ easyPercent: 40, mediumPercent: 40, hardPercent: 20 });
  const [blooms, setBlooms] = useState<BloomLevel[]>([]);
  const [topicIds, setTopicIds] = useState<string[]>([]);
  const [draft, setDraft] = useState<PaperView | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const klass = (classes.data ?? []).find((c) => c.id === classId);
  const topics = useApiData(classId && subjectId ? `assist:topics:${classId}:${subjectId}` : null, () => lessonsApi.topics(classId, subjectId));
  const blueprint: Blueprint = { sections, ...mix, bloomLevels: blooms, topicIds };

  const updateSection = (index: number, patch: Partial<BlueprintSection>) =>
    setSections((list) => list.map((s, i) => (i === index ? { ...s, ...patch } : s)));

  const makeDraft = async () => {
    const issue = blueprintProblem(blueprint);
    if (issue) {
      setProblem(t(issue));
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      setDraft(await assistApi.draftPaper({ classId, subjectId, title, durationMinutes: duration, blueprint }));
      settings.reload();
    } catch (error) {
      setProblem(assistError(toApiError(error), t));
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!draft) return;
    setBusy(true);
    setProblem(null);
    try {
      const saved = await assistApi.savePaper({
        classId: draft.classId,
        subjectId: draft.subjectId,
        title: draft.title,
        durationMinutes: draft.durationMinutes,
        instructions: draft.instructions,
        blueprint: draft.blueprint,
        sections: draft.sections,
        version: null,
      });
      toast(t("assist.papers.saved"));
      router.push(`/app/assist/papers/${saved.id}`);
    } catch (error) {
      setProblem(assistError(toApiError(error), t));
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHead eyebrow={t("assist.eyebrow")} title={t("assist.papers.newTitle")} actions={<SimulatedLabel simulated={settings.data?.simulated ?? true} />} />
      <AssistNav />
      {settings.data ? <AssistOffNotice settings={settings.data} feature="papersEnabled" /> : null}
      <form
        method="post"
        className="card flex flex-col gap-4"
        data-testid="paper-builder"
        onSubmit={(e) => {
          e.preventDefault();
          void makeDraft();
        }}
      >
        <div className="flex flex-wrap gap-3">
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("assist.papers.class")}</span>
            <select
              className="input"
              name="classId"
              required
              value={classId}
              onChange={(e) => {
                setClassId(e.target.value);
                setSubjectId("");
                setTopicIds([]);
              }}
            >
              <option value="">{t("assist.choose")}</option>
              {(classes.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("assist.papers.subject")}</span>
            <select
              className="input"
              name="subjectId"
              required
              disabled={!klass}
              value={subjectId}
              onChange={(e) => {
                setSubjectId(e.target.value);
                setTopicIds([]);
              }}
            >
              <option value="">{t("assist.choose")}</option>
              {(klass?.subjects ?? []).map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:flex-1">
            <span className="field-label">{t("assist.papers.paperTitle")}</span>
            <input className="input" name="title" required maxLength={160} value={title} onChange={(e) => setTitle(e.target.value)} />
          </label>
          <label className="field w-full sm:w-32">
            <span className="field-label">{t("assist.papers.minutes")}</span>
            <input className="input" type="number" name="durationMinutes" min={10} max={240} required value={duration} onChange={(e) => setDuration(Number(e.target.value))} />
          </label>
        </div>
        <fieldset className="flex flex-col gap-2">
          <legend className="field-label">{t("assist.papers.sections")}</legend>
          {sections.map((s, index) => (
            <div key={index} className="flex flex-wrap items-end gap-2" data-testid="blueprint-section">
              <label className="field min-w-0 flex-[1_1_10rem]">
                <span className="field-label">{t("assist.papers.sectionName")}</span>
                <input className="input" name={`section-${index}-name`} value={s.name} maxLength={40} onChange={(e) => updateSection(index, { name: e.target.value })} />
              </label>
              <label className="field w-24">
                <span className="field-label">{t("assist.papers.questions")}</span>
                <input className="input" type="number" min={1} max={50} name={`section-${index}-questions`} value={s.questions} onChange={(e) => updateSection(index, { questions: Number(e.target.value) })} />
              </label>
              <label className="field w-24">
                <span className="field-label">{t("assist.papers.marksEach")}</span>
                <input className="input" type="number" min={0.5} step={0.5} name={`section-${index}-marks`} value={s.marksEach} onChange={(e) => updateSection(index, { marksEach: Number(e.target.value) })} />
              </label>
              <button
                type="button"
                className="iconbtn"
                aria-label={t("assist.papers.removeSection", { name: s.name || String(index + 1) })}
                disabled={sections.length === 1}
                onClick={() => setSections((list) => list.filter((_, i) => i !== index))}
              >
                <Trash2 size={16} aria-hidden="true" />
              </button>
            </div>
          ))}
          <div className="flex flex-wrap items-center justify-between gap-2">
            <button
              type="button"
              className="btn btn-sm"
              disabled={sections.length >= 10}
              onClick={() => setSections((list) => [...list, { name: `Section ${String.fromCharCode(65 + list.length)}`, questions: 5, marksEach: 2 }])}
            >
              <Plus size={14} aria-hidden="true" />
              {t("assist.papers.addSection")}
            </button>
            <span className="text-[13px] text-ink-3" data-testid="blueprint-marks">
              {t("assist.papers.totalMarks", { marks: blueprintMarks(sections) })}
            </span>
          </div>
        </fieldset>
        <fieldset className="flex flex-wrap gap-3">
          <legend className="field-label">{t("assist.papers.mix")}</legend>
          {(["easyPercent", "mediumPercent", "hardPercent"] as const).map((key) => (
            <label key={key} className="field w-24">
              <span className="field-label">{t(`assist.papers.${key}` as MessageKey)}</span>
              <input className="input" type="number" min={0} max={100} name={key} value={mix[key]} onChange={(e) => setMix((m) => ({ ...m, [key]: Number(e.target.value) }))} />
            </label>
          ))}
        </fieldset>
        <fieldset className="flex flex-wrap gap-3">
          <legend className="field-label">{t("assist.papers.bloom")}</legend>
          {BLOOM_LEVELS.map((level) => (
            <label key={level} className="check">
              <input
                type="checkbox"
                name="bloomLevels"
                value={level}
                checked={blooms.includes(level)}
                onChange={(e) => setBlooms((list) => (e.target.checked ? [...list, level] : list.filter((l) => l !== level)))}
              />
              <span>{t(`lessons.bloom.${level}` as MessageKey)}</span>
            </label>
          ))}
        </fieldset>
        {topics.data && topics.data.length > 0 ? (
          <fieldset className="flex flex-wrap gap-3">
            <legend className="field-label">{t("assist.papers.chapters")}</legend>
            {topics.data.map((topic) => (
              <label key={topic.id} className="check">
                <input
                  type="checkbox"
                  name="topicIds"
                  value={topic.id}
                  checked={topicIds.includes(topic.id)}
                  onChange={(e) => setTopicIds((list) => (e.target.checked ? [...list, topic.id] : list.filter((x) => x !== topic.id)))}
                />
                <span>{topic.title}</span>
              </label>
            ))}
          </fieldset>
        ) : null}
        <p className="text-[13px] text-ink-3">{t("assist.papers.hint")}</p>
        <FormAlert message={problem} />
        <div className="flex flex-wrap gap-2">
          <button type="submit" className="btn btn-primary" disabled={busy} data-testid="paper-draft">
            <Wand2 size={16} aria-hidden="true" />
            {t("assist.papers.draft")}
          </button>
          {draft ? (
            <button type="button" className="btn" disabled={busy} onClick={() => void save()} data-testid="paper-save">
              {t("assist.papers.save")}
            </button>
          ) : null}
        </div>
      </form>
      {draft ? (
        <>
          {draft.warnings.length > 0 ? (
            <ul className="card flex flex-col gap-1 text-[14px]" role="status" data-testid="paper-warnings">
              {draft.warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          ) : null}
          <PaperDocument paper={draft} />
        </>
      ) : null}
    </div>
  );
}

/** A saved draft paper: print it (A4), rename it or change its instructions, or delete it. No export. */
export function PaperDetailView({ paperId }: { paperId: string }) {
  const { t } = useI18n();
  const router = useRouter();
  const { toast } = useToast();
  const paper = useApiData(`assist:paper:${paperId}`, () => assistApi.paper(paperId));
  const [editing, setEditing] = useState<{ title: string; instructions: string } | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const data = paper.data;

  const saveEdit = async () => {
    if (!data || !editing) return;
    setProblem(null);
    try {
      await assistApi.updatePaper(paperId, {
        classId: data.classId,
        subjectId: data.subjectId,
        title: editing.title,
        durationMinutes: data.durationMinutes,
        instructions: editing.instructions || null,
        blueprint: data.blueprint,
        sections: data.sections,
        version: data.version,
      });
      setEditing(null);
      toast(t("assist.saved"));
      paper.reload();
    } catch (error) {
      setProblem(assistError(toApiError(error), t));
    }
  };

  if (paper.error && !data) return <ErrorState error={paper.error} onRetry={paper.reload} />;
  if (!data) return <LoadingRows rows={6} />;
  return (
    <div className="flex flex-col gap-4">
      <div className="no-print flex flex-col gap-4">
        <PageHead
          eyebrow={t("assist.eyebrow")}
          title={data.title}
          actions={
            <>
              <button type="button" className="btn btn-primary" onClick={() => window.print()} data-testid="paper-print">
                <Printer size={16} aria-hidden="true" />
                {t("assist.papers.print")}
              </button>
              <button type="button" className="btn" onClick={() => setEditing({ title: data.title, instructions: data.instructions ?? "" })}>
                {t("assist.papers.edit")}
              </button>
              <button type="button" className="btn btn-ghost" onClick={() => setDeleting(true)}>
                <Trash2 size={16} aria-hidden="true" />
                {t("assist.papers.delete")}
              </button>
            </>
          }
        />
        <AssistNav />
        <p className="text-[13px] text-ink-3">{t("assist.papers.printOnly")}</p>
        {editing ? (
          <form
            method="post"
            className="card flex flex-col gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              void saveEdit();
            }}
          >
            <label className="field">
              <span className="field-label">{t("assist.papers.paperTitle")}</span>
              <input className="input" name="title" required maxLength={160} value={editing.title} onChange={(e) => setEditing({ ...editing, title: e.target.value })} />
            </label>
            <label className="field">
              <span className="field-label">{t("assist.papers.instructions")}</span>
              <textarea className="input" name="instructions" rows={5} maxLength={2000} value={editing.instructions} onChange={(e) => setEditing({ ...editing, instructions: e.target.value })} />
            </label>
            <FormAlert message={problem} />
            <div className="flex gap-2">
              <button type="submit" className="btn btn-primary">
                {t("assist.save")}
              </button>
              <button type="button" className="btn" onClick={() => setEditing(null)}>
                {t("common.cancel")}
              </button>
            </div>
          </form>
        ) : null}
      </div>
      <PaperDocument paper={data} />
      <ConfirmDialog
        open={deleting}
        title={t("assist.papers.deleteTitle")}
        body={t("assist.papers.deleteBody", { title: data.title })}
        confirmLabel={t("assist.papers.delete")}
        onClose={() => setDeleting(false)}
        onConfirm={async () => {
          await assistApi.deletePaper(paperId);
          router.push("/app/assist/papers");
        }}
      />
    </div>
  );
}

/** Questions are numbered through the paper: the number before each section's first question. Pure, exported for tests. */
export function questionStarts(paper: Pick<PaperView, "sections">): number[] {
  const starts: number[] = [];
  let count = 0;
  for (const section of paper.sections) {
    starts.push(count);
    count += section.questions.length;
  }
  return starts;
}

/** The question paper as it prints on A4: heading, time and marks, instructions, numbered questions by section. */
export function PaperDocument({ paper }: { paper: PaperView }) {
  const { t } = useI18n();
  const starts = questionStarts(paper);
  return (
    <article className="card print-area flex flex-col gap-4" data-testid="paper-document" aria-label={paper.title}>
      <header className="flex flex-col items-center gap-1 text-center">
        <div className="no-print">
          <SimulatedLabel simulated={paper.simulated} />
        </div>
        <h2 className="text-[18px] font-semibold">{paper.title}</h2>
        <p>
          {paper.className} · {paper.subjectName}
        </p>
        <p className="flex w-full justify-between text-[14px]">
          <span>{t("assist.papers.timeAllowed", { minutes: paper.durationMinutes })}</span>
          <span>{t("assist.papers.maxMarks", { marks: paper.totalMarks })}</span>
        </p>
      </header>
      {paper.instructions ? <p className="whitespace-pre-line text-[14px]">{paper.instructions}</p> : null}
      {paper.sections.map((section, sectionIndex) => (
        <section key={section.name} className="flex flex-col gap-2" data-testid="paper-section">
          <h3 className="font-semibold">{section.name}</h3>
          {section.instructions ? <p className="text-[13px] italic">{section.instructions}</p> : null}
          <ol className="flex flex-col gap-2">
            {section.questions.map((q, questionIndex) => {
              const number = starts[sectionIndex] + questionIndex + 1;
              return (
                <li key={q.questionId} className="flex gap-2" data-testid="paper-question">
                  <span className="num shrink-0">{number}.</span>
                  <div className="min-w-0 flex-1">
                    <p className="whitespace-pre-line">{q.text}</p>
                    {q.options.length > 0 ? (
                      <ol className="mt-1 grid grid-cols-1 gap-1 sm:grid-cols-2" type="a">
                        {q.options.map((option, i) => (
                          <li key={i}>
                            ({String.fromCharCode(97 + i)}) {option}
                          </li>
                        ))}
                      </ol>
                    ) : null}
                  </div>
                  <span className="num shrink-0 text-[13px]">[{q.marks}]</span>
                </li>
              );
            })}
          </ol>
        </section>
      ))}
    </article>
  );
}
