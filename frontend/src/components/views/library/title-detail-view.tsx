"use client";

import { ArrowLeft, BookmarkPlus, Pencil, Plus, Tags, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import type { CopyView, LibraryMember } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import {
  CopyStatusPill,
  HoldStatusPill,
  LibraryNav,
  MemberPicker,
  memberName,
  memberSub,
  sourceLabel,
  useCanManageLibrary,
} from "./library-shared";
import { AddCopiesDialog, CopyDialog, TitleDialog } from "./title-dialogs";

/** /app/library/titles/[id]: a title, its copies and (for library.manage) who has them and who is waiting. */
export function TitleDetailView({ id }: { id: string }) {
  const { t } = useI18n();
  const router = useRouter();
  const { toast } = useToast();
  const canManage = useCanManageLibrary();
  const title = useApiData(`library:title:${id}`, () => libraryApi.title(id));
  const [editing, setEditing] = useState(false);
  const [addingCopies, setAddingCopies] = useState(false);
  const [copy, setCopy] = useState<CopyView | null>(null);
  const [holding, setHolding] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const data = title.data;

  return (
    <>
      <PageHead
        eyebrow={data?.authors ?? t("nav.library")}
        title={data?.title ?? t("common.loading")}
        actions={
          <>
            <Link href="/app/library" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("library.back")}
            </Link>
            {canManage && data ? (
              <>
                <button type="button" className="btn" onClick={() => setEditing(true)}>
                  <Pencil size={18} aria-hidden="true" />
                  {t("common.edit")}
                </button>
                <Link href={`/app/library/labels?titleId=${data.id}`} className="btn">
                  <Tags size={18} aria-hidden="true" />
                  {t("library.labels.short")}
                </Link>
                <button type="button" className="btn btn-primary" onClick={() => setAddingCopies(true)}>
                  <Plus size={18} aria-hidden="true" />
                  {t("library.copies.add")}
                </button>
              </>
            ) : null}
          </>
        }
      />
      <LibraryNav />

      {title.error?.status === 404 ? (
        <p className="empty">{t("library.title.notFound")}</p>
      ) : title.error && !data ? (
        <ErrorState error={title.error} onRetry={title.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          <section className="card flex flex-col gap-3" aria-labelledby="title-about">
            <div className="card-head">
              <h2 id="title-about">{t("library.title.about")}</h2>
              <span className="text-[13px] text-ink-2" data-testid="title-availability">
                {data.copiesTotal === 0
                  ? t("library.noCopies")
                  : t("library.available", { available: data.available, copies: data.copiesTotal })}
                {" · "}
                {plural(t, "library.title.borrowed", data.timesBorrowed)}
              </span>
            </div>
            <dl className="kv">
              {data.subtitle ? (
                <>
                  <dt>{t("library.field.subtitle")}</dt>
                  <dd>{data.subtitle}</dd>
                </>
              ) : null}
              <dt>{t("library.field.authors")}</dt>
              <dd>{data.authors ?? "—"}</dd>
              <dt>{t("library.field.publisher")}</dt>
              <dd>{[data.publisher, data.publicationYear, data.edition].filter(Boolean).join(" · ") || "—"}</dd>
              <dt>{t("library.field.language")}</dt>
              <dd>{data.language ?? "—"}</dd>
              <dt>{t("library.field.subject")}</dt>
              <dd>{data.subject ?? "—"}</dd>
              <dt>{t("library.field.callNumber")}</dt>
              <dd className="mono">{data.callNumber ?? "—"}</dd>
              <dt>{t("library.field.shelf")}</dt>
              <dd>{data.shelfLocation ?? "—"}</dd>
              <dt>{t("library.field.isbn")}</dt>
              <dd className="mono">{[data.isbn13, data.isbn10].filter(Boolean).join(" · ") || "—"}</dd>
            </dl>
          </section>

          <section className="card flex flex-col gap-3" aria-labelledby="title-copies">
            <div className="card-head">
              <h2 id="title-copies">{plural(t, "library.copies.heading", data.copies.length)}</h2>
            </div>
            {data.copies.length === 0 ? (
              <p className="empty">{t("library.copies.none")}</p>
            ) : (
              <>
                <CopyTable copies={data.copies} canManage={canManage} onEdit={setCopy} />
                <CopyCards copies={data.copies} canManage={canManage} onEdit={setCopy} />
              </>
            )}
          </section>

          {canManage ? (
            <section className="card flex flex-col gap-3" aria-labelledby="title-queue">
              <div className="card-head">
                <h2 id="title-queue">{t("library.queue.heading")}</h2>
                <button type="button" className="btn btn-sm" onClick={() => setHolding(true)} disabled={data.copiesTotal === 0}>
                  <BookmarkPlus size={16} aria-hidden="true" />
                  {t("library.hold.place")}
                </button>
              </div>
              {data.queue.length === 0 ? (
                <p className="text-[13px] text-ink-3">{t("library.queue.empty")}</p>
              ) : (
                <ol className="rowcards" data-testid="title-queue-list">
                  {data.queue.map((entry) => (
                    <li key={entry.reservationId} className="rowcard">
                      <b className="num w-6 text-center">{entry.position}</b>
                      <span className="min-w-0 flex-1">
                        <b className="block truncate">{memberName(t, entry.member)}</b>
                        <span className="block text-[12.5px] text-ink-3">{memberSub(entry.member)}</span>
                      </span>
                      <HoldStatusPill status={entry.status} />
                    </li>
                  ))}
                </ol>
              )}
              <div className="flex justify-end">
                <button type="button" className="btn btn-sm btn-ghost text-bad" onClick={() => setDeleting(true)}>
                  <Trash2 size={16} aria-hidden="true" />
                  {t("library.title.delete")}
                </button>
              </div>
            </section>
          ) : null}

          {editing ? (
            <TitleDialog
              open
              title={data}
              onClose={() => setEditing(false)}
              onSaved={() => {
                setEditing(false);
                toast(t("library.title.saved"));
                title.reload();
              }}
            />
          ) : null}
          {addingCopies ? (
            <AddCopiesDialog
              open
              title={data}
              onClose={() => setAddingCopies(false)}
              onSaved={(_, added) => {
                setAddingCopies(false);
                toast(plural(t, "library.copies.added", added));
                title.reload();
              }}
            />
          ) : null}
          {copy ? (
            <CopyDialog
              key={copy.id}
              copy={copy}
              onClose={() => setCopy(null)}
              onSaved={(saved) => {
                setCopy(null);
                toast(t("library.copy.saved", { copy: saved.accessionNo }));
                title.reload();
              }}
            />
          ) : null}
          {holding ? (
            <HoldDialog
              titleId={data.id}
              titleName={data.title}
              onClose={() => setHolding(false)}
              onPlaced={(name) => {
                setHolding(false);
                toast(t("library.hold.placed", { name, title: data.title }));
                title.reload();
              }}
            />
          ) : null}
          <ConfirmDialog
            open={deleting}
            title={t("library.title.deleteTitle", { title: data.title })}
            body={t("library.title.deleteBody")}
            confirmLabel={t("library.title.delete")}
            onClose={() => setDeleting(false)}
            onConfirm={async () => {
              await libraryApi.deleteTitle(data.id);
              setDeleting(false);
              toast(t("library.title.deleted", { title: data.title }));
              router.push("/app/library");
            }}
          />
        </>
      )}
    </>
  );
}

function WithWhom({ copy }: { copy: CopyView }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  if (copy.loan) {
    return (
      <span className="block leading-tight">
        {memberName(t, copy.loan.member)}
        <span className={`block text-[12.5px] ${copy.loan.overdue ? "text-bad font-semibold" : "text-ink-3"}`}>
          {t("library.loan.due", { date: formatPlainDate(copy.loan.dueOn, locale) })}
        </span>
      </span>
    );
  }
  if (copy.heldFor) {
    return (
      <span className="block leading-tight">
        {memberName(t, copy.heldFor.member)}
        <span className="block text-[12.5px] text-ink-3">
          {t("library.copy.keptUntil", { date: formatPlainDate(copy.heldFor.holdUntil, locale) })}
        </span>
      </span>
    );
  }
  return <>{copy.statusNote ?? "—"}</>;
}

function CopyTable({ copies, canManage, onEdit }: { copies: CopyView[]; canManage: boolean; onEdit: (copy: CopyView) => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <div className="table-wrap hidden md:block">
      <table className="table" data-testid="copies-table">
        <thead>
          <tr>
            <th scope="col">{t("library.col.accession")}</th>
            <th scope="col">{t("library.col.status")}</th>
            {canManage ? <th scope="col">{t("library.col.with")}</th> : null}
            <th scope="col" className="r">
              {t("library.col.price")}
            </th>
            <th scope="col">{t("library.col.acquired")}</th>
            {canManage ? (
              <th scope="col">
                <span className="sr-only">{t("common.actions")}</span>
              </th>
            ) : null}
          </tr>
        </thead>
        <tbody>
          {copies.map((copy) => (
            <tr key={copy.id} data-testid="copy-row">
              <td className="mono font-semibold">{copy.accessionNo}</td>
              <td>
                <CopyStatusPill status={copy.status} />
              </td>
              {canManage ? (
                <td>
                  <WithWhom copy={copy} />
                </td>
              ) : null}
              <td className="r num">{copy.pricePaise === null ? "—" : formatPaise(copy.pricePaise)}</td>
              <td>
                {sourceLabel(t, copy.source)}
                {copy.acquiredOn ? <span className="block text-[12.5px] text-ink-3">{formatPlainDate(copy.acquiredOn, locale)}</span> : null}
              </td>
              {canManage ? (
                <td className="r">
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => onEdit(copy)}
                    aria-label={t("library.copy.editLabel", { copy: copy.accessionNo })}
                  >
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                </td>
              ) : null}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function CopyCards({ copies, canManage, onEdit }: { copies: CopyView[]; canManage: boolean; onEdit: (copy: CopyView) => void }) {
  const { t } = useI18n();
  return (
    <ul className="rowcards md:hidden" data-testid="copies-cards">
      {copies.map((copy) => (
        <li key={copy.id} className="rowcard items-start">
          <span className="min-w-0 flex-1">
            <b className="mono block">{copy.accessionNo}</b>
            {canManage ? (
              <span className="block text-[13px] text-ink-2">
                <WithWhom copy={copy} />
              </span>
            ) : null}
            <span className="block text-[12.5px] text-ink-3">
              {[sourceLabel(t, copy.source), copy.pricePaise === null ? null : formatPaise(copy.pricePaise)].filter(Boolean).join(" · ")}
            </span>
          </span>
          <span className="flex flex-col items-end gap-1.5">
            <CopyStatusPill status={copy.status} />
            {canManage ? (
              <button type="button" className="btn btn-sm" onClick={() => onEdit(copy)} aria-label={t("library.copy.editLabel", { copy: copy.accessionNo })}>
                <Pencil size={16} aria-hidden="true" />
              </button>
            ) : null}
          </span>
        </li>
      ))}
    </ul>
  );
}

/** Puts a member in the queue for this title. */
function HoldDialog({
  titleId,
  titleName,
  onClose,
  onPlaced,
}: {
  titleId: string;
  titleName: string;
  onClose: () => void;
  onPlaced: (name: string) => void;
}) {
  const { t } = useI18n();
  const [member, setMember] = useState<LibraryMember | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const place = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!member || busy) return;
    setBusy(true);
    setError(null);
    try {
      await libraryApi.placeHold({ titleId, memberType: member.type, memberId: member.id });
      onPlaced(memberName(t, member));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} title={t("library.hold.title", { title: titleName })} description={t("library.hold.description")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={place} noValidate>
        <FormAlert message={error} />
        {member ? (
          <div className="person rounded-lg border border-line p-3">
            <span className="min-w-0 flex-1">
              <b className="block truncate">{memberName(t, member)}</b>
              <span className="sub">{memberSub(member)}</span>
            </span>
            <button type="button" className="btn btn-sm" onClick={() => setMember(null)}>
              {t("library.desk.changeMember")}
            </button>
          </div>
        ) : (
          <MemberPicker onPick={setMember} autoFocus />
        )}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={!member || busy}>
            {busy ? t("common.working") : t("library.hold.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
