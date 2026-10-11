"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Pill, type PillTone } from "@/components/ui/pill";
import { useAuth } from "@/lib/auth";
import { formatPlainDate } from "@/lib/format";
import { translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { hasAnyPermission, PERMISSIONS } from "@/lib/permissions";
import type {
  Difficulty,
  LessonsClassOption,
  LessonsOptions,
  LessonsSubjectRef,
  PlanStatus,
  QuestionType,
  StudyTestState,
  TestState,
  YearMonthText,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/* Shared parts of the lessons screens (docs/api/phase-2-lessons.md). */

/** Every lessons permission: holding any of them opens the lessons area. */
export const LESSONS_PERMISSIONS = [
  PERMISSIONS.lessonsRead,
  PERMISSIONS.lessonsManage,
  PERMISSIONS.lessonsApprove,
] as const;

/** The year's pickers: classes with sections and subjects, and what the caller teaches. */
export function useLessonsOptions(yearId = "") {
  return useApiData(`lessons:options:${yearId}`, () => lessonsApi.options(yearId || null));
}

type LessonsLink = { href: string; labelKey: MessageKey; permissions: readonly string[]; exact?: boolean };

const LESSONS_LINKS: LessonsLink[] = [
  { href: "/app/lessons", labelKey: "lessons.nav.plans", permissions: LESSONS_PERMISSIONS, exact: true },
  { href: "/app/lessons/approvals", labelKey: "lessons.nav.approvals", permissions: [PERMISSIONS.lessonsApprove] },
  { href: "/app/lessons/syllabus", labelKey: "lessons.nav.syllabus", permissions: LESSONS_PERMISSIONS },
  { href: "/app/lessons/material", labelKey: "lessons.nav.material", permissions: LESSONS_PERMISSIONS },
  { href: "/app/lessons/questions", labelKey: "lessons.nav.questions", permissions: [PERMISSIONS.lessonsManage] },
  { href: "/app/lessons/tests", labelKey: "lessons.nav.tests", permissions: LESSONS_PERMISSIONS },
  { href: "/app/reports/syllabus-coverage", labelKey: "lessons.nav.coverage", permissions: LESSONS_PERMISSIONS },
];

/** The lessons area's own navigation, under each lessons page's heading. */
export function LessonsNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const links = LESSONS_LINKS.filter((link) => hasAnyPermission(me, link.permissions));
  return (
    <nav className="subnav no-print" aria-label={t("lessons.nav.label")}>
      {links.map((link) => {
        const active = link.exact
          ? pathname === link.href || pathname.startsWith(`${link.href}/plans`)
          : pathname === link.href || pathname.startsWith(`${link.href}/`);
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

const PLAN_TONES: Record<PlanStatus, PillTone> = {
  DRAFT: "neutral",
  SUBMITTED: "info",
  APPROVED: "good",
  RETURNED: "warn",
  TAUGHT: "accent",
};

export function PlanStatusPill({ status }: { status: PlanStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={PLAN_TONES[status] ?? "neutral"} dot>
      {translateOr(t, `lessons.plan.status.${status}`, status)}
    </Pill>
  );
}

const TEST_TONES: Record<TestState | StudyTestState, PillTone> = {
  DRAFT: "neutral",
  SCHEDULED: "info",
  UPCOMING: "info",
  OPEN: "good",
  CLOSED: "neutral",
};

export function TestStatePill({ state }: { state: TestState | StudyTestState }) {
  const { t } = useI18n();
  return (
    <Pill tone={TEST_TONES[state] ?? "neutral"} dot>
      {translateOr(t, `lessons.test.state.${state}`, state)}
    </Pill>
  );
}

export function typeLabel(t: Translate, type: QuestionType): string {
  return translateOr(t, `lessons.type.${type}`, type);
}

export function difficultyLabel(t: Translate, difficulty: Difficulty): string {
  return translateOr(t, `lessons.difficulty.${difficulty}`, difficulty);
}

const DAY_MS = 86_400_000;

function utcDay(date: string): number {
  return Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)) - 1, Number(date.slice(8, 10)));
}

/** The Monday of the date's week ("2026-10-15" → "2026-10-12"), as the API stores a plan's week. Pure. */
export function mondayOf(date: string): string {
  const day = new Date(utcDay(date)).getUTCDay();
  const back = (day + 6) % 7;
  return new Date(utcDay(date) - back * DAY_MS).toISOString().slice(0, 10);
}

/** "2026-10-12" plus n days. Pure. */
export function plusDays(date: string, days: number): string {
  return new Date(utcDay(date) + days * DAY_MS).toISOString().slice(0, 10);
}

/** "Week of 12 Oct 2026". */
export function weekLabel(t: Translate, weekStart: string, locale: string): string {
  return t("lessons.plan.weekOf", { date: formatPlainDate(weekStart, locale) });
}

/** "2026-10" → "Oct 2026" in the reader's language; null → "". */
export function monthLabel(month: YearMonthText | null | undefined, locale: string): string {
  if (!month || !/^\d{4}-\d{2}$/.test(month)) return "";
  const date = new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)) - 1, 1));
  return new Intl.DateTimeFormat(locale, { month: "short", year: "numeric", timeZone: "UTC" }).format(date);
}

/** A section someone may plan for, with the subjects they may plan in it. */
export type PlanChoice = {
  sectionId: string;
  label: string;
  classId: string;
  subjects: LessonsSubjectRef[];
};

/**
 * The sections and subjects the caller may plan or set work for: every one for approvers (whole school), otherwise
 * the ones they teach in the year. Pure, exported for tests.
 */
export function planChoices(options: Pick<LessonsOptions, "classes" | "wholeSchool" | "teaching">): PlanChoice[] {
  const choices: PlanChoice[] = [];
  for (const c of options.classes) {
    for (const s of c.sections) {
      const subjects = options.wholeSchool
        ? c.subjects
        : c.subjects.filter((subject) =>
            options.teaching.some((x) => x.sectionId === s.id && x.subjectId === subject.id),
          );
      if (subjects.length > 0) choices.push({ sectionId: s.id, label: s.label, classId: c.id, subjects });
    }
  }
  return choices;
}

/** The classes (with the subjects) the caller teaches in, for class-wide work such as material and tests. Pure. */
export function classChoices(options: Pick<LessonsOptions, "classes" | "wholeSchool" | "teaching">): LessonsClassOption[] {
  if (options.wholeSchool) return options.classes.filter((c) => c.subjects.length > 0);
  return options.classes
    .map((c) => {
      const mine = options.teaching.filter((x) => c.sections.some((s) => s.id === x.sectionId));
      return {
        ...c,
        sections: c.sections.filter((s) => mine.some((x) => x.sectionId === s.id)),
        subjects: c.subjects.filter((subject) => mine.some((x) => x.subjectId === subject.id)),
      };
    })
    .filter((c) => c.subjects.length > 0);
}

/** A section's label from the options ("Class 5 A"), or "". */
export function sectionName(options: LessonsOptions | undefined, sectionId: string): string {
  for (const c of options?.classes ?? []) {
    const s = c.sections.find((x) => x.id === sectionId);
    if (s) return s.label;
  }
  return "";
}

/** Reads a chosen CSV file as text (UTF-8, with or without a byte order mark). */
export async function readCsvFile(file: File): Promise<string> {
  const text = await file.text();
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
}
