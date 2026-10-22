"use client";

import { Ban, Pencil, Trash2 } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatDate } from "@/lib/format";
import { frontOfficeApi } from "@/lib/frontoffice-api";
import { localeFor, useI18n } from "@/lib/i18n";
import type { BlockedView, BlockRequest } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { FrontOfficeNav, phoneProblem, useCanManageOffice } from "./frontoffice-shared";

type BlockValues = { name: string; phone: string; reason: string };
const EMPTY: BlockValues = { name: "", phone: "", reason: "" };

/** Client-side checks mirroring BlockForm on the API. Pure and exported for tests. */
export function blockProblems(values: BlockValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "frontoffice.v.name";
  const phone = phoneProblem(values.phone);
  if (phone) problems.phone = phone;
  if (!values.reason.trim()) problems.reason = "frontoffice.v.reason";
  return problems;
}

/** /app/frontoffice/blocklist: people the gate must not let in, with the reason; the office keeps it. */
export function BlocklistView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageOffice();
  const list = useApiData("frontoffice:blocklist", frontOfficeApi.blocklist);
  const [editing, setEditing] = useState<BlockedView | "new" | null>(null);
  const [removing, setRemoving] = useState<BlockedView | null>(null);
  const rows = list.data ?? [];
  return (
    <>
      <PageHead
        eyebrow={t("nav.frontoffice")}
        title={t("frontoffice.blocklist.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="block-add">
              <Ban size={18} aria-hidden="true" />
              {t("frontoffice.blocklist.add")}
            </button>
          ) : undefined
        }
      />
      <FrontOfficeNav />
      <p className="text-[13.5px] text-ink-2">{t("frontoffice.blocklist.intro")}</p>
      {list.error && !list.data ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !list.data ? (
        <LoadingRows rows={3} />
      ) : rows.length === 0 ? (
        <p className="empty">{t("frontoffice.blocklist.empty")}</p>
      ) : (
        <ul className="rowcards" data-testid="blocklist">
          {rows.map((row) => (
            <li key={row.id} className="rowcard items-start">
              <span className="badge-ic" aria-hidden="true">
                <Ban size={18} />
              </span>
              <div className="min-w-0 flex-1">
                <p className="font-semibold text-ink">
                  {row.name}
                  {row.phoneMasked ? <span className="ml-2 num text-[12.5px] font-normal text-ink-3">{row.phoneMasked}</span> : null}
                </p>
                <p className="text-[13px] text-ink-2">{row.reason}</p>
                <p className="text-[12.5px] text-ink-3">
                  {t("frontoffice.blocklist.addedBy", { name: row.addedByName ?? "—", date: formatDate(row.createdAt, locale) })}
                </p>
              </div>
              {canManage ? (
                <div className="flex gap-1">
                  <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => setEditing(row)}>
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                  <button type="button" className="iconbtn" aria-label={t("frontoffice.blocklist.remove")} onClick={() => setRemoving(row)}>
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      <BlockDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        entry={editing === "new" ? null : editing}
        open={editing !== null}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          toast(t("frontoffice.blocklist.saved"));
          list.reload();
        }}
      />
      <ConfirmDialog
        open={removing !== null}
        title={t("frontoffice.blocklist.remove")}
        body={removing ? t("frontoffice.blocklist.removeBody", { name: removing.name }) : ""}
        confirmLabel={t("frontoffice.blocklist.remove")}
        onConfirm={async () => {
          if (!removing) return;
          await frontOfficeApi.unblock(removing.id);
          setRemoving(null);
          toast(t("frontoffice.blocklist.removed"));
          list.reload();
        }}
        onClose={() => setRemoving(null)}
      />
    </>
  );
}

function BlockDialog({
  entry,
  open,
  onClose,
  onSaved,
}: {
  entry: BlockedView | null;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const form = useForm<BlockValues>(entry ? { name: entry.name, phone: "", reason: entry.reason } : EMPTY);
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(blockProblems(form.values), t, formEl)) return;
    const body: BlockRequest = {
      name: form.values.name.trim(),
      phone: form.values.phone.trim() || null,
      reason: form.values.reason.trim(),
    };
    const ok = await form.submit(t, async () => {
      if (entry) await frontOfficeApi.updateBlock(entry.id, body);
      else await frontOfficeApi.block(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={entry ? t("frontoffice.blocklist.edit") : t("frontoffice.blocklist.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="block-form">
        <FormAlert message={form.formError} />
        <TextField label={t("frontoffice.field.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={200} required />
        <TextField
          label={t("frontoffice.field.phone")}
          name="phone"
          type="tel"
          inputMode="numeric"
          value={form.values.phone}
          onChange={(e) => form.set("phone", e.target.value)}
          error={form.errors.phone}
          hint={t("frontoffice.blocklist.phoneHint")}
          maxLength={20}
        />
        <TextAreaField label={t("frontoffice.field.reason")} name="reason" value={form.values.reason} onChange={(e) => form.set("reason", e.target.value)} error={form.errors.reason} maxLength={500} rows={3} required />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
