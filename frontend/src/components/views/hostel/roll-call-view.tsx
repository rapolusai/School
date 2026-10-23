"use client";

import { MoonStar } from "lucide-react";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { hostelApi } from "@/lib/hostel-api";
import { plural, useI18n } from "@/lib/i18n";
import { ROLL_CALL_STATUSES, type RollCallRow, type RollCallSheet, type RollCallStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { HostelNav, HostelPicker, ROLL_CALL_TONE, rollCallLabel, useChosenHostel, useLocale, when } from "./hostel-shared";

/* /app/hostel/roll-call: the warden's night roll call on a phone, one big row per boarder; the register below. */

type Mark = { status: RollCallStatus | null; note: string };
type Marks = Record<string, Mark>;

/** The form's marks from the sheet: saved marks, else the suggestion (ON_LEAVE for a boarder away on a pass). */
export function initialMarks(rows: RollCallRow[]): Marks {
  const marks: Marks = {};
  for (const row of rows) marks[row.studentId] = { status: row.status ?? (row.suggestedOnLeave ? "ON_LEAVE" : null), note: row.note ?? "" };
  return marks;
}

/** The boarders still unmarked. */
export function unmarked(marks: Marks): number {
  return Object.values(marks).filter((m) => m.status === null).length;
}

/** The counts of a set of marks. */
export function tally(marks: Marks): Record<RollCallStatus, number> {
  const counts: Record<RollCallStatus, number> = { PRESENT: 0, ON_LEAVE: 0, ABSENT: 0 };
  for (const m of Object.values(marks)) if (m.status) counts[m.status]++;
  return counts;
}

export function RollCallView() {
  const { t } = useI18n();
  const { hostels, list, hostel, choose } = useChosenHostel();
  const [night, setNight] = useState(() => todayInIndia());
  const [saves, setSaves] = useState(0);
  return (
    <>
      <PageHead eyebrow={t("nav.hostel")} title={t("hostel.rollCall.title")} />
      <HostelNav />
      {hostels.error && !hostels.data ? (
        <ErrorState error={hostels.error} onRetry={hostels.reload} />
      ) : !hostels.data ? (
        <LoadingRows rows={3} />
      ) : !hostel ? (
        <p className="empty">{t("hostel.hostels.empty")}</p>
      ) : (
        <>
          <div className="flex flex-wrap items-end gap-3">
            <HostelPicker list={list} value={hostel.id} onChange={choose} />
            <label className="field">
              <span className="field-label">{t("hostel.rollCall.night")}</span>
              <input type="date" className="input" name="night" value={night} max={todayInIndia()} onChange={(e) => setNight(e.target.value)} data-testid="roll-call-night" />
            </label>
          </div>
          <Sheet key={`${hostel.id}:${night}`} hostelId={hostel.id} night={night} onSaved={() => setSaves((n) => n + 1)} />
          <Register key={`register:${hostel.id}:${saves}`} hostelId={hostel.id} />
        </>
      )}
    </>
  );
}

function Sheet({ hostelId, night, onSaved }: { hostelId: string; night: string; onSaved: () => void }) {
  const sheet = useApiData(`hostel:rollcall:${hostelId}:${night}`, () => hostelApi.rollCall(hostelId, night));
  const data = sheet.data;
  if (sheet.error && !data) return <ErrorState error={sheet.error} onRetry={sheet.reload} />;
  if (!data) return <LoadingRows rows={4} />;
  return (
    <SheetForm
      key={`${data.takenAt ?? ""}:${data.rows.length}`}
      sheet={data}
      onSaved={() => {
        sheet.reload();
        onSaved();
      }}
    />
  );
}

function SheetForm({ sheet, onSaved }: { sheet: RollCallSheet; onSaved: () => void }) {
  const { t } = useI18n();
  const locale = useLocale();
  const { toast } = useToast();
  const form = useForm<Marks>(initialMarks(sheet.rows));
  const left = unmarked(form.values);
  const counts = tally(form.values);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (left > 0) {
      form.setFormError(plural(t, "hostel.rollCall.unmarked", left));
      return;
    }
    const entries = sheet.rows.map((row) => {
      const m = form.values[row.studentId];
      return { studentId: row.studentId, status: m.status as RollCallStatus, note: m.note.trim() || null };
    });
    const ok = await form.submit(t, async () => {
      await hostelApi.takeRollCall(sheet.hostelId, sheet.night, { entries });
    });
    if (ok) {
      toast(counts.ABSENT > 0 ? plural(t, "hostel.rollCall.savedAbsent", counts.ABSENT) : t("hostel.rollCall.saved"));
      onSaved();
    }
  };
  const markAll = (status: RollCallStatus) => {
    const next: Marks = {};
    for (const row of sheet.rows) next[row.studentId] = { ...form.values[row.studentId], status: form.values[row.studentId].status ?? status };
    form.setValues(next);
  };
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="roll-call-sheet" data-testid="roll-call-sheet">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h2 id="roll-call-sheet" className="flex items-center gap-2">
            <MoonStar size={18} className="text-ink-3" aria-hidden="true" />
            {t("hostel.rollCall.nightOf", { date: formatPlainDate(sheet.night, locale) })}
          </h2>
          <p className="text-[13px] text-ink-3" data-testid="roll-call-state">
            {sheet.taken ? t("hostel.rollCall.takenBy", { who: sheet.takenByName ?? "", time: when(sheet.takenAt, locale) }) : t("hostel.rollCall.notTaken")}
          </p>
        </div>
        <div className="flex flex-wrap gap-1.5" data-testid="roll-call-counts">
          <Pill tone="good">{t("hostel.rollCall.PRESENT")}: {counts.PRESENT}</Pill>
          <Pill tone="info">{t("hostel.rollCall.ON_LEAVE")}: {counts.ON_LEAVE}</Pill>
          <Pill tone="bad">{t("hostel.rollCall.ABSENT")}: {counts.ABSENT}</Pill>
        </div>
      </div>
      {sheet.rows.length === 0 ? (
        <p className="empty" data-testid="roll-call-empty">
          {t("hostel.rollCall.noBoarders")}
        </p>
      ) : (
        <form method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="roll-call-form">
          <FormAlert message={form.formError} />
          {sheet.canMark ? (
            <div className="flex flex-wrap gap-2">
              <button type="button" className="btn btn-sm" onClick={() => markAll("PRESENT")} data-testid="roll-call-all-present">
                {t("hostel.rollCall.restPresent")}
              </button>
              <span className="text-[13px] text-ink-3 self-center">{plural(t, "hostel.rollCall.unmarked", left)}</span>
            </div>
          ) : null}
          <ul className="rowcards">
            {sheet.rows.map((row) => {
              const mark = form.values[row.studentId];
              return (
                <li key={row.studentId} className="rowcard flex-col items-stretch gap-2" data-testid="roll-call-row" data-status={mark.status ?? ""}>
                  <div className="flex items-center gap-2">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate">{row.studentName}</b>
                      <span className="block text-[12.5px] text-ink-3">
                        {row.className} · {t("hostel.occupancy.room", { number: row.roomNumber ?? "" })} / {row.bedLabel}
                        {row.suggestedOnLeave ? ` · ${t("hostel.rollCall.onPass")}` : ""}
                      </span>
                    </span>
                    {!sheet.canMark && mark.status ? <Pill tone={ROLL_CALL_TONE[mark.status]} dot>{rollCallLabel(t, mark.status)}</Pill> : null}
                  </div>
                  {sheet.canMark ? (
                    <>
                      <div className="seg" role="radiogroup" aria-label={row.studentName ?? row.studentId}>
                        {ROLL_CALL_STATUSES.map((status) => (
                          <label key={status} className={`min-h-11 ${mark.status === status ? `seg-${ROLL_CALL_TONE[status]}` : ""}`}>
                            <input type="radio" name={`status-${row.studentId}`} value={status} checked={mark.status === status} onChange={() => form.set(row.studentId, { ...mark, status })} />
                            {rollCallLabel(t, status)}
                          </label>
                        ))}
                      </div>
                      {mark.status === "ABSENT" ? (
                        <input
                          className="input"
                          name={`note-${row.studentId}`}
                          aria-label={t("hostel.field.note")}
                          placeholder={t("hostel.rollCall.noteHint")}
                          value={mark.note}
                          maxLength={300}
                          onChange={(e) => form.set(row.studentId, { ...mark, note: e.target.value })}
                        />
                      ) : null}
                    </>
                  ) : mark.note ? (
                    <p className="text-[12.5px] text-ink-3">{mark.note}</p>
                  ) : null}
                </li>
              );
            })}
          </ul>
          {sheet.canMark ? (
            <button type="submit" className="btn btn-primary btn-lg sticky bottom-20 self-end sm:bottom-4" disabled={form.submitting} data-testid="roll-call-save">
              {sheet.taken ? t("hostel.rollCall.update") : t("hostel.rollCall.save")}
            </button>
          ) : null}
        </form>
      )}
    </section>
  );
}

function Register({ hostelId }: { hostelId: string }) {
  const { t } = useI18n();
  const locale = useLocale();
  const register = useApiData(`hostel:register:${hostelId}`, () => hostelApi.rollCallRegister(hostelId, {}));
  const data = register.data;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="roll-call-register" data-testid="roll-call-register">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="roll-call-register">{t("hostel.rollCall.register")}</h2>
      </div>
      {register.error && !data ? (
        <ErrorState error={register.error} onRetry={register.reload} />
      ) : !data ? (
        <LoadingRows rows={2} />
      ) : data.nights.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{t("hostel.rollCall.registerEmpty")}</p>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">{t("hostel.rollCall.night")}</th>
                <th scope="col" className="r">
                  {t("hostel.rollCall.PRESENT")}
                </th>
                <th scope="col" className="r">
                  {t("hostel.rollCall.ON_LEAVE")}
                </th>
                <th scope="col" className="r">
                  {t("hostel.rollCall.ABSENT")}
                </th>
                <th scope="col">{t("hostel.rollCall.absentees")}</th>
                <th scope="col">{t("hostel.rollCall.takenByCol")}</th>
              </tr>
            </thead>
            <tbody>
              {data.nights.map((n) => (
                <tr key={n.id} data-testid="register-night">
                  <td>{formatPlainDate(n.night, locale)}</td>
                  <td className="r num">{n.present}</td>
                  <td className="r num">{n.onLeave}</td>
                  <td className="r num">{n.absent}</td>
                  <td>{n.absentees.join(", ") || "—"}</td>
                  <td>{n.takenByName ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
