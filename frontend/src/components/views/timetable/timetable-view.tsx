"use client";

import { useId, useRef, useState } from "react";
import { AccessDenied } from "@/components/access";
import { PageHead } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { AssignmentsPanel } from "./assignments-panel";
import { BellsPanel } from "./bells-panel";
import { ClashesPanel } from "./clashes-panel";
import { ParentTimetableView, StudentTimetableView } from "./family-timetable";
import { SectionsPanel } from "./sections-panel";
import { SubstitutionsPanel } from "./substitutions-panel";
import { MyTimetablePanel, TeachersPanel } from "./teachers-panel";

export const TIMETABLE_TABS = ["mine", "sections", "teachers", "substitutions", "bells", "assignments", "clashes"] as const;
export type TimetableTab = (typeof TIMETABLE_TABS)[number];

const TAB_LABELS: Record<TimetableTab, MessageKey> = {
  mine: "timetable.tab.mine",
  sections: "timetable.tab.sections",
  teachers: "timetable.tab.teachers",
  substitutions: "timetable.tab.substitutions",
  bells: "timetable.tab.bells",
  assignments: "timetable.tab.assignments",
  clashes: "timetable.tab.clashes",
};

/** The tabs a person sees: teachers start on their own timetable; managers also get the set-up tabs. Pure. */
export function timetableTabs(canManage: boolean, isTeacher: boolean): TimetableTab[] {
  return TIMETABLE_TABS.filter((tab) => {
    if (tab === "mine") return isTeacher;
    if (tab === "assignments" || tab === "clashes") return canManage;
    return true;
  });
}

/** Staff timetables (timetable.read): sections, teachers, substitutions, the bell schedule and set-up. */
export function TimetableView({
  initialTab,
  initialSectionId,
  initialDate,
}: {
  initialTab?: string;
  initialSectionId?: string;
  initialDate?: string;
}) {
  const { t } = useI18n();
  const { me } = useAuth();
  const baseId = useId();
  const canManage = hasPermission(me, PERMISSIONS.timetableManage);
  const isTeacher = me?.roles.includes("TEACHER") ?? false;
  const tabs = timetableTabs(canManage, isTeacher);
  const [tab, setTab] = useState<TimetableTab>(() =>
    tabs.includes(initialTab as TimetableTab) ? (initialTab as TimetableTab) : tabs[0],
  );
  const [sectionId, setSectionId] = useState(initialSectionId);
  const [sectionVersion, setSectionVersion] = useState(0);
  const tabRefs = useRef<Partial<Record<TimetableTab, HTMLButtonElement | null>>>({});

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const index = tabs.indexOf(tab);
    let next: TimetableTab | null = null;
    if (event.key === "ArrowRight") next = tabs[(index + 1) % tabs.length];
    else if (event.key === "ArrowLeft") next = tabs[(index - 1 + tabs.length) % tabs.length];
    else if (event.key === "Home") next = tabs[0];
    else if (event.key === "End") next = tabs[tabs.length - 1];
    if (!next) return;
    event.preventDefault();
    setTab(next);
    tabRefs.current[next]?.focus();
  };

  const openSection = (id: string) => {
    setSectionId(id);
    setSectionVersion((v) => v + 1);
    setTab("sections");
  };

  return (
    <>
      <PageHead title={t("timetable.title")} eyebrow={t("timetable.eyebrow")} />
      <div role="tablist" aria-label={t("timetable.title")} className="tabs" onKeyDown={onKeyDown}>
        {tabs.map((key) => (
          <button
            key={key}
            ref={(el) => {
              tabRefs.current[key] = el;
            }}
            type="button"
            role="tab"
            id={`${baseId}-tab-${key}`}
            aria-selected={tab === key}
            aria-controls={`${baseId}-panel-${key}`}
            tabIndex={tab === key ? 0 : -1}
            onClick={() => setTab(key)}
          >
            {t(TAB_LABELS[key])}
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id={`${baseId}-panel-${tab}`}
        aria-labelledby={`${baseId}-tab-${tab}`}
        className={tab === "mine" || tab === "substitutions" ? undefined : "card"}
      >
        {tab === "mine" ? <MyTimetablePanel /> : null}
        {tab === "sections" ? (
          <SectionsPanel
            key={sectionVersion}
            initialSectionId={sectionId}
            onOpenBells={canManage ? () => setTab("bells") : undefined}
          />
        ) : null}
        {tab === "teachers" ? <TeachersPanel /> : null}
        {tab === "substitutions" ? <SubstitutionsPanel canManage={canManage} initialDate={initialDate} /> : null}
        {tab === "bells" ? <BellsPanel canManage={canManage} /> : null}
        {tab === "assignments" ? <AssignmentsPanel /> : null}
        {tab === "clashes" ? <ClashesPanel onOpenSection={openSection} /> : null}
      </div>
    </>
  );
}

/**
 * /app/timetable for whoever opens it: staff with timetable.read get the timetables; a student
 * their section's; a parent their children's.
 */
export function TimetablePage(props: {
  initialTab?: string;
  initialSectionId?: string;
  initialDate?: string;
  initialChildId?: string;
}) {
  const { me } = useAuth();
  if (hasPermission(me, PERMISSIONS.timetableRead)) return <TimetableView {...props} />;
  if (me?.roles.includes("STUDENT")) return <StudentTimetableView />;
  if (hasPermission(me, PERMISSIONS.childView)) return <ParentTimetableView initialChildId={props.initialChildId} />;
  return <AccessDenied />;
}
