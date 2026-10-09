"use client";

import { ArrowLeft, Pencil, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { staffApi } from "@/lib/staff-api";
import type { StaffDepartment } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";

type DepartmentValues = { name: string; headUserId: string };

/** Pure and exported for tests. */
export function validateDepartment(values: DepartmentValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 100) problems.name = "validation.tooLong";
  return problems;
}

function DepartmentDialog({
  department,
  onClose,
  onSaved,
}: {
  department: StaffDepartment | null;
  onClose: () => void;
  onSaved: (department: StaffDepartment) => void;
}) {
  const { t } = useI18n();
  const initial: DepartmentValues = { name: department?.name ?? "", headUserId: department?.head?.id ?? "" };
  const form = useForm<DepartmentValues>(initial);
  // Heads are active staff with a profile; the API checks the same.
  const staff = useApiData("staff:list:heads", () => staffApi.list({ status: "ACTIVE", size: 100 }));
  const candidates = (staff.data?.items ?? []).filter((s) => s.profileComplete);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateDepartment(form.values), t, event.currentTarget)) return;
    const body = { name: form.values.name.trim(), headUserId: form.values.headUserId || null };
    let saved: StaffDepartment | undefined;
    const ok = await form.submit(t, async () => {
      saved = department
        ? await staffApi.updateDepartment(department.id, body)
        : await staffApi.createDepartment(body);
    });
    if (ok && saved) onSaved(saved);
  };

  const headOptions = [
    { value: "", label: t("staff.departments.noHead") },
    ...candidates.map((s) => ({
      value: s.userId,
      label: [s.name, s.designation].filter(Boolean).join(" · "),
    })),
  ];
  // Keep the current head selectable even if they are not in the first page of staff.
  if (department?.head && !candidates.some((s) => s.userId === department.head?.id)) {
    headOptions.push({ value: department.head.id, label: department.head.name });
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title={department ? t("staff.departments.editTitle", { name: department.name }) : t("staff.departments.addTitle")}
      description={t("staff.departments.headHint")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("staff.departments.field.name")}
          name="name"
          value={form.values.name}
          onChange={(e) => form.set("name", e.target.value)}
          error={form.errors.name}
          maxLength={100}
          required
          data-autofocus
        />
        <SelectField
          label={t("staff.departments.field.head")}
          name="headUserId"
          value={form.values.headUserId}
          onChange={(e) => form.set("headUserId", e.target.value)}
          error={form.errors.headUserId}
          options={headOptions}
          disabled={!staff.data && !staff.error}
        />
        <div className="flex justify-end gap-2 pt-1">
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

type Open = { kind: "edit"; department: StaffDepartment | null } | { kind: "delete"; department: StaffDepartment } | null;

/** Departments and their heads; a head decides the department's leave requests. */
export function DepartmentsView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.staffManage);
  const departments = useApiData("staff:departments", staffApi.departments);
  const [open, setOpen] = useState<Open>(null);
  const list = departments.data ?? [];

  return (
    <>
      <PageHead
        eyebrow={departments.data ? plural(t, "staff.departments.eyebrow", list.length) : t("common.loading")}
        title={t("staff.departments.title")}
        actions={
          <>
            <Link href="/app/staff" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("staff.back")}
            </Link>
            {canManage ? (
              <button type="button" className="btn btn-primary" onClick={() => setOpen({ kind: "edit", department: null })}>
                <Plus size={18} aria-hidden="true" />
                {t("staff.departments.add")}
              </button>
            ) : null}
          </>
        }
      />

      <section className="card">
        <p className="mb-3 text-[13.5px] text-ink-2">{t("staff.departments.intro")}</p>
        {departments.error && !departments.data ? (
          <ErrorState error={departments.error} onRetry={departments.reload} />
        ) : !departments.data ? (
          <LoadingRows rows={4} />
        ) : list.length === 0 ? (
          <div className="empty flex flex-col items-center gap-3" data-testid="departments-empty">
            <p>{t("staff.departments.empty")}</p>
            {canManage ? (
              <button type="button" className="btn btn-primary" onClick={() => setOpen({ kind: "edit", department: null })}>
                {t("staff.departments.add")}
              </button>
            ) : null}
          </div>
        ) : (
          <ul className="list" data-testid="departments-list">
            {list.map((d) => (
              <li key={d.id} className="li flex-wrap items-center">
                <div className="min-w-0 flex-1 basis-[200px]">
                  <p className="font-semibold">{d.name}</p>
                  <p className="text-[13px] text-ink-3">
                    {[
                      d.head ? t("staff.departments.headIs", { name: d.head.name }) : t("staff.departments.noHeadLine"),
                      plural(t, "staff.departments.members", d.staffCount),
                    ].join(" · ")}
                  </p>
                </div>
                {canManage ? (
                  <div className="flex gap-2">
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() => setOpen({ kind: "edit", department: d })}
                      aria-label={t("staff.departments.editTitle", { name: d.name })}
                    >
                      <Pencil size={16} aria-hidden="true" />
                      {t("common.edit")}
                    </button>
                    <button
                      type="button"
                      className="btn btn-sm btn-ghost"
                      onClick={() => setOpen({ kind: "delete", department: d })}
                      aria-label={t("staff.departments.deleteTitle", { name: d.name })}
                    >
                      <Trash2 size={16} aria-hidden="true" />
                    </button>
                  </div>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </section>

      {canManage && open?.kind === "edit" ? (
        <DepartmentDialog
          key={open.department?.id ?? "new"}
          department={open.department}
          onClose={() => setOpen(null)}
          onSaved={(d) => {
            setOpen(null);
            departments.reload();
            toast(t("staff.departments.saved", { name: d.name }));
          }}
        />
      ) : null}
      {canManage && open?.kind === "delete" ? (
        <ConfirmDialog
          open
          title={t("staff.departments.deleteTitle", { name: open.department.name })}
          body={
            open.department.staffCount > 0
              ? plural(t, "staff.departments.deleteInUse", open.department.staffCount)
              : t("staff.departments.deleteBody")
          }
          confirmLabel={t("common.delete")}
          onConfirm={async () => {
            await staffApi.deleteDepartment(open.department.id);
            const name = open.department.name;
            setOpen(null);
            departments.reload();
            toast(t("staff.departments.deleted", { name }));
          }}
          onClose={() => setOpen(null)}
        />
      ) : null}
    </>
  );
}
