import type { PillTone } from "@/components/ui/pill";
import { formatINR, formatPlainDate } from "@/lib/format";
import { translateOr, type Translate } from "@/lib/i18n";
import type { ApplicationStage, TimelineEntry } from "@/lib/types";

const STAGE_TONES: Record<ApplicationStage, PillTone> = {
  ENQUIRY: "info",
  APPLICATION: "accent",
  ASSESSMENT: "warn",
  OFFERED: "accent",
  ADMITTED: "good",
  REJECTED: "bad",
  WITHDRAWN: "neutral",
};

export function stageTone(stage: ApplicationStage): PillTone {
  return STAGE_TONES[stage] ?? "neutral";
}

export function stageLabel(t: Translate, stage: string): string {
  return translateOr(t, `admissions.stage.${stage}`, stage);
}

/** The menu item that moves an application to `stage`, e.g. "Offer a place". */
export function moveLabel(t: Translate, stage: string): string {
  return translateOr(t, `admissions.move.${stage}`, stage);
}

export function sourceLabel(t: Translate, source: string): string {
  return translateOr(t, `admissions.source.${source}`, source);
}

export function kindLabel(t: Translate, kind: string): string {
  return translateOr(t, `admissions.kind.${kind}`, kind);
}

export function modeLabel(t: Translate, mode: string): string {
  return translateOr(t, `admissions.mode.${mode}`, mode);
}

export function methodLabel(t: Translate, method: string): string {
  return translateOr(t, `admissions.method.${method}`, method);
}

/** 50000 paise → "₹500". */
export function rupees(paise: number | null | undefined): string {
  return paise === null || paise === undefined ? "" : formatINR(paise / 100);
}

/** "₹500" typed by a person → 50000 paise; null when it is not a whole, positive rupee amount. */
export function paiseFromRupees(text: string): number | null {
  const clean = text.replace(/[₹,\s]/g, "");
  if (!/^\d{1,7}(\.\d{1,2})?$/.test(clean)) return null;
  const paise = Math.round(Number(clean) * 100);
  return paise > 0 ? paise : null;
}

const str = (value: unknown) => (typeof value === "string" ? value : "");

/** One line describing a timeline entry, such as "Moved from Enquiry to Application". */
export function timelineTitle(t: Translate, entry: TimelineEntry, locale: string): string {
  const d = entry.details ?? {};
  switch (entry.kind) {
    case "CREATED":
      return d.publicForm === true
        ? t("admissions.timeline.createdPublic")
        : t("admissions.timeline.created", { stage: stageLabel(t, entry.toStage ?? "ENQUIRY") });
    case "STAGE_CHANGED":
      return entry.toStage === "ADMITTED"
        ? t("admissions.timeline.admitted", { admissionNo: str(d.admissionNo), section: str(d.section) })
        : t("admissions.timeline.moved", {
            from: stageLabel(t, entry.fromStage ?? ""),
            to: stageLabel(t, entry.toStage ?? ""),
          });
    case "NOTE":
      return t("admissions.timeline.note");
    case "UPDATED":
      return t("admissions.timeline.updated");
    case "FEE_RECORDED":
      return d.status === "WAIVED"
        ? t("admissions.timeline.feeWaived", { date: formatPlainDate(str(d.paidOn), locale) })
        : t("admissions.timeline.feePaid", {
            amount: rupees(typeof d.amountPaise === "number" ? d.amountPaise : null),
            method: methodLabel(t, str(d.method)),
          });
    case "OFFER_UPDATED":
      return d.validUntil
        ? t("admissions.timeline.offerUpdated", { date: formatPlainDate(str(d.validUntil), locale) })
        : t("admissions.timeline.offerUpdatedOpen");
    case "SLOT_SCHEDULED":
      return t("admissions.timeline.slotScheduled", { kind: kindLabel(t, str(d.kind)) });
    case "SLOT_RESCHEDULED":
      return t("admissions.timeline.slotRescheduled", { kind: kindLabel(t, str(d.kind)) });
    case "SLOT_OUTCOME":
      return t("admissions.timeline.slotOutcome", { kind: kindLabel(t, str(d.kind)) });
    case "SLOT_CANCELLED":
      return t("admissions.timeline.slotCancelled", { kind: kindLabel(t, str(d.kind)) });
    default:
      return entry.kind;
  }
}
