"use client";

import {
  ArrowRight,
  CarFront,
  Pencil,
  Trash2,
  UserRoundPlus,
} from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import {
  ErrorState,
  FormAlert,
  LoadingRows,
  PageHead,
} from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import {
  localToInstant,
  PassStatusPill,
  PhotoCapture,
  PhotoThumb,
  phoneProblem,
  when,
} from "@/components/views/frontoffice/frontoffice-shared";
import {
  collectorLine,
  EMPTY_PASS,
  PassFields,
  passProblems,
  type PassValues,
} from "@/components/views/frontoffice/passes-view";
import { frontOfficeApi } from "@/lib/frontoffice-api";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type {
  FamilyPickup,
  GatePassView,
  PickupPersonRequest,
  PickupPersonView,
  PickupRequest,
} from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { ChildSwitcher, useFamilyChildren } from "./family-shared";

type PersonValues = { name: string; relation: string; phone: string };

/** Client-side checks mirroring PickupPersonForm on the API. Pure and exported for tests. */
export function personProblems(values: PersonValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "frontoffice.v.name";
  if (!values.relation.trim()) problems.relation = "frontoffice.v.relation";
  const phone = phoneProblem(values.phone, true);
  if (phone) problems.phone = phone;
  return problems;
}

/** /app/family/pickup: a parent asks for an early pickup, follows the pass and keeps the list of people allowed to collect. */
export function FamilyPickupView({
  initialChildId,
}: {
  initialChildId?: string;
}) {
  const { t } = useI18n();
  const { children, list, child, choose } = useFamilyChildren(initialChildId);
  return (
    <>
      <PageHead title={t("nav.pickup")} />
      {children.error && !children.data ? (
        <ErrorState error={children.error} onRetry={children.reload} />
      ) : !children.data ? (
        <LoadingRows rows={3} />
      ) : !child ? (
        <p className="empty">{t("children.empty")}</p>
      ) : (
        <>
          <ChildSwitcher list={list} value={child.id} onChange={choose} />
          <ChildPickup key={child.id} childId={child.id} />
        </>
      )}
    </>
  );
}

function ChildPickup({ childId }: { childId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const pickup = useApiData(`frontoffice:family-pickup:${childId}`, () =>
    frontOfficeApi.childPickup(childId),
  );
  const [requesting, setRequesting] = useState(false);
  const [cancelling, setCancelling] = useState<GatePassView | null>(null);
  const [editing, setEditing] = useState<PickupPersonView | "new" | null>(null);
  const [removing, setRemoving] = useState<PickupPersonView | null>(null);
  const data = pickup.data;
  if (pickup.error && !data)
    return <ErrorState error={pickup.error} onRetry={pickup.reload} />;
  if (!data) return <LoadingRows rows={3} />;
  const open = data.passes.filter(
    (p) => p.status === "REQUESTED" || p.status === "APPROVED",
  );
  const past = data.passes.filter((p) => !open.includes(p));
  return (
    <>
      <section
        className="card flex flex-col gap-3"
        aria-labelledby="family-pickup-passes"
        data-testid="family-pickup-passes"
      >
        <div className="card-head" style={{ marginBottom: 0 }}>
          <div className="min-w-0">
            <h2 id="family-pickup-passes">{t("frontoffice.family.passes")}</h2>
            <p className="mt-1 text-[13px] text-ink-3">
              {t("frontoffice.family.intro")}
            </p>
          </div>
          {data.canRequest ? (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => setRequesting(true)}
              data-testid="pickup-request"
            >
              <CarFront size={18} aria-hidden="true" />
              {t("frontoffice.family.request")}
            </button>
          ) : null}
        </div>
        {open.length === 0 && past.length === 0 ? (
          <p className="text-[13.5px] text-ink-3" data-testid="pickup-none">
            {t("frontoffice.family.noPasses")}
          </p>
        ) : (
          <ul className="rowcards">
            {[...open, ...past].map((pass) => (
              <li
                key={pass.id}
                className="rowcard items-start"
                data-testid="family-pass"
                data-status={pass.status}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold text-ink">
                      {t("frontoffice.pass.at", {
                        time: when(pass.pickupAt, locale),
                      })}
                    </span>
                    <PassStatusPill status={pass.status} />
                  </div>
                  <p className="text-[13px] text-ink-2">{pass.reason}</p>
                  <p className="text-[12.5px] text-ink-3">
                    {t("frontoffice.pass.collector", {
                      who: collectorLine(t, pass),
                    })}
                  </p>
                  {pass.status === "APPROVED" ? (
                    <p
                      className="text-[12.5px] text-ink-2"
                      data-testid="pass-otp-hint"
                    >
                      {t("frontoffice.family.otpSent", {
                        time: when(pass.otpExpiresAt, locale),
                      })}
                    </p>
                  ) : null}
                  {pass.status === "RELEASED" ? (
                    <p className="text-[12.5px] text-ink-3">
                      {t("frontoffice.family.released", {
                        who: pass.collectedByName ?? "",
                        time: when(pass.releasedAt, locale),
                      })}
                    </p>
                  ) : null}
                  {pass.decisionNote ? (
                    <p className="text-[12.5px] text-ink-3">
                      {pass.decisionNote}
                    </p>
                  ) : null}
                </div>
                {pass.canCancel ? (
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setCancelling(pass)}
                    data-testid="pass-cancel"
                  >
                    {t("frontoffice.family.cancel")}
                  </button>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section
        className="card flex flex-col gap-3"
        aria-labelledby="family-pickup-persons"
        data-testid="family-pickup-persons"
      >
        <div className="card-head" style={{ marginBottom: 0 }}>
          <div className="min-w-0">
            <h2 id="family-pickup-persons">{t("nav.pickup.persons")}</h2>
            <p className="mt-1 text-[13px] text-ink-3">
              {t("frontoffice.family.personsIntro")}
            </p>
          </div>
          <button
            type="button"
            className="btn"
            onClick={() => setEditing("new")}
            data-testid="person-add"
          >
            <UserRoundPlus size={18} aria-hidden="true" />
            {t("frontoffice.family.addPerson")}
          </button>
        </div>
        {data.persons.length === 0 ? (
          <p className="text-[13.5px] text-ink-3">
            {t("frontoffice.family.noPersons")}
          </p>
        ) : (
          <ul className="list">
            {data.persons.map((person) => (
              <li
                key={person.id}
                className="li flex-wrap items-center gap-3"
                data-testid="pickup-person"
              >
                <PhotoThumb
                  fileId={person.photoFileId}
                  alt={person.name}
                  size={44}
                />
                <span className="min-w-[10rem] flex-1">
                  <b className="block">{person.name}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {person.relation} · {person.phoneMasked}
                  </span>
                </span>
                <span className="flex items-center gap-1 sm:ml-auto">
                  <PhotoCapture
                    label={
                      person.photoFileId
                        ? t("frontoffice.photo.retake")
                        : t("frontoffice.photo.take")
                    }
                    testId="person-photo"
                    onPhoto={async (blob, name) => {
                      await frontOfficeApi.personPhoto(
                        childId,
                        person.id,
                        blob,
                        name,
                      );
                      toast(t("frontoffice.photo.saved"));
                      pickup.reload();
                    }}
                  />
                  <button
                    type="button"
                    className="iconbtn"
                    aria-label={t("common.edit")}
                    onClick={() => setEditing(person)}
                  >
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    className="iconbtn"
                    aria-label={t("common.delete")}
                    onClick={() => setRemoving(person)}
                  >
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <RequestDialog
        open={requesting}
        childId={childId}
        onClose={() => setRequesting(false)}
        onSaved={() => {
          setRequesting(false);
          toast(t("frontoffice.family.requested"));
          pickup.reload();
        }}
      />
      <PersonDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        open={editing !== null}
        childId={childId}
        person={editing === "new" ? null : editing}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          toast(t("frontoffice.family.personSaved"));
          pickup.reload();
        }}
      />
      <ConfirmDialog
        open={cancelling !== null}
        title={t("frontoffice.family.cancelTitle")}
        body={t("frontoffice.family.cancelBody")}
        confirmLabel={t("frontoffice.family.cancel")}
        onConfirm={async () => {
          if (!cancelling) return;
          await frontOfficeApi.cancelPickup(childId, cancelling.id);
          setCancelling(null);
          pickup.reload();
        }}
        onClose={() => setCancelling(null)}
      />
      <ConfirmDialog
        open={removing !== null}
        title={t("frontoffice.family.removePerson")}
        body={
          removing
            ? t("frontoffice.family.removePersonBody", { name: removing.name })
            : ""
        }
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          if (!removing) return;
          await frontOfficeApi.removePerson(childId, removing.id);
          setRemoving(null);
          pickup.reload();
        }}
        onClose={() => setRemoving(null)}
      />
    </>
  );
}

function RequestDialog({
  open,
  childId,
  onClose,
  onSaved,
}: {
  open: boolean;
  childId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const form = useForm<PassValues>(EMPTY_PASS());
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(passProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: PickupRequest = {
      pickupAt: localToInstant(v.pickupAt) ?? "",
      reason: v.reason.trim(),
      collectorKind: v.collectorKind,
      collectorName:
        v.collectorKind === "OTHER" ? v.collectorName.trim() : null,
      collectorPhone:
        v.collectorKind === "OTHER" ? v.collectorPhone.trim() : null,
    };
    const ok = await form.submit(t, async () => {
      await frontOfficeApi.requestPickup(childId, body);
    });
    if (ok) {
      form.reset(EMPTY_PASS());
      onSaved();
    }
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t("frontoffice.family.request")}
      description={t("frontoffice.family.requestHint")}
      closeLabel={t("common.close")}
    >
      <form
        ref={setFormEl}
        method="post"
        noValidate
        onSubmit={submit}
        className="flex flex-col gap-3"
        data-testid="pickup-form"
      >
        <FormAlert message={form.formError} />
        <PassFields form={form} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={form.submitting}
            data-testid="pickup-save"
          >
            {t("frontoffice.family.send")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function PersonDialog({
  open,
  childId,
  person,
  onClose,
  onSaved,
}: {
  open: boolean;
  childId: string;
  person: PickupPersonView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const form = useForm<PersonValues>(
    person
      ? { name: person.name, relation: person.relation, phone: "" }
      : { name: "", relation: "", phone: "" },
  );
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(personProblems(form.values), t, formEl)) return;
    const body: PickupPersonRequest = {
      name: form.values.name.trim(),
      relation: form.values.relation.trim(),
      phone: form.values.phone.trim(),
    };
    const ok = await form.submit(t, async () => {
      if (person) await frontOfficeApi.updatePerson(childId, person.id, body);
      else await frontOfficeApi.addPerson(childId, body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={
        person
          ? t("frontoffice.family.editPerson")
          : t("frontoffice.family.addPerson")
      }
      closeLabel={t("common.close")}
    >
      <form
        ref={setFormEl}
        method="post"
        noValidate
        onSubmit={submit}
        className="flex flex-col gap-3"
        data-testid="person-form"
      >
        <FormAlert message={form.formError} />
        <TextField
          label={t("frontoffice.field.name")}
          name="name"
          value={form.values.name}
          onChange={(e) => form.set("name", e.target.value)}
          error={form.errors.name}
          maxLength={200}
          required
        />
        <TextField
          label={t("frontoffice.family.relation")}
          name="relation"
          value={form.values.relation}
          onChange={(e) => form.set("relation", e.target.value)}
          error={form.errors.relation}
          hint={t("frontoffice.family.relationHint")}
          maxLength={60}
          required
        />
        <TextField
          label={t("frontoffice.field.phone")}
          name="phone"
          type="tel"
          inputMode="tel"
          value={form.values.phone}
          onChange={(e) => form.set("phone", e.target.value)}
          error={form.errors.phone}
          hint={
            person
              ? t("frontoffice.family.phoneAgain", {
                  masked: person.phoneMasked,
                })
              : undefined
          }
          maxLength={15}
          required
        />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={form.submitting}
            data-testid="person-save"
          >
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** The "Early pickup" card on the family home: today's pass if any, the people allowed to collect, and the link. */
export function FamilyPickupCard({
  data,
  href,
}: {
  data: ApiData<FamilyPickup>;
  href: string;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const view = data.data;
  const open = view?.passes.find(
    (p) => p.status === "REQUESTED" || p.status === "APPROVED",
  );
  return (
    <section
      className="card flex flex-col gap-3"
      aria-labelledby="family-pickup-card"
      data-testid="family-pickup-card"
    >
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="family-pickup-card">{t("nav.pickup")}</h2>
        <CarFront size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {data.error?.status === 404 ? (
        <p className="text-sm text-ink-2">{t("children.notLinked")}</p>
      ) : data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={2} />
      ) : (
        <div className="flex flex-col gap-1.5 text-[13.5px]">
          {open ? (
            <p className="flex flex-wrap items-center gap-2 font-semibold">
              {t("frontoffice.pass.at", { time: when(open.pickupAt, locale) })}
              <PassStatusPill status={open.status} />
            </p>
          ) : (
            <p className="text-ink-2">{t("frontoffice.family.noPassToday")}</p>
          )}
          <p className="text-ink-2">
            {view.persons.length > 0
              ? plural(
                  t,
                  "frontoffice.family.personsCount",
                  view.persons.length,
                )
              : t("frontoffice.family.noPersonsShort")}
          </p>
          {view.persons.length > 0 ? (
            <Pill tone="neutral">
              {view.persons.map((p) => p.name).join(", ")}
            </Pill>
          ) : null}
        </div>
      )}
      <Link
        href={href}
        className="link mt-auto inline-flex items-center gap-1 text-[13.5px]"
      >
        {t("frontoffice.family.open")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
    </section>
  );
}
