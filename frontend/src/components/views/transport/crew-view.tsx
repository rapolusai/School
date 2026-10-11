"use client";

import { Pencil, Search, UserPlus } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { initials } from "@/lib/format";
import { localeFor, plural, roleLabel, useI18n } from "@/lib/i18n";
import { LICENCE_PATTERN, transportApi } from "@/lib/transport-api";
import { CREW_KINDS, type CrewKind, type CrewMember, type StaffOption } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";
import { crewKindLabel, dateOrDash, DocumentStatePill, FlagPills, TransportNav, useCanManageTransport } from "./transport-shared";

/** /app/transport/crew: drivers (with their licences) and attendants, who are members of staff. */
export function CrewView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageTransport();
  const crew = useApiData("transport:crew", transportApi.crew);
  const [editing, setEditing] = useState<CrewMember | "new" | null>(null);
  const [removing, setRemoving] = useState<CrewMember | null>(null);
  const list = crew.data ?? [];

  return (
    <>
      <PageHead
        eyebrow={crew.data ? plural(t, "transport.crew.eyebrow", list.length) : t("nav.transport")}
        title={t("transport.crew.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="crew-new">
              <UserPlus size={18} aria-hidden="true" />
              {t("transport.crew.new")}
            </button>
          ) : null
        }
      />
      <TransportNav />
      <section className="card flex flex-col gap-3" aria-label={t("transport.crew.title")} data-testid="crew">
        <p className="text-[13px] text-ink-2">{t("transport.crew.intro")}</p>
        {crew.error && !crew.data ? (
          <ErrorState error={crew.error} onRetry={crew.reload} />
        ) : !crew.data ? (
          <LoadingRows rows={4} />
        ) : list.length === 0 ? (
          <p className="empty">{t("transport.crew.empty")}</p>
        ) : (
          <ul className="rowcards">
            {list.map((c) => (
              <li key={c.id} className="rowcard flex-wrap items-start" data-testid="crew-row">
                <span className="avatar" aria-hidden="true">
                  {initials(c.name)}
                </span>
                <span className="min-w-0 flex-1">
                  <b className="block">
                    {c.name} <Pill tone={c.kind === "DRIVER" ? "info" : "accent"}>{crewKindLabel(t, c.kind)}</Pill>
                  </b>
                  <span className="block text-[12.5px] text-ink-3">
                    {[c.employeeCode, c.designation, c.mobile].filter(Boolean).join(" · ")}
                  </span>
                  <span className="block text-[12.5px] text-ink-2">
                    {c.routes.length > 0 ? c.routes.map((r) => r.name).join(", ") : t("transport.crew.noRoute")}
                  </span>
                  {c.kind === "DRIVER" ? (
                    <span className="mt-1 flex flex-wrap items-center gap-1.5 text-[12.5px]">
                      <span className="text-ink-2">
                        {t("transport.crew.licence", { no: c.licenceNo ?? "—", date: dateOrDash(c.licenceExpiry, locale) })}
                      </span>
                      {c.licenceState ? <DocumentStatePill state={c.licenceState} daysLeft={c.licenceDaysLeft} /> : null}
                    </span>
                  ) : null}
                  <span className="block text-[12.5px] text-ink-3">
                    {c.policeVerifiedOn
                      ? t("transport.crew.verified", { date: dateOrDash(c.policeVerifiedOn, locale) })
                      : t("transport.crew.notVerified")}
                  </span>
                  <span className="mt-1 block">
                    <FlagPills flags={c.flags.filter((f) => f !== "LICENCE_EXPIRING")} />
                  </span>
                </span>
                {canManage ? (
                  <span className="flex gap-1.5">
                    <button type="button" className="btn btn-sm" onClick={() => setEditing(c)} aria-label={t("transport.crew.editTitle", { name: c.name })}>
                      <Pencil size={16} aria-hidden="true" />
                      {t("common.edit")}
                    </button>
                    <button type="button" className="btn btn-sm" onClick={() => setRemoving(c)}>
                      {t("common.remove")}
                    </button>
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </section>

      {editing ? (
        <CrewDialog
          member={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            setEditing(null);
            toast(t("transport.crew.saved", { name: saved.name }));
            crew.reload();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={removing !== null}
        title={t("transport.crew.removeTitle", { name: removing?.name ?? "" })}
        body={t("transport.crew.removeBody")}
        confirmLabel={t("common.remove")}
        onClose={() => setRemoving(null)}
        onConfirm={async () => {
          if (!removing) return;
          await transportApi.removeCrew(removing.id);
          setRemoving(null);
          toast(t("transport.crew.removed"));
          crew.reload();
        }}
      />
    </>
  );
}

type CrewValues = { userId: string; kind: CrewKind; licenceNo: string; licenceExpiry: string; policeVerifiedOn: string };

/** The same checks the API makes on a crew member. Pure and exported for tests. */
export function validateCrew(values: CrewValues, needUser = true): Problems {
  const problems: Problems = {};
  if (needUser && !values.userId) problems.userId = "transport.v.staff";
  const licence = values.licenceNo.trim();
  if (values.kind === "DRIVER") {
    if (!licence) problems.licenceNo = "validation.required";
    if (!values.licenceExpiry) problems.licenceExpiry = "validation.required";
  }
  if (licence && !LICENCE_PATTERN.test(licence)) problems.licenceNo = "transport.v.licence";
  if (values.licenceExpiry && !isPlainDate(values.licenceExpiry)) problems.licenceExpiry = "validation.date";
  if (values.policeVerifiedOn && !isPlainDate(values.policeVerifiedOn)) problems.policeVerifiedOn = "validation.date";
  return problems;
}

function StaffPicker({ picked, onPick, error }: { picked: StaffOption | null; onPick: (s: StaffOption | null) => void; error?: string }) {
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
  const results = useApiData(`transport:staff:${query.trim()}`, () => transportApi.staff(query.trim()));
  if (picked) {
    return (
      <div className="field">
        <span className="field-label">{t("transport.crew.person")}</span>
        <div className="rowcard" data-testid="crew-picked">
          <span className="avatar" aria-hidden="true">
            {initials(picked.name)}
          </span>
          <span className="min-w-0 flex-1">
            <b className="block">{picked.name}</b>
            <span className="block text-[12.5px] text-ink-3">{[picked.employeeCode, picked.designation].filter(Boolean).join(" · ")}</span>
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
    <div className="field" data-testid="crew-staff-picker">
      <label className="search max-w-none flex-none">
        <Search size={18} aria-hidden="true" />
        <span className="sr-only">{t("transport.crew.findStaff")}</span>
        <input
          type="search"
          name="userId"
          value={text}
          placeholder={t("transport.crew.findStaff")}
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
      {results.error && !results.data ? (
        <ErrorState error={results.error} onRetry={results.reload} />
      ) : !results.data ? (
        <LoadingRows rows={2} />
      ) : hits.length === 0 ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("transport.crew.noStaff")}
        </p>
      ) : (
        <ul className="pick-list" aria-label={t("transport.crew.findStaff")}>
          {hits.map((hit) => (
            <li key={hit.userId}>
              <button type="button" className="pick" onClick={() => onPick(hit)} disabled={hit.crew}>
                <span className="avatar" aria-hidden="true">
                  {initials(hit.name)}
                </span>
                <span className="min-w-0 flex-1 text-left">
                  <b className="block truncate font-semibold">{hit.name}</b>
                  <span className="block truncate text-[12.5px] text-ink-3">
                    {hit.crew
                      ? t("transport.crew.alreadyCrew")
                      : [hit.employeeCode, hit.designation, hit.roles.map((r) => roleLabel(t, r)).join(", ")].filter(Boolean).join(" · ")}
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

function CrewDialog({ member, onClose, onSaved }: { member: CrewMember | null; onClose: () => void; onSaved: (c: CrewMember) => void }) {
  const { t } = useI18n();
  const [person, setPerson] = useState<StaffOption | null>(null);
  const form = useForm<CrewValues>({
    userId: member?.userId ?? "",
    kind: member?.kind ?? "DRIVER",
    licenceNo: member?.licenceNo ?? "",
    licenceExpiry: member?.licenceExpiry ?? "",
    policeVerifiedOn: member?.policeVerifiedOn ?? "",
  });
  const { values, set, errors } = form;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateCrew(values, member === null), t, event.currentTarget)) return;
    const details = {
      kind: values.kind,
      licenceNo: values.licenceNo.trim() ? values.licenceNo.trim().toUpperCase() : null,
      licenceExpiry: values.licenceExpiry || null,
      policeVerifiedOn: values.policeVerifiedOn || null,
    };
    const result: { member?: CrewMember } = {};
    const ok = await form.submit(t, async () => {
      result.member = member
        ? await transportApi.updateCrew(member.id, details)
        : await transportApi.addCrew({ userId: values.userId, ...details });
    });
    if (ok && result.member) onSaved(result.member);
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={member ? t("transport.crew.editTitle", { name: member.name }) : t("transport.crew.new")}
      description={t("transport.crew.dialogIntro")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="crew-form">
        <FormAlert message={form.formError} />
        {member ? null : (
          <StaffPicker
            picked={person}
            error={errors.userId}
            onPick={(s) => {
              setPerson(s);
              set("userId", s?.userId ?? "");
            }}
          />
        )}
        <SelectField
          label={t("transport.crew.kind")}
          name="kind"
          value={values.kind}
          onChange={(e) => set("kind", e.target.value as CrewKind)}
          error={errors.kind}
          options={CREW_KINDS.map((kind) => ({ value: kind, label: crewKindLabel(t, kind) }))}
        />
        <div className="grid2">
          <TextField
            label={t("transport.crew.licenceNo")}
            name="licenceNo"
            value={values.licenceNo}
            onChange={(e) => set("licenceNo", e.target.value.toUpperCase())}
            error={errors.licenceNo}
            hint={values.kind === "DRIVER" ? undefined : t("transport.crew.optional")}
            maxLength={30}
            autoComplete="off"
          />
          <TextField
            label={t("transport.crew.licenceExpiry")}
            name="licenceExpiry"
            type="date"
            value={values.licenceExpiry}
            onChange={(e) => set("licenceExpiry", e.target.value)}
            error={errors.licenceExpiry}
          />
        </div>
        <TextField
          label={t("transport.crew.policeVerified")}
          name="policeVerifiedOn"
          type="date"
          value={values.policeVerifiedOn}
          onChange={(e) => set("policeVerifiedOn", e.target.value)}
          error={errors.policeVerifiedOn}
          hint={t("transport.crew.policeHint")}
        />
        <p className="text-[12.5px] text-ink-3">{t("transport.crew.roleNote")}</p>
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
