"use client";

import { Copy, Pencil, Plus, RotateCcw, Trash2, X } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { examsApi } from "@/lib/exams-api";
import { plural, translateOr, useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { ClassSchemes, ExamSettings, GradingScheme, SchemeKind, SchemeRequest } from "@/lib/types";
import { MAX_BANDS } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ExamsNav, formatScore, parseDecimal, YearSelect } from "./exams-shared";

const NAME_MAX = 100;
const DESCRIPTION_MAX = 500;
const GRADE_MAX = 10;
const BAND_REMARK_MAX = 100;
const HPC_LEVEL_MAX = 12;

/** /app/exams/grading: the school's grading schemes, which class uses which, and the exam settings. */
export function GradingView() {
  const { t } = useI18n();
  const { toast } = useToast();
  const schemes = useApiData("exams:schemes", examsApi.schemes);
  const settings = useApiData("exams:settings", examsApi.settings);
  const [yearId, setYearId] = useState("");
  const classSchemes = useApiData(`exams:class-schemes:${yearId}`, () => examsApi.classSchemes(yearId || null));
  const { me } = useAuth();
  const canSetup = hasPermission(me, PERMISSIONS.examsSetup);
  const [editing, setEditing] = useState<{ scheme: GradingScheme | null; copyOf: GradingScheme | null } | null>(null);
  const [deleting, setDeleting] = useState<GradingScheme | null>(null);
  const [restoring, setRestoring] = useState(false);
  const list = schemes.data ?? [];

  const reloadAll = () => {
    schemes.reload();
    classSchemes.reload();
    settings.reload();
  };

  return (
    <>
      <PageHead
        title={t("exams.grading.title")}
        actions={
          canSetup ? (
            <>
              <button type="button" className="btn" onClick={() => setRestoring(true)}>
                <RotateCcw size={18} aria-hidden="true" />
                {t("exams.grading.restore")}
              </button>
              <button type="button" className="btn btn-primary" onClick={() => setEditing({ scheme: null, copyOf: null })}>
                <Plus size={18} aria-hidden="true" />
                {t("exams.grading.add")}
              </button>
            </>
          ) : null
        }
      />
      <ExamsNav />

      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        <section className="card flex flex-col gap-3" aria-labelledby="class-schemes">
          <h2 id="class-schemes">{t("exams.grading.classes")}</h2>
          {classSchemes.error && !classSchemes.data ? (
            <ErrorState error={classSchemes.error} onRetry={classSchemes.reload} />
          ) : !classSchemes.data || !schemes.data ? (
            <LoadingRows rows={4} />
          ) : (
            <ClassSchemesPanel
              data={classSchemes.data}
              schemes={list}
              yearId={yearId}
              onYear={setYearId}
              canSetup={canSetup}
              onSaved={() => {
                toast(t("exams.grading.classSaved"));
                reloadAll();
              }}
            />
          )}
        </section>
        <section className="card flex flex-col gap-3" aria-labelledby="exam-settings">
          <h2 id="exam-settings">{t("exams.settings.title")}</h2>
          {settings.error && !settings.data ? (
            <ErrorState error={settings.error} onRetry={settings.reload} />
          ) : !settings.data || !schemes.data ? (
            <LoadingRows rows={3} />
          ) : (
            <SettingsForm
              key={JSON.stringify(settings.data)}
              settings={settings.data}
              schemes={list.filter((s) => s.kind === "CO_SCHOLASTIC")}
              canSetup={canSetup}
              onSaved={() => {
                toast(t("exams.settings.saved"));
                reloadAll();
              }}
            />
          )}
        </section>
      </div>

      <section className="flex flex-col gap-3.5" aria-labelledby="schemes-title">
        <h2 id="schemes-title">{t("exams.grading.schemes")}</h2>
        {schemes.error && !schemes.data ? (
          <section className="card">
            <ErrorState error={schemes.error} onRetry={schemes.reload} />
          </section>
        ) : !schemes.data ? (
          <section className="card">
            <LoadingRows rows={4} />
          </section>
        ) : list.length === 0 ? (
          <section className="card">
            <p className="empty">{t("exams.grading.empty")}</p>
          </section>
        ) : (
          <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
            {list.map((scheme) => (
              <SchemeCard
                key={scheme.id}
                scheme={scheme}
                canSetup={canSetup}
                onEdit={() => setEditing({ scheme, copyOf: null })}
                onCopy={() => setEditing({ scheme: null, copyOf: scheme })}
                onDelete={() => setDeleting(scheme)}
              />
            ))}
          </div>
        )}
      </section>

      {editing ? (
        <SchemeDialog
          scheme={editing.scheme}
          copyOf={editing.copyOf}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            setEditing(null);
            toast(t("exams.grading.saved", { name: saved.name }));
            reloadAll();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={deleting !== null}
        title={t("exams.grading.deleteTitle", { name: deleting?.name ?? "" })}
        body={t("exams.grading.deleteBody")}
        confirmLabel={t("common.delete")}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          if (!deleting) return;
          await examsApi.deleteScheme(deleting.id);
          setDeleting(null);
          toast(t("common.deleted"));
          reloadAll();
        }}
      />
      <ConfirmDialog
        open={restoring}
        title={t("exams.grading.restoreTitle")}
        body={t("exams.grading.restoreBody")}
        confirmLabel={t("exams.grading.restore")}
        danger={false}
        onClose={() => setRestoring(false)}
        onConfirm={async () => {
          await examsApi.restoreSchemes();
          setRestoring(false);
          toast(t("exams.grading.restored"));
          reloadAll();
        }}
      />
    </>
  );
}

function SchemeCard({
  scheme,
  canSetup,
  onEdit,
  onCopy,
  onDelete,
}: {
  scheme: GradingScheme;
  canSetup: boolean;
  onEdit: () => void;
  onCopy: () => void;
  onDelete: () => void;
}) {
  const { t } = useI18n();
  const scholastic = scheme.kind === "SCHOLASTIC";
  return (
    <article className="card flex flex-col gap-3" aria-label={scheme.name} data-testid="grading-scheme">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h3 className="font-display text-base font-bold leading-tight">{scheme.name}</h3>
          <p className="mt-1 flex flex-wrap gap-1.5">
            <Pill tone={scholastic ? "info" : "accent"}>{t(`exams.kind.${scheme.kind}`)}</Pill>
            {scheme.builtIn ? <Pill>{t("exams.grading.builtIn")}</Pill> : null}
            {scheme.inUse > 0 ? <Pill tone="good">{plural(t, "exams.grading.inUse", scheme.inUse)}</Pill> : null}
          </p>
        </div>
        {canSetup ? (
          <div className="flex gap-1">
            <button type="button" className="iconbtn" onClick={onCopy} aria-label={t("exams.grading.copyNamed", { name: scheme.name })}>
              <Copy size={16} aria-hidden="true" />
            </button>
            <button type="button" className="iconbtn" onClick={onEdit} aria-label={t("exams.grading.editNamed", { name: scheme.name })}>
              <Pencil size={16} aria-hidden="true" />
            </button>
            <button type="button" className="iconbtn" onClick={onDelete} aria-label={t("exams.grading.deleteNamed", { name: scheme.name })}>
              <Trash2 size={16} aria-hidden="true" />
            </button>
          </div>
        ) : null}
      </div>
      {scheme.description ? <p className="text-[13.5px] text-ink-2">{scheme.description}</p> : null}
      {scholastic ? (
        <p className="text-[13px] text-ink-3">
          {t("exams.grading.passRule", {
            subject: formatScore(scheme.passPercent),
            overall: formatScore(scheme.overallPassPercent),
          })}
        </p>
      ) : null}
      <div className="table-wrap">
        <table className="table">
          <caption className="sr-only">{t("exams.grading.bandsOf", { name: scheme.name })}</caption>
          <thead>
            <tr>
              <th scope="col">{t("exams.col.grade")}</th>
              <th scope="col" className="r">
                {scholastic ? t("exams.band.from") : t("exams.band.order")}
              </th>
              <th scope="col" className="r">
                {t("exams.band.point")}
              </th>
              <th scope="col">{t("exams.band.remark")}</th>
            </tr>
          </thead>
          <tbody>
            {scheme.bands.map((band) => (
              <tr key={band.grade}>
                <td className="font-semibold">{band.grade}</td>
                <td className="r num">{scholastic ? `${formatScore(band.minPercent)}%` : formatScore(band.minPercent)}</td>
                <td className="r num">{band.gradePoint === null ? "—" : formatScore(band.gradePoint)}</td>
                <td className="text-[13px] text-ink-2">{band.remark ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </article>
  );
}

function ClassSchemesPanel({
  data,
  schemes,
  yearId,
  onYear,
  canSetup,
  onSaved,
}: {
  data: ClassSchemes;
  schemes: GradingScheme[];
  yearId: string;
  onYear: (yearId: string) => void;
  canSetup: boolean;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const scholastic = schemes.filter((s) => s.kind === "SCHOLASTIC");

  const assign = async (classId: string, schemeId: string) => {
    setBusy(classId);
    setError(null);
    try {
      await examsApi.assignSchemes(data.year?.id ?? null, { classIds: [classId], schemeId: schemeId || null });
      onSaved();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <p className="text-[13.5px] text-ink-2">
        {t("exams.grading.classesIntro", { board: translateOr(t, `board.${data.board ?? ""}`, data.board ?? "") })}
      </p>
      {data.years.length > 1 ? (
        <form method="post" onSubmit={(e) => e.preventDefault()}>
          <YearSelect years={data.years} value={yearId || data.year?.id || ""} onChange={onYear} />
        </form>
      ) : null}
      <FormAlert message={error} />
      {!data.year ? (
        <p className="empty">{t("exams.noYear")}</p>
      ) : data.classes.length === 0 ? (
        <p className="empty">{t("exams.exam.noSchoolClasses")}</p>
      ) : (
        <ul className="rowcards" data-testid="class-schemes">
          {data.classes.map((row) => (
            <li key={row.classId} className="rowcard flex-wrap">
              <span className="min-w-0 flex-1">
                <b className="block font-semibold">{row.className}</b>
                <span className="block text-[12.5px] text-ink-3">
                  {row.hpc ? t("exams.grading.hpcYes") : t("exams.grading.hpcNo")}
                </span>
              </span>
              {canSetup ? (
                <select
                  className="input w-full sm:w-56"
                  name={`scheme-${row.classId}`}
                  aria-label={t("exams.grading.schemeFor", { name: row.className })}
                  value={row.defaultScheme ? "" : (row.schemeId ?? "")}
                  disabled={busy !== null}
                  onChange={(e) => assign(row.classId, e.target.value)}
                >
                  <option value="">{t("exams.grading.default", { name: row.defaultScheme ? (row.schemeName ?? "—") : t("exams.grading.boardDefault") })}</option>
                  {scholastic.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              ) : (
                <span className="text-[13.5px] font-semibold">{row.schemeName ?? "—"}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function SettingsForm({
  settings,
  schemes,
  canSetup,
  onSaved,
}: {
  settings: ExamSettings;
  schemes: GradingScheme[];
  canSetup: boolean;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const [ranks, setRanks] = useState(settings.ranksEnabled);
  const [hpcLevel, setHpcLevel] = useState(String(settings.hpcUpToLevel));
  const [coScheme, setCoScheme] = useState(settings.coScholasticSchemeId ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await examsApi.saveSettings({
        ranksEnabled: ranks,
        hpcUpToLevel: Number(hpcLevel),
        coScholasticSchemeId: coScheme || null,
      });
      onSaved();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form method="post" className="flex flex-col gap-3" onSubmit={onSubmit} noValidate>
      <FormAlert message={error} />
      <fieldset className="flex flex-col gap-3" disabled={!canSetup}>
        <label className="check">
          <input type="checkbox" name="ranksEnabled" checked={ranks} onChange={(e) => setRanks(e.target.checked)} />
          <span>{t("exams.settings.ranks")}</span>
        </label>
        <label className="field">
          <span className="field-label">{t("exams.settings.hpc")}</span>
          <select className="input" name="hpcUpToLevel" value={hpcLevel} onChange={(e) => setHpcLevel(e.target.value)}>
            {Array.from({ length: HPC_LEVEL_MAX + 1 }, (_, level) => (
              <option key={level} value={level}>
                {level === 0 ? t("exams.settings.hpcPrePrimary") : t("exams.settings.hpcUpTo", { level })}
              </option>
            ))}
          </select>
          <span className="field-hint">{t("exams.settings.hpcHint")}</span>
        </label>
        <label className="field">
          <span className="field-label">{t("exams.settings.coScholastic")}</span>
          <select className="input" name="coScholasticSchemeId" value={coScheme} onChange={(e) => setCoScheme(e.target.value)}>
            <option value="">{t("exams.settings.coScholasticDefault")}</option>
            {schemes.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
      </fieldset>
      {canSetup ? (
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? t("common.saving") : t("common.save")}
          </button>
        </div>
      ) : null}
    </form>
  );
}

/* ------------------------------------------------------------------ scheme dialog */

type BandText = { grade: string; minPercent: string; gradePoint: string; remark: string };
type SchemeText = {
  name: string;
  kind: SchemeKind;
  description: string;
  passPercent: string;
  overallPassPercent: string;
  bands: BandText[];
};

function schemeText(scheme: GradingScheme | null, copyOf: GradingScheme | null, copyName: string): SchemeText {
  const from = scheme ?? copyOf;
  if (!from) {
    return {
      name: "",
      kind: "SCHOLASTIC",
      description: "",
      passPercent: "33",
      overallPassPercent: "33",
      bands: [
        { grade: "A", minPercent: "75", gradePoint: "", remark: "" },
        { grade: "B", minPercent: "50", gradePoint: "", remark: "" },
        { grade: "C", minPercent: "33", gradePoint: "", remark: "" },
        { grade: "D", minPercent: "0", gradePoint: "", remark: "" },
      ],
    };
  }
  return {
    name: scheme ? scheme.name : copyName,
    kind: from.kind,
    description: from.description ?? "",
    passPercent: formatScore(from.passPercent),
    overallPassPercent: formatScore(from.overallPassPercent),
    bands: from.bands.map((b) => ({
      grade: b.grade,
      minPercent: formatScore(b.minPercent),
      gradePoint: b.gradePoint === null ? "" : formatScore(b.gradePoint),
      remark: b.remark ?? "",
    })),
  };
}

/** The scheme as the API takes it, or the first problem (mirrors the API's checks). Pure, exported for tests. */
export function schemeRequest(values: SchemeText): { request: SchemeRequest } | { problem: MessageKey } {
  const name = values.name.trim();
  if (!name) return { problem: "exams.v.name" };
  if (name.length > NAME_MAX) return { problem: "exams.v.schemeNameLong" };
  if (values.description.length > DESCRIPTION_MAX) return { problem: "exams.v.descriptionLong" };
  const pass = parseDecimal(values.passPercent);
  const overall = parseDecimal(values.overallPassPercent);
  if (pass === null || Number.isNaN(pass) || pass > 100 || overall === null || Number.isNaN(overall) || overall > 100) {
    return { problem: "exams.v.passPercent" };
  }
  if (values.bands.length === 0 || values.bands.length > MAX_BANDS) return { problem: "exams.v.bandCount" };
  const grades = new Set<string>();
  const mins = new Set<number>();
  const bands = [];
  for (const band of values.bands) {
    const grade = band.grade.trim();
    if (!grade || grade.length > GRADE_MAX) return { problem: "exams.v.bandGrade" };
    if (grades.has(grade.toUpperCase())) return { problem: "exams.v.bandGradeTwice" };
    grades.add(grade.toUpperCase());
    const min = parseDecimal(band.minPercent);
    if (min === null || Number.isNaN(min) || min > 100) return { problem: "exams.v.bandMin" };
    if (mins.has(min)) return { problem: "exams.v.bandMinTwice" };
    mins.add(min);
    const point = band.gradePoint.trim() === "" ? null : Number(band.gradePoint);
    if (point !== null && (!/^\d{1,2}(\.\d)?$/.test(band.gradePoint.trim()) || point > 10)) {
      return { problem: "exams.v.bandPoint" };
    }
    if (band.remark.length > BAND_REMARK_MAX) return { problem: "exams.v.bandRemark" };
    bands.push({ grade, minPercent: min, gradePoint: point, remark: band.remark.trim() || null });
  }
  if (!mins.has(0)) return { problem: "exams.v.bandZero" };
  bands.sort((a, b) => b.minPercent - a.minPercent);
  return {
    request: {
      name,
      kind: values.kind,
      description: values.description.trim() || null,
      passPercent: pass,
      overallPassPercent: overall,
      bands,
    },
  };
}

function SchemeDialog({
  scheme,
  copyOf,
  onClose,
  onSaved,
}: {
  scheme: GradingScheme | null;
  copyOf: GradingScheme | null;
  onClose: () => void;
  onSaved: (scheme: GradingScheme) => void;
}) {
  const { t } = useI18n();
  const [values, setValues] = useState<SchemeText>(() =>
    schemeText(scheme, copyOf, copyOf ? t("exams.grading.copyName", { name: copyOf.name }) : ""),
  );
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = <K extends keyof SchemeText>(key: K, value: SchemeText[K]) => setValues((prev) => ({ ...prev, [key]: value }));
  const setBand = (index: number, change: Partial<BandText>) =>
    set(
      "bands",
      values.bands.map((band, i) => (i === index ? { ...band, ...change } : band)),
    );
  const scholastic = values.kind === "SCHOLASTIC";

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const checked = schemeRequest(values);
    if ("problem" in checked) {
      setError(t(checked.problem, { max: MAX_BANDS }));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const saved = scheme
        ? await examsApi.updateScheme(scheme.id, checked.request)
        : await examsApi.createScheme(checked.request);
      onSaved(saved);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={scheme ? t("exams.grading.edit") : t("exams.grading.add")}
      description={t("exams.grading.dialog")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={error} />
        <TextField
          label={t("exams.grading.name")}
          name="name"
          value={values.name}
          maxLength={NAME_MAX}
          onChange={(e) => set("name", e.target.value)}
          required
          data-autofocus
        />
        {!scheme && !copyOf ? (
          <div className="seg self-start" role="radiogroup" aria-label={t("exams.grading.kind")}>
            {(["SCHOLASTIC", "CO_SCHOLASTIC"] as const).map((kind) => (
              <label key={kind}>
                <input type="radio" name="kind" value={kind} checked={values.kind === kind} onChange={() => set("kind", kind)} />
                {t(`exams.kind.${kind}`)}
              </label>
            ))}
          </div>
        ) : null}
        <TextField
          label={t("exams.grading.description")}
          name="description"
          value={values.description}
          maxLength={DESCRIPTION_MAX}
          onChange={(e) => set("description", e.target.value)}
        />
        {scholastic ? (
          <div className="grid2">
            <TextField
              label={t("exams.grading.passSubject")}
              name="passPercent"
              inputMode="decimal"
              value={values.passPercent}
              onChange={(e) => set("passPercent", e.target.value)}
              hint={t("exams.grading.passHint")}
            />
            <TextField
              label={t("exams.grading.passOverall")}
              name="overallPassPercent"
              inputMode="decimal"
              value={values.overallPassPercent}
              onChange={(e) => set("overallPassPercent", e.target.value)}
            />
          </div>
        ) : null}
        <fieldset className="fieldset">
          <legend className="field-label">{t("exams.grading.bands")}</legend>
          <p className="field-hint">{scholastic ? t("exams.grading.bandsHint") : t("exams.grading.bandsHintCo")}</p>
          {values.bands.map((band, i) => (
            <div key={i} className="grid grid-cols-[1fr_1fr_1fr_auto] items-end gap-2" data-testid="band-row">
              <label className="field">
                <span className="field-label text-[12px]">{t("exams.col.grade")}</span>
                <input className="input" name={`grade-${i}`} value={band.grade} maxLength={GRADE_MAX} onChange={(e) => setBand(i, { grade: e.target.value })} />
              </label>
              <label className="field">
                <span className="field-label text-[12px]">{scholastic ? t("exams.band.from") : t("exams.band.order")}</span>
                <input className="input num" name={`min-${i}`} inputMode="decimal" value={band.minPercent} onChange={(e) => setBand(i, { minPercent: e.target.value })} />
              </label>
              <label className="field">
                <span className="field-label text-[12px]">{t("exams.band.point")}</span>
                <input className="input num" name={`point-${i}`} inputMode="decimal" value={band.gradePoint} onChange={(e) => setBand(i, { gradePoint: e.target.value })} />
              </label>
              <button
                type="button"
                className="iconbtn mb-1"
                disabled={values.bands.length < 2}
                onClick={() => set("bands", values.bands.filter((_, j) => j !== i))}
                aria-label={t("exams.grading.removeBand", { grade: band.grade || String(i + 1) })}
              >
                <X size={16} aria-hidden="true" />
              </button>
              <label className="field col-span-4">
                <span className="sr-only">{t("exams.band.remark")}</span>
                <input
                  className="input"
                  name={`remark-${i}`}
                  placeholder={t("exams.band.remarkPlaceholder")}
                  value={band.remark}
                  maxLength={BAND_REMARK_MAX}
                  onChange={(e) => setBand(i, { remark: e.target.value })}
                />
              </label>
            </div>
          ))}
          <button
            type="button"
            className="btn btn-sm self-start"
            disabled={values.bands.length >= MAX_BANDS}
            onClick={() => set("bands", [...values.bands, { grade: "", minPercent: "", gradePoint: "", remark: "" }])}
          >
            <Plus size={16} aria-hidden="true" />
            {t("exams.grading.addBand")}
          </button>
        </fieldset>
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
