"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatDate, formatPaise } from "@/lib/format";
import { localeFor, useI18n, type Translate } from "@/lib/i18n";
import { libraryApi, type FineQuery } from "@/lib/library-api";
import type { FineStatus, FineView, LibraryMember } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { toastReceipt } from "./desk-view";
import { CollectDialog, collectKey, WaiveDialog } from "./fine-dialogs";
import { FineStatusPill, fineKindLabel, fineStatusLabel, LibraryNav, memberName, memberSub, modeLabel } from "./library-shared";

const PAGE_SIZE = 25;

/** What happened to a fine: the receipt and mode, the waiver and its reason, or how late the book was. */
function fineDetail(fine: FineView, t: Translate, locale: string): string {
  if (fine.status === "PAID") {
    return [fine.receiptNo, fine.paymentMode ? modeLabel(t, fine.paymentMode) : null, formatDate(fine.paidAt, locale)]
      .filter(Boolean)
      .join(" · ");
  }
  if (fine.status === "WAIVED") return t("library.fine.waivedBecause", { reason: fine.waiveReason ?? "—" });
  return [fine.daysLate ? t("library.fine.daysLate", { days: fine.daysLate }) : null, fine.note].filter(Boolean).join(" · ");
}

/** /app/library/fines: fines due, paid and waived; collect a member's fines under one receipt, or waive one. */
export function FinesView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [status, setStatus] = useState<FineStatus | "">("DUE");
  const [page, setPage] = useState(0);
  const [collectFor, setCollectFor] = useState<LibraryMember | null>(null);
  const [waiving, setWaiving] = useState<FineView | null>(null);
  const query: FineQuery = { status: status || undefined, page, size: PAGE_SIZE };
  const fines = useApiData(`library:fines:${JSON.stringify(query)}`, () => libraryApi.fines(query));
  const data = fines.data;
  const rows = data?.items ?? [];
  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;

  const actions = (fine: FineView) =>
    fine.status === "DUE" ? (
      <span className="flex flex-wrap justify-end gap-1.5">
        <button type="button" className="btn btn-sm btn-primary" onClick={() => setCollectFor(fine.member)} disabled={!fine.member}>
          {t("library.desk.collect")}
        </button>
        <button type="button" className="btn btn-sm" onClick={() => setWaiving(fine)}>
          {t("library.waive.button")}
        </button>
      </span>
    ) : fine.status === "PAID" && fine.receiptNo ? (
      <Link className="btn btn-sm" href={`/app/library/receipt?no=${encodeURIComponent(fine.receiptNo)}`}>
        {t("library.receipt.open")}
      </Link>
    ) : null;

  return (
    <>
      <PageHead
        eyebrow={
          data && status === "DUE" ? t("library.fines.totalDue", { amount: formatPaise(data.totalDuePaise) }) : t("nav.library")
        }
        title={t("library.fines.title")}
      />
      <LibraryNav />
      <section className="card flex flex-col gap-3">
        <div className="seg self-start" role="radiogroup" aria-label={t("library.fines.filter")}>
          {(["DUE", "PAID", "WAIVED", ""] as const).map((s) => (
            <label key={s || "all"}>
              <input
                type="radio"
                name="status"
                value={s}
                checked={status === s}
                onChange={() => {
                  setStatus(s);
                  setPage(0);
                }}
              />
              {s ? fineStatusLabel(t, s) : t("library.fines.all")}
            </label>
          ))}
        </div>
        {fines.error && !data ? (
          <ErrorState error={fines.error} onRetry={fines.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : rows.length === 0 ? (
          <p className="empty">{status === "DUE" ? t("library.fines.noneDue") : t("library.fines.none")}</p>
        ) : (
          <div aria-busy={fines.loading}>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="fines-table">
                <thead>
                  <tr>
                    <th scope="col">{t("library.col.member")}</th>
                    <th scope="col">{t("library.col.title")}</th>
                    <th scope="col" className="r">
                      {t("library.col.amount")}
                    </th>
                    <th scope="col">{t("library.col.status")}</th>
                    <th scope="col">
                      <span className="sr-only">{t("common.actions")}</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((fine) => (
                    <tr key={fine.id}>
                      <td>
                        <b className="block">{memberName(t, fine.member)}</b>
                        <span className="block text-[12.5px] text-ink-3">{memberSub(fine.member)}</span>
                      </td>
                      <td>
                        {fine.title ?? "—"}
                        <span className="block text-[12.5px] text-ink-3">
                          {fineKindLabel(t, fine.kind)} · <span className="mono">{fine.accessionNo}</span>
                        </span>
                      </td>
                      <td className="r num font-semibold">{formatPaise(fine.amountPaise)}</td>
                      <td>
                        <FineStatusPill status={fine.status} />
                        <span className="block text-[12.5px] text-ink-3">{fineDetail(fine, t, locale)}</span>
                      </td>
                      <td className="r">{actions(fine)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden" data-testid="fines-cards">
              {rows.map((fine) => (
                <li key={fine.id} className="rowcard flex-wrap items-start">
                  <span className="min-w-0 flex-1">
                    <b className="block">{memberName(t, fine.member)}</b>
                    <span className="block text-[13px]">
                      {fine.title ?? "—"} · {fineKindLabel(t, fine.kind)}
                    </span>
                    <span className="block text-[12.5px] text-ink-3">{fineDetail(fine, t, locale)}</span>
                  </span>
                  <span className="flex flex-col items-end gap-1.5">
                    <b className="num">{formatPaise(fine.amountPaise)}</b>
                    <FineStatusPill status={fine.status} />
                  </span>
                  <span className="w-full">{actions(fine)}</span>
                </li>
              ))}
            </ul>
            {data.total > data.size ? (
              <div className="pager">
                <span>{t("common.showing", { shown: rows.length, total: data.total })}</span>
                <div className="flex gap-2">
                  <button type="button" className="btn btn-sm" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>
                    <ChevronLeft size={16} aria-hidden="true" />
                    {t("common.previous")}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setPage((p) => Math.min(lastPage, p + 1))}
                    disabled={page >= lastPage}
                  >
                    {t("common.next")}
                    <ChevronRight size={16} aria-hidden="true" />
                  </button>
                </div>
              </div>
            ) : null}
          </div>
        )}
        <p className="text-[12.5px] text-ink-3">{t("library.fines.rule")}</p>
      </section>

      {collectFor ? (
        <MemberCollect
          member={collectFor}
          onClose={() => setCollectFor(null)}
          onDone={() => {
            setCollectFor(null);
            fines.reload();
          }}
          onReceipt={(receipt) => toastReceipt(toast, t, receipt)}
        />
      ) : null}
      {waiving ? (
        <WaiveDialog
          fine={waiving}
          onClose={() => setWaiving(null)}
          onWaived={() => {
            setWaiving(null);
            toast(t("library.waive.done"));
            fines.reload();
          }}
        />
      ) : null}
    </>
  );
}

/** Loads every fine the member still owes, then asks how they pay. */
function MemberCollect({
  member,
  onClose,
  onDone,
  onReceipt,
}: {
  member: LibraryMember;
  onClose: () => void;
  onDone: () => void;
  onReceipt: Parameters<typeof CollectDialog>[0]["onCollected"];
}) {
  const due = useApiData(`library:fines:member:${member.type}:${member.id}`, () =>
    libraryApi.fines({ status: "DUE", memberType: member.type, memberId: member.id, size: 50 }),
  );
  const { t } = useI18n();
  if (due.error && !due.data) {
    return (
      <Dialog open onClose={onClose} title={t("library.collect.title", { name: memberName(t, member) })} closeLabel={t("common.close")}>
        <ErrorState error={due.error} onRetry={due.reload} />
      </Dialog>
    );
  }
  if (!due.data) return null;
  return (
    <CollectDialog
      key={collectKey(due.data.items)}
      open
      fines={due.data.items}
      onClose={onClose}
      onCollected={(receipt) => {
        onReceipt(receipt);
        onDone();
      }}
    />
  );
}
