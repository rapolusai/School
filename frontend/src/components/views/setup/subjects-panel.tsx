"use client";

import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api } from "@/lib/api";
import { plural, useI18n } from "@/lib/i18n";
import type { Subject } from "@/lib/types";
import type { ApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { SUBJECT_CODE_PATTERN } from "@/lib/validation";

type SubjectValues = { name: string; code: string };

export function validateSubject(values: SubjectValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 100) problems.name = "validation.tooLong";
  const code = values.code.trim();
  if (code.length > 20) problems.code = "validation.tooLong";
  else if (!SUBJECT_CODE_PATTERN.test(code)) problems.code = "setup.subjects.codeFormat";
  return problems;
}

function SubjectDialog({
  open,
  subject,
  onClose,
  onSaved,
}: {
  open: boolean;
  subject: Subject | null;
  onClose: () => void;
  onSaved: (saved: Subject) => void;
}) {
  const { t } = useI18n();
  const initial: SubjectValues = { name: subject?.name ?? "", code: subject?.code ?? "" };
  const form = useForm<SubjectValues>(initial);
  const close = () => {
    form.reset(initial);
    onClose();
  };
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateSubject(form.values), t, event.currentTarget)) return;
    const body = { name: form.values.name.trim(), code: form.values.code.trim() || null };
    let saved: Subject | undefined;
    const ok = await form.submit(t, async () => {
      saved = subject ? await api.updateSubject(subject.id, body) : await api.createSubject(body);
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog
      open={open}
      onClose={close}
      title={subject ? t("setup.subjects.edit") : t("setup.subjects.add")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("setup.subjects.name")}
          name="name"
          value={form.values.name}
          maxLength={100}
          placeholder={t("setup.subjects.name.placeholder")}
          onChange={(e) => form.set("name", e.target.value)}
          error={form.errors.name}
          required
          data-autofocus
        />
        <TextField
          label={t("setup.subjects.code")}
          name="code"
          value={form.values.code}
          maxLength={20}
          className="[&_input]:font-mono"
          hint={t("setup.subjects.code.hint")}
          onChange={(e) => form.set("code", e.target.value)}
          error={form.errors.code}
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
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

export function SubjectsPanel({ subjects, canManage }: { subjects: ApiData<Subject[]>; canManage: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [editing, setEditing] = useState<Subject | "new" | null>(null);
  const [deleting, setDeleting] = useState<Subject | null>(null);
  const list = subjects.data ?? [];

  return (
    <section className="card" aria-labelledby="subjects-title">
      <div className="card-head">
        <div>
          <h2 id="subjects-title">{t("setup.subjects.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("setup.subjects.sub")}</p>
        </div>
        {canManage ? (
          <button type="button" className="btn btn-primary" onClick={() => setEditing("new")}>
            <Plus size={18} aria-hidden="true" />
            {t("setup.subjects.add")}
          </button>
        ) : null}
      </div>

      {subjects.error && !subjects.data ? (
        <ErrorState error={subjects.error} onRetry={subjects.reload} />
      ) : subjects.loading && !subjects.data ? (
        <LoadingRows rows={3} />
      ) : list.length === 0 ? (
        <p className="empty">{canManage ? t("setup.subjects.empty") : t("setup.subjects.emptyReadOnly")}</p>
      ) : (
        <div className="table-wrap">
          <table className="table" data-testid="subjects-table">
            <thead>
              <tr>
                <th scope="col">{t("setup.subjects.name")}</th>
                <th scope="col">{t("setup.subjects.code")}</th>
                <th scope="col" className="hidden sm:table-cell">
                  {t("setup.subjects.classes")}
                </th>
                {canManage ? (
                  <th scope="col" className="r">
                    <span className="sr-only">{t("common.actions")}</span>
                  </th>
                ) : null}
              </tr>
            </thead>
            <tbody>
              {list.map((subject) => (
                <tr key={subject.id}>
                  <td className="font-semibold">
                    {subject.name}
                    <span className="block text-[12.5px] font-normal text-ink-3 sm:hidden">
                      {plural(t, "setup.subjects.classCount", subject.classCount)}
                    </span>
                  </td>
                  <td className="mono text-ink-2">{subject.code ?? "—"}</td>
                  <td className="hidden text-ink-2 sm:table-cell">
                    {plural(t, "setup.subjects.classCount", subject.classCount)}
                  </td>
                  {canManage ? (
                    <td className="r whitespace-nowrap">
                      <button
                        type="button"
                        className="iconbtn"
                        aria-label={t("setup.subjects.editNamed", { name: subject.name })}
                        onClick={() => setEditing(subject)}
                      >
                        <Pencil size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="iconbtn"
                        aria-label={t("setup.subjects.deleteNamed", { name: subject.name })}
                        onClick={() => setDeleting(subject)}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </button>
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {canManage ? (
        <>
          <SubjectDialog
            key={editing === null ? "closed" : editing === "new" ? "new" : editing.id}
            open={editing !== null}
            subject={editing === "new" ? null : editing}
            onClose={() => setEditing(null)}
            onSaved={(subject) => {
              setEditing(null);
              subjects.reload();
              toast(t("setup.subjects.saved", { name: subject.name }));
            }}
          />
          <ConfirmDialog
            open={deleting !== null}
            title={t("setup.subjects.deleteTitle", { name: deleting?.name ?? "" })}
            body={t("setup.subjects.deleteBody")}
            confirmLabel={t("common.delete")}
            onClose={() => setDeleting(null)}
            onConfirm={async () => {
              if (!deleting) return;
              await api.deleteSubject(deleting.id);
              setDeleting(null);
              subjects.reload();
              toast(t("common.deleted"));
            }}
          />
        </>
      ) : null}
    </section>
  );
}
