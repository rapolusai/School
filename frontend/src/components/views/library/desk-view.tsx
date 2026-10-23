"use client";

import { BookCheck, Receipt, RotateCcw, UserRoundX } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage, fieldErrorMessage } from "@/lib/error-message";
import { formatPaise, formatPlainDate, initials, parseRupees, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n, type Translate } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import type { FineReceipt, LibraryMember, LoanView, MemberAccount, ReturnResult } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { CollectDialog, collectKey } from "./fine-dialogs";
import {
  fineKindLabel,
  HoldStatusPill,
  LibraryNav,
  MAX_BACKDATE_DAYS,
  MAX_PRICE_PAISE,
  MemberPicker,
  memberName,
  memberSub,
  memberTypeLabel,
  shiftDate,
} from "./library-shared";
import { ScanField } from "./scan-field";

/** Why nothing more can be lent to this member, in words, or null. Pure and exported for tests. */
export function blockedReason(t: Translate, account: MemberAccount): string | null {
  if (!account.member.active) return t("library.desk.blocked.left", { name: memberName(t, account.member) });
  if (account.loans.length >= account.rule.maxBooks) {
    return plural(t, "library.desk.blocked.limit", account.rule.maxBooks, { name: memberName(t, account.member) });
  }
  return null;
}

/** The message under the accession field for a failed issue or return: the field's own error first. */
function deskError(caught: unknown, t: Translate): string {
  const error = toApiError(caught);
  const field = error.errors?.accessionNo ?? error.errors?.issuedOn;
  return field ? fieldErrorMessage(field, t) : errorMessage(error, t);
}

/** /app/library/desk: lend books to a member and take books back, with a barcode scanner or by hand. */
export function DeskView() {
  const { t } = useI18n();
  const [member, setMember] = useState<LibraryMember | null>(null);
  const [returnedTick, setReturnedTick] = useState(0);

  return (
    <>
      <PageHead eyebrow={t("nav.library")} title={t("library.desk.title")} />
      <LibraryNav />
      <div className="desk-grid">
        <section className="card flex flex-col gap-3.5" aria-labelledby="desk-lend" data-testid="desk-lend">
          <div className="card-head">
            <h2 id="desk-lend">{t("library.desk.lend")}</h2>
            {member ? (
              <button type="button" className="btn btn-sm" onClick={() => setMember(null)}>
                {t("library.desk.changeMember")}
              </button>
            ) : null}
          </div>
          {member ? (
            <MemberDesk key={`${member.type}:${member.id}`} member={member} refresh={returnedTick} />
          ) : (
            <>
              <p className="text-[13px] text-ink-2">{t("library.desk.pickMember")}</p>
              <MemberPicker onPick={setMember} />
            </>
          )}
        </section>
        <ReturnPanel autoFocus={member === null} onReturned={() => setReturnedTick((n) => n + 1)} />
      </div>
    </>
  );
}

/* ------------------------------------------------------------------ lending */

/** One member's account at the desk; `refresh` changes when a book comes back, to show the account again. */
function MemberDesk({ member, refresh }: { member: LibraryMember; refresh: number }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const account = useApiData(`library:account:${member.type}:${member.id}:${refresh}`, () =>
    libraryApi.account(member.type, member.id),
  );
  const [today] = useState(() => todayInIndia());
  const [backdate, setBackdate] = useState(false);
  const [issuedOn, setIssuedOn] = useState(today);
  const [collecting, setCollecting] = useState(false);
  const [lost, setLost] = useState<LoanView | null>(null);
  const [renewing, setRenewing] = useState<LoanView | null>(null);
  const earliest = shiftDate(today, -MAX_BACKDATE_DAYS);
  const dateProblem = backdate && (!/^\d{4}-\d{2}-\d{2}$/.test(issuedOn) || issuedOn > today || issuedOn < earliest);

  const issue = async (accessionNo: string): Promise<string | null> => {
    if (dateProblem) return t("library.desk.v.issuedOn", { days: MAX_BACKDATE_DAYS });
    try {
      const loan = await libraryApi.issue({
        accessionNo,
        memberType: member.type,
        memberId: member.id,
        issuedOn: backdate && issuedOn !== today ? issuedOn : null,
      });
      toast(t("library.desk.lent", { title: loan.title, date: formatPlainDate(loan.dueOn, locale) }));
      account.reload();
      return null;
    } catch (caught) {
      return deskError(caught, t);
    }
  };

  const data = account.data;
  const blocked = data ? blockedReason(t, data) : null;

  return (
    <div className="flex flex-col gap-3.5" data-testid="member-desk">
      <div className="person">
        <span className="avatar" aria-hidden="true">
          {initials(member.name ?? "?")}
        </span>
        <span className="min-w-0 flex-1">
          <b className="block truncate" data-testid="desk-member-name">
            {memberName(t, member)}
          </b>
          <span className="sub">{memberSub(member)}</span>
        </span>
        <Pill tone={member.type === "STUDENT" ? "info" : "accent"}>{memberTypeLabel(t, member.type)}</Pill>
      </div>

      {account.error && !data ? (
        <ErrorState error={account.error} onRetry={account.reload} />
      ) : !data ? (
        <LoadingRows rows={3} />
      ) : (
        <>
          <p className="text-[13px] text-ink-2" data-testid="desk-rule">
            {t("library.desk.rule", { books: data.rule.maxBooks, days: data.rule.loanDays })}{" "}
            {plural(t, "library.desk.renewals", data.rule.maxRenewals)}
            {data.rule.finePerDayPaise > 0 ? ` ${t("library.desk.finePerDay", { amount: formatPaise(data.rule.finePerDayPaise) })}` : ""}
          </p>
          {blocked ? (
            <p className="alert alert-warn" role="status" data-testid="desk-blocked">
              <UserRoundX size={18} aria-hidden="true" className="mt-0.5 flex-none" />
              <span>{blocked}</span>
            </p>
          ) : null}
          {data.finesDuePaise > 0 ? (
            <div className="alert alert-warn flex-wrap items-center justify-between" data-testid="desk-fines">
              <span>{t("library.desk.finesDue", { amount: formatPaise(data.finesDuePaise) })}</span>
              <button type="button" className="btn btn-sm" onClick={() => setCollecting(true)}>
                {t("library.desk.collect")}
              </button>
            </div>
          ) : null}

          <ScanField
            label={t("library.desk.accession")}
            name="issueAccessionNo"
            action={t("library.desk.lendButton")}
            onScan={issue}
            hint={t("library.desk.scanHint")}
            testId="issue-form"
          />
          <div className="flex flex-col gap-2">
            <label className="check">
              <input type="checkbox" name="backdate" checked={backdate} onChange={(e) => setBackdate(e.target.checked)} />
              <span>{t("library.desk.backdate")}</span>
            </label>
            {backdate ? (
              <TextField
                label={t("library.desk.issuedOn")}
                name="issuedOn"
                type="date"
                value={issuedOn}
                min={earliest}
                max={today}
                onChange={(e) => setIssuedOn(e.target.value)}
                error={dateProblem ? t("library.desk.v.issuedOn", { days: MAX_BACKDATE_DAYS }) : undefined}
                hint={t("library.desk.issuedOnHint")}
                className="sm:max-w-56"
              />
            ) : null}
          </div>

          <LoanList
            loans={data.loans}
            onRenew={setRenewing}
            onLost={setLost}
          />
          {data.holds.length > 0 ? (
            <div className="flex flex-col gap-1.5">
              <h3 className="text-[14px] font-semibold">{t("library.desk.holds")}</h3>
              <ul className="rowcards">
                {data.holds.map((hold) => (
                  <li key={hold.id} className="rowcard">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate">{hold.title}</b>
                      <span className="block text-[12.5px] text-ink-3">
                        {hold.status === "READY" && hold.holdUntil
                          ? t("library.hold.keptUntil", { date: formatPlainDate(hold.holdUntil, locale), copy: hold.copyAccessionNo ?? "" })
                          : t("library.hold.position", { n: hold.position })}
                      </span>
                    </span>
                    <HoldStatusPill status={hold.status} />
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
          {data.history.length > 0 ? (
            <details className="text-[13px]">
              <summary className="cursor-pointer font-semibold">{t("library.desk.history", { count: data.history.length })}</summary>
              <ul className="mt-2 flex flex-col gap-1 text-ink-2">
                {data.history.map((loan) => (
                  <li key={loan.id}>
                    {loan.title} · <span className="mono">{loan.accessionNo}</span> ·{" "}
                    {loan.returnedOn
                      ? t("library.desk.returnedOn", { date: formatPlainDate(loan.returnedOn, locale) })
                      : t("library.loan.status.LOST")}
                  </li>
                ))}
              </ul>
            </details>
          ) : null}

          <CollectDialog
            key={collectKey(data.finesDue)}
            open={collecting}
            fines={data.finesDue}
            onClose={() => setCollecting(false)}
            onCollected={(receipt) => {
              setCollecting(false);
              toastReceipt(toast, t, receipt);
              account.reload();
            }}
          />
          <LostDialog
            loan={lost}
            onClose={() => setLost(null)}
            onDone={(result) => {
              setLost(null);
              const fine = result.fines.reduce((sum, f) => sum + f.amountPaise, 0);
              toast(t("library.lost.done", { title: result.loan.title, amount: formatPaise(fine) }));
              account.reload();
            }}
          />
          <ConfirmDialog
            open={renewing !== null}
            title={t("library.renew.title", { title: renewing?.title ?? "" })}
            body={t("library.renew.body", { left: renewing?.renewalsLeft ?? 0 })}
            confirmLabel={t("library.renew.submit")}
            danger={false}
            onClose={() => setRenewing(null)}
            onConfirm={async () => {
              if (!renewing) return;
              const loan = await libraryApi.renew(renewing.id);
              setRenewing(null);
              toast(t("library.renew.done", { title: loan.title, date: formatPlainDate(loan.dueOn, locale) }));
              account.reload();
            }}
          />
        </>
      )}
    </div>
  );
}

export function toastReceipt(toast: (message: string) => void, t: Translate, receipt: FineReceipt) {
  toast(t("library.collect.done", { amount: formatPaise(receipt.totalPaise), receipt: receipt.receiptNo }));
}

/** The member's books on loan, with renew and lost. */
function LoanList({
  loans,
  onRenew,
  onLost,
}: {
  loans: LoanView[];
  onRenew: (loan: LoanView) => void;
  onLost: (loan: LoanView) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <div className="flex flex-col gap-1.5">
      <h3 className="text-[14px] font-semibold">{t("library.desk.onLoan", { count: loans.length })}</h3>
      {loans.length === 0 ? (
        <p className="text-[13px] text-ink-3">{t("library.desk.noLoans")}</p>
      ) : (
        <ul className="rowcards" data-testid="desk-loans">
          {loans.map((loan) => (
            <li key={loan.id} className="rowcard flex-wrap items-start">
              <span className="min-w-0 flex-1">
                <b className="block">{loan.title}</b>
                <span className="block text-[12.5px] text-ink-3">
                  <span className="mono">{loan.accessionNo}</span> ·{" "}
                  {t("library.loan.due", { date: formatPlainDate(loan.dueOn, locale) })}
                </span>
                {loan.overdue ? (
                  <span className="mt-1 flex flex-wrap gap-1">
                    <Pill tone="bad">{plural(t, "library.loan.daysOverdue", loan.daysOverdue)}</Pill>
                    {loan.fineSoFarPaise > 0 ? (
                      <Pill tone="warn">{t("library.loan.fineSoFar", { amount: formatPaise(loan.fineSoFarPaise) })}</Pill>
                    ) : null}
                  </span>
                ) : null}
              </span>
              <span className="flex gap-1.5">
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => onRenew(loan)}
                  disabled={loan.renewalsLeft === 0 || loan.overdue}
                  title={loan.overdue ? t("library.renew.overdue") : loan.renewalsLeft === 0 ? t("library.renew.none") : undefined}
                >
                  <RotateCcw size={16} aria-hidden="true" />
                  {t("library.renew.button")}
                </button>
                <button type="button" className="btn btn-sm" onClick={() => onLost(loan)}>
                  {t("library.lost.button")}
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** The member reports a book lost: the charge (the copy's price when left empty) becomes a fine. */
function LostDialog({ loan, onClose, onDone }: { loan: LoanView | null; onClose: () => void; onDone: (result: ReturnResult) => void }) {
  const { t } = useI18n();
  const empty = { charge: "", note: "" };
  const form = useForm(empty);
  const { values, set, errors } = form;

  const close = () => {
    form.reset(empty);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!loan) return;
    const problems: Problems = {};
    const charge = values.charge.trim() ? parseRupees(values.charge) : null;
    if (values.charge.trim() && (charge === null || charge > MAX_PRICE_PAISE)) problems.chargePaise = "library.v.amount";
    if (values.note.trim().length > 300) problems.note = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    let result: ReturnResult | undefined;
    const ok = await form.submit(t, async () => {
      result = await libraryApi.lost(loan.id, { chargePaise: charge, note: values.note.trim() || null });
    });
    if (ok && result) {
      form.reset(empty);
      onDone(result);
    }
  };

  return (
    <Dialog
      open={loan !== null}
      onClose={close}
      title={t("library.lost.title", { title: loan?.title ?? "" })}
      description={t("library.lost.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("library.lost.charge")}
          name="chargePaise"
          inputMode="decimal"
          value={values.charge}
          onChange={(e) => set("charge", e.target.value)}
          error={errors.chargePaise}
          hint={t("library.lost.chargeHint")}
          autoComplete="off"
          data-autofocus
        />
        <TextAreaField
          label={t("library.field.noteOptional")}
          name="note"
          value={values.note}
          onChange={(e) => set("note", e.target.value)}
          error={errors.note}
          maxLength={300}
          rows={2}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("library.lost.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ returns */

function ReturnPanel({ autoFocus, onReturned }: { autoFocus: boolean; onReturned: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [damaged, setDamaged] = useState(false);
  const [charge, setCharge] = useState("");
  const [note, setNote] = useState("");
  const [result, setResult] = useState<ReturnResult | null>(null);
  const [collecting, setCollecting] = useState(false);
  const [receipt, setReceipt] = useState<FineReceipt | null>(null);

  const chargePaise = charge.trim() ? parseRupees(charge) : null;
  const chargeProblem = damaged && charge.trim() !== "" && (chargePaise === null || chargePaise > MAX_PRICE_PAISE);

  const giveBack = async (accessionNo: string): Promise<string | null> => {
    if (chargeProblem) return t("library.v.amount");
    try {
      const done = await libraryApi.giveBack({
        accessionNo,
        damaged,
        chargePaise: damaged ? chargePaise : null,
        note: damaged ? note.trim() || null : null,
      });
      setResult(done);
      setReceipt(null);
      setDamaged(false);
      setCharge("");
      setNote("");
      toast(t("library.return.done", { title: done.loan.title }));
      onReturned();
      return null;
    } catch (caught) {
      return deskError(caught, t);
    }
  };

  const due = result ? result.fines.filter((f) => f.status === "DUE") : [];
  const fineTotal = due.reduce((sum, f) => sum + f.amountPaise, 0);

  return (
    <section className="card flex flex-col gap-3.5" aria-labelledby="desk-return" data-testid="desk-return">
      <div className="card-head">
        <h2 id="desk-return">{t("library.desk.return")}</h2>
      </div>
      <ScanField
        label={t("library.desk.accession")}
        name="returnAccessionNo"
        action={t("library.desk.returnButton")}
        onScan={giveBack}
        autoFocus={autoFocus}
        testId="return-form"
      />
      <div className="flex flex-col gap-2">
        <label className="check">
          <input type="checkbox" name="damaged" checked={damaged} onChange={(e) => setDamaged(e.target.checked)} />
          <span>{t("library.return.damaged")}</span>
        </label>
        {damaged ? (
          <div className="grid2">
            <TextField
              label={t("library.return.charge")}
              name="chargePaise"
              inputMode="decimal"
              value={charge}
              onChange={(e) => setCharge(e.target.value)}
              error={chargeProblem ? t("library.v.amount") : undefined}
              hint={t("library.return.chargeHint")}
              autoComplete="off"
            />
            <TextField
              label={t("library.field.noteOptional")}
              name="note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={300}
              autoComplete="off"
            />
          </div>
        ) : null}
      </div>

      {result ? (
        <div className="return-result" role="status" data-testid="return-result">
          <p className="flex items-start gap-2">
            <BookCheck size={18} aria-hidden="true" className="mt-0.5 flex-none text-good" />
            <span>
              {t("library.return.summary", {
                title: result.loan.title,
                copy: result.loan.accessionNo,
                name: memberName(t, result.loan.member),
              })}
            </span>
          </p>
          {result.loan.returnedOn && result.loan.dueOn < result.loan.returnedOn && result.fines.some((f) => f.kind === "OVERDUE") ? (
            <p className="text-[13px] text-ink-2">
              {t("library.return.late", { date: formatPlainDate(result.loan.dueOn, locale) })}
            </p>
          ) : null}
          {result.fines.map((fine) => (
            <p key={fine.id} className="flex items-baseline justify-between gap-2">
              <span>
                {fineKindLabel(t, fine.kind)}
                {fine.daysLate ? ` · ${plural(t, "library.loan.daysOverdue", fine.daysLate)}` : ""}
              </span>
              <b className="num">{formatPaise(fine.amountPaise)}</b>
            </p>
          ))}
          {fineTotal > 0 && !receipt ? (
            <button type="button" className="btn btn-primary self-start" onClick={() => setCollecting(true)}>
              {t("library.return.collect", { amount: formatPaise(fineTotal) })}
            </button>
          ) : null}
          {receipt ? (
            <p className="alert alert-good" data-testid="return-receipt">
              <Receipt size={18} aria-hidden="true" className="mt-0.5 flex-none" />
              <span>
                {t("library.collect.done", { amount: formatPaise(receipt.totalPaise), receipt: receipt.receiptNo })}{" "}
                <Link className="link" href={`/app/library/receipt?no=${encodeURIComponent(receipt.receiptNo)}`}>
                  {t("library.receipt.open")}
                </Link>
              </span>
            </p>
          ) : null}
          {result.heldFor ? (
            <p className="alert alert-info" data-testid="return-hold">
              {t("library.return.hold", {
                name: memberName(t, result.heldFor.member),
                date: formatPlainDate(result.heldFor.holdUntil, locale),
              })}
            </p>
          ) : null}
          <CollectDialog
            key={collectKey(result.fines)}
            open={collecting}
            fines={result.fines}
            onClose={() => setCollecting(false)}
            onCollected={(paid) => {
              setCollecting(false);
              setReceipt(paid);
              toastReceipt(toast, t, paid);
            }}
          />
        </div>
      ) : null}
    </section>
  );
}
