"use client";

import { GraduationCap } from "lucide-react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { studentStatusTone } from "@/components/views/students/student-status";
import { api } from "@/lib/api";
import { classLabel, initials } from "@/lib/format";
import { translateOr, useI18n } from "@/lib/i18n";
import type { Child } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

function ChildCard({ child }: { child: Child }) {
  const { t } = useI18n();
  const place = classLabel(child.className, child.sectionName);
  return (
    <article className="card flex flex-col gap-3" aria-label={child.fullName} data-testid="child-card">
      <div className="person">
        <span className="avatar h-11 w-11 text-[15px]" aria-hidden="true">
          {initials(child.fullName)}
        </span>
        <div className="min-w-0">
          <h3 className="font-display text-base font-bold leading-tight">{child.fullName}</h3>
          <span className="sub">{place || translateOr(t, `studentStatus.${child.status}`, child.status)}</span>
        </div>
        {child.status !== "ACTIVE" ? (
          <span className="ml-auto">
            <Pill tone={studentStatusTone(child.status)}>
              {translateOr(t, `studentStatus.${child.status}`, child.status)}
            </Pill>
          </span>
        ) : null}
      </div>
      <dl className="kv text-[13.5px]">
        {child.rollNo ? (
          <>
            <dt>{t("children.roll")}</dt>
            <dd className="num">{child.rollNo}</dd>
          </>
        ) : null}
        <dt>{t("children.classTeacher")}</dt>
        <dd>{child.classTeacherName ?? "—"}</dd>
        <dt>{t("students.field.admissionNo")}</dt>
        <dd className="mono">{child.admissionNo}</dd>
        {child.academicYearName ? (
          <>
            <dt>{t("children.year")}</dt>
            <dd>{child.academicYearName}</dd>
          </>
        ) : null}
      </dl>
    </article>
  );
}

/** Parent dashboard: one card per child linked to the parent's sign-in. */
export function MyChildren() {
  const { t } = useI18n();
  const children = useApiData("me:children", api.myChildren);
  const list = children.data ?? [];
  return (
    <section aria-labelledby="my-children" className="flex flex-col gap-3">
      <h2 id="my-children" className="flex items-center gap-2">
        <GraduationCap size={20} aria-hidden="true" className="text-ink-3" />
        {t("children.title")}
      </h2>
      {children.error && !children.data ? (
        <section className="card">
          <ErrorState error={children.error} onRetry={children.reload} />
        </section>
      ) : !children.data ? (
        <section className="card">
          <LoadingRows rows={2} />
        </section>
      ) : list.length === 0 ? (
        <section className="card">
          <p className="empty">{t("children.empty")}</p>
        </section>
      ) : (
        <div className="grid grid-cols-1 gap-3.5 md:grid-cols-2 xl:grid-cols-3">
          {list.map((child) => (
            <ChildCard key={child.id} child={child} />
          ))}
        </div>
      )}
    </section>
  );
}

/** Student dashboard: their own class card. Shows nothing until the sign-in is linked to a record. */
export function MyClass() {
  const { t } = useI18n();
  const record = useApiData("me:student", api.myStudentRecord);
  if (record.error?.status === 404) {
    return (
      <section className="card">
        <p className="text-sm text-ink-2">{t("children.notLinked")}</p>
      </section>
    );
  }
  if (record.error && !record.data) {
    return (
      <section className="card">
        <ErrorState error={record.error} onRetry={record.reload} />
      </section>
    );
  }
  if (!record.data) return null;
  return (
    <section aria-labelledby="my-class" className="flex flex-col gap-3">
      <h2 id="my-class">{t("children.myClass")}</h2>
      <div className="grid grid-cols-1 gap-3.5 md:grid-cols-2 xl:grid-cols-3">
        <ChildCard child={record.data} />
      </div>
    </section>
  );
}
