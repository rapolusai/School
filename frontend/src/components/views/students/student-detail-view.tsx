"use client";

import { ArrowLeft, KeyRound, LogOut, Mail, Pencil, Phone, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { classLabel, formatPhone, formatPlainDate, initials } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { Guardian, StudentDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { EditStudentDialog, GuardianDialog, LeaveDialog, SignInDialog } from "./student-dialogs";
import { MAX_GUARDIANS } from "./add-student-dialog";
import { studentStatusTone } from "./student-status";

type Open =
  | { kind: "edit" }
  | { kind: "leave" }
  | { kind: "guardian"; guardian: Guardian | null }
  | { kind: "removeGuardian"; guardian: Guardian }
  | { kind: "guardianSignIn"; guardian: Guardian }
  | { kind: "studentSignIn" }
  | null;

export function StudentDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = hasPermission(me, PERMISSIONS.studentsManage);
  const student = useApiData(`student:${id}`, () => api.getStudent(id));
  const [open, setOpen] = useState<Open>(null);

  const s = student.data;
  const closeAnd = (message?: string) => {
    setOpen(null);
    student.reload();
    if (message) toast(message);
  };

  const back = (
    <Link href="/app/students" className="link inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("student.back")}
    </Link>
  );

  if (student.error && !s) {
    return (
      <>
        {back}
        {student.error.status === 404 ? (
          <section className="card">
            <p className="empty">{t("student.notFound")}</p>
          </section>
        ) : (
          <section className="card">
            <ErrorState error={student.error} onRetry={student.reload} />
          </section>
        )}
      </>
    );
  }
  if (!s) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      </>
    );
  }

  const active = s.status === "ACTIVE";
  const current = s.currentEnrollment;
  const statusLabel = translateOr(t, `studentStatus.${s.status}`, s.status);
  const genderLabel = translateOr(t, `gender.${s.gender}`, s.gender);

  return (
    <>
      {back}
      <div className="page-head">
        <div className="person min-w-0">
          <span className="avatar h-12 w-12 text-base" aria-hidden="true">
            {initials(s.fullName)}
          </span>
          <div className="min-w-0">
            <p className="eyebrow">{t("student.eyebrow")}</p>
            <h1 className="mt-0.5 flex flex-wrap items-center gap-2">
              {s.fullName}
              <Pill tone={studentStatusTone(s.status)} dot>
                {statusLabel}
              </Pill>
            </h1>
            <p className="mt-1 text-sm text-ink-2">
              {[
                current ? classLabel(current.className, current.sectionName) : null,
                current?.rollNo ? t("students.rollShort", { roll: current.rollNo }) : null,
                s.admissionNo,
              ]
                .filter(Boolean)
                .join(" · ")}
            </p>
          </div>
        </div>
        {canManage ? (
          <div className="flex flex-wrap gap-2">
            {!s.hasSignIn ? (
              <button type="button" className="btn" onClick={() => setOpen({ kind: "studentSignIn" })}>
                <KeyRound size={18} aria-hidden="true" />
                {t("student.signIn.forStudent")}
              </button>
            ) : null}
            {active ? (
              <button type="button" className="btn" onClick={() => setOpen({ kind: "leave" })}>
                <LogOut size={18} aria-hidden="true" />
                {t("student.leave.button")}
              </button>
            ) : null}
            <button type="button" className="btn btn-primary" onClick={() => setOpen({ kind: "edit" })}>
              <Pencil size={18} aria-hidden="true" />
              {t("student.edit.button")}
            </button>
          </div>
        ) : null}
      </div>

      {!active ? (
        <section className="alert alert-info" role="status">
          {t(s.status === "ALUMNI" ? "student.leftAlumni" : "student.left", {
            status: statusLabel,
            date: formatPlainDate(s.leftOn, locale),
          })}
          {s.leavingReason ? ` ${t("student.leftReason", { reason: s.leavingReason })}` : ""}
        </section>
      ) : null}

      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        <section className="card" aria-labelledby="profile-heading">
          <div className="card-head">
            <h2 id="profile-heading">{t("student.profile")}</h2>
          </div>
          <dl className="kv">
            <dt>{t("students.field.admissionNo")}</dt>
            <dd className="mono">{s.admissionNo}</dd>
            <dt>{t("students.field.dateOfBirth")}</dt>
            <dd>{formatPlainDate(s.dateOfBirth, locale)}</dd>
            <dt>{t("students.field.gender")}</dt>
            <dd>{genderLabel}</dd>
            <dt>{t("students.field.admissionDate")}</dt>
            <dd>{formatPlainDate(s.admissionDate, locale)}</dd>
            <dt>{t("students.field.bloodGroup")}</dt>
            <dd>{s.bloodGroup ?? "—"}</dd>
            <dt>{t("students.field.apaarId")}</dt>
            <dd className="mono">{s.apaarId ?? "—"}</dd>
            <dt>{t("students.field.address")}</dt>
            <dd className="whitespace-pre-line">{s.address ?? "—"}</dd>
            <dt>{t("students.field.previousSchool")}</dt>
            <dd>{s.previousSchool ?? "—"}</dd>
            <dt>{t("student.signIn.label")}</dt>
            <dd>{s.hasSignIn ? (s.signInEmail ?? t("student.signIn.yes")) : t("student.signIn.none")}</dd>
          </dl>
        </section>

        <section className="card" aria-labelledby="guardians-heading">
          <div className="card-head">
            <h2 id="guardians-heading">{t("student.guardians")}</h2>
            {canManage && s.guardians.length < MAX_GUARDIANS ? (
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => setOpen({ kind: "guardian", guardian: null })}
              >
                <Plus size={16} aria-hidden="true" />
                {t("student.guardian.add")}
              </button>
            ) : null}
          </div>
          <ul className="list" data-testid="guardians-list">
            {s.guardians.map((g) => (
              <li key={g.id} className="li">
                <span className="avatar" aria-hidden="true">
                  {initials(g.name)}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-1.5 font-semibold">
                    {g.name}
                    <span className="text-[13px] font-normal text-ink-3">
                      {translateOr(t, `relation.${g.relation}`, g.relation)}
                    </span>
                    {g.primary ? <Pill tone="accent">{t("student.guardian.primaryPill")}</Pill> : null}
                  </p>
                  <p className="mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5 text-[13.5px] text-ink-2">
                    <a href={`tel:+91${g.phone}`} className="inline-flex items-center gap-1 num">
                      <Phone size={14} aria-hidden="true" />
                      {formatPhone(g.phone)}
                    </a>
                    {g.email ? (
                      <a href={`mailto:${g.email}`} className="inline-flex min-w-0 items-center gap-1 break-all">
                        <Mail size={14} aria-hidden="true" />
                        {g.email}
                      </a>
                    ) : null}
                  </p>
                  {g.occupation ? <p className="text-[13px] text-ink-3">{g.occupation}</p> : null}
                  <p className="mt-0.5 text-[12.5px] text-ink-3">
                    {g.hasSignIn
                      ? t("student.guardian.signedInAs", { email: g.signInEmail ?? "" })
                      : t("student.guardian.noSignIn")}
                  </p>
                  {canManage ? (
                    <div className="mt-1.5 flex flex-wrap gap-1.5">
                      <button
                        type="button"
                        className="btn btn-sm"
                        onClick={() => setOpen({ kind: "guardian", guardian: g })}
                        aria-label={t("student.guardian.editNamed", { name: g.name })}
                      >
                        <Pencil size={14} aria-hidden="true" />
                        {t("common.edit")}
                      </button>
                      {!g.hasSignIn ? (
                        <button
                          type="button"
                          className="btn btn-sm"
                          onClick={() => setOpen({ kind: "guardianSignIn", guardian: g })}
                          aria-label={t("student.guardian.signInNamed", { name: g.name })}
                        >
                          <KeyRound size={14} aria-hidden="true" />
                          {t("student.guardian.giveSignIn")}
                        </button>
                      ) : null}
                      {s.guardians.length > 1 ? (
                        <button
                          type="button"
                          className="btn btn-sm btn-ghost"
                          onClick={() => setOpen({ kind: "removeGuardian", guardian: g })}
                          aria-label={t("student.guardian.removeNamed", { name: g.name })}
                        >
                          <Trash2 size={14} aria-hidden="true" />
                          {t("common.remove")}
                        </button>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        </section>

        <section className="card" aria-labelledby="history-heading">
          <div className="card-head">
            <h2 id="history-heading">{t("student.history")}</h2>
          </div>
          {s.enrollments.length === 0 ? (
            <p className="empty">{t("student.history.empty")}</p>
          ) : (
            <ul className="list">
              {s.enrollments.map((e) => (
                <li key={e.id} className="li items-center">
                  <div className="min-w-0 flex-1">
                    <p className="font-semibold">{classLabel(e.className, e.sectionName)}</p>
                    <p className="text-[13px] text-ink-3">
                      {e.academicYearName}
                      {e.rollNo ? ` · ${t("students.rollShort", { roll: e.rollNo })}` : ""}
                    </p>
                  </div>
                  {e.currentYear ? <Pill tone="good">{t("setup.years.current")}</Pill> : null}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="card" aria-labelledby="siblings-heading">
          <div className="card-head">
            <h2 id="siblings-heading">{t("student.siblings")}</h2>
          </div>
          {s.siblings.length === 0 ? (
            <p className="text-sm text-ink-3">{t("student.siblings.none")}</p>
          ) : (
            <ul className="rowcards">
              {s.siblings.map((sib) => (
                <li key={sib.id}>
                  <Link href={`/app/students/${sib.id}`} className="rowcard">
                    <span className="avatar" aria-hidden="true">
                      {initials(sib.fullName)}
                    </span>
                    <span className="min-w-0 flex-1">
                      <b className="block truncate font-semibold">{sib.fullName}</b>
                      <span className="block text-[13px] text-ink-3">
                        {classLabel(sib.className, sib.sectionName) ||
                          translateOr(t, `studentStatus.${sib.status}`, sib.status)}
                      </span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      {canManage ? (
        <StudentDialogs
          student={s}
          open={open}
          setOpen={setOpen}
          onChanged={closeAnd}
        />
      ) : null}
    </>
  );
}

function StudentDialogs({
  student,
  open,
  setOpen,
  onChanged,
}: {
  student: StudentDetail;
  open: Open;
  setOpen: (open: Open) => void;
  onChanged: (message?: string) => void;
}) {
  const { t } = useI18n();
  const close = () => setOpen(null);
  // Each dialog mounts fresh when it opens, so it starts from the latest student data.
  const guardianKey = open?.kind === "guardian" ? (open.guardian?.id ?? "new") : "none";
  return (
    <>
      <EditStudentDialog
        key={open?.kind === "edit" ? "edit-open" : "edit"}
        open={open?.kind === "edit"}
        student={student}
        onClose={close}
        onSaved={(saved) => onChanged(t("student.edit.saved", { name: saved.fullName }))}
      />
      <LeaveDialog
        key={open?.kind === "leave" ? "leave-open" : "leave"}
        open={open?.kind === "leave"}
        student={student}
        onClose={close}
        onSaved={(saved) =>
          onChanged(
            t("student.leave.saved", {
              name: saved.fullName,
              status: translateOr(t, `studentStatus.${saved.status}`, saved.status),
            }),
          )
        }
      />
      <GuardianDialog
        key={guardianKey}
        open={open?.kind === "guardian"}
        studentId={student.id}
        guardian={open?.kind === "guardian" ? open.guardian : null}
        onClose={close}
        onSaved={(g) => onChanged(t("student.guardian.saved", { name: g.name }))}
      />
      <ConfirmDialog
        open={open?.kind === "removeGuardian"}
        title={t("student.guardian.removeTitle", {
          name: open?.kind === "removeGuardian" ? open.guardian.name : "",
        })}
        body={t("student.guardian.removeBody")}
        confirmLabel={t("common.remove")}
        onClose={close}
        onConfirm={async () => {
          if (open?.kind !== "removeGuardian") return;
          await api.removeGuardian(student.id, open.guardian.id);
          onChanged(t("student.guardian.removed", { name: open.guardian.name }));
        }}
      />
      <SignInDialog
        key={open?.kind === "guardianSignIn" ? `g-${open.guardian.id}` : "g-none"}
        open={open?.kind === "guardianSignIn"}
        title={t("student.signIn.guardianTitle", {
          name: open?.kind === "guardianSignIn" ? open.guardian.name : "",
        })}
        description={t("student.signIn.guardianDescription")}
        defaultEmail={open?.kind === "guardianSignIn" ? (open.guardian.email ?? "") : ""}
        onClose={close}
        onSubmit={async (values) => {
          if (open?.kind !== "guardianSignIn") return;
          await api.guardianSignIn(
            student.id,
            open.guardian.id,
            values.mode === "CREATE"
              ? { mode: "CREATE", email: values.email, password: values.password }
              : { mode: "LINK", email: values.email },
          );
        }}
        onSaved={(values) => onChanged(t("student.signIn.saved", { email: values.email }))}
      />
      <SignInDialog
        key={open?.kind === "studentSignIn" ? "s-open" : "s"}
        open={open?.kind === "studentSignIn"}
        title={t("student.signIn.studentTitle", { name: student.fullName })}
        description={t("student.signIn.studentDescription")}
        defaultEmail=""
        onClose={close}
        onSubmit={async (values) => {
          await api.studentSignIn(
            student.id,
            values.mode === "CREATE"
              ? { mode: "CREATE", email: values.email, password: values.password }
              : { mode: "LINK", email: values.email },
          );
        }}
        onSaved={(values) => onChanged(t("student.signIn.saved", { email: values.email }))}
      />
    </>
  );
}
