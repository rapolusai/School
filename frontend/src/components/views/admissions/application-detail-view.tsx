"use client";

import {
  ArrowLeft,
  CalendarClock,
  ExternalLink,
  GraduationCap,
  History,
  IndianRupee,
  Mail,
  MapPin,
  MessageSquareText,
  NotebookPen,
  Pencil,
  Phone,
  Plus,
  Printer,
  Video,
} from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { admissionsApi } from "@/lib/admissions-api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatPhone, formatPlainDate, initials } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { ApplicationDetail, AssessmentSlot, TimelineEntry } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import {
  kindLabel,
  methodLabel,
  modeLabel,
  rupees,
  sourceLabel,
  stageLabel,
  stageTone,
  timelineTitle,
} from "./admission-labels";
import {
  AdmitDialog,
  FeeDialog,
  OfferDialog,
  OutcomeDialog,
  SlotDialog,
  type ApplicationRef,
} from "./application-dialogs";
import { ApplicationFormDialog } from "./application-form-dialog";
import { StageActions } from "./stage-actions";

type Open =
  | { kind: "edit" }
  | { kind: "fee" }
  | { kind: "offer" }
  | { kind: "admit" }
  | { kind: "slot"; slot: AssessmentSlot | null }
  | { kind: "outcome"; slot: AssessmentSlot }
  | { kind: "cancelSlot"; slot: AssessmentSlot }
  | null;

const SLOT_TONES = { SCHEDULED: "info", DONE: "good", CANCELLED: "neutral" } as const;

export function ApplicationDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = hasPermission(me, PERMISSIONS.admissionsManage);
  const loaded = useApiData(`admission:${id}`, () => admissionsApi.get(id));
  const [open, setOpen] = useState<Open>(null);
  // Changes answer with the updated application, shown at once instead of reloading.
  const [latest, setLatest] = useState<ApplicationDetail | null>(null);
  const a = latest && latest.id === id ? latest : loaded.data;

  const changed = (updated: ApplicationDetail, message?: string) => {
    setOpen(null);
    setLatest(updated);
    if (message) toast(message);
  };

  const back = (
    <Link href="/app/admissions" className="link inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("admissions.back")}
    </Link>
  );

  if (loaded.error && !a) {
    return (
      <>
        {back}
        <section className="card">
          {loaded.error.status === 404 ? (
            <p className="empty">{t("admissions.notFound")}</p>
          ) : (
            <ErrorState error={loaded.error} onRetry={loaded.reload} />
          )}
        </section>
      </>
    );
  }
  if (!a) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      </>
    );
  }

  const ref: ApplicationRef = {
    id: a.id,
    childName: a.childName,
    stage: a.stage,
    classId: a.classId,
    className: a.className,
    gender: a.gender,
  };
  const isOpen = a.nextStages.length > 0;
  const canSchedule = a.stage === "APPLICATION" || a.stage === "ASSESSMENT";
  const stageMoves = a.nextStages.filter((s) => s !== "ADMITTED");

  return (
    <>
      {back}
      <div className="page-head">
        <div className="person min-w-0">
          <span className="avatar h-12 w-12 text-base" aria-hidden="true">
            {initials(a.childName)}
          </span>
          <div className="min-w-0">
            <p className="eyebrow">{t("admissions.detail.eyebrow", { className: a.className, year: a.academicYearName })}</p>
            <h1 className="mt-0.5 flex flex-wrap items-center gap-2">
              {a.childName}
              <Pill tone={stageTone(a.stage)} dot>
                {stageLabel(t, a.stage)}
              </Pill>
            </h1>
            <p className="mt-1 text-sm text-ink-2">
              {[
                a.daysInStage === 0
                  ? t("admissions.card.today")
                  : plural(t, "admissions.card.days", a.daysInStage),
                sourceLabel(t, a.source),
                a.followUpOn
                  ? t("admissions.card.followUp", { date: formatPlainDate(a.followUpOn, locale) })
                  : null,
              ]
                .filter(Boolean)
                .join(" · ")}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {a.offer && (a.stage === "OFFERED" || a.stage === "ADMITTED") ? (
            <Link href={`/app/admissions/${a.id}/offer-letter`} className="btn">
              <Printer size={18} aria-hidden="true" />
              {t("admissions.offer.letter")}
            </Link>
          ) : null}
          {canManage && isOpen ? (
            <button type="button" className="btn" onClick={() => setOpen({ kind: "edit" })}>
              <Pencil size={18} aria-hidden="true" />
              {t("admissions.detail.edit")}
            </button>
          ) : null}
          {canManage && stageMoves.length > 0 ? (
            <StageActions
              application={ref}
              nextStages={stageMoves}
              size="md"
              onChanged={(updated) => changed(updated)}
            />
          ) : null}
          {canManage && a.stage === "OFFERED" ? (
            <button type="button" className="btn btn-primary" onClick={() => setOpen({ kind: "admit" })}>
              <GraduationCap size={18} aria-hidden="true" />
              {t("admissions.move.ADMITTED")}
            </button>
          ) : null}
        </div>
      </div>

      {a.stage === "ADMITTED" && a.studentId ? (
        <section className="alert alert-info flex-wrap items-center" role="status" data-testid="admitted-banner">
          <GraduationCap size={18} aria-hidden="true" className="flex-none" />
          <span className="min-w-0 flex-1">{t("admissions.detail.admitted", { name: a.childName })}</span>
          <Link href={`/app/students/${a.studentId}`} className="btn btn-sm">
            {t("admissions.admit.openStudent")}
          </Link>
        </section>
      ) : null}
      {a.stage === "REJECTED" || a.stage === "WITHDRAWN" ? (
        <section className="alert alert-info" role="status">
          {t("admissions.detail.closed", { stage: stageLabel(t, a.stage).toLowerCase() })}
        </section>
      ) : null}

      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        <section className="card" aria-labelledby="child-heading">
          <div className="card-head">
            <h2 id="child-heading">{t("admissions.detail.child")}</h2>
          </div>
          <dl className="kv">
            <dt>{t("students.field.dateOfBirth")}</dt>
            <dd>{formatPlainDate(a.dateOfBirth, locale)}</dd>
            <dt>{t("students.field.gender")}</dt>
            <dd>{a.gender ? translateOr(t, `gender.${a.gender}`, a.gender) : "—"}</dd>
            <dt>{t("students.field.previousSchool")}</dt>
            <dd>{a.previousSchool ?? "—"}</dd>
            <dt>{t("admissions.field.class")}</dt>
            <dd>{a.className}</dd>
            <dt>{t("admissions.field.year")}</dt>
            <dd>{a.academicYearName}</dd>
            <dt>{t("admissions.field.source")}</dt>
            <dd>{sourceLabel(t, a.source)}</dd>
            <dt>{t("admissions.field.counsellor")}</dt>
            <dd>{a.assignedTo?.name ?? "—"}</dd>
            <dt>{t("admissions.field.followUpOn")}</dt>
            <dd>{formatPlainDate(a.followUpOn, locale) || "—"}</dd>
            <dt>{t("admissions.detail.received")}</dt>
            <dd>{formatDateTime(a.createdAt, locale)}</dd>
            {a.consentAt ? (
              <>
                <dt>{t("admissions.detail.consent")}</dt>
                <dd>
                  {t("admissions.detail.consentGiven", { date: formatDateTime(a.consentAt, locale) })}
                  <span className="block text-[12.5px] text-ink-3 mono">{a.consentVersion}</span>
                </dd>
              </>
            ) : null}
          </dl>
          {a.message ? (
            <div className="mt-3 rounded-lg bg-surface-2 p-3 text-[14px]">
              <p className="mb-1 flex items-center gap-1.5 text-[12.5px] font-semibold text-ink-3">
                <MessageSquareText size={14} aria-hidden="true" />
                {t("admissions.detail.message")}
              </p>
              <p className="whitespace-pre-line break-words">{a.message}</p>
            </div>
          ) : null}
        </section>

        <section className="card" aria-labelledby="contacts-heading">
          <div className="card-head">
            <h2 id="contacts-heading">{t("admissions.detail.contacts")}</h2>
          </div>
          <ul className="list" data-testid="contacts-list">
            {a.guardians.map((g, index) => (
              <li key={`${g.phone}-${index}`} className="li">
                <span className="avatar" aria-hidden="true">
                  {initials(g.name)}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-1.5 font-semibold">
                    {g.name}
                    <span className="text-[13px] font-normal text-ink-3">
                      {translateOr(t, `relation.${g.relation}`, g.relation)}
                    </span>
                    {g.primary ? <Pill tone="accent">{t("student.guardian.primaryPill")}</Pill> : null}
                  </p>
                  <p className="mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5 text-[13.5px] text-ink-2">
                    <a href={`tel:+91${g.phone}`} className="inline-flex items-center gap-1 num">
                      <Phone size={14} aria-hidden="true" />
                      {formatPhone(g.phone)}
                    </a>
                    {g.email ? (
                      <a href={`mailto:${g.email}`} className="inline-flex min-w-0 items-center gap-1 break-all">
                        <Mail size={14} aria-hidden="true" />
                        {g.email}
                      </a>
                    ) : null}
                  </p>
                </div>
              </li>
            ))}
          </ul>
        </section>

        <section className="card" aria-labelledby="slots-heading">
          <div className="card-head">
            <h2 id="slots-heading">{t("admissions.detail.slots")}</h2>
            {canManage && canSchedule ? (
              <button type="button" className="btn btn-sm" onClick={() => setOpen({ kind: "slot", slot: null })}>
                <Plus size={16} aria-hidden="true" />
                {t("admissions.slot.schedule")}
              </button>
            ) : null}
          </div>
          {a.slots.length === 0 ? (
            <p className="text-sm text-ink-3">
              {canSchedule ? t("admissions.detail.noSlots") : t("admissions.detail.noSlotsYet")}
            </p>
          ) : (
            <ul className="list" data-testid="slots-list">
              {a.slots.map((slot) => (
                <li key={slot.id} className="li">
                  <span className="badge-ic">
                    {slot.mode === "ONLINE" ? <Video size={18} aria-hidden="true" /> : <MapPin size={18} aria-hidden="true" />}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="flex flex-wrap items-center gap-1.5 font-semibold">
                      {kindLabel(t, slot.kind)}
                      <Pill tone={SLOT_TONES[slot.status]}>
                        {translateOr(t, `admissions.slotStatus.${slot.status}`, slot.status)}
                      </Pill>
                    </p>
                    <p className="text-[13.5px] text-ink-2">
                      <time dateTime={slot.scheduledAt}>{formatDateTime(slot.scheduledAt, locale)}</time>
                      {" · "}
                      {slot.mode === "ONLINE" && slot.meetingLink ? (
                        <a href={slot.meetingLink} target="_blank" rel="noopener noreferrer" className="link break-all">
                          {modeLabel(t, slot.mode)}
                          <ExternalLink size={12} aria-hidden="true" className="ml-0.5 inline" />
                        </a>
                      ) : (
                        (slot.location ?? modeLabel(t, slot.mode))
                      )}
                      {slot.interviewer ? ` · ${slot.interviewer.name}` : ""}
                    </p>
                    {slot.outcomeNotes ? (
                      <p className="mt-1 whitespace-pre-line text-[13.5px]">{slot.outcomeNotes}</p>
                    ) : null}
                    {canManage && slot.status !== "CANCELLED" && isOpen ? (
                      <div className="mt-1.5 flex flex-wrap gap-1.5">
                        {slot.status === "SCHEDULED" ? (
                          <button
                            type="button"
                            className="btn btn-sm"
                            onClick={() => setOpen({ kind: "slot", slot })}
                          >
                            <CalendarClock size={14} aria-hidden="true" />
                            {t("admissions.slot.reschedule")}
                          </button>
                        ) : null}
                        <button type="button" className="btn btn-sm" onClick={() => setOpen({ kind: "outcome", slot })}>
                          <NotebookPen size={14} aria-hidden="true" />
                          {slot.outcomeNotes ? t("admissions.slot.editOutcome") : t("admissions.slot.recordOutcome")}
                        </button>
                        {slot.status === "SCHEDULED" ? (
                          <button
                            type="button"
                            className="btn btn-sm btn-ghost"
                            onClick={() => setOpen({ kind: "cancelSlot", slot })}
                          >
                            {t("admissions.slot.cancel")}
                          </button>
                        ) : null}
                      </div>
                    ) : null}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="card" aria-labelledby="fee-heading">
          <div className="card-head">
            <h2 id="fee-heading">{t("admissions.detail.fee")}</h2>
            {canManage && isOpen ? (
              <button type="button" className="btn btn-sm" onClick={() => setOpen({ kind: "fee" })}>
                <IndianRupee size={16} aria-hidden="true" />
                {a.fee ? t("admissions.fee.change") : t("admissions.fee.record")}
              </button>
            ) : null}
          </div>
          {a.fee ? (
            <dl className="kv" data-testid="fee-details">
              <dt>{t("admissions.fee.status")}</dt>
              <dd>
                <Pill tone={a.fee.status === "PAID" ? "good" : "info"}>
                  {t(a.fee.status === "PAID" ? "admissions.fee.paid" : "admissions.fee.waived")}
                </Pill>
              </dd>
              {a.fee.status === "PAID" ? (
                <>
                  <dt>{t("admissions.fee.amount")}</dt>
                  <dd className="num">{rupees(a.fee.amountPaise)}</dd>
                  <dt>{t("admissions.fee.method")}</dt>
                  <dd>{a.fee.method ? methodLabel(t, a.fee.method) : "—"}</dd>
                </>
              ) : null}
              <dt>{t(a.fee.status === "PAID" ? "admissions.fee.paidOn" : "admissions.fee.waivedOn")}</dt>
              <dd>{formatPlainDate(a.fee.paidOn, locale)}</dd>
              {a.fee.reference ? (
                <>
                  <dt>{t("admissions.fee.reference")}</dt>
                  <dd className="mono break-all">{a.fee.reference}</dd>
                </>
              ) : null}
            </dl>
          ) : (
            <p className="text-sm text-ink-3">{t("admissions.fee.none")}</p>
          )}
          {a.offer ? (
            <div className="mt-4 border-t border-line pt-3">
              <div className="card-head mb-2">
                <h3 className="text-[15px] font-semibold">{t("admissions.offer.title")}</h3>
                {canManage && a.stage === "OFFERED" ? (
                  <button type="button" className="btn btn-sm" onClick={() => setOpen({ kind: "offer" })}>
                    <Pencil size={14} aria-hidden="true" />
                    {t("admissions.offer.change")}
                  </button>
                ) : null}
              </div>
              <dl className="kv" data-testid="offer-details">
                <dt>{t("admissions.field.offeredOn")}</dt>
                <dd>{formatPlainDate(a.offer.offeredOn, locale)}</dd>
                <dt>{t("admissions.field.validUntil")}</dt>
                <dd>{formatPlainDate(a.offer.validUntil, locale) || t("admissions.offer.noEnd")}</dd>
              </dl>
            </div>
          ) : null}
        </section>
      </div>

      <section className="card" aria-labelledby="timeline-heading">
        <div className="card-head">
          <h2 id="timeline-heading">{t("admissions.detail.timeline")}</h2>
        </div>
        {canManage ? <NoteForm id={a.id} onAdded={(updated) => changed(updated, t("admissions.note.added"))} /> : null}
        <Timeline entries={a.timeline} />
      </section>

      {canManage && open?.kind === "edit" ? (
        <ApplicationFormDialog
          open
          existing={a}
          onClose={() => setOpen(null)}
          onSaved={(updated) => changed(updated, t("admissions.saved"))}
        />
      ) : null}
      {canManage && open?.kind === "fee" ? (
        <FeeDialog
          open
          application={ref}
          fee={a.fee}
          onClose={() => setOpen(null)}
          onSaved={(updated) => changed(updated, t("admissions.fee.saved"))}
        />
      ) : null}
      {canManage && open?.kind === "offer" && a.offer ? (
        <OfferDialog
          open
          application={ref}
          offer={a.offer}
          onClose={() => setOpen(null)}
          onSaved={(updated) => changed(updated, t("admissions.offer.saved"))}
        />
      ) : null}
      {canManage && open?.kind === "admit" ? (
        <AdmitDialog
          open
          application={ref}
          onClose={() => setOpen(null)}
          onAdmitted={(updated) => setLatest(updated)}
        />
      ) : null}
      {canManage && open?.kind === "slot" ? (
        <SlotDialog
          open
          application={ref}
          slot={open.slot}
          onClose={() => setOpen(null)}
          onSaved={(updated) => changed(updated, t("admissions.slot.saved"))}
        />
      ) : null}
      {canManage && open?.kind === "outcome" ? (
        <OutcomeDialog
          open
          application={ref}
          slot={open.slot}
          onClose={() => setOpen(null)}
          onSaved={(updated) => changed(updated, t("admissions.slot.outcomeSaved"))}
        />
      ) : null}
      {canManage && open?.kind === "cancelSlot" ? (
        <ConfirmDialog
          open
          title={t("admissions.slot.cancelTitle")}
          body={t("admissions.slot.cancelBody", {
            kind: kindLabel(t, open.slot.kind).toLowerCase(),
            date: formatDateTime(open.slot.scheduledAt, locale),
          })}
          confirmLabel={t("admissions.slot.cancelConfirm")}
          onConfirm={async () => {
            const updated = await admissionsApi.cancelSlot(a.id, open.slot.id);
            changed(updated, t("admissions.slot.cancelled"));
          }}
          onClose={() => setOpen(null)}
        />
      ) : null}
    </>
  );
}

function NoteForm({ id, onAdded }: { id: string; onAdded: (updated: ApplicationDetail) => void }) {
  const { t } = useI18n();
  const form = useForm<{ note: string }>({ note: "" });

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const note = form.values.note.trim();
    if (!form.check(note ? (note.length > 2000 ? { note: "validation.tooLong" } : {}) : { note: "validation.required" }, t, event.currentTarget)) {
      return;
    }
    let updated: ApplicationDetail | undefined;
    const ok = await form.submit(t, async () => {
      updated = await admissionsApi.addNote(id, note);
    });
    if (ok && updated) {
      form.reset({ note: "" });
      onAdded(updated);
    }
  };

  return (
    <form method="post" className="mb-4 flex flex-col gap-2" onSubmit={onSubmit} noValidate>
      <FormAlert message={form.formError} />
      <TextAreaField
        label={t("admissions.note.label")}
        name="note"
        rows={2}
        maxLength={2000}
        placeholder={t("admissions.note.placeholder")}
        value={form.values.note}
        onChange={(e) => form.set("note", e.target.value)}
        error={form.errors.note}
      />
      <button type="submit" className="btn btn-sm self-end" disabled={form.submitting}>
        <Plus size={14} aria-hidden="true" />
        {form.submitting ? t("common.saving") : t("admissions.note.add")}
      </button>
    </form>
  );
}

function Timeline({ entries }: { entries: TimelineEntry[] }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <ol className="timeline" data-testid="timeline">
      {entries.map((entry) => {
        const when = typeof entry.details.scheduledAt === "string" ? entry.details.scheduledAt : null;
        return (
          <li key={entry.id} className="timeline-item">
            <span className="timeline-dot" aria-hidden="true">
              <History size={14} />
            </span>
            <div className="min-w-0 flex-1">
              <p className="font-semibold">{timelineTitle(t, entry, locale)}</p>
              {when && entry.kind !== "SLOT_OUTCOME" ? (
                <p className="text-[13.5px] text-ink-2">{formatDateTime(when, locale)}</p>
              ) : null}
              {entry.note ? <p className="mt-0.5 whitespace-pre-line break-words text-[14px]">{entry.note}</p> : null}
              <p className="mt-0.5 text-[12.5px] text-ink-3">
                {entry.actorName ?? t("admissions.timeline.website")} ·{" "}
                <time dateTime={entry.at}>{formatDateTime(entry.at, locale)}</time>
              </p>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
