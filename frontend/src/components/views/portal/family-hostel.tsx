"use client";

import { ArrowRight, BedDouble, DoorOpen } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { HostelPassPill, passDates, passKindLabel, ROLL_CALL_TONE, rollCallLabel, when } from "@/components/views/hostel/hostel-shared";
import { EMPTY_PASS, PassFields, passProblems, type PassValues } from "@/components/views/hostel/passes-view";
import { formatPlainDate } from "@/lib/format";
import { hostelApi } from "@/lib/hostel-api";
import { localeFor, useI18n } from "@/lib/i18n";
import type { FamilyHostel, HostelPassRequest, HostelPassView } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { ChildSwitcher, useFamilyChildren } from "./family-shared";

/* The family app's hostel pieces: the home card for a boarder and /app/family/hostel, the outing and leave passes. */

/** The passes still to come, first. */
export function openFirst(passes: HostelPassView[]): HostelPassView[] {
  const open = passes.filter((p) => p.status === "REQUESTED" || p.status === "APPROVED" || p.status === "OUT");
  return [...open, ...passes.filter((p) => !open.includes(p))];
}

/**
 * The "Hostel" card on the family home: the bed, the warden, the next pass and last night's roll call. Rendered only
 * for a boarder; a day scholar (or a child the API does not know as one) shows nothing at all.
 */
export function FamilyHostelCard({ data, href }: { data: ApiData<FamilyHostel>; href: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const view = data.data;
  if (!view || !view.boarder) return null;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="family-hostel-card" data-testid="family-hostel-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="family-hostel-card">{t("nav.hostel")}</h2>
        <BedDouble size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      <div className="flex flex-col gap-1.5 text-[13.5px]">
        <p className="font-semibold" data-testid="family-hostel-bed">
          {view.hostelName} · {t("hostel.occupancy.room", { number: view.roomNumber ?? "" })} / {view.bedLabel}
        </p>
        <p className="text-ink-2">{view.wardenName ? t("hostel.family.warden", { name: view.wardenName }) : t("hostel.hostels.noWarden")}</p>
        <p className="flex flex-wrap items-center gap-2 text-ink-2" data-testid="family-hostel-next">
          {view.nextPass ? (
            <>
              {passKindLabel(t, view.nextPass.kind)} · {passDates(view.nextPass.fromDate, view.nextPass.toDate, locale, t)}
              <HostelPassPill status={view.nextPass.status} late={view.nextPass.late} />
            </>
          ) : (
            t("hostel.family.noPass")
          )}
        </p>
        <p className="flex flex-wrap items-center gap-2 text-ink-2" data-testid="family-hostel-roll-call">
          {view.lastRollCallNight && view.lastRollCallStatus ? (
            <>
              {t("hostel.family.rollCall", { date: formatPlainDate(view.lastRollCallNight, locale) })}
              <Pill tone={ROLL_CALL_TONE[view.lastRollCallStatus]} dot>
                {rollCallLabel(t, view.lastRollCallStatus)}
              </Pill>
            </>
          ) : (
            t("hostel.family.noRollCall")
          )}
        </p>
      </div>
      <Link href={href} className="btn btn-sm self-start" data-testid="family-hostel-open">
        {t("hostel.family.passes")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
    </section>
  );
}

/** /app/family/hostel: a parent asks for an outing or home leave for a boarder and follows the passes. */
export function FamilyHostelView({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const { children, list, child, choose } = useFamilyChildren(initialChildId);
  return (
    <>
      <PageHead title={t("nav.hostel")} />
      {children.error && !children.data ? (
        <ErrorState error={children.error} onRetry={children.reload} />
      ) : !children.data ? (
        <LoadingRows rows={3} />
      ) : !child ? (
        <p className="empty">{t("children.empty")}</p>
      ) : (
        <>
          <ChildSwitcher list={list} value={child.id} onChange={choose} />
          <ChildHostel key={child.id} childId={child.id} />
        </>
      )}
    </>
  );
}

function ChildHostel({ childId }: { childId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const hostel = useApiData(`hostel:family:${childId}`, () => hostelApi.childHostel(childId));
  const passes = useApiData(`hostel:family-passes:${childId}`, () => hostelApi.childPasses(childId));
  const [requesting, setRequesting] = useState(false);
  const [cancelling, setCancelling] = useState<HostelPassView | null>(null);
  const view = hostel.data;
  if (hostel.error && !view) return <ErrorState error={hostel.error} onRetry={hostel.reload} />;
  if (!view) return <LoadingRows rows={3} />;
  if (!view.boarder)
    return (
      <p className="empty" data-testid="family-hostel-none">
        {t("hostel.family.notBoarder", { name: view.studentName })}
      </p>
    );
  const rows = passes.data;
  return (
    <>
      <FamilyHostelCard data={hostel} href="#family-hostel-passes" />
      <section className="card flex flex-col gap-3" aria-labelledby="family-hostel-passes" data-testid="family-hostel-passes">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <div className="min-w-0">
            <h2 id="family-hostel-passes">{t("hostel.family.passes")}</h2>
            <p className="mt-1 text-[13px] text-ink-3">{t("hostel.family.intro")}</p>
          </div>
          {view.canRequest ? (
            <button type="button" className="btn btn-primary" onClick={() => setRequesting(true)} data-testid="hostel-pass-request">
              <DoorOpen size={18} aria-hidden="true" />
              {t("hostel.family.request")}
            </button>
          ) : null}
        </div>
        {passes.error && !rows ? (
          <ErrorState error={passes.error} onRetry={passes.reload} />
        ) : !rows ? (
          <LoadingRows rows={2} />
        ) : rows.length === 0 ? (
          <p className="text-[13.5px] text-ink-3" data-testid="hostel-pass-none">
            {t("hostel.family.noPasses")}
          </p>
        ) : (
          <ul className="rowcards">
            {openFirst(rows).map((pass) => (
              <li key={pass.id} className="rowcard items-start" data-testid="family-hostel-pass" data-status={pass.status}>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold text-ink">
                      {passKindLabel(t, pass.kind)} · {passDates(pass.fromDate, pass.toDate, locale, t)}
                    </span>
                    <HostelPassPill status={pass.status} late={pass.late} />
                  </div>
                  <p className="text-[13px] text-ink-2">{pass.reason}</p>
                  <p className="text-[12.5px] text-ink-3">
                    {t("hostel.pass.returnBy", { time: when(pass.returnBy, locale) })}
                    {pass.decidedByName ? ` · ${t("hostel.pass.decidedBy", { who: pass.decidedByName, time: when(pass.decidedAt, locale) })}` : ""}
                    {pass.outAt ? ` · ${t("hostel.pass.outAt", { time: when(pass.outAt, locale) })}` : ""}
                    {pass.inAt ? ` · ${t("hostel.pass.inAt", { time: when(pass.inAt, locale) })}` : ""}
                  </p>
                  {pass.decisionNote ? <p className="text-[12.5px] text-ink-3">{pass.decisionNote}</p> : null}
                </div>
                {pass.canCancel ? (
                  <button type="button" className="btn btn-sm" onClick={() => setCancelling(pass)} data-testid="hostel-pass-cancel">
                    {t("hostel.family.cancel")}
                  </button>
                ) : null}
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
          toast(t("hostel.family.requested"));
          passes.reload();
          hostel.reload();
        }}
      />
      <ConfirmDialog
        open={cancelling !== null}
        title={t("hostel.family.cancelTitle")}
        body={t("hostel.family.cancelBody")}
        confirmLabel={t("hostel.family.cancel")}
        onConfirm={async () => {
          if (!cancelling) return;
          await hostelApi.cancelPass(childId, cancelling.id);
          setCancelling(null);
          toast(t("hostel.family.cancelled"));
          passes.reload();
          hostel.reload();
        }}
        onClose={() => setCancelling(null)}
      />
    </>
  );
}

function RequestDialog({ open, childId, onClose, onSaved }: { open: boolean; childId: string; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<PassValues>(EMPTY_PASS());
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(passProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: HostelPassRequest = { kind: v.kind, fromDate: v.fromDate, toDate: v.kind === "HOME_LEAVE" ? v.toDate : null, reason: v.reason.trim() };
    const ok = await form.submit(t, async () => {
      await hostelApi.requestPass(childId, body);
    });
    if (ok) {
      form.reset(EMPTY_PASS());
      onSaved();
    }
  };
  return (
    <Dialog open={open} onClose={onClose} title={t("hostel.family.request")} description={t("hostel.family.requestHint")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="hostel-pass-form">
        <FormAlert message={form.formError} />
        <PassFields form={form} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="hostel-pass-save">
            {t("hostel.family.send")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
