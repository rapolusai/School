"use client";

import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatDate, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import type { HoldView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { HoldStatusPill, LibraryNav, memberName, memberSub } from "./library-shared";

/** "Kept until 14 Oct 2026 (LIB00012)" for a ready hold, "2nd in the queue" for a waiting one. */
function holdState(hold: HoldView, t: ReturnType<typeof useI18n>["t"], locale: string): string {
  if (hold.status === "READY" && hold.holdUntil) {
    return t("library.hold.keptUntil", { date: formatPlainDate(hold.holdUntil, locale), copy: hold.copyAccessionNo ?? "" });
  }
  return t("library.hold.position", { n: hold.position });
}

/** /app/library/holds: members waiting for a title, and copies kept on the hold shelf for them. */
export function HoldsView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const holds = useApiData("library:holds", libraryApi.holds);
  const [cancelling, setCancelling] = useState<HoldView | null>(null);
  const list = holds.data ?? [];
  const ready = list.filter((h) => h.status === "READY");
  const waiting = list.filter((h) => h.status === "WAITING");

  const section = (rows: HoldView[], heading: string, empty: string, testId: string) => (
    <section className="card flex flex-col gap-3" aria-label={heading} data-testid={testId}>
      <div className="card-head">
        <h2>{heading}</h2>
      </div>
      {rows.length === 0 ? (
        <p className="text-[13px] text-ink-3">{empty}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">{t("library.col.member")}</th>
                  <th scope="col">{t("library.col.title")}</th>
                  <th scope="col">{t("library.col.status")}</th>
                  <th scope="col">{t("library.col.placed")}</th>
                  <th scope="col">
                    <span className="sr-only">{t("common.actions")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((hold) => (
                  <tr key={hold.id}>
                    <td>
                      <b className="block">{memberName(t, hold.member)}</b>
                      <span className="block text-[12.5px] text-ink-3">{memberSub(hold.member)}</span>
                    </td>
                    <td>
                      <Link className="link" href={`/app/library/titles/${hold.titleId}`}>
                        {hold.title ?? "—"}
                      </Link>
                    </td>
                    <td>
                      <HoldStatusPill status={hold.status} />
                      <span className="block text-[12.5px] text-ink-3">{holdState(hold, t, locale)}</span>
                    </td>
                    <td>{formatDate(hold.createdAt, locale)}</td>
                    <td className="r">
                      <button type="button" className="btn btn-sm" onClick={() => setCancelling(hold)}>
                        {t("library.hold.cancel")}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="rowcards md:hidden">
            {rows.map((hold) => (
              <li key={hold.id} className="rowcard items-start">
                <span className="min-w-0 flex-1">
                  <b className="block">{hold.title ?? "—"}</b>
                  <span className="block text-[13px]">{memberName(t, hold.member)}</span>
                  <span className="block text-[12.5px] text-ink-3">{holdState(hold, t, locale)}</span>
                </span>
                <span className="flex flex-col items-end gap-1.5">
                  <HoldStatusPill status={hold.status} />
                  <button type="button" className="btn btn-sm" onClick={() => setCancelling(hold)}>
                    {t("library.hold.cancel")}
                  </button>
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );

  return (
    <>
      <PageHead
        eyebrow={holds.data ? plural(t, "library.holds.eyebrow", list.length) : t("common.loading")}
        title={t("library.holds.title")}
      />
      <LibraryNav />
      {holds.error && !holds.data ? (
        <ErrorState error={holds.error} onRetry={holds.reload} />
      ) : !holds.data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          {section(ready, t("library.holds.ready"), t("library.holds.noneReady"), "holds-ready")}
          {section(waiting, t("library.holds.waiting"), t("library.holds.noneWaiting"), "holds-waiting")}
          <p className="text-[12.5px] text-ink-3">{t("library.holds.rule")}</p>
        </>
      )}
      <ConfirmDialog
        open={cancelling !== null}
        title={t("library.hold.cancelTitle", { name: memberName(t, cancelling?.member) })}
        body={t("library.hold.cancelBody", { title: cancelling?.title ?? "" })}
        confirmLabel={t("library.hold.cancel")}
        onClose={() => setCancelling(null)}
        onConfirm={async () => {
          if (!cancelling) return;
          await libraryApi.cancelHold(cancelling.id);
          setCancelling(null);
          toast(t("library.hold.cancelled"));
          holds.reload();
        }}
      />
    </>
  );
}
