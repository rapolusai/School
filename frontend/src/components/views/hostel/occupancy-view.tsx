"use client";

import { ArrowRightLeft, BedDouble, LogOut, UserRoundPlus } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { hostelApi } from "@/lib/hostel-api";
import { plural, useI18n } from "@/lib/i18n";
import type { BedView, OccupancyBoard, RoomCard } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { HostelNav, HostelPicker, roomTypeLabel, useChosenHostel, useLocale } from "./hostel-shared";

/* /app/hostel/occupancy: the board of rooms as cards with their beds; beds are allotted, moved and vacated here. */

type AllotValues = { studentId: string; studentLabel: string; fromDate: string; guardianConsent: boolean };
type VacateValues = { reason: string; toDate: string };

/** Client-side checks mirroring AllotForm on the API. Pure and exported for tests. */
export function allotProblems(v: AllotValues, today = todayInIndia()): Problems {
  const problems: Problems = {};
  if (!v.studentId) problems.studentId = "hostel.v.student";
  if (!v.fromDate) problems.fromDate = "hostel.v.fromDate";
  else {
    const from = new Date(`${v.fromDate}T00:00:00Z`).getTime();
    const now = new Date(`${today}T00:00:00Z`).getTime();
    const days = Math.round((from - now) / 86_400_000);
    if (Number.isNaN(days) || days < -365 || days > 120) problems.fromDate = "hostel.v.fromDateRange";
  }
  if (!v.guardianConsent) problems.guardianConsent = "hostel.v.consent";
  return problems;
}

export function vacateProblems(v: VacateValues): Problems {
  const problems: Problems = {};
  if (!v.reason.trim()) problems.reason = "hostel.v.reason";
  if (!v.toDate) problems.toDate = "hostel.v.toDate";
  return problems;
}

/** Free beds first, then taken ones, as the card shows them. */
export function freeFirst(beds: BedView[]): BedView[] {
  return [...beds].sort((a, b) => Number(Boolean(a.allotmentId)) - Number(Boolean(b.allotmentId)));
}

type Open = { kind: "allot"; room: RoomCard; bed: BedView } | { kind: "move"; room: RoomCard; bed: BedView } | { kind: "vacate"; room: RoomCard; bed: BedView } | null;

export function OccupancyView() {
  const { t } = useI18n();
  const { hostels, list, hostel, choose } = useChosenHostel();
  return (
    <>
      <PageHead eyebrow={t("nav.hostel")} title={t("hostel.occupancy.title")} />
      <HostelNav />
      {hostels.error && !hostels.data ? (
        <ErrorState error={hostels.error} onRetry={hostels.reload} />
      ) : !hostels.data ? (
        <LoadingRows rows={3} />
      ) : !hostel ? (
        <p className="empty">{t("hostel.hostels.empty")}</p>
      ) : (
        <>
          <HostelPicker list={list} value={hostel.id} onChange={choose} />
          <Board key={hostel.id} hostelId={hostel.id} canManage={hostel.canManage} />
        </>
      )}
    </>
  );
}

function Board({ hostelId, canManage }: { hostelId: string; canManage: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const board = useApiData(`hostel:board:${hostelId}`, () => hostelApi.occupancy(hostelId));
  const [open, setOpen] = useState<Open>(null);
  const data = board.data;
  if (board.error && !data) return <ErrorState error={board.error} onRetry={board.reload} />;
  if (!data) return <LoadingRows rows={4} />;
  const saved = (message: string) => {
    setOpen(null);
    toast(message);
    board.reload();
  };
  return (
    <>
      <div className="report-figures" data-testid="occupancy-figures">
        <Figure label={t("hostel.occupancy.beds")} value={data.beds} />
        <Figure label={t("hostel.occupancy.occupied")} value={data.occupied} testId="occupancy-occupied" />
        <Figure label={t("hostel.occupancy.free")} value={data.free} testId="occupancy-free" />
        <Figure label={t("hostel.occupancy.percent")} value={`${data.occupancyPercent}%`} />
      </div>
      {data.rooms.length === 0 ? (
        <p className="empty" data-testid="board-empty">
          {t("hostel.occupancy.empty")}
        </p>
      ) : (
        <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3" data-testid="occupancy-board">
          {data.rooms.map((room) => (
            <RoomCardView key={room.id} room={room} canManage={canManage} onOpen={setOpen} />
          ))}
        </ul>
      )}
      <AllotDialog
        key={open?.kind === "allot" ? open.bed.id : "allot-closed"}
        open={open?.kind === "allot"}
        room={open?.kind === "allot" ? open.room : null}
        bed={open?.kind === "allot" ? open.bed : null}
        onClose={() => setOpen(null)}
        onSaved={() => saved(t("hostel.allot.saved"))}
      />
      <MoveDialog
        key={open?.kind === "move" ? open.bed.id : "move-closed"}
        open={open?.kind === "move"}
        board={data}
        bed={open?.kind === "move" ? open.bed : null}
        onClose={() => setOpen(null)}
        onSaved={() => saved(t("hostel.move.saved"))}
      />
      <VacateDialog
        key={open?.kind === "vacate" ? open.bed.id : "vacate-closed"}
        open={open?.kind === "vacate"}
        bed={open?.kind === "vacate" ? open.bed : null}
        onClose={() => setOpen(null)}
        onSaved={() => saved(t("hostel.vacate.saved"))}
      />
    </>
  );
}

function Figure({ label, value, testId }: { label: string; value: React.ReactNode; testId?: string }) {
  return (
    <div className="report-figure" data-testid={testId}>
      <span className="report-figure-label">{label}</span>
      <b className="report-figure-value num">{value}</b>
    </div>
  );
}

function RoomCardView({ room, canManage, onOpen }: { room: RoomCard; canManage: boolean; onOpen: (o: Open) => void }) {
  const { t } = useI18n();
  const locale = useLocale();
  const full = room.freeBeds === 0;
  return (
    <li className="card flex flex-col gap-2" data-testid="room-card" data-room={room.number} data-free={room.freeBeds}>
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h2 className="flex items-center gap-2">
            <BedDouble size={18} className="text-ink-3" aria-hidden="true" />
            {t("hostel.occupancy.room", { number: room.number })}
          </h2>
          <p className="text-[12.5px] text-ink-3">
            {room.blockName ? `${room.blockName} · ` : ""}
            {t("hostel.occupancy.floor", { floor: room.floor })} · {roomTypeLabel(t, room.type)} · {formatPaise(room.monthlyFeePaise)}
            {t("hostel.occupancy.perMonth")}
          </p>
        </div>
        <Pill tone={!room.active ? "neutral" : full ? "warn" : "good"} dot>
          {!room.active ? t("hostel.rooms.notInUse") : full ? t("hostel.occupancy.full") : plural(t, "hostel.occupancy.freeBeds", room.freeBeds)}
        </Pill>
      </div>
      {room.openComplaints > 0 ? (
        <p className="text-[12.5px] text-warn" data-testid="room-complaints">
          {plural(t, "hostel.occupancy.complaints", room.openComplaints)}
        </p>
      ) : null}
      {room.beds.length === 0 ? (
        <p className="text-[13px] text-ink-3">{t("hostel.occupancy.noBeds")}</p>
      ) : (
        <ul className="rowcards">
          {freeFirst(room.beds).map((bed) => (
            <li key={bed.id} className="rowcard items-center" data-testid="bed" data-bed={bed.label} data-taken={bed.allotmentId ? "yes" : "no"}>
              <span className="pill pill-neutral">{t("hostel.occupancy.bed", { label: bed.label })}</span>
              <span className="min-w-0 flex-1">
                {bed.studentName ? (
                  <>
                    <b className="block truncate">{bed.studentName}</b>
                    <span className="block text-[12.5px] text-ink-3">
                      {bed.className ?? ""}
                      {bed.since ? ` · ${t("hostel.occupancy.since", { date: formatPlainDate(bed.since, locale) })}` : ""}
                    </span>
                  </>
                ) : (
                  <span className="text-ink-3">{t("hostel.occupancy.freeBed")}</span>
                )}
              </span>
              {canManage ? (
                bed.allotmentId ? (
                  <span className="inline-flex gap-1">
                    <button type="button" className="iconbtn" aria-label={t("hostel.move.title")} onClick={() => onOpen({ kind: "move", room, bed })} data-testid="bed-move">
                      <ArrowRightLeft size={16} aria-hidden="true" />
                    </button>
                    <button type="button" className="iconbtn" aria-label={t("hostel.vacate.title")} onClick={() => onOpen({ kind: "vacate", room, bed })} data-testid="bed-vacate">
                      <LogOut size={16} aria-hidden="true" />
                    </button>
                  </span>
                ) : room.active ? (
                  <button type="button" className="btn btn-sm" onClick={() => onOpen({ kind: "allot", room, bed })} data-testid="bed-allot">
                    <UserRoundPlus size={16} aria-hidden="true" />
                    {t("hostel.allot.title")}
                  </button>
                ) : null
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

/* ------------------------------------------------------------------ dialogs */

function StudentSearch({ value, label, onPick, error }: { value: string; label: string; onPick: (id: string, label: string) => void; error?: string }) {
  const { t } = useI18n();
  const [q, setQ] = useState(label);
  const search = useApiData(q.trim().length >= 2 && q !== label ? `hostel:students:${q.trim()}` : null, () => hostelApi.students(q.trim()));
  const matches = q.trim().length >= 2 && q !== label ? (search.data ?? []) : [];
  return (
    <div className="flex flex-col gap-1">
      <TextField
        label={t("hostel.field.student")}
        name="student"
        value={q}
        onChange={(e) => {
          setQ(e.target.value);
          if (value) onPick("", "");
        }}
        error={error}
        hint={value ? t("hostel.allot.picked", { name: label }) : t("hostel.allot.searchHint")}
        autoComplete="off"
        required
        data-testid="student-search"
      />
      {matches.length > 0 ? (
        <ul className="list max-h-48 overflow-auto" data-testid="student-matches">
          {matches.map((s) => (
            <li key={s.id} className="li">
              <button
                type="button"
                className="link text-left"
                onClick={() => {
                  const text = `${s.fullName} · ${s.admissionNo}`;
                  setQ(text);
                  onPick(s.id, text);
                }}
              >
                {s.fullName} <span className="text-ink-3">· {s.admissionNo}{s.className ? ` · ${s.className}` : ""}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : q.trim().length >= 2 && q !== label && search.data && matches.length === 0 ? (
        <p className="text-[12.5px] text-ink-3">{t("hostel.allot.noMatch")}</p>
      ) : null}
    </div>
  );
}

function AllotDialog({ open, room, bed, onClose, onSaved }: { open: boolean; room: RoomCard | null; bed: BedView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<AllotValues>({ studentId: "", studentLabel: "", fromDate: todayInIndia(), guardianConsent: false });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!bed) return;
    if (!form.check(allotProblems(form.values), t, formEl)) return;
    const ok = await form.submit(t, async () => {
      await hostelApi.allot({ studentId: form.values.studentId, bedId: bed.id, fromDate: form.values.fromDate, guardianConsent: true });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t("hostel.allot.title")}
      description={room && bed ? t("hostel.allot.intro", { room: room.number, bed: bed.label }) : undefined}
      closeLabel={t("common.close")}
    >
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="allot-form">
        <FormAlert message={form.formError} />
        <StudentSearch
          value={form.values.studentId}
          label={form.values.studentLabel}
          error={form.errors.studentId}
          onPick={(id, label) => {
            form.set("studentId", id);
            form.set("studentLabel", label);
          }}
        />
        <TextField label={t("hostel.field.fromDate")} name="fromDate" type="date" value={form.values.fromDate} onChange={(e) => form.set("fromDate", e.target.value)} error={form.errors.fromDate} required />
        <label className="check">
          <input type="checkbox" name="guardianConsent" checked={form.values.guardianConsent} onChange={(e) => form.set("guardianConsent", e.target.checked)} data-testid="allot-consent" />
          {t("hostel.field.consent")}
        </label>
        {form.errors.guardianConsent ? (
          <p className="field-error" role="alert">
            {form.errors.guardianConsent}
          </p>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="allot-save">
            {t("hostel.allot.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function MoveDialog({ open, board, bed, onClose, onSaved }: { open: boolean; board: OccupancyBoard; bed: BedView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<{ bedId: string; fromDate: string }>({ bedId: "", fromDate: todayInIndia() });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const free = board.rooms.filter((r) => r.active).flatMap((r) => r.beds.filter((b) => !b.allotmentId).map((b) => ({ value: b.id, label: t("hostel.move.option", { room: r.number, bed: b.label }) })));
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!bed?.allotmentId) return;
    if (!form.check(form.values.bedId ? {} : { bedId: "hostel.v.bed" }, t, formEl)) return;
    const ok = await form.submit(t, async () => {
      await hostelApi.move(bed.allotmentId as string, { bedId: form.values.bedId, fromDate: form.values.fromDate || null });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={t("hostel.move.title")} description={bed?.studentName ?? undefined} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="move-form">
        <FormAlert message={form.formError} />
        {free.length === 0 ? (
          <p className="text-[13.5px] text-ink-3">{t("hostel.move.noFree")}</p>
        ) : (
          <SelectField
            label={t("hostel.field.newBed")}
            name="bedId"
            value={form.values.bedId}
            onChange={(e) => form.set("bedId", e.target.value)}
            error={form.errors.bedId}
            options={[{ value: "", label: t("hostel.move.pick") }, ...free]}
            required
          />
        )}
        <TextField label={t("hostel.field.fromDate")} name="fromDate" type="date" value={form.values.fromDate} onChange={(e) => form.set("fromDate", e.target.value)} error={form.errors.fromDate} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting || free.length === 0} data-testid="move-save">
            {t("hostel.move.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function VacateDialog({ open, bed, onClose, onSaved }: { open: boolean; bed: BedView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<VacateValues>({ reason: "", toDate: todayInIndia() });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!bed?.allotmentId) return;
    if (!form.check(vacateProblems(form.values), t, formEl)) return;
    const ok = await form.submit(t, async () => {
      await hostelApi.vacate(bed.allotmentId as string, { reason: form.values.reason.trim(), toDate: form.values.toDate });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={t("hostel.vacate.title")} description={bed?.studentName ?? undefined} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="vacate-form">
        <FormAlert message={form.formError} />
        <TextAreaField label={t("hostel.field.vacateReason")} name="reason" value={form.values.reason} onChange={(e) => form.set("reason", e.target.value)} error={form.errors.reason} rows={2} maxLength={300} required />
        <TextField label={t("hostel.field.toDate")} name="toDate" type="date" value={form.values.toDate} onChange={(e) => form.set("toDate", e.target.value)} error={form.errors.toDate} required />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="vacate-save">
            {t("hostel.vacate.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
