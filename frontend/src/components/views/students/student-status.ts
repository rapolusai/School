import type { PillTone } from "@/components/ui/pill";
import type { StudentStatus } from "@/lib/types";

const TONES: Record<StudentStatus, PillTone> = {
  ACTIVE: "good",
  TRANSFERRED: "info",
  WITHDRAWN: "neutral",
  ALUMNI: "accent",
};

export function studentStatusTone(status: StudentStatus): PillTone {
  return TONES[status] ?? "neutral";
}
