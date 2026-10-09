"use client";

import { useId } from "react";
import { roleLabel, useI18n } from "@/lib/i18n";
import { FAMILY_ROLES, type AudienceOptions, type AudienceRequest } from "@/lib/types";

const toggle = (list: string[], value: string, on: boolean) =>
  on ? (list.includes(value) ? list : [...list, value]) : list.filter((v) => v !== value);

/**
 * Who a circular is for: the whole school, or people (parents, students, staff roles) narrowed to classes and
 * sections. Without notices.approve only the caller's own sections and their parents and students are offered.
 */
export function AudiencePicker({
  options,
  value,
  onChange,
  error,
}: {
  options: AudienceOptions;
  value: AudienceRequest;
  onChange: (next: AudienceRequest) => void;
  error?: string;
}) {
  const { t } = useI18n();
  const errorId = useId();
  const set = (patch: Partial<AudienceRequest>) => onChange({ ...value, ...patch });
  const ownSections = options.classes.flatMap((c) => c.sections.filter((s) => s.own));
  const scopeHint = options.canApprove ? t("notices.audience.classesHint") : t("notices.audience.ownHint");

  return (
    <fieldset
      className="fieldset"
      aria-describedby={error ? errorId : undefined}
      aria-invalid={error ? true : undefined}
      data-testid="audience-picker"
    >
      <legend>{t("notices.audience.title")}</legend>
      {options.canAddressWholeSchool ? (
        <label className="check">
          <input
            type="checkbox"
            name="wholeSchool"
            checked={value.wholeSchool}
            onChange={(e) => set({ wholeSchool: e.target.checked })}
          />
          <span>
            <b className="font-semibold">{t("notices.audience.wholeSchool")}</b>
            <span className="block text-[12.5px] text-ink-3">{t("notices.audience.wholeSchoolHint")}</span>
          </span>
        </label>
      ) : null}

      {value.wholeSchool ? null : (
        <>
          <div className="flex flex-col gap-1.5">
            <p className="field-label">{t("notices.audience.people")}</p>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {[...FAMILY_ROLES.map((code) => ({ code, name: code })), ...options.staffRoles].map((role) => (
                <label key={role.code} className="check">
                  <input
                    type="checkbox"
                    name="roles"
                    value={role.code}
                    checked={value.roles.includes(role.code)}
                    onChange={(e) => set({ roles: toggle(value.roles, role.code, e.target.checked) })}
                  />
                  <span>
                    {role.code === "PARENT"
                      ? t("notices.audience.parents")
                      : role.code === "STUDENT"
                        ? t("notices.audience.students")
                        : roleLabel(t, role.code, role.name)}
                  </span>
                </label>
              ))}
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <p className="field-label">
              {options.canApprove ? t("notices.audience.classes") : t("notices.audience.ownSections")}
            </p>
            <p className="field-hint">{scopeHint}</p>
            {options.canApprove ? (
              options.classes.length === 0 ? (
                <p className="text-[13px] text-ink-3">{t("notices.audience.noClasses")}</p>
              ) : (
                <div className="flex flex-col gap-2">
                  {options.classes.map((c) => {
                    const whole = value.classIds.includes(c.id);
                    return (
                      <div key={c.id} className="audience-class">
                        <label className="check">
                          <input
                            type="checkbox"
                            name="classIds"
                            value={c.id}
                            checked={whole}
                            onChange={(e) =>
                              set({
                                classIds: toggle(value.classIds, c.id, e.target.checked),
                                // A whole class covers its sections.
                                sectionIds: e.target.checked
                                  ? value.sectionIds.filter((s) => !c.sections.some((x) => x.id === s))
                                  : value.sectionIds,
                              })
                            }
                          />
                          <span className="font-semibold">{c.name}</span>
                        </label>
                        {c.sections.length > 1 ? (
                          <div className="audience-sections" role="group" aria-label={c.name}>
                            {c.sections.map((s) => (
                              <label key={s.id} className="check">
                                <input
                                  type="checkbox"
                                  name="sectionIds"
                                  value={s.id}
                                  aria-label={s.label}
                                  checked={whole || value.sectionIds.includes(s.id)}
                                  disabled={whole}
                                  onChange={(e) =>
                                    set({ sectionIds: toggle(value.sectionIds, s.id, e.target.checked) })
                                  }
                                />
                                <span>{s.name}</span>
                              </label>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              )
            ) : ownSections.length === 0 ? (
              <p className="text-[13px] text-ink-3">{t("notices.audience.noOwnSections")}</p>
            ) : (
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {ownSections.map((s) => (
                  <label key={s.id} className="check">
                    <input
                      type="checkbox"
                      name="sectionIds"
                      value={s.id}
                      checked={value.sectionIds.includes(s.id)}
                      onChange={(e) => set({ sectionIds: toggle(value.sectionIds, s.id, e.target.checked) })}
                    />
                    <span>{s.label}</span>
                  </label>
                ))}
              </div>
            )}
          </div>
        </>
      )}
      {error ? (
        <p id={errorId} className="field-error">
          {error}
        </p>
      ) : null}
    </fieldset>
  );
}
