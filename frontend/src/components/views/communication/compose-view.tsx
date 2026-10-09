"use client";

import { ArrowLeft, Info } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { noticesApi } from "@/lib/communication-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import {
  CIRCULAR_CATEGORIES,
  CIRCULAR_CHANNELS,
  type AudienceOptions,
  type AudienceRequest,
  type CircularCategory,
  type CircularChannel,
  type CircularDetail,
  type CircularRequest,
  type EstimateRequest,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { AudiencePicker } from "./audience-picker";
import {
  audienceChosen,
  audienceProblem,
  categoryLabel,
  channelLabel,
  EMPTY_AUDIENCE,
  indiaLocalToInstant,
  instantToIndiaLocal,
  MAX_BODY,
  MAX_TITLE,
} from "./communication-labels";
import { EstimatePanel } from "./estimate-panel";

export type ComposeValues = {
  title: string;
  body: string;
  category: CircularCategory;
  audience: AudienceRequest;
  channels: CircularChannel[];
  when: "now" | "later";
  /** "YYYY-MM-DDTHH:mm", India time */
  scheduledLocal: string;
};

const YEAR_MS = 365 * 86_400_000;
export const ESTIMATE_DELAY_MS = 300;

/** A new circular starts addressed to parents: of the teacher's own sections, or of the whole school. */
export function initialValues(options: AudienceOptions, existing?: CircularDetail): ComposeValues {
  if (existing) {
    return {
      title: existing.title,
      body: existing.body,
      category: existing.category,
      audience: {
        wholeSchool: existing.audience.wholeSchool,
        classIds: existing.audience.classes.map((c) => c.id),
        sectionIds: existing.audience.sections.map((s) => s.id),
        roles: existing.audience.roles.map((r) => r.code),
      },
      channels: existing.channels,
      when: existing.scheduledAt ? "later" : "now",
      scheduledLocal: instantToIndiaLocal(existing.scheduledAt),
    };
  }
  const own = options.classes.flatMap((c) => c.sections.filter((s) => s.own).map((s) => s.id));
  return {
    title: "",
    body: "",
    category: "GENERAL",
    audience: { ...EMPTY_AUDIENCE, sectionIds: options.canApprove ? [] : own, roles: ["PARENT"] },
    channels: [],
    when: "now",
    scheduledLocal: "",
  };
}

/** The same checks as the API (docs/api/phase-1-communication.md). Exported for tests. */
export function validateCircular(values: ComposeValues, options: AudienceOptions | undefined, now: Date): Problems {
  const problems: Problems = {};
  const title = values.title.trim();
  if (!title) problems.title = "validation.required";
  else if (title.length > MAX_TITLE) problems.title = "validation.tooLong";
  if (!values.body.trim()) problems.body = "validation.required";
  else if (values.body.length > MAX_BODY) problems.body = "validation.tooLong";
  const audience = audienceProblem(values.audience, options);
  if (audience) problems.audience = audience;
  if (values.when === "later") {
    const at = indiaLocalToInstant(values.scheduledLocal);
    if (!at) problems.scheduledAt = "notices.v.schedule";
    else if (new Date(at).getTime() <= now.getTime()) problems.scheduledAt = "notices.v.schedulePast";
    else if (new Date(at).getTime() > now.getTime() + YEAR_MS) problems.scheduledAt = "notices.v.scheduleFar";
  }
  return problems;
}

/** The request body: a whole-school audience carries nothing else. Exported for tests. */
export function toCircularRequest(values: ComposeValues): CircularRequest {
  return {
    title: values.title.trim(),
    body: values.body,
    category: values.category,
    audience: values.audience.wholeSchool ? { ...EMPTY_AUDIENCE, wholeSchool: true } : values.audience,
    channels: values.channels,
    scheduledAt: values.when === "later" ? indiaLocalToInstant(values.scheduledLocal) : null,
  };
}

/** New circular (no id) or a draft to change. */
export function ComposeView({ id }: { id?: string }) {
  const { t } = useI18n();
  const options = useApiData("notices:options", noticesApi.audienceOptions);
  const detail = useApiData(id ? `notices:detail:${id}` : null, () => noticesApi.get(id ?? ""));
  const existing = id && detail.data?.id === id ? detail.data : undefined;
  const failed = options.error ?? (id ? detail.error : undefined);

  return (
    <>
      <PageHead
        eyebrow={t("notices.title")}
        title={id ? t("notices.edit.title") : t("notices.new.title")}
        actions={
          <Link href={id ? `/app/notices/${id}` : "/app/notices"} className="btn">
            <ArrowLeft size={18} aria-hidden="true" />
            {id ? t("notices.backToCircular") : t("notices.backToList")}
          </Link>
        }
      />
      {failed && (!options.data || (id && !existing)) ? (
        <section className="card">
          <ErrorState error={failed} onRetry={id && !existing ? detail.reload : options.reload} />
        </section>
      ) : !options.data || (id && !existing) ? (
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      ) : existing && !existing.actions.edit ? (
        <section className="card flex flex-col items-start gap-3" data-testid="not-editable">
          <p>{t("notices.edit.notDraft")}</p>
          <Link href={`/app/notices/${existing.id}`} className="btn">
            {t("notices.backToCircular")}
          </Link>
        </section>
      ) : (
        <ComposeForm key={existing?.id ?? "new"} options={options.data} existing={existing} />
      )}
    </>
  );
}

function ComposeForm({ options, existing }: { options: AudienceOptions; existing?: CircularDetail }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const router = useRouter();
  const { toast } = useToast();
  const form = useForm<ComposeValues>(initialValues(options, existing));
  const { values, errors, set } = form;
  const [savedId, setSavedId] = useState<string | null>(existing?.id ?? null);
  const [confirming, setConfirming] = useState(false);
  const intent = useRef<"draft" | "send">("draft");

  // Ask the API what this would reach and cost, a moment after the last change.
  const estimateRequest: EstimateRequest = {
    title: values.title,
    body: values.body,
    audience: values.audience.wholeSchool ? { ...EMPTY_AUDIENCE, wholeSchool: true } : values.audience,
    channels: values.channels,
  };
  const requestJson = JSON.stringify(estimateRequest);
  const [debounced, setDebounced] = useState(requestJson);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(requestJson), ESTIMATE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [requestJson]);
  const pending = JSON.parse(debounced) as EstimateRequest;
  const canEstimate = audienceChosen(pending.audience) && audienceProblem(pending.audience, options) === null;
  const estimate = useApiData(canEstimate ? `notices:estimate:${debounced}` : null, () =>
    noticesApi.estimate(pending),
  );
  const problem = audienceProblem(values.audience, options);
  const emptyNote = !audienceChosen(values.audience)
    ? t("notices.estimate.choose")
    : problem
      ? t(problem)
      : null;

  const later = values.when === "later";
  const primaryLabel = options.needsApproval
    ? t("notices.submitForApproval")
    : later
      ? t("notices.schedule")
      : t("notices.sendNow");

  const persist = async () => {
    const request = toCircularRequest(values);
    const saved = savedId ? await noticesApi.update(savedId, request) : await noticesApi.create(request);
    setSavedId(saved.id);
    return saved;
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateCircular(values, options, new Date()), t, event.currentTarget)) return;
    if (intent.current === "send") {
      setConfirming(true);
      return;
    }
    await form.submit(t, async () => {
      const saved = await persist();
      toast(t("notices.toast.saved"));
      router.push(`/app/notices/${saved.id}`);
    });
  };

  const send = async () => {
    const saved = await persist();
    const result = await noticesApi.submit(saved.id);
    setConfirming(false);
    toast(
      result.status === "SENT"
        ? t("notices.toast.sent")
        : result.status === "SCHEDULED"
          ? t("notices.toast.scheduled")
          : t("notices.toast.submitted"),
    );
    router.push(`/app/notices/${result.id}`);
  };

  const toggleChannel = (channel: CircularChannel, on: boolean) =>
    set(
      "channels",
      on
        ? CIRCULAR_CHANNELS.filter((c) => c === channel || values.channels.includes(c))
        : values.channels.filter((c) => c !== channel),
    );

  const est = estimate.data;
  const scheduledText = later ? formatDateTime(indiaLocalToInstant(values.scheduledLocal), locale) : "";
  const confirmTitle = options.needsApproval
    ? t("notices.confirm.submitTitle")
    : later
      ? t("notices.confirm.scheduleTitle")
      : t("notices.confirm.sendTitle");
  const reach = est
    ? t("notices.confirm.reach", { parents: est.parents, students: est.students, staff: est.staff })
    : "";
  const confirmBody = options.needsApproval
    ? t("notices.confirm.submitBody")
    : later
      ? `${t("notices.confirm.scheduleBody", { time: scheduledText })} ${reach}`.trim()
      : `${reach} ${t("notices.confirm.sendBody")}`.trim();

  return (
    <>
      <form method="post" className="compose" onSubmit={onSubmit} noValidate aria-label={t("notices.form")}>
        <div className="flex min-w-0 flex-col gap-3.5">
          {existing?.reviewOutcome === "REJECTED" && existing.reviewNote ? (
            <div className="alert alert-bad" role="note">
              <Info size={18} aria-hidden="true" className="mt-0.5 flex-none" />
              <span>
                {t("notices.sentBackBy", { name: existing.reviewedByName ?? t("common.system") })}{" "}
                <q>{existing.reviewNote}</q>
              </span>
            </div>
          ) : null}
          {options.needsApproval ? (
            <div className="alert alert-info">
              <Info size={18} aria-hidden="true" className="mt-0.5 flex-none" />
              <span>{t("notices.needsApprovalNote")}</span>
            </div>
          ) : null}
          <FormAlert message={form.formError} />

          <section className="card flex flex-col gap-3.5">
            <TextField
              label={t("notices.field.title")}
              name="title"
              value={values.title}
              onChange={(e) => set("title", e.target.value)}
              error={errors.title}
              maxLength={MAX_TITLE}
              required
              autoComplete="off"
              data-autofocus
            />
            <SelectField
              label={t("notices.field.category")}
              name="category"
              value={values.category}
              onChange={(e) => set("category", e.target.value as CircularCategory)}
              options={CIRCULAR_CATEGORIES.map((c) => ({ value: c, label: categoryLabel(t, c) }))}
              hint={values.category === "URGENT" ? t("notices.field.category.urgentHint") : undefined}
              error={errors.category}
            />
            <TextAreaField
              label={t("notices.field.body")}
              name="body"
              rows={9}
              value={values.body}
              onChange={(e) => set("body", e.target.value)}
              error={errors.body}
              maxLength={MAX_BODY}
              hint={t("notices.field.body.hint", { count: values.body.length, max: MAX_BODY })}
              required
            />
          </section>

          <section className="card flex flex-col gap-3.5">
            <AudiencePicker
              options={options}
              value={values.audience}
              onChange={(next) => set("audience", next)}
              error={errors.audience}
            />

            <fieldset className="fieldset">
              <legend>{t("notices.channels.title")}</legend>
              <label className="check">
                <input type="checkbox" checked disabled readOnly />
                <span>
                  {t("notices.channels.app")}
                  <span className="block text-[12.5px] text-ink-3">{t("notices.channels.appHint")}</span>
                </span>
              </label>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                {CIRCULAR_CHANNELS.map((channel) => (
                  <label key={channel} className="check">
                    <input
                      type="checkbox"
                      name="channels"
                      value={channel}
                      checked={values.channels.includes(channel)}
                      onChange={(e) => toggleChannel(channel, e.target.checked)}
                    />
                    <span>{channelLabel(t, channel)}</span>
                  </label>
                ))}
              </div>
              <p className="field-hint">{t("notices.channels.hint")}</p>
            </fieldset>

            <fieldset className="fieldset">
              <legend>{t("notices.when.title")}</legend>
              <div className="seg self-start" role="radiogroup" aria-label={t("notices.when.title")}>
                <label>
                  <input
                    type="radio"
                    name="when"
                    value="now"
                    checked={!later}
                    onChange={() => set("when", "now")}
                  />
                  {options.needsApproval ? t("notices.when.onApproval") : t("notices.when.now")}
                </label>
                <label>
                  <input
                    type="radio"
                    name="when"
                    value="later"
                    checked={later}
                    onChange={() => set("when", "later")}
                  />
                  {t("notices.when.later")}
                </label>
              </div>
              {later ? (
                <TextField
                  label={t("notices.field.scheduledAt")}
                  name="scheduledAt"
                  type="datetime-local"
                  className="sm:max-w-xs"
                  value={values.scheduledLocal}
                  onChange={(e) => set("scheduledLocal", e.target.value)}
                  error={errors.scheduledAt}
                  hint={options.needsApproval ? t("notices.field.scheduledAt.approvalHint") : t("notices.field.scheduledAt.hint")}
                />
              ) : null}
            </fieldset>
          </section>

          <div className="compose-actions">
            <button
              type="submit"
              className="btn"
              disabled={form.submitting}
              onClick={() => {
                intent.current = "draft";
              }}
            >
              {form.submitting ? t("common.saving") : t("notices.saveDraft")}
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={form.submitting}
              onClick={() => {
                intent.current = "send";
              }}
              data-testid="compose-send"
            >
              {primaryLabel}
            </button>
          </div>
        </div>

        <aside className="compose-side">
          <EstimatePanel
            estimate={canEstimate ? est : undefined}
            channels={values.channels}
            loading={estimate.loading}
            error={estimate.error}
            empty={emptyNote}
          />
        </aside>
      </form>
      <ConfirmDialog
        open={confirming}
        danger={false}
        title={confirmTitle}
        body={confirmBody}
        confirmLabel={primaryLabel}
        onConfirm={send}
        onClose={() => setConfirming(false)}
      />
    </>
  );
}
