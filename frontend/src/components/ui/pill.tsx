export type PillTone = "good" | "warn" | "bad" | "info" | "neutral" | "accent";

export function Pill({
  tone = "neutral",
  dot = false,
  children,
  title,
}: {
  tone?: PillTone;
  dot?: boolean;
  children: React.ReactNode;
  title?: string;
}) {
  return (
    <span className={`pill pill-${tone}${dot ? " pill-dot" : ""}`} title={title}>
      {children}
    </span>
  );
}

const STATUS_TONES: Record<string, PillTone> = {
  ACTIVE: "good",
  TRIAL: "info",
  PAST_DUE: "warn",
  SUSPENDED: "bad",
  DISABLED: "neutral",
};

export function statusTone(status: string): PillTone {
  return STATUS_TONES[status] ?? "neutral";
}
