"use client";

import { KeyRound, RefreshCw, UserRoundPlus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { initials, todayInIndia } from "@/lib/format";
import { frontOfficeApi } from "@/lib/frontoffice-api";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { GatePassView, PickupPersonView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { FrontOfficeNav, PassStatusPill, PhotoThumb, when } from "./frontoffice-shared";
import { collectorLine } from "./passes-view";
import { VisitorDialog, VisitorRow } from "./visitors-view";

/** The API's refusal types at the gate (GatePassService). */
export const WRONG_OTP_TYPE = "urn:akshara:frontoffice:wrong-otp";
export const LOCKED_TYPE = "urn:akshara:frontoffice:pass-locked";

/** A typed OTP is six digits (spaces allowed). Pure and exported for tests. */
export function otpProblem(otp: string): boolean {
  return !/^[0-9]{6}$/.test(otp.replace(/\s/g, ""));
}

/** /app/frontoffice/gate: the security guard's phone screen: children to release against the OTP, visitors in. */
export function GateView() {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const gate = useApiData("frontoffice:gate", frontOfficeApi.gate);
  const [adding, setAdding] = useState(false);
  const data = gate.data;
  return (
    <>
      <PageHead
        eyebrow={t("nav.frontoffice")}
        title={t("frontoffice.gate.title")}
        actions={
          <>
            <button type="button" className="btn" onClick={gate.reload} aria-label={t("frontoffice.gate.refresh")}>
              <RefreshCw size={18} aria-hidden="true" />
              <span className="hidden sm:inline">{t("frontoffice.gate.refresh")}</span>
            </button>
            <button type="button" className="btn btn-primary" onClick={() => setAdding(true)} data-testid="gate-visitor-add">
              <UserRoundPlus size={18} aria-hidden="true" />
              {t("frontoffice.visitors.add")}
            </button>
          </>
        }
      />
      <FrontOfficeNav />
      {gate.error && !data ? (
        <ErrorState error={gate.error} onRetry={gate.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <section className="card flex flex-col gap-3" aria-labelledby="gate-passes" data-testid="gate-passes">
            <div className="card-head" style={{ marginBottom: 0 }}>
              <h2 id="gate-passes" className="flex items-center gap-2">
                <KeyRound size={18} className="text-ink-3" aria-hidden="true" />
                {t("frontoffice.gate.passes")}
              </h2>
              <Pill tone={data.passes.length > 0 ? "info" : "neutral"}>{plural(t, "frontoffice.passes.count", data.passes.length)}</Pill>
            </div>
            <p className="text-[13px] text-ink-3">{t("frontoffice.gate.intro")}</p>
            {data.passes.length === 0 ? (
              <p className="text-[13.5px] text-ink-3">{t("frontoffice.gate.noPasses")}</p>
            ) : (
              <ul className="rowcards">
                {data.passes.map((pass) => (
                  <GatePassRow key={pass.id} pass={pass} onChange={gate.reload} />
                ))}
              </ul>
            )}
          </section>
          <section className="card flex flex-col gap-3" aria-labelledby="gate-inside" data-testid="gate-inside">
            <div className="card-head" style={{ marginBottom: 0 }}>
              <h2 id="gate-inside">{t("frontoffice.visitors.inside")}</h2>
              <Pill tone={data.inside.length > 0 ? "good" : "neutral"}>{plural(t, "frontoffice.visitors.count", data.inside.length)}</Pill>
            </div>
            {data.inside.length === 0 ? (
              <p className="text-[13.5px] text-ink-3">{t("frontoffice.visitors.noneInside")}</p>
            ) : (
              <ul className="rowcards">
                {data.inside.map((visitor) => (
                  <VisitorRow key={visitor.id} visitor={visitor} onChange={gate.reload} />
                ))}
              </ul>
            )}
          </section>
          <section className="card flex flex-col gap-3" aria-labelledby="gate-expected" data-testid="gate-expected">
            <div className="card-head" style={{ marginBottom: 0 }}>
              <h2 id="gate-expected">{t("frontoffice.visitors.expected")}</h2>
            </div>
            {data.expected.length === 0 ? (
              <p className="text-[13.5px] text-ink-3">{t("frontoffice.visitors.noneExpected")}</p>
            ) : (
              <ul className="rowcards">
                {data.expected.map((visitor) => (
                  <VisitorRow key={visitor.id} visitor={visitor} onChange={gate.reload} />
                ))}
              </ul>
            )}
          </section>
          <p className="text-[12.5px] text-ink-3">
            <Link href="/app/frontoffice" className="link">
              {t("frontoffice.gate.allVisitors")}
            </Link>
          </p>
        </>
      )}
      <VisitorDialog
        open={adding}
        today={today}
        onClose={() => setAdding(false)}
        onSaved={() => {
          setAdding(false);
          gate.reload();
        }}
      />
    </>
  );
}

/** One approved pass: who collects (with the family's authorised persons), the OTP field and who collected. */
function GatePassRow({ pass, onChange }: { pass: GatePassView; onChange: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [otp, setOtp] = useState("");
  const [collectedBy, setCollectedBy] = useState(pass.collectorKind === "OTHER" ? (pass.collectorName ?? "") : (pass.guardianName ?? ""));
  const [error, setError] = useState<string | null>(null);
  const [otpError, setOtpError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [showPersons, setShowPersons] = useState(false);
  const persons = useApiData(showPersons ? `frontoffice:pickup-persons:${pass.studentId}` : null, () => frontOfficeApi.pickupPersons(pass.studentId));

  const release = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setOtpError(null);
    if (otpProblem(otp)) {
      setOtpError(t("frontoffice.v.otp"));
      return;
    }
    if (!collectedBy.trim()) {
      setError(t("frontoffice.v.collectedBy"));
      return;
    }
    setBusy(true);
    try {
      await frontOfficeApi.release(pass.id, { otp: otp.replace(/\s/g, ""), collectedByName: collectedBy.trim() });
      toast(t("frontoffice.gate.released", { name: pass.studentName }));
      onChange();
    } catch (caught) {
      const apiError = toApiError(caught);
      if (apiError.type === WRONG_OTP_TYPE) setOtpError(apiError.detail ?? t("frontoffice.gate.wrongOtp"));
      else setError(apiError.detail ?? errorMessage(apiError, t));
      if (apiError.type === LOCKED_TYPE) onChange();
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className="rowcard flex-col items-stretch gap-2" data-testid="gate-pass" data-status={pass.status}>
      <div className="flex items-start gap-3">
        <span className="avatar" aria-hidden="true">
          {initials(pass.studentName)}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold text-ink">{pass.studentName}</span>
            {pass.className ? <span className="text-[12.5px] text-ink-3">{pass.className}</span> : null}
            <PassStatusPill status={pass.status} />
          </div>
          <p className="text-[13px] text-ink-2">{t("frontoffice.pass.at", { time: when(pass.pickupAt, locale) })}</p>
          <p className="text-[12.5px] text-ink-3">
            {t("frontoffice.pass.collector", { who: collectorLine(t, pass) })} · {t("frontoffice.pass.validTill", { time: when(pass.otpExpiresAt, locale) })}
          </p>
          <p className="text-[12.5px] text-ink-3">{plural(t, "frontoffice.gate.triesLeft", pass.attemptsLeft)}</p>
        </div>
      </div>
      <button type="button" className="link self-start text-[13px]" onClick={() => setShowPersons((v) => !v)} aria-expanded={showPersons}>
        {t("frontoffice.gate.persons")}
      </button>
      {showPersons ? (
        persons.error && !persons.data ? (
          <ErrorState error={persons.error} onRetry={persons.reload} />
        ) : !persons.data ? (
          <LoadingRows rows={1} />
        ) : persons.data.persons.length === 0 ? (
          <p className="text-[13px] text-ink-3">{t("frontoffice.gate.noPersons")}</p>
        ) : (
          <ul className="list" data-testid="gate-persons">
            {persons.data.persons.map((person: PickupPersonView) => (
              <li key={person.id} className="li items-center gap-3">
                <PhotoThumb fileId={person.photoFileId} alt={person.name} size={40} />
                <span className="min-w-0 flex-1">
                  <b className="block">{person.name}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {person.relation} · {person.phoneMasked}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )
      ) : null}
      {pass.canRelease ? (
        <form method="post" noValidate onSubmit={release} className="grid grid-cols-1 gap-2 sm:grid-cols-[1fr_1fr_auto] sm:items-end" data-testid="release-form">
          <TextField
            label={t("frontoffice.gate.otp")}
            name="otp"
            inputMode="numeric"
            autoComplete="one-time-code"
            value={otp}
            onChange={(e) => setOtp(e.target.value)}
            error={otpError ?? undefined}
            maxLength={7}
            required
          />
          <TextField label={t("frontoffice.gate.collectedBy")} name="collectedByName" value={collectedBy} onChange={(e) => setCollectedBy(e.target.value)} maxLength={200} required />
          <button type="submit" className="btn btn-primary" disabled={busy} data-testid="release-button">
            {t("frontoffice.gate.release")}
          </button>
          {error ? (
            <p className="alert alert-warn text-[13px] sm:col-span-3" role="alert">
              {error}
            </p>
          ) : null}
        </form>
      ) : null}
    </li>
  );
}
