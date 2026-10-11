"use client";

import { Search, UserPlus, Users } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useClasses } from "@/components/views/reports/report-frame";
import { isApiError } from "@/lib/api";
import { formatPaise, formatPlainDate, initials, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n, type Translate } from "@/lib/i18n";
import { transportApi, type AllocationQuery } from "@/lib/transport-api";
import type { Allocation, BulkAllocationResult, RouteSummary, TransportStudentOption } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";
import { TransportNav, useCanManageTransport } from "./transport-shared";

/* /app/transport/allocations: which students travel on which route and stop, from when to when. */

/** The API's problem type for a full bus; the form then asks for a reason to allocate anyway. */
export const ROUTE_FULL_TYPE = "urn:akshara:problem:route-full";

type Status = NonNullable<AllocationQuery["status"]>;

/** "Since 1 Jun 2026", "From 14 Oct 2026", "1 Jun – 30 Sept 2026" or "Ended 30 Sept 2026". Pure and exported for tests. */
export function allocationDates(t: Translate, a: Pick<Allocation, "startDate" | "endDate" | "status" | "current">, locale: string): string {
  const start = formatPlainDate(a.startDate, locale);
  if (a.status === "ENDED") return t("transport.alloc.ended", { date: a.endDate ? formatPlainDate(a.endDate, locale) : start });
  if (a.endDate) return t("transport.alloc.range", { from: start, to: formatPlainDate(a.endDate, locale) });
  return a.current ? t("transport.alloc.since", { date: start }) : t("transport.alloc.from", { date: start });
}

export function AllocationsView({ initialRouteId }: { initialRouteId?: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageTransport();
  const classes = useClasses();
  const routes = useApiData("transport:routes", transportApi.routes);
  const [routeId, setRouteId] = useState(initialRouteId ?? "");
  const [classId, setClassId] = useState("");
  const [status, setStatus] = useState<Status>("ACTIVE");
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  const params: AllocationQuery = { routeId: routeId || undefined, classId: classId || undefined, status, q: q.trim() || undefined };
  const list = useApiData(`transport:allocations:${JSON.stringify(params)}`, () => transportApi.allocations(params));
  const [dialog, setDialog] = useState<null | "one" | "class">(null);
  const [changing, setChanging] = useState<Allocation | null>(null);
  const [ending, setEnding] = useState<Allocation | null>(null);
  const rows = list.data ?? [];
  const routeList = routes.data ?? [];

  const refresh = () => {
    list.reload();
    routes.reload();
  };

  return (
    <>
      <PageHead
        eyebrow={list.data ? plural(t, "transport.alloc.eyebrow", rows.length) : t("nav.transport")}
        title={t("transport.alloc.title")}
        actions={
          canManage ? (
            <>
              <button type="button" className="btn" onClick={() => setDialog("class")} data-testid="alloc-class">
                <Users size={18} aria-hidden="true" />
                {t("transport.alloc.byClass")}
              </button>
              <button type="button" className="btn btn-primary" onClick={() => setDialog("one")} data-testid="alloc-one">
                <UserPlus size={18} aria-hidden="true" />
                {t("transport.alloc.new")}
              </button>
            </>
          ) : null
        }
      />
      <TransportNav />
      <section className="card flex flex-col gap-3" aria-label={t("transport.alloc.title")} data-testid="allocations">
        <form method="post" className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label={t("transport.alloc.filters")}>
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("transport.alloc.search")}</span>
            <input
              type="search"
              name="q"
              value={text}
              placeholder={t("transport.alloc.search")}
              maxLength={100}
              autoComplete="off"
              onChange={(e) => {
                const value = e.target.value;
                setText(value);
                if (timer.current) clearTimeout(timer.current);
                timer.current = setTimeout(() => setQ(value), 250);
              }}
            />
          </label>
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("transport.col.route")}</span>
            <select className="input" name="routeId" value={routeId} onChange={(e) => setRouteId(e.target.value)}>
              <option value="">{t("transport.alloc.allRoutes")}</option>
              {routeList.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-36">
            <span className="field-label">{t("reports.filter.class")}</span>
            <select className="input" name="classId" value={classId} onChange={(e) => setClassId(e.target.value)}>
              <option value="">{t("reports.filter.allClasses")}</option>
              {classes.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-32">
            <span className="field-label">{t("transport.alloc.status")}</span>
            <select className="input" name="status" value={status} onChange={(e) => setStatus(e.target.value as Status)}>
              <option value="ACTIVE">{t("transport.alloc.status.ACTIVE")}</option>
              <option value="ENDED">{t("transport.alloc.status.ENDED")}</option>
              <option value="ALL">{t("transport.alloc.status.ALL")}</option>
            </select>
          </label>
        </form>
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={5} />
        ) : rows.length === 0 ? (
          <p className="empty">{params.q || params.routeId || params.classId ? t("transport.alloc.noMatch") : t("transport.alloc.empty")}</p>
        ) : (
          <>
            <div className="table-wrap hidden md:block">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">{t("transport.col.student")}</th>
                    <th scope="col">{t("transport.col.routeStop")}</th>
                    <th scope="col">{t("transport.stop.zone")}</th>
                    <th scope="col">{t("transport.col.dates")}</th>
                    {canManage ? (
                      <th scope="col">
                        <span className="sr-only">{t("common.actions")}</span>
                      </th>
                    ) : null}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((a) => (
                    <tr key={a.id} data-testid="allocation-row">
                      <td>
                        <b className="block">{a.student.name}</b>
                        <span className="block text-[12.5px] text-ink-3">
                          {[a.student.classLabel, a.student.admissionNo].filter(Boolean).join(" · ")}
                        </span>
                      </td>
                      <td>
                        <span className="block">{a.route.name}</span>
                        <span className="block text-[12.5px] text-ink-3">{a.stop.name}</span>
                        {a.overCapacityReason ? (
                          <span className="block text-[12px] text-warn">{t("transport.alloc.overNote", { reason: a.overCapacityReason })}</span>
                        ) : null}
                      </td>
                      <td>
                        {a.feeZone ? (
                          <>
                            <span className="block">{a.feeZone.name}</span>
                            <span className="block text-[12.5px] text-ink-3">{t("transport.zone.perYear", { amount: formatPaise(a.feeZone.annualFeePaise) })}</span>
                          </>
                        ) : (
                          "—"
                        )}
                      </td>
                      <td>
                        <span className="block whitespace-nowrap">{allocationDates(t, a, locale)}</span>
                        {a.status === "ENDED" && a.endReason ? <span className="block text-[12.5px] text-ink-3">{a.endReason}</span> : null}
                      </td>
                      {canManage ? (
                        <td className="r whitespace-nowrap">
                          {a.status === "ACTIVE" ? (
                            <span className="flex justify-end gap-1.5">
                              <button type="button" className="btn btn-sm" onClick={() => setChanging(a)}>
                                {t("transport.alloc.change")}
                              </button>
                              <button type="button" className="btn btn-sm" onClick={() => setEnding(a)}>
                                {t("transport.alloc.end")}
                              </button>
                            </span>
                          ) : (
                            <Pill>{t("transport.alloc.status.ENDED")}</Pill>
                          )}
                        </td>
                      ) : null}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden">
              {rows.map((a) => (
                <li key={a.id} className="rowcard items-start" data-testid="allocation-card">
                  <span className="min-w-0 flex-1">
                    <b className="block">{a.student.name}</b>
                    <span className="block text-[12.5px] text-ink-3">{a.student.classLabel}</span>
                    <span className="block text-[13px]">
                      {a.route.name} · {a.stop.name}
                    </span>
                    <span className="block text-[12.5px] text-ink-3">{allocationDates(t, a, locale)}</span>
                  </span>
                  {canManage && a.status === "ACTIVE" ? (
                    <span className="flex flex-col items-end gap-1.5">
                      <button type="button" className="btn btn-sm" onClick={() => setChanging(a)}>
                        {t("transport.alloc.change")}
                      </button>
                      <button type="button" className="btn btn-sm" onClick={() => setEnding(a)}>
                        {t("transport.alloc.end")}
                      </button>
                    </span>
                  ) : a.status === "ENDED" ? (
                    <Pill>{t("transport.alloc.status.ENDED")}</Pill>
                  ) : null}
                </li>
              ))}
            </ul>
          </>
        )}
      </section>

      {dialog === "one" ? (
        <AllocateDialog
          routes={routeList}
          initialRouteId={routeId}
          onClose={() => setDialog(null)}
          onSaved={(a) => {
            setDialog(null);
            toast(t("transport.alloc.saved", { name: a.student.name, route: a.route.name }));
            refresh();
          }}
        />
      ) : null}
      {dialog === "class" ? (
        <BulkDialog
          routes={routeList}
          initialRouteId={routeId}
          onClose={() => {
            setDialog(null);
            refresh();
          }}
        />
      ) : null}
      {changing ? (
        <ChangeDialog
          allocation={changing}
          onClose={() => setChanging(null)}
          onSaved={() => {
            setChanging(null);
            toast(t("transport.alloc.changed"));
            refresh();
          }}
        />
      ) : null}
      {ending ? (
        <EndDialog
          allocation={ending}
          onClose={() => setEnding(null)}
          onSaved={() => {
            setEnding(null);
            toast(t("transport.alloc.endedToast", { name: ending.student.name }));
            refresh();
          }}
        />
      ) : null}
    </>
  );
}

/* ------------------------------------------------------------------ route and stop */

function useRouteStops(routeId: string) {
  return useApiData(routeId ? `transport:route:${routeId}` : null, () => transportApi.route(routeId));
}

function RouteStopFields({
  routes,
  routeId,
  stopId,
  onRoute,
  onStop,
  errors,
  routeLocked = false,
}: {
  routes: RouteSummary[];
  routeId: string;
  stopId: string;
  onRoute: (id: string) => void;
  onStop: (id: string) => void;
  errors: Record<string, string | undefined>;
  routeLocked?: boolean;
}) {
  const { t } = useI18n();
  const route = useRouteStops(routeId);
  const selected = routes.find((r) => r.id === routeId);
  const stops = route.data?.stops ?? [];
  return (
    <>
      <SelectField
        label={t("transport.col.route")}
        name="routeId"
        value={routeId}
        disabled={routeLocked}
        onChange={(e) => onRoute(e.target.value)}
        error={errors.routeId}
        hint={selected?.seats ? t("transport.alloc.seatsHint", { taken: selected.allocated, seats: selected.seats }) : undefined}
        options={[
          { value: "", label: t("common.choose") },
          ...routes.filter((r) => r.active || r.id === routeId).map((r) => ({ value: r.id, label: r.name })),
        ]}
      />
      <SelectField
        label={t("transport.col.stop")}
        name="stopId"
        value={stopId}
        disabled={!routeId || !route.data}
        onChange={(e) => onStop(e.target.value)}
        error={errors.stopId ?? (route.error ? t("transport.alloc.stopsFailed") : undefined)}
        options={[
          { value: "", label: routeId && !route.data && !route.error ? t("common.loading") : t("common.choose") },
          ...stops.map((s) => ({
            value: s.id,
            label: [s.name, s.feeZone ? `${s.feeZone.name} · ${formatPaise(s.feeZone.annualFeePaise)}` : null].filter(Boolean).join(" — "),
          })),
        ]}
      />
    </>
  );
}

function OverCapacityField({ value, onChange, error }: { value: string; onChange: (value: string) => void; error?: string }) {
  const { t } = useI18n();
  return (
    <TextAreaField
      label={t("transport.alloc.overReason")}
      name="overCapacityReason"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      error={error}
      hint={t("transport.alloc.overHint")}
      maxLength={300}
      rows={2}
    />
  );
}

type AllocateValues = { studentId: string; routeId: string; stopId: string; startDate: string; endDate: string; overCapacityReason: string };

/** The same checks the API makes on an allocation. Pure and exported for tests. */
export function validateAllocation(values: Omit<AllocateValues, "studentId"> & { studentId?: string }, needStudent = true): Problems {
  const problems: Problems = {};
  if (needStudent && !values.studentId) problems.studentId = "transport.v.student";
  if (!values.routeId) problems.routeId = "validation.choose";
  if (!values.stopId) problems.stopId = "validation.choose";
  if (!isPlainDate(values.startDate)) problems.startDate = "validation.date";
  if (values.endDate && !isPlainDate(values.endDate)) problems.endDate = "validation.date";
  else if (values.endDate && isPlainDate(values.startDate) && values.endDate < values.startDate) problems.endDate = "transport.v.endBeforeStart";
  if (values.overCapacityReason.trim().length > 300) problems.overCapacityReason = "validation.tooLong";
  return problems;
}

function StudentPicker({ picked, onPick, error }: { picked: TransportStudentOption | null; onPick: (s: TransportStudentOption | null) => void; error?: string }) {
  const { t } = useI18n();
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  const q = query.trim();
  const ready = q.length >= 2;
  const results = useApiData(ready ? `transport:students:${q}` : null, () => transportApi.students({ q }));
  if (picked) {
    return (
      <div className="field">
        <span className="field-label">{t("transport.col.student")}</span>
        <div className="rowcard" data-testid="alloc-picked">
          <span className="avatar" aria-hidden="true">
            {initials(picked.name)}
          </span>
          <span className="min-w-0 flex-1">
            <b className="block">{picked.name}</b>
            <span className="block text-[12.5px] text-ink-3">{[picked.classLabel, picked.admissionNo].filter(Boolean).join(" · ")}</span>
          </span>
          <button type="button" className="btn btn-sm" onClick={() => onPick(null)}>
            {t("transport.alloc.changeStudent")}
          </button>
        </div>
      </div>
    );
  }
  const hits = results.data ?? [];
  return (
    <div className="field" data-testid="alloc-student-picker">
      <label className="search max-w-none flex-none">
        <Search size={18} aria-hidden="true" />
        <span className="sr-only">{t("transport.alloc.findStudent")}</span>
        <input
          type="search"
          name="studentId"
          value={text}
          placeholder={t("transport.alloc.findStudent")}
          maxLength={100}
          autoComplete="off"
          aria-invalid={error ? true : undefined}
          onChange={(e) => {
            const value = e.target.value;
            setText(value);
            if (timer.current) clearTimeout(timer.current);
            timer.current = setTimeout(() => setQuery(value), 250);
          }}
        />
      </label>
      {error ? <p className="field-error">{error}</p> : null}
      {!q ? null : !ready ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("transport.alloc.typeMore")}
        </p>
      ) : results.error && !results.data ? (
        <ErrorState error={results.error} onRetry={results.reload} />
      ) : results.loading && !results.data ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("common.loading")}
        </p>
      ) : hits.length === 0 ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("transport.alloc.noStudents", { query: q })}
        </p>
      ) : (
        <ul className="pick-list" aria-label={t("transport.alloc.results")}>
          {hits.map((hit) => (
            <li key={hit.id}>
              <button type="button" className="pick" onClick={() => onPick(hit)} disabled={hit.allocation !== null}>
                <span className="avatar" aria-hidden="true">
                  {initials(hit.name)}
                </span>
                <span className="min-w-0 flex-1 text-left">
                  <b className="block truncate font-semibold">{hit.name}</b>
                  <span className="block truncate text-[12.5px] text-ink-3">
                    {hit.allocation
                      ? t("transport.alloc.alreadyOn", { route: hit.allocation.routeName, stop: hit.allocation.stopName })
                      : [hit.classLabel, hit.admissionNo].filter(Boolean).join(" · ")}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function AllocateDialog({
  routes,
  initialRouteId,
  onClose,
  onSaved,
}: {
  routes: RouteSummary[];
  initialRouteId: string;
  onClose: () => void;
  onSaved: (a: Allocation) => void;
}) {
  const { t } = useI18n();
  const [student, setStudent] = useState<TransportStudentOption | null>(null);
  const [needsReason, setNeedsReason] = useState(false);
  const form = useForm<AllocateValues>({
    studentId: "",
    routeId: initialRouteId,
    stopId: "",
    startDate: todayInIndia(),
    endDate: "",
    overCapacityReason: "",
  });
  const { values, set, errors } = form;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateAllocation(values), t, event.currentTarget)) return;
    const result: { allocation?: Allocation } = {};
    const ok = await form.submit(t, async () => {
      try {
        result.allocation = await transportApi.allocate({
          studentId: values.studentId,
          routeId: values.routeId,
          stopId: values.stopId,
          startDate: values.startDate,
          endDate: values.endDate || null,
          overCapacityReason: values.overCapacityReason.trim() || null,
        });
      } catch (caught) {
        if (isApiError(caught) && caught.type === ROUTE_FULL_TYPE) setNeedsReason(true);
        throw caught;
      }
    });
    if (ok && result.allocation) onSaved(result.allocation);
  };

  return (
    <Dialog open onClose={onClose} title={t("transport.alloc.new")} description={t("transport.alloc.newIntro")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="allocate-form">
        <FormAlert message={form.formError} />
        <StudentPicker
          picked={student}
          error={errors.studentId}
          onPick={(s) => {
            setStudent(s);
            set("studentId", s?.id ?? "");
          }}
        />
        <RouteStopFields
          routes={routes}
          routeId={values.routeId}
          stopId={values.stopId}
          errors={errors}
          onRoute={(id) => {
            set("routeId", id);
            set("stopId", "");
            setNeedsReason(false);
          }}
          onStop={(id) => set("stopId", id)}
        />
        <div className="grid2">
          <TextField
            label={t("transport.alloc.startDate")}
            name="startDate"
            type="date"
            value={values.startDate}
            onChange={(e) => set("startDate", e.target.value)}
            error={errors.startDate}
          />
          <TextField
            label={t("transport.alloc.endDate")}
            name="endDate"
            type="date"
            value={values.endDate}
            onChange={(e) => set("endDate", e.target.value)}
            error={errors.endDate}
            hint={t("transport.alloc.endDateHint")}
          />
        </div>
        {needsReason ? (
          <OverCapacityField value={values.overCapacityReason} onChange={(v) => set("overCapacityReason", v)} error={errors.overCapacityReason} />
        ) : null}
        <p className="text-[12.5px] text-ink-3">{t("transport.alloc.feeNote")}</p>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : needsReason ? t("transport.alloc.allocateAnyway") : t("transport.alloc.allocate")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type BulkValues = Omit<AllocateValues, "studentId"> & { classId: string; sectionId: string };

function BulkDialog({ routes, initialRouteId, onClose }: { routes: RouteSummary[]; initialRouteId: string; onClose: () => void }) {
  const { t } = useI18n();
  const classes = useClasses();
  const [needsReason, setNeedsReason] = useState(false);
  const [left, setLeft] = useState<Set<string>>(new Set());
  const [done, setDone] = useState<BulkAllocationResult | null>(null);
  const form = useForm<BulkValues>({
    classId: "",
    sectionId: "",
    routeId: initialRouteId,
    stopId: "",
    startDate: todayInIndia(),
    endDate: "",
    overCapacityReason: "",
  });
  const { values, set, errors } = form;
  const klass = classes.find((c) => c.id === values.classId);
  const students = useApiData(
    values.classId ? `transport:students:class:${values.classId}:${values.sectionId}` : null,
    () => transportApi.students({ classId: values.classId, sectionId: values.sectionId || undefined }),
  );
  const free = (students.data ?? []).filter((s) => s.allocation === null);
  const chosen = free.filter((s) => !left.has(s.id));

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems = validateAllocation(values, false);
    if (!values.classId) problems.classId = "validation.choose";
    if (values.classId && students.data && chosen.length === 0) problems.studentIds = "transport.v.noneChosen";
    if (!form.check(problems, t, event.currentTarget)) return;
    const result: { done?: BulkAllocationResult } = {};
    const ok = await form.submit(t, async () => {
      try {
        result.done = await transportApi.allocateClass({
          classId: values.classId,
          sectionId: values.sectionId || null,
          routeId: values.routeId,
          stopId: values.stopId,
          startDate: values.startDate,
          endDate: values.endDate || null,
          studentIds: left.size > 0 ? chosen.map((s) => s.id) : null,
          overCapacityReason: values.overCapacityReason.trim() || null,
        });
      } catch (caught) {
        if (isApiError(caught) && caught.type === ROUTE_FULL_TYPE) setNeedsReason(true);
        throw caught;
      }
    });
    if (ok && result.done) setDone(result.done);
  };

  if (done) {
    return (
      <Dialog open onClose={onClose} title={t("transport.alloc.byClass")} closeLabel={t("common.close")}>
        <div className="flex flex-col gap-3" data-testid="bulk-result">
          <p className="alert alert-good">{plural(t, "transport.alloc.bulkDone", done.allocated)}</p>
          {done.skipped.length > 0 ? (
            <>
              <p className="text-[13px] text-ink-2">{plural(t, "transport.alloc.bulkSkipped", done.skipped.length)}</p>
              <ul className="flex flex-col gap-1 text-[13px]">
                {done.skipped.map((s) => (
                  <li key={s.studentId}>
                    <b>{s.name}</b> <span className="text-ink-3">· {s.reason}</span>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
          <div className="flex justify-end">
            <button type="button" className="btn btn-primary" onClick={onClose}>
              {t("common.done")}
            </button>
          </div>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog open onClose={onClose} title={t("transport.alloc.byClass")} description={t("transport.alloc.byClassIntro")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="bulk-form">
        <FormAlert message={form.formError} />
        <div className="grid2">
          <SelectField
            label={t("reports.filter.class")}
            name="classId"
            value={values.classId}
            onChange={(e) => {
              set("classId", e.target.value);
              set("sectionId", "");
              setLeft(new Set());
            }}
            error={errors.classId}
            options={[{ value: "", label: t("common.choose") }, ...classes.map((c) => ({ value: c.id, label: c.name }))]}
          />
          <SelectField
            label={t("transport.alloc.section")}
            name="sectionId"
            value={values.sectionId}
            disabled={!klass}
            onChange={(e) => {
              set("sectionId", e.target.value);
              setLeft(new Set());
            }}
            error={errors.sectionId}
            options={[{ value: "", label: t("transport.alloc.allSections") }, ...(klass?.sections ?? []).map((s) => ({ value: s.id, label: s.name }))]}
          />
        </div>
        <RouteStopFields
          routes={routes}
          routeId={values.routeId}
          stopId={values.stopId}
          errors={errors}
          onRoute={(id) => {
            set("routeId", id);
            set("stopId", "");
            setNeedsReason(false);
          }}
          onStop={(id) => set("stopId", id)}
        />
        <div className="grid2">
          <TextField
            label={t("transport.alloc.startDate")}
            name="startDate"
            type="date"
            value={values.startDate}
            onChange={(e) => set("startDate", e.target.value)}
            error={errors.startDate}
          />
          <TextField
            label={t("transport.alloc.endDate")}
            name="endDate"
            type="date"
            value={values.endDate}
            onChange={(e) => set("endDate", e.target.value)}
            error={errors.endDate}
          />
        </div>
        {values.classId ? (
          <fieldset className="fieldset" data-testid="bulk-students">
            <legend>{t("transport.alloc.whichStudents")}</legend>
            {students.error && !students.data ? (
              <ErrorState error={students.error} onRetry={students.reload} />
            ) : !students.data ? (
              <LoadingRows rows={2} />
            ) : students.data.length === 0 ? (
              <p className="text-[13px] text-ink-3">{t("transport.alloc.classEmpty")}</p>
            ) : (
              <ul className="flex max-h-60 flex-col gap-1 overflow-y-auto">
                {students.data.map((s) => (
                  <li key={s.id}>
                    <label className="check">
                      <input
                        type="checkbox"
                        name="studentIds"
                        value={s.id}
                        disabled={s.allocation !== null}
                        checked={s.allocation === null && !left.has(s.id)}
                        onChange={(e) =>
                          setLeft((prev) => {
                            const next = new Set(prev);
                            if (e.target.checked) next.delete(s.id);
                            else next.add(s.id);
                            return next;
                          })
                        }
                      />
                      <span>
                        {s.name}
                        <span className="text-[12.5px] text-ink-3">
                          {" "}
                          · {s.allocation ? t("transport.alloc.alreadyOn", { route: s.allocation.routeName, stop: s.allocation.stopName }) : s.classLabel}
                        </span>
                      </span>
                    </label>
                  </li>
                ))}
              </ul>
            )}
            {errors.studentIds ? <p className="field-error">{errors.studentIds}</p> : null}
            {students.data && students.data.length > 0 ? (
              <p className="field-hint">{t("transport.alloc.chosen", { chosen: chosen.length, total: free.length })}</p>
            ) : null}
          </fieldset>
        ) : null}
        {needsReason ? (
          <OverCapacityField value={values.overCapacityReason} onChange={(v) => set("overCapacityReason", v)} error={errors.overCapacityReason} />
        ) : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting
              ? t("common.saving")
              : needsReason
                ? t("transport.alloc.allocateAnyway")
                : t("transport.alloc.allocateN", { count: chosen.length })}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function ChangeDialog({ allocation, onClose, onSaved }: { allocation: Allocation; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const route = useRouteStops(allocation.route.id);
  const form = useForm({ stopId: allocation.stop.id, endDate: allocation.endDate ?? "" });
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (!values.stopId) problems.stopId = "validation.choose";
    if (values.endDate && !isPlainDate(values.endDate)) problems.endDate = "validation.date";
    else if (values.endDate && values.endDate < allocation.startDate) problems.endDate = "transport.v.endBeforeStart";
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await transportApi.updateAllocation(allocation.id, { stopId: values.stopId, endDate: values.endDate || null });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("transport.alloc.changeTitle", { name: allocation.student.name })}
      description={t("transport.alloc.changeIntro", { route: allocation.route.name })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="change-form">
        <FormAlert message={form.formError} />
        <SelectField
          label={t("transport.col.stop")}
          name="stopId"
          value={values.stopId}
          onChange={(e) => set("stopId", e.target.value)}
          error={errors.stopId}
          options={(route.data?.stops ?? [{ id: allocation.stop.id, name: allocation.stop.name, feeZone: null }]).map((s) => ({
            value: s.id,
            label: [s.name, s.feeZone ? `${s.feeZone.name} · ${formatPaise(s.feeZone.annualFeePaise)}` : null].filter(Boolean).join(" — "),
          }))}
        />
        <TextField
          label={t("transport.alloc.endDate")}
          name="endDate"
          type="date"
          value={values.endDate}
          onChange={(e) => set("endDate", e.target.value)}
          error={errors.endDate}
          hint={t("transport.alloc.endDateHint")}
        />
        <p className="text-[12.5px] text-ink-3">{t("transport.alloc.moveRouteNote")}</p>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function EndDialog({ allocation, onClose, onSaved }: { allocation: Allocation; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const today = todayInIndia();
  const form = useForm({ endDate: today < allocation.startDate ? allocation.startDate : today, reason: "" });
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (!isPlainDate(values.endDate)) problems.endDate = "validation.date";
    else if (values.endDate < allocation.startDate) problems.endDate = "transport.v.endBeforeStart";
    if (values.reason.trim().length > 300) problems.reason = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await transportApi.endAllocation(allocation.id, { endDate: values.endDate, reason: values.reason.trim() || null });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("transport.alloc.endTitle", { name: allocation.student.name })}
      description={t("transport.alloc.endIntro")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="end-form">
        <FormAlert message={form.formError} />
        <TextField
          label={t("transport.alloc.lastDay")}
          name="endDate"
          type="date"
          value={values.endDate}
          onChange={(e) => set("endDate", e.target.value)}
          error={errors.endDate}
        />
        <TextAreaField
          label={t("transport.alloc.endReason")}
          name="reason"
          value={values.reason}
          onChange={(e) => set("reason", e.target.value)}
          error={errors.reason}
          maxLength={300}
          rows={2}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("transport.alloc.end")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
