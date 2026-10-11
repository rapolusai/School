"use client";

import { ArrowLeft, Printer } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { api } from "@/lib/api";
import { examsApi } from "@/lib/exams-api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import type { ExamSchoolHeader, HallTickets } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { formatScore, shortTime } from "./exams-shared";

/** The school's name, board, city and address over a printed page. */
export function SchoolHead({ school, title, sub }: { school: ExamSchoolHeader; title: string; sub?: string }) {
  const { t } = useI18n();
  const place = [school.address, school.city].filter(Boolean).join(", ");
  return (
    <header className="exam-page-head">
      <span className="exam-school">{school.name}</span>
      {place ? <p>{place}</p> : null}
      <p>
        {[school.board ? translateOr(t, `board.${school.board}`, school.board) : null, school.phone]
          .filter(Boolean)
          .join(" · ")}
      </p>
      <h2>{title}</h2>
      {sub ? <p>{sub}</p> : null}
    </header>
  );
}

/** /app/exams/[examId]/[classId]/hall-tickets: one A4 hall ticket per student with the date sheet. */
export function HallTicketsView({
  examId,
  classId,
  initialSectionId,
}: {
  examId: string;
  classId: string;
  initialSectionId?: string;
}) {
  const { t } = useI18n();
  const [sectionId, setSectionId] = useState(initialSectionId ?? "");
  const tickets = useApiData(`exams:hall-tickets:${examId}:${classId}:${sectionId}`, () =>
    examsApi.hallTickets(examId, classId, sectionId || null),
  );
  const classes = useApiData("exams:school-classes", api.listClasses);
  const sections = classes.data?.find((c) => c.id === classId)?.sections ?? [];
  const data = tickets.data;
  const back = `/app/exams/${encodeURIComponent(examId)}/${encodeURIComponent(classId)}`;

  return (
    <>
      <PageHead
        eyebrow={data ? `${data.examName} · ${data.className}` : undefined}
        title={t("exams.hallTickets.title")}
        actions={
          <>
            <Link href={back} className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("exams.backToExam")}
            </Link>
            <button type="button" className="btn btn-primary" disabled={!data || data.students.length === 0} onClick={() => window.print()}>
              <Printer size={18} aria-hidden="true" />
              {t("exams.print")}
            </button>
          </>
        }
      />
      <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("exams.filters")}>
        <label className="field w-full sm:w-auto sm:min-w-44">
          <span className="field-label">{t("exams.section")}</span>
          <select className="input" name="sectionId" value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
            <option value="">{t("exams.allSections")}</option>
            {sections.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
        {data ? <p className="self-end text-[13.5px] text-ink-2">{plural(t, "exams.hallTickets.count", data.students.length)}</p> : null}
      </form>
      {tickets.error && !data ? (
        <section className="card">
          <ErrorState error={tickets.error} onRetry={tickets.reload} />
        </section>
      ) : !data || tickets.loading ? (
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      ) : data.students.length === 0 ? (
        <section className="card">
          <p className="empty">{t("exams.hallTickets.empty")}</p>
        </section>
      ) : (
        <div className="exam-doc" data-testid="hall-tickets">
          {data.students.map((student) => (
            <HallTicket key={student.studentId} data={data} student={student} />
          ))}
        </div>
      )}
    </>
  );
}

function HallTicket({ data, student }: { data: HallTickets; student: HallTickets["students"][number] }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <article className="card exam-page hall-ticket" aria-label={t("exams.hallTickets.for", { name: student.fullName })}>
      <SchoolHead
        school={data.school}
        title={t("exams.hallTickets.heading", { exam: data.examName })}
        sub={`${data.termName} · ${data.academicYearName}`}
      />
      <dl className="exam-facts">
        <div>
          <dt>{t("exams.card.student")}</dt>
          <dd>{student.fullName}</dd>
        </div>
        <div>
          <dt>{t("exams.card.class")}</dt>
          <dd>{student.sectionLabel}</dd>
        </div>
        <div>
          <dt>{t("exams.card.roll")}</dt>
          <dd className="num">{student.rollNo ?? "—"}</dd>
        </div>
        <div>
          <dt>{t("exams.card.admissionNo")}</dt>
          <dd>{student.admissionNo}</dd>
        </div>
      </dl>
      <div className="table-wrap">
        <table className="table exam-table">
          <caption className="sr-only">{t("exams.dateSheet.title")}</caption>
          <thead>
            <tr>
              <th scope="col">{t("exams.col.date")}</th>
              <th scope="col">{t("exams.col.time")}</th>
              <th scope="col">{t("exams.col.subject")}</th>
              <th scope="col" className="r">
                {t("exams.col.maxMarks")}
              </th>
              <th scope="col">{t("exams.col.room")}</th>
              <th scope="col">{t("exams.col.invigilator")}</th>
            </tr>
          </thead>
          <tbody>
            {data.papers.map((p) => (
              <tr key={p.id}>
                <td className="whitespace-nowrap">{p.date ? formatPlainDate(p.date, locale) : "—"}</td>
                <td className="whitespace-nowrap num">
                  {p.startTime ? `${shortTime(p.startTime)}–${shortTime(p.endTime)}` : "—"}
                </td>
                <th scope="row">{p.subjectName}</th>
                <td className="r num">{formatScore(p.maxTotal)}</td>
                <td>{p.room ?? "—"}</td>
                <td />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data.instructions ? (
        <div className="flex flex-col gap-1">
          <p className="exam-section-title">{t("exams.hallTickets.instructions")}</p>
          <p className="whitespace-pre-line text-[13.5px]">{data.instructions}</p>
        </div>
      ) : null}
      <div className="exam-signs">
        <span>{t("exams.sign.student")}</span>
        <span>{t("exams.sign.classTeacher")}</span>
        <span>{t("exams.sign.principal")}</span>
      </div>
    </article>
  );
}
