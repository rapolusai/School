"use client";

import { ArrowLeft, Plus, Sparkles } from "lucide-react";
import Link from "next/link";
import { useMemo, useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { plural, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { PlanClass, SubjectPlan } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/** A row of the grid: a subject the school has (key = its id) or a new one to create (key = "new:" + name). */
export type PlanRow = { key: string; name: string; code: string | null; isNew: boolean };

/** Ticks per class: class id → row keys. */
export type Ticks = Record<string, string[]>;

export const newKey = (name: string) => `new:${name.trim().toLowerCase().replace(/\s+/g, " ")}`;

/** The school's subjects, then the board's usual subjects it does not have yet, then subjects typed in here. */
export function planRows(plan: SubjectPlan, extra: PlanRow[], ticks: Ticks): PlanRow[] {
  const rows: PlanRow[] = [];
  const seen = new Set<string>();
  const add = (row: PlanRow) => {
    const nameKey = newKey(row.name);
    if (seen.has(nameKey)) return;
    seen.add(nameKey);
    rows.push(row);
  };
  const ticked = new Set(Object.values(ticks).flat());
  const relevant = new Set(plan.classes.flatMap((c) => [...c.suggested, ...c.optional]).map(newKey));
  for (const s of plan.subjects) add({ key: s.id, name: s.name, code: s.code, isNew: false });
  for (const s of plan.standard) {
    if (s.subjectId) continue;
    const key = newKey(s.name);
    if (relevant.has(key) || ticked.has(key)) add({ key, name: s.name, code: s.code, isNew: true });
  }
  for (const row of extra) add(row);
  return rows;
}

/** The suggested (not optional) subjects of a class, as row keys. */
export function suggestedKeys(plan: SubjectPlan, planClass: PlanClass): string[] {
  const idByName = new Map(plan.subjects.map((s) => [newKey(s.name), s.id]));
  return planClass.suggested.map((name) => idByName.get(newKey(name)) ?? newKey(name));
}

export function initialTicks(plan: SubjectPlan): Ticks {
  return Object.fromEntries(plan.classes.map((c) => [c.id, [...c.subjectIds]]));
}

function sameSet(a: string[], b: string[]) {
  if (a.length !== b.length) return false;
  const set = new Set(a);
  return b.every((x) => set.has(x));
}

/** Subjects by class: tick what every class studies, start from the board's usual subjects, save once. */
export function SubjectPlanView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.settingsManage);
  const [board, setBoard] = useState<string | null>(null);
  const plan = useApiData(`academics:subject-plan:${board ?? "school"}`, () => api.getSubjectPlan(board ?? undefined));
  const [ticks, setTicks] = useState<Ticks | null>(null);
  const [saved, setSaved] = useState<Ticks | null>(null);
  const [extra, setExtra] = useState<PlanRow[]>([]);
  const [newName, setNewName] = useState("");
  const [newError, setNewError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The plan as it was when saved: ticks are read again only from a fresher one.
  const [stale, setStale] = useState<SubjectPlan | null>(null);

  const data = plan.data;
  // The school's ticks load once; changing the board's suggestions keeps what has been ticked so far.
  if (data && ticks === null && data !== stale) {
    const start = initialTicks(data);
    setTicks(start);
    setSaved(start);
  }
  const current = ticks ?? {};
  const rows = useMemo(() => (data ? planRows(data, extra, ticks ?? {}) : []), [data, extra, ticks]);
  const suggested = useMemo(() => {
    const map = new Map<string, Set<string>>();
    const optional = new Map<string, Set<string>>();
    if (data) {
      const idByName = new Map(data.subjects.map((s) => [newKey(s.name), s.id]));
      const keyOf = (name: string) => idByName.get(newKey(name)) ?? newKey(name);
      for (const c of data.classes) {
        map.set(c.id, new Set(c.suggested.map(keyOf)));
        optional.set(c.id, new Set(c.optional.map(keyOf)));
      }
    }
    return { map, optional };
  }, [data]);

  const changedClasses = data
    ? data.classes.filter((c) => !sameSet(current[c.id] ?? [], saved?.[c.id] ?? []))
    : [];

  const setClass = (classId: string, keys: string[]) => setTicks((prev) => ({ ...(prev ?? {}), [classId]: keys }));
  const toggle = (classId: string, key: string, on: boolean) => {
    const keys = current[classId] ?? [];
    setClass(classId, on ? [...keys, key] : keys.filter((k) => k !== key));
  };
  const setRow = (key: string, on: boolean) =>
    setTicks((prev) => {
      const next: Ticks = { ...(prev ?? {}) };
      for (const c of data?.classes ?? []) {
        const keys = next[c.id] ?? [];
        next[c.id] = on ? (keys.includes(key) ? keys : [...keys, key]) : keys.filter((k) => k !== key);
      }
      return next;
    });

  const fillStandard = () => {
    if (!data) return;
    let added = 0;
    const next: Ticks = { ...current };
    for (const c of data.classes) {
      const keys = new Set(next[c.id] ?? []);
      for (const key of suggestedKeys(data, c)) {
        if (!keys.has(key)) {
          keys.add(key);
          added++;
        }
      }
      next[c.id] = [...keys];
    }
    setTicks(next);
    toast(plural(t, "subjectPlan.filled", added));
  };

  const addSubject = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const name = newName.trim();
    if (!name) return setNewError(t("validation.required"));
    if (name.length > 100) return setNewError(t("validation.tooLong"));
    if (rows.some((r) => newKey(r.name) === newKey(name))) {
      return setNewError(t("subjectPlan.add.exists", { name }));
    }
    setExtra((prev) => [...prev, { key: newKey(name), name, code: null, isNew: true }]);
    setNewName("");
    setNewError(null);
  };

  const save = async () => {
    if (!data) return;
    setSaving(true);
    setError(null);
    const used = new Set(changedClasses.flatMap((c) => current[c.id] ?? []));
    const newSubjects = rows.filter((r) => r.isNew && used.has(r.key)).map((r) => ({ name: r.name, code: r.code }));
    const nameOf = new Map(rows.map((r) => [r.key, r.name]));
    try {
      const result = await api.saveSubjectPlan({
        newSubjects,
        classes: changedClasses.map((c) => {
          const keys = current[c.id] ?? [];
          return {
            classId: c.id,
            subjectIds: keys.filter((k) => !k.startsWith("new:")),
            newSubjects: keys.filter((k) => k.startsWith("new:")).map((k) => nameOf.get(k) ?? k.slice(4)),
          };
        }),
      });
      toast(t("subjectPlan.saved", { classes: result.classesChanged, subjects: result.subjectsCreated }));
      // Reload so new subjects come back with their ids; the ticks are read again from the school.
      setStale(data);
      setTicks(null);
      setSaved(null);
      setExtra([]);
      plan.reload();
    } catch (e) {
      setError(e instanceof ApiError ? errorMessage(e, t) : t("common.error.generic"));
    } finally {
      setSaving(false);
    }
  };

  const boardLabel = (b: string) => translateOr(t, `board.${b}`, b);

  return (
    <>
      <Link href="/app/setup" className="inline-flex items-center gap-1.5 text-sm font-semibold text-accent">
        <ArrowLeft size={16} aria-hidden="true" />
        {t("setup.title")}
      </Link>
      <PageHead eyebrow={t("setup.title")} title={t("subjectPlan.title")} />

      {plan.error && !data ? (
        <ErrorState error={plan.error} onRetry={plan.reload} />
      ) : !data || ticks === null ? (
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      ) : data.classes.length === 0 ? (
        <section className="card">
          <p className="empty">{t("subjectPlan.noClasses")}</p>
          <div className="flex flex-wrap justify-center gap-2">
            <Link href="/app/setup" className="btn">
              {t("subjectPlan.noClasses.setup")}
            </Link>
            <Link href="/app/setup/import" className="btn btn-primary">
              {t("subjectPlan.noClasses.import")}
            </Link>
          </div>
        </section>
      ) : (
        <>
          {canManage ? (
            <section className="card flex flex-col gap-3" aria-labelledby="subject-plan-start">
              <div className="flex items-start gap-3">
                <span className="badge-ic" style={{ background: "var(--accent-soft)", color: "var(--accent)" }}>
                  <Sparkles size={20} aria-hidden="true" />
                </span>
                <div className="min-w-0">
                  <h2 id="subject-plan-start">{t("subjectPlan.start.title")}</h2>
                  <p className="mt-1 text-sm text-ink-2">
                    {t("subjectPlan.start.body", { board: boardLabel(data.schoolBoard) })}
                  </p>
                </div>
              </div>
              <div className="flex flex-wrap items-end gap-2">
                <div className="field">
                  <label htmlFor="subject-plan-board">{t("subjectPlan.board")}</label>
                  <select
                    id="subject-plan-board"
                    className="input"
                    value={data.board}
                    onChange={(e) => setBoard(e.target.value === data.schoolBoard ? null : e.target.value)}
                  >
                    {data.boards.map((b) => (
                      <option key={b} value={b}>
                        {b === data.schoolBoard ? t("subjectPlan.board.yours", { board: boardLabel(b) }) : boardLabel(b)}
                      </option>
                    ))}
                  </select>
                </div>
                <button type="button" className="btn btn-primary" onClick={fillStandard}>
                  <Sparkles size={18} aria-hidden="true" />
                  {t("subjectPlan.fill")}
                </button>
              </div>
              <p className="field-hint">{t("subjectPlan.legend")}</p>
            </section>
          ) : null}

          <section className="card" aria-labelledby="subject-plan-grid">
            <div className="card-head">
              <div>
                <h2 id="subject-plan-grid">{t("subjectPlan.grid.title")}</h2>
                <p className="mt-1 text-sm text-ink-2">
                  {canManage ? t("subjectPlan.grid.sub") : t("subjectPlan.grid.subReadOnly")}
                </p>
              </div>
            </div>

            <div className="table-wrap">
              <table className="table subject-plan" data-testid="subject-plan-grid">
                <thead>
                  <tr>
                    <th scope="col" className="subject-plan-name">
                      {t("setup.subjects.name")}
                    </th>
                    {data.classes.map((c) => (
                      <th key={c.id} scope="col" className="text-center">
                        <span className="block normal-case">{c.name}</span>
                        {canManage && data.classes.length > 1 ? (
                          <select
                            className="input subject-plan-copy"
                            aria-label={t("subjectPlan.copy.label", { name: c.name })}
                            value=""
                            onChange={(e) => {
                              const from = e.target.value;
                              if (from) setClass(c.id, [...(current[from] ?? [])]);
                            }}
                          >
                            <option value="">{t("subjectPlan.copy")}</option>
                            {data.classes
                              .filter((o) => o.id !== c.id)
                              .map((o) => (
                                <option key={o.id} value={o.id}>
                                  {o.name}
                                </option>
                              ))}
                          </select>
                        ) : null}
                      </th>
                    ))}
                    {canManage ? (
                      <th scope="col" className="r">
                        <span className="sr-only">{t("common.actions")}</span>
                      </th>
                    ) : null}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.key}>
                      <th scope="row" className="subject-plan-name">
                        <span className="font-semibold normal-case text-ink">{row.name}</span>
                        {row.isNew ? <span className="pill pill-accent ml-2">{t("subjectPlan.new")}</span> : null}
                      </th>
                      {data.classes.map((c) => {
                        const on = (current[c.id] ?? []).includes(row.key);
                        const hint = suggested.map.get(c.id)?.has(row.key)
                          ? "suggested"
                          : suggested.optional.get(c.id)?.has(row.key)
                            ? "optional"
                            : undefined;
                        return (
                          <td key={c.id} className="text-center" data-hint={hint}>
                            <input
                              type="checkbox"
                              className="subject-plan-box"
                              checked={on}
                              disabled={!canManage}
                              aria-label={t("subjectPlan.cell", { subject: row.name, name: c.name })}
                              onChange={(e) => toggle(c.id, row.key, e.target.checked)}
                            />
                          </td>
                        );
                      })}
                      {canManage ? (
                        <td className="r whitespace-nowrap">
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm"
                            aria-label={t("subjectPlan.row.all", { subject: row.name })}
                            onClick={() => setRow(row.key, true)}
                          >
                            {t("subjectPlan.row.allShort")}
                          </button>
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm"
                            aria-label={t("subjectPlan.row.none", { subject: row.name })}
                            onClick={() => setRow(row.key, false)}
                          >
                            {t("subjectPlan.row.noneShort")}
                          </button>
                        </td>
                      ) : null}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {canManage ? (
              <form className="mt-4 flex flex-wrap items-start gap-2" onSubmit={addSubject} noValidate>
                <div className="field min-w-[220px] flex-1">
                  <label htmlFor="subject-plan-new">{t("subjectPlan.add.label")}</label>
                  <input
                    id="subject-plan-new"
                    className="input"
                    value={newName}
                    maxLength={100}
                    placeholder={t("setup.subjects.name.placeholder")}
                    aria-invalid={newError ? true : undefined}
                    aria-describedby={newError ? "subject-plan-new-error" : undefined}
                    onChange={(e) => {
                      setNewName(e.target.value);
                      setNewError(null);
                    }}
                  />
                  {newError ? (
                    <p id="subject-plan-new-error" className="field-error">
                      {newError}
                    </p>
                  ) : null}
                </div>
                <button type="submit" className="btn mt-[26px]">
                  <Plus size={18} aria-hidden="true" />
                  {t("subjectPlan.add")}
                </button>
              </form>
            ) : null}
          </section>

          {canManage ? (
            <div className="subject-plan-bar card" role="region" aria-label={t("subjectPlan.bar")}>
              <FormAlert message={error} />
              <p className="text-sm text-ink-2" aria-live="polite">
                {changedClasses.length === 0
                  ? t("subjectPlan.unchanged")
                  : plural(t, "subjectPlan.changed", changedClasses.length)}
              </p>
              <div className="flex gap-2">
                <button
                  type="button"
                  className="btn"
                  disabled={changedClasses.length === 0 || saving}
                  onClick={() => {
                    setTicks(saved);
                    setExtra([]);
                    setError(null);
                  }}
                >
                  {t("subjectPlan.undo")}
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={changedClasses.length === 0 || saving}
                  onClick={save}
                >
                  {saving ? t("common.saving") : t("common.save")}
                </button>
              </div>
            </div>
          ) : null}
        </>
      )}
    </>
  );
}
