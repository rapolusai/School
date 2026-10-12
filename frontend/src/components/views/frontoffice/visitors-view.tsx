"use client";

import { LogIn, LogOut, Printer, UserRoundPlus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, initials, todayInIndia } from "@/lib/format";
import { frontOfficeApi } from "@/lib/frontoffice-api";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { ID_TYPES, VISITOR_PURPOSES, type IdType, type VisitorPurpose, type VisitorRequest, type VisitorView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  FrontOfficeNav,
  idTypeLabel,
  PhotoCapture,
  PhotoThumb,
  phoneProblem,
  purposeLabel,
  useAtGate,
  useCanManageOffice,
  VisitorStatusPill,
  when,
} from "./frontoffice-shared";

/** The API's refusal type when a visitor is on the blocklist (VisitorService.BLOCKED_TYPE). */
export const BLOCKED_TYPE = "urn:akshara:frontoffice:blocked";

type VisitorValues = {
  name: string;
  phone: string;
  purpose: VisitorPurpose;
  purposeNote: string;
  toMeetUserId: string;
  toMeetName: string;
  peopleCount: string;
  idType: IdType;
  badgeNo: string;
  expected: boolean;
  expectedOn: string;
  override: boolean;
};

const EMPTY: VisitorValues = {
  name: "",
  phone: "",
  purpose: "MEETING",
  purposeNote: "",
  toMeetUserId: "",
  toMeetName: "",
  peopleCount: "1",
  idType: "NONE",
  badgeNo: "",
  expected: false,
  expectedOn: "",
  override: false,
};

/** Client-side checks mirroring VisitorForm on the API. Pure and exported for tests. */
export function visitorProblems(values: VisitorValues, today: string): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "frontoffice.v.name";
  const phone = phoneProblem(values.phone);
  if (phone) problems.phone = phone;
  const count = Number(values.peopleCount);
  if (!Number.isInteger(count) || count < 1 || count > 50) problems.peopleCount = "frontoffice.v.people";
  if (values.expected) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(values.expectedOn)) problems.expectedOn = "frontoffice.v.expectedOn";
    else if (values.expectedOn < today) problems.expectedOn = "frontoffice.v.expectedPast";
  }
  return problems;
}

/** /app/frontoffice: today's visitors (inside, expected and gone) and the check-in form. */
export function VisitorsView() {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const day = useApiData("frontoffice:visitors:today", () => frontOfficeApi.day());
  const [adding, setAdding] = useState(false);
  const atGate = useAtGate();
  const data = day.data;
  const inside = data?.visitors.filter((v) => v.status === "IN") ?? [];
  const gone = data?.visitors.filter((v) => v.status === "OUT") ?? [];

  return (
    <>
      <PageHead
        eyebrow={t("nav.frontoffice")}
        title={t("frontoffice.visitors.title")}
        actions={
          atGate ? (
            <button type="button" className="btn btn-primary" onClick={() => setAdding(true)} data-testid="visitor-add">
              <UserRoundPlus size={18} aria-hidden="true" />
              {t("frontoffice.visitors.add")}
            </button>
          ) : undefined
        }
      />
      <FrontOfficeNav />
      {day.error && !data ? (
        <ErrorState error={day.error} onRetry={day.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
          <VisitorList
            id="visitors-inside"
            title={t("frontoffice.visitors.inside")}
            count={data.inside}
            rows={inside}
            empty={t("frontoffice.visitors.noneInside")}
            onChange={day.reload}
          />
          <VisitorList
            id="visitors-expected"
            title={t("frontoffice.visitors.expected")}
            count={data.expected.length}
            rows={data.expected}
            empty={t("frontoffice.visitors.noneExpected")}
            onChange={day.reload}
          />
          <VisitorList
            id="visitors-out"
            title={t("frontoffice.visitors.gone")}
            count={gone.length}
            rows={gone}
            empty={t("frontoffice.visitors.noneGone")}
            onChange={day.reload}
          />
        </div>
      )}
      <VisitorDialog
        open={adding}
        today={today}
        onClose={() => setAdding(false)}
        onSaved={() => {
          setAdding(false);
          day.reload();
        }}
      />
    </>
  );
}

function VisitorList({
  id,
  title,
  count,
  rows,
  empty,
  onChange,
}: {
  id: string;
  title: string;
  count: number;
  rows: VisitorView[];
  empty: string;
  onChange: () => void;
}) {
  const { t } = useI18n();
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={id} data-testid={id}>
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id={id}>{title}</h2>
        <Pill tone={count > 0 ? "info" : "neutral"}>{plural(t, "frontoffice.visitors.count", count)}</Pill>
      </div>
      {rows.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{empty}</p>
      ) : (
        <ul className="rowcards">
          {rows.map((visitor) => (
            <VisitorRow key={visitor.id} visitor={visitor} onChange={onChange} />
          ))}
        </ul>
      )}
    </section>
  );
}

/** One visitor with what the gate can do next: check in (expected), check out and a photo (inside), the slip. */
export function VisitorRow({ visitor, onChange }: { visitor: VisitorView; onChange: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const atGate = useAtGate();
  const canManage = useCanManageOffice();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [override, setOverride] = useState(false);

  const run = async (action: () => Promise<unknown>, done?: string) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      if (done) toast(done);
      onChange();
    } catch (caught) {
      const apiError = toApiError(caught);
      setError(apiError.type === BLOCKED_TYPE ? (apiError.detail ?? errorMessage(apiError, t)) : errorMessage(apiError, t));
      if (apiError.type === BLOCKED_TYPE && canManage) setOverride(true);
    } finally {
      setBusy(false);
    }
  };

  const subtitle = [
    purposeLabel(t, visitor.purpose),
    visitor.toMeetName ? t("frontoffice.visitors.toMeet", { name: visitor.toMeetName }) : null,
    visitor.peopleCount > 1 ? plural(t, "frontoffice.visitors.people", visitor.peopleCount) : null,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <li className="rowcard items-start" data-testid="visitor-row" data-status={visitor.status}>
      <span className="avatar" aria-hidden="true">
        {initials(visitor.name)}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <Link href={`/app/frontoffice/visitors/${encodeURIComponent(visitor.id)}`} className="font-semibold text-ink">
            {visitor.name}
          </Link>
          {visitor.badgeNo ? <span className="mono text-[12.5px] text-ink-3">{visitor.badgeNo}</span> : null}
          <VisitorStatusPill status={visitor.status} />
          {visitor.blocked ? <Pill tone="bad">{t("frontoffice.visitors.blocked")}</Pill> : null}
        </div>
        <p className="text-[13px] text-ink-3">{subtitle}</p>
        <p className="text-[12.5px] text-ink-3">
          {visitor.phoneMasked ? `${visitor.phoneMasked} · ` : ""}
          {visitor.status === "EXPECTED"
            ? t("frontoffice.visitors.expectedOn", { date: formatPlainDate(visitor.expectedOn, locale) })
            : visitor.status === "IN"
              ? t("frontoffice.visitors.inAt", { time: when(visitor.checkedInAt, locale) })
              : t("frontoffice.visitors.outAt", { time: when(visitor.checkedOutAt, locale) })}
        </p>
        {error ? (
          <p className="alert alert-warn mt-2 text-[13px]" role="alert">
            {error}
          </p>
        ) : null}
        {override && visitor.status === "EXPECTED" ? (
          <label className="check mt-2 text-[13px]">
            <input type="checkbox" name="override" checked={override} onChange={(e) => setOverride(e.target.checked)} />
            {t("frontoffice.visitors.override")}
          </label>
        ) : null}
        {atGate ? (
          <div className="mt-2 flex flex-wrap gap-2">
            {visitor.status === "EXPECTED" ? (
              <button
                type="button"
                className="btn btn-sm btn-primary"
                disabled={busy}
                onClick={() => run(() => frontOfficeApi.checkIn(visitor.id, { override }), t("frontoffice.visitors.checkedIn", { name: visitor.name }))}
                data-testid="visitor-check-in"
              >
                <LogIn size={16} aria-hidden="true" />
                {t("frontoffice.visitors.checkIn")}
              </button>
            ) : null}
            {visitor.status === "IN" ? (
              <>
                <button
                  type="button"
                  className="btn btn-sm"
                  disabled={busy}
                  onClick={() => run(() => frontOfficeApi.checkOut(visitor.id), t("frontoffice.visitors.checkedOut", { name: visitor.name }))}
                  data-testid="visitor-check-out"
                >
                  <LogOut size={16} aria-hidden="true" />
                  {t("frontoffice.visitors.checkOut")}
                </button>
                <PhotoCapture
                  label={visitor.photoFileId ? t("frontoffice.photo.retake") : t("frontoffice.photo.take")}
                  busy={busy}
                  onPhoto={(blob, name) => run(() => frontOfficeApi.visitorPhoto(visitor.id, blob, name), t("frontoffice.photo.saved"))}
                  testId="visitor-photo"
                />
                <Link href={`/app/frontoffice/visitors/${encodeURIComponent(visitor.id)}`} className="btn btn-sm">
                  <Printer size={16} aria-hidden="true" />
                  {t("frontoffice.slip.open")}
                </Link>
              </>
            ) : null}
          </div>
        ) : null}
      </div>
      {visitor.photoFileId ? <PhotoThumb fileId={visitor.photoFileId} alt={visitor.name} size={48} /> : null}
    </li>
  );
}

/** The check-in form: a visitor arriving now, or (with a date) one expected on a day. */
export function VisitorDialog({
  open,
  today,
  onClose,
  onSaved,
}: {
  open: boolean;
  today: string;
  onClose: () => void;
  onSaved: (visitor: VisitorView) => void;
}) {
  const { t } = useI18n();
  const canManage = useCanManageOffice();
  const staff = useApiData(open ? "frontoffice:staff" : null, frontOfficeApi.staff);
  const form = useForm<VisitorValues>(EMPTY);
  const [blocked, setBlocked] = useState<string | null>(null);
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);

  const close = () => {
    form.reset(EMPTY);
    setBlocked(null);
    onClose();
  };

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const v = form.values;
    if (!form.check(visitorProblems(v, today), t, formEl)) return;
    const body: VisitorRequest = {
      name: v.name.trim(),
      phone: v.phone.trim() || null,
      purpose: v.purpose,
      purposeNote: v.purposeNote.trim() || null,
      toMeetUserId: v.toMeetUserId || null,
      toMeetName: v.toMeetUserId ? null : v.toMeetName.trim() || null,
      peopleCount: Number(v.peopleCount),
      idType: v.idType,
      badgeNo: v.badgeNo.trim() || null,
      expectedOn: v.expected ? v.expectedOn : null,
      override: blocked !== null && v.override ? true : undefined,
    };
    // The blocklist refusal (a 409 with its own type) is caught inside the submit so the office can be offered
    // the override instead of a plain error.
    setBlocked(null);
    const ok = await form.submit(t, async () => {
      try {
        const saved = await frontOfficeApi.addVisitor(body);
        form.reset(EMPTY);
        onSaved(saved);
      } catch (caught) {
        const apiError = toApiError(caught);
        if (apiError.type === BLOCKED_TYPE) setBlocked(apiError.detail ?? "");
        throw apiError;
      }
    });
    if (ok) setBlocked(null);
  };

  return (
    <Dialog open={open} onClose={close} title={t("frontoffice.visitors.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="visitor-form">
        {blocked !== null ? (
          <div className="alert alert-warn text-[13px]" role="alert" data-testid="visitor-blocked">
            <p>{blocked}</p>
            {canManage ? (
              <label className="check mt-1">
                <input type="checkbox" name="override" checked={form.values.override} onChange={(e) => form.set("override", e.target.checked)} />
                {t("frontoffice.visitors.override")}
              </label>
            ) : (
              <p className="mt-1">{t("frontoffice.visitors.askOffice")}</p>
            )}
          </div>
        ) : (
          <FormAlert message={form.formError} />
        )}
        <TextField
          label={t("frontoffice.field.name")}
          name="name"
          value={form.values.name}
          onChange={(e) => form.set("name", e.target.value)}
          error={form.errors.name}
          maxLength={200}
          autoComplete="off"
          required
        />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField
            label={t("frontoffice.field.phone")}
            name="phone"
            type="tel"
            inputMode="numeric"
            value={form.values.phone}
            onChange={(e) => form.set("phone", e.target.value)}
            error={form.errors.phone}
            hint={t("frontoffice.field.phoneHint")}
            maxLength={20}
            autoComplete="off"
          />
          <SelectField
            label={t("frontoffice.field.purpose")}
            name="purpose"
            value={form.values.purpose}
            onChange={(e) => form.set("purpose", e.target.value as VisitorPurpose)}
            options={VISITOR_PURPOSES.map((p) => ({ value: p, label: purposeLabel(t, p) }))}
            error={form.errors.purpose}
          />
        </div>
        <TextField
          label={t("frontoffice.field.purposeNote")}
          name="purposeNote"
          value={form.values.purposeNote}
          onChange={(e) => form.set("purposeNote", e.target.value)}
          error={form.errors.purposeNote}
          maxLength={300}
        />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <SelectField
            label={t("frontoffice.field.toMeet")}
            name="toMeetUserId"
            value={form.values.toMeetUserId}
            onChange={(e) => form.set("toMeetUserId", e.target.value)}
            options={[
              { value: "", label: t("frontoffice.field.toMeetOther") },
              ...(staff.data ?? []).map((s) => ({ value: s.id, label: s.designation ? `${s.name} · ${s.designation}` : s.name })),
            ]}
            error={form.errors.toMeetUserId}
          />
          {form.values.toMeetUserId ? null : (
            <TextField
              label={t("frontoffice.field.toMeetName")}
              name="toMeetName"
              value={form.values.toMeetName}
              onChange={(e) => form.set("toMeetName", e.target.value)}
              error={form.errors.toMeetName}
              maxLength={200}
            />
          )}
        </div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <TextField
            label={t("frontoffice.field.people")}
            name="peopleCount"
            type="number"
            inputMode="numeric"
            min={1}
            max={50}
            value={form.values.peopleCount}
            onChange={(e) => form.set("peopleCount", e.target.value)}
            error={form.errors.peopleCount}
          />
          <SelectField
            label={t("frontoffice.field.idType")}
            name="idType"
            value={form.values.idType}
            onChange={(e) => form.set("idType", e.target.value as IdType)}
            options={ID_TYPES.map((type) => ({ value: type, label: idTypeLabel(t, type) }))}
            hint={t("frontoffice.field.idHint")}
            error={form.errors.idType}
          />
          <TextField
            label={t("frontoffice.field.badge")}
            name="badgeNo"
            value={form.values.badgeNo}
            onChange={(e) => form.set("badgeNo", e.target.value)}
            error={form.errors.badgeNo}
            hint={t("frontoffice.field.badgeHint")}
            maxLength={20}
          />
        </div>
        <label className="check">
          <input type="checkbox" name="expected" checked={form.values.expected} onChange={(e) => form.set("expected", e.target.checked)} />
          {t("frontoffice.field.expected")}
        </label>
        {form.values.expected ? (
          <TextField
            label={t("frontoffice.field.expectedOn")}
            name="expectedOn"
            type="date"
            min={today}
            value={form.values.expectedOn}
            onChange={(e) => form.set("expectedOn", e.target.value)}
            error={form.errors.expectedOn}
            required
          />
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="visitor-save">
            {form.values.expected ? t("frontoffice.visitors.saveExpected") : t("frontoffice.visitors.checkIn")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
