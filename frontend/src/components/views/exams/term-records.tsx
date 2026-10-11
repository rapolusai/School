"use client";

import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { examsApi } from "@/lib/exams-api";
import { translateOr, useI18n } from "@/lib/i18n";
import type { HpcDomain, HpcLevel, StudentTermRecord, TermRecordRequest, TermRecords } from "@/lib/types";
import { CO_SCHOLASTIC_AREAS, HPC_DOMAINS, HPC_LEVELS, REFLECTION_MAX, REMARK_MAX } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

const REFLECTIONS = ["teacherReflection", "selfReflection", "parentReflection"] as const;

/** A student's record as the request sends it (blank text as null). Pure, exported for tests. */
export function recordRequest(record: StudentTermRecord): TermRecordRequest {
  const text = (value: string | null | undefined) => (value && value.trim() ? value.trim() : null);
  const hpc = record.hpc;
  return {
    studentId: record.studentId,
    workEducation: record.coScholastic.workEducation || null,
    artEducation: record.coScholastic.artEducation || null,
    healthPhysical: record.coScholastic.healthPhysical || null,
    discipline: record.coScholastic.discipline || null,
    remark: text(record.remark),
    physical: hpc?.physical ?? null,
    socioEmotional: hpc?.socioEmotional ?? null,
    cognitive: hpc?.cognitive ?? null,
    language: hpc?.language ?? null,
    numeracy: hpc?.numeracy ?? null,
    aesthetic: hpc?.aesthetic ?? null,
    teacherReflection: text(hpc?.teacherReflection),
    selfReflection: text(hpc?.selfReflection),
    parentReflection: text(hpc?.parentReflection),
  };
}

const EMPTY_HPC = {
  physical: null,
  socioEmotional: null,
  cognitive: null,
  language: null,
  numeracy: null,
  aesthetic: null,
  teacherReflection: null,
  selfReflection: null,
  parentReflection: null,
};

/** /app/exams/records/[termId]/[sectionId]: co-scholastic grades, remarks and the holistic progress card. */
export function TermRecordsView({ termId, sectionId }: { termId: string; sectionId: string }) {
  const { t } = useI18n();
  const loaded = useApiData(`exams:records:${termId}:${sectionId}`, () => examsApi.records(termId, sectionId));
  const [fresh, setFresh] = useState<TermRecords | null>(null);
  const data = fresh ?? loaded.data;

  return (
    <>
      <PageHead
        eyebrow={data ? `${data.termName} · ${data.sectionLabel}` : undefined}
        title={t("exams.records.title")}
        actions={
          <Link
            href={`/app/exams/report-cards?section=${encodeURIComponent(sectionId)}&term=${encodeURIComponent(termId)}`}
            className="btn"
          >
            <ArrowLeft size={18} aria-hidden="true" />
            {t("exams.reportCards.title")}
          </Link>
        }
      />
      {loaded.error && !data ? (
        <section className="card">
          <ErrorState error={loaded.error} onRetry={loaded.reload} />
        </section>
      ) : !data ? (
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      ) : (
        <RecordsForm key={JSON.stringify(data.students.map(recordRequest))} data={data} onSaved={setFresh} />
      )}
    </>
  );
}

function RecordsForm({ data, onSaved }: { data: TermRecords; onSaved: (next: TermRecords) => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [records, setRecords] = useState<StudentTermRecord[]>(data.students);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const editable = data.canEdit;

  const update = (studentId: string, change: (record: StudentTermRecord) => StudentTermRecord) =>
    setRecords((prev) => prev.map((r) => (r.studentId === studentId ? change(r) : r)));

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const tooLong = records.some(
      (r) =>
        (r.remark ?? "").length > REMARK_MAX ||
        REFLECTIONS.some((key) => (r.hpc?.[key] ?? "").length > REFLECTION_MAX),
    );
    if (tooLong) {
      setError(t("exams.v.recordsLong", { remark: REMARK_MAX, reflection: REFLECTION_MAX }));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const next = await examsApi.saveRecords(data.termId, data.sectionId, records.map(recordRequest));
      toast(t("exams.records.saved"));
      onSaved(next);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  if (records.length === 0) {
    return (
      <section className="card">
        <p className="empty">{t("exams.marks.noStudents")}</p>
      </section>
    );
  }

  return (
    <form method="post" onSubmit={onSubmit} noValidate className="flex flex-col gap-3.5" data-testid="term-records">
      <p className="text-[13.5px] text-ink-2">
        {data.hpcEnabled ? t("exams.records.introHpc") : t("exams.records.intro")}
        {editable ? "" : ` ${t("exams.records.readOnly")}`}
      </p>
      {records.map((record) => (
        <fieldset key={record.studentId} className="card flex flex-col gap-3" disabled={!editable}>
          <legend className="sr-only">{record.fullName}</legend>
          <div className="flex flex-wrap items-baseline gap-x-2">
            <b className="font-display text-[15px]">
              {record.rollNo !== null ? `${record.rollNo}. ` : ""}
              {record.fullName}
            </b>
            <span className="text-[12.5px] text-ink-3">{record.admissionNo}</span>
          </div>
          <div className="grid grid-cols-2 gap-2.5 md:grid-cols-4">
            {CO_SCHOLASTIC_AREAS.map((area) => (
              <label key={area} className="field">
                <span className="field-label text-[12.5px]">{t(`exams.area.${area}`)}</span>
                <select
                  className="input"
                  name={`${area}-${record.studentId}`}
                  aria-label={t("exams.records.fieldFor", { field: t(`exams.area.${area}`), name: record.fullName })}
                  value={record.coScholastic[area] ?? ""}
                  onChange={(e) =>
                    update(record.studentId, (r) => ({
                      ...r,
                      coScholastic: { ...r.coScholastic, [area]: e.target.value || null },
                    }))
                  }
                >
                  <option value="">—</option>
                  {data.coScholasticGrades.map((grade) => (
                    <option key={grade} value={grade}>
                      {grade}
                    </option>
                  ))}
                </select>
              </label>
            ))}
          </div>
          <label className="field">
            <span className="field-label text-[12.5px]">{t("exams.card.remark")}</span>
            <textarea
              className="input"
              rows={2}
              name={`remark-${record.studentId}`}
              maxLength={REMARK_MAX}
              aria-label={t("exams.records.fieldFor", { field: t("exams.card.remark"), name: record.fullName })}
              value={record.remark ?? ""}
              onChange={(e) => update(record.studentId, (r) => ({ ...r, remark: e.target.value }))}
            />
          </label>
          {data.hpcEnabled ? (
            <details className="flex flex-col gap-2.5">
              <summary className="cursor-pointer text-[13.5px] font-semibold text-accent">{t("exams.hpc.title")}</summary>
              <div className="mt-2.5 grid grid-cols-1 gap-2.5 sm:grid-cols-2 lg:grid-cols-3">
                {HPC_DOMAINS.map((domain) => (
                  <HpcSelect
                    key={domain}
                    domain={domain}
                    record={record}
                    onChange={(level) =>
                      update(record.studentId, (r) => ({ ...r, hpc: { ...(r.hpc ?? EMPTY_HPC), [domain]: level } }))
                    }
                  />
                ))}
              </div>
              <div className="mt-2.5 grid grid-cols-1 gap-2.5 lg:grid-cols-3">
                {REFLECTIONS.map((key) => (
                  <label key={key} className="field">
                    <span className="field-label text-[12.5px]">{t(`exams.hpc.${key}`)}</span>
                    <textarea
                      className="input"
                      rows={2}
                      name={`${key}-${record.studentId}`}
                      maxLength={REFLECTION_MAX}
                      value={record.hpc?.[key] ?? ""}
                      onChange={(e) =>
                        update(record.studentId, (r) => ({ ...r, hpc: { ...(r.hpc ?? EMPTY_HPC), [key]: e.target.value } }))
                      }
                    />
                  </label>
                ))}
              </div>
            </details>
          ) : null}
        </fieldset>
      ))}
      {editable ? (
        <div className="card att-savebar" style={{ marginTop: 0 }}>
          <FormAlert message={error} />
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? t("common.saving") : t("exams.records.save")}
          </button>
        </div>
      ) : null}
    </form>
  );
}

function HpcSelect({
  domain,
  record,
  onChange,
}: {
  domain: HpcDomain;
  record: StudentTermRecord;
  onChange: (level: HpcLevel | null) => void;
}) {
  const { t } = useI18n();
  return (
    <label className="field">
      <span className="field-label text-[12.5px]">{t(`exams.hpc.${domain}`)}</span>
      <select
        className="input"
        name={`${domain}-${record.studentId}`}
        value={record.hpc?.[domain] ?? ""}
        onChange={(e) => onChange((e.target.value || null) as HpcLevel | null)}
      >
        <option value="">—</option>
        {HPC_LEVELS.map((level) => (
          <option key={level} value={level}>
            {translateOr(t, `exams.hpc.level.${level}`, level)}
          </option>
        ))}
      </select>
    </label>
  );
}
