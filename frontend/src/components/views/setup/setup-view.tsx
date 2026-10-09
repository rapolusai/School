"use client";

import { useId, useRef, useState } from "react";
import { PageHead } from "@/components/ui/states";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { useApiData } from "@/lib/use-api-data";
import { ClassesPanel } from "./classes-panel";
import { ProfilePanel } from "./profile-panel";
import { SubjectsPanel } from "./subjects-panel";
import { YearsPanel } from "./years-panel";

export const SETUP_TABS = ["years", "classes", "subjects", "profile"] as const;
export type SetupTab = (typeof SETUP_TABS)[number];

const TAB_LABELS: Record<SetupTab, MessageKey> = {
  years: "setup.tab.years",
  classes: "setup.tab.classes",
  subjects: "setup.tab.subjects",
  profile: "setup.tab.profile",
};

/** School setup: academic years, classes and sections, subjects, and the school's profile. */
export function SetupView({ initialTab = "years" }: { initialTab?: SetupTab }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const baseId = useId();
  const [tab, setTab] = useState<SetupTab>(initialTab);
  const tabRefs = useRef<Partial<Record<SetupTab, HTMLButtonElement | null>>>({});
  const canManage = hasPermission(me, PERMISSIONS.settingsManage);

  const years = useApiData("academics:years", api.listYears);
  const classes = useApiData(tab === "classes" ? "academics:classes" : null, api.listClasses);
  const subjects = useApiData(tab === "subjects" ? "academics:subjects" : null, api.listSubjects);

  const current = years.data?.find((y) => y.current);

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const index = SETUP_TABS.indexOf(tab);
    let next: SetupTab | null = null;
    if (event.key === "ArrowRight") next = SETUP_TABS[(index + 1) % SETUP_TABS.length];
    else if (event.key === "ArrowLeft") next = SETUP_TABS[(index - 1 + SETUP_TABS.length) % SETUP_TABS.length];
    else if (event.key === "Home") next = SETUP_TABS[0];
    else if (event.key === "End") next = SETUP_TABS[SETUP_TABS.length - 1];
    if (!next) return;
    event.preventDefault();
    setTab(next);
    tabRefs.current[next]?.focus();
  };

  return (
    <>
      <PageHead
        eyebrow={
          current
            ? t("setup.eyebrow.current", { name: current.name })
            : years.data
              ? t("setup.eyebrow.noYear")
              : t("common.loading")
        }
        title={t("setup.title")}
      />

      <div role="tablist" aria-label={t("setup.title")} className="tabs" onKeyDown={onKeyDown}>
        {SETUP_TABS.map((key) => (
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

      <div role="tabpanel" id={`${baseId}-panel-${tab}`} aria-labelledby={`${baseId}-tab-${tab}`}>
        {tab === "years" ? <YearsPanel years={years} canManage={canManage} /> : null}
        {tab === "classes" ? <ClassesPanel classes={classes} canManage={canManage} /> : null}
        {tab === "subjects" ? <SubjectsPanel subjects={subjects} canManage={canManage} /> : null}
        {tab === "profile" ? <ProfilePanel canManage={canManage} /> : null}
      </div>
    </>
  );
}
