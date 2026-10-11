"use client";

import { ArrowRight, Lightbulb } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { formatPct, formatScore } from "@/components/views/exams/exams-shared";
import { AttachmentList } from "@/components/views/files/attachments";
import { ChildSwitcher, useFamilyChildren } from "@/components/views/portal/family-shared";
import { useAuth } from "@/lib/auth";
import { isParent } from "@/lib/family";
import { classLabel, formatDateTime, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import type { Study, StudyTest } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { TestStatePill } from "./lessons-shared";
import { MaterialBody } from "./material-view";

const STATE_ORDER = { OPEN: 0, UPCOMING: 1, CLOSED: 2 } as const;

/** Open tests first (closing soonest), then upcoming, then closed (latest first). Pure, exported for tests. */
export function sortStudyTests(tests: StudyTest[]): StudyTest[] {
  return [...tests].sort((a, b) => {
    const byState = STATE_ORDER[a.state] - STATE_ORDER[b.state];
    if (byState !== 0) return byState;
    if (a.state === "CLOSED") return b.closesAt.localeCompare(a.closesAt);
    return a.state === "OPEN" ? a.closesAt.localeCompare(b.closesAt) : a.opensAt.localeCompare(b.opensAt);
  });
}

/** /app/family/study: study material and online tests for the student, or the parent's chosen child (read-only). */
export function FamilyStudyView({ initialChildId }: { initialChildId?: string }) {
  const { me } = useAuth();
  return isParent(me) ? <ParentStudy initialChildId={initialChildId} /> : <StudentStudy />;
}

function ParentStudy({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const { children, list, child, choose } = useFamilyChildren(initialChildId);
  return (
    <>
      <PageHead
        eyebrow={child ? [child.fullName, classLabel(child.className, child.sectionName)].filter(Boolean).join(" · ") : undefined}
        title={t("nav.study")}
      />
      {children.error && !children.data ? (
        <section className="card">
          <ErrorState error={children.error} onRetry={children.reload} />
        </section>
      ) : !children.data ? (
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      ) : !child ? (
        <section className="card">
          <p className="empty">{t("children.empty")}</p>
        </section>
      ) : (
        <>
          {list.length > 1 ? <ChildSwitcher list={list} value={child.id} onChange={choose} /> : null}
          <StudyBody key={child.id} cacheKey={`lessons:study:${child.id}`} load={() => lessonsApi.childStudy(child.id)} self={false} />
        </>
      )}
    </>
  );
}

function StudentStudy() {
  const { t } = useI18n();
  return (
    <>
      <PageHead title={t("nav.study")} />
      <StudyBody cacheKey="lessons:study:me" load={lessonsApi.myStudy} self />
    </>
  );
}

function StudyBody({ cacheKey, load, self }: { cacheKey: string; load: () => Promise<Study>; self: boolean }) {
  const { t } = useI18n();
  const study = useApiData(cacheKey, load);
  const [subjectId, setSubjectId] = useState("");
  const data = study.data;
  if (study.error?.status === 404) {
    return (
      <section className="card">
        <p className="empty">{t("children.notLinked")}</p>
      </section>
    );
  }
  if (study.error && !data) {
    return (
      <section className="card">
        <ErrorState error={study.error} onRetry={study.reload} />
      </section>
    );
  }
  if (!data) {
    return (
      <section className="card">
        <LoadingRows rows={5} />
      </section>
    );
  }
  const subjects = new Map<string, string>();
  for (const x of [...data.tests, ...data.material]) subjects.set(x.subjectId, x.subjectName);
  const tests = sortStudyTests(data.tests.filter((x) => !subjectId || x.subjectId === subjectId));
  const material = data.material.filter((x) => !subjectId || x.subjectId === subjectId);
  return (
    <>
      {subjects.size > 1 ? (
        <form method="post" className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label={t("lessons.filters")}>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("lessons.subject")}</span>
            <select className="input" name="subjectId" value={subjectId} onChange={(e) => setSubjectId(e.target.value)}>
              <option value="">{t("lessons.allSubjects")}</option>
              {[...subjects].map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          </label>
        </form>
      ) : null}
      <section className="card flex flex-col gap-3" aria-labelledby="study-tests" data-testid="study-tests">
        <h2 id="study-tests">{t("lessons.study.tests")}</h2>
        {tests.length === 0 ? (
          <p className="text-sm text-ink-2">{t("lessons.study.noTests")}</p>
        ) : (
          <ul className="rowcards">
            {tests.map((test) => (
              <StudyTestRow key={test.id} test={test} self={self} />
            ))}
          </ul>
        )}
      </section>
      <section className="card flex flex-col gap-3" aria-labelledby="study-material" data-testid="study-material">
        <h2 id="study-material">{t("lessons.study.material")}</h2>
        {material.length === 0 ? (
          <p className="text-sm text-ink-2">{t("lessons.study.noMaterial")}</p>
        ) : (
          <ul className="flex flex-col gap-3">
            {material.map((m) => (
              <MaterialItem key={m.id} item={m} />
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

function MaterialItem({ item }: { item: Study["material"][number] }) {
  const { lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <li className="rounded-[10px] border border-line p-3" data-testid="study-material-item">
      <h3 className="font-display text-[15px] font-bold leading-tight">{item.title}</h3>
      <p className="mt-0.5 text-[12.5px] text-ink-3">
        {[item.subjectName, item.topicTitle, formatPlainDate(item.publishOn, locale), item.createdByName].filter(Boolean).join(" · ")}
      </p>
      <MaterialBody notes={item.notes} linkUrl={item.linkUrl} />
      <div className="mt-2">
        <AttachmentList files={item.files} />
      </div>
    </li>
  );
}

/** "Score 8 / 10 · 80%" once released; otherwise where the student stands. */
export function studyTestStatus(t: ReturnType<typeof useI18n>["t"], test: StudyTest): string {
  if (test.released && test.score !== null) {
    return t("lessons.study.score", {
      score: formatScore(test.score),
      max: formatScore(test.maxScore ?? test.totalMarks),
      pct: formatPct(test.percent),
    });
  }
  if (test.inProgressAttemptId) return t("lessons.study.inProgress");
  if (test.submitted) return t("lessons.study.submitted");
  if (test.state === "CLOSED") return t("lessons.study.notTaken");
  return t("lessons.study.attemptsLeft", { used: test.attemptsUsed, allowed: test.attemptsAllowed });
}

function StudyTestRow({ test, self }: { test: StudyTest; self: boolean }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const body = (
    <>
      <span className="min-w-0 flex-1">
        <b className="block font-semibold">{test.title}</b>
        <span className="block text-[12.5px] text-ink-3">
          {[
            test.subjectName,
            plural(t, "lessons.minutes", test.durationMinutes),
            plural(t, "lessons.tests.questionsCount", test.questions),
            plural(t, "lessons.marksCount", test.totalMarks, { marks: formatScore(test.totalMarks) }),
          ].join(" · ")}
        </span>
        <span className="block text-[12.5px] text-ink-3">
          {test.state === "UPCOMING"
            ? t("lessons.study.opens", { date: formatDateTime(test.opensAt, locale) })
            : test.state === "OPEN"
              ? t("lessons.study.closes", { date: formatDateTime(test.closesAt, locale) })
              : t("lessons.study.closed", { date: formatDateTime(test.closesAt, locale) })}
        </span>
        <span className="block text-[13px] font-semibold text-ink-2" data-testid="study-test-status">
          {studyTestStatus(t, test)}
        </span>
      </span>
      <TestStatePill state={test.state} />
    </>
  );
  return (
    <li>
      {self ? (
        <Link href={`/app/family/study/tests/${encodeURIComponent(test.id)}`} className="rowcard" data-testid="study-test">
          {body}
          <ArrowRight size={18} aria-hidden="true" className="flex-none text-ink-3" />
        </Link>
      ) : (
        <div className="rowcard" data-testid="study-test">
          {body}
        </div>
      )}
    </li>
  );
}

/** The family home card: open tests, the latest released score and the newest material. */
export function FamilyStudyCard({ data, href }: { data: ApiData<Study>; href: string }) {
  const { t } = useI18n();
  const view = data.data;
  const open = view?.tests.filter((x) => x.state === "OPEN" && !x.submitted) ?? [];
  const scored = view?.tests.filter((x) => x.released && x.score !== null).sort((a, b) => b.closesAt.localeCompare(a.closesAt))[0];
  const newest = view?.material[0];
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="family-study-card" data-testid="family-study-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="family-study-card">{t("nav.study")}</h2>
        <Lightbulb size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {data.error?.status === 404 ? (
        <p className="text-sm text-ink-2">{t("children.notLinked")}</p>
      ) : data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={2} />
      ) : (
        <div className="flex flex-col gap-1.5 text-[13.5px]">
          <p className={open.length > 0 ? "font-semibold" : "text-ink-2"}>
            {open.length > 0 ? plural(t, "lessons.study.openTests", open.length) : t("lessons.study.noOpenTests")}
          </p>
          {scored ? <p>{t("lessons.study.latestScore", { title: scored.title, status: studyTestStatus(t, scored) })}</p> : null}
          {newest ? (
            <p className="text-ink-2">{t("lessons.study.newest", { title: newest.title, subject: newest.subjectName })}</p>
          ) : null}
        </div>
      )}
      <Link href={href} className="link mt-auto inline-flex items-center gap-1 text-[13.5px]">
        {t("lessons.study.open")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
    </section>
  );
}
