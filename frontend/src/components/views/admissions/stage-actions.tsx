"use client";

import { useState } from "react";
import { useToast } from "@/components/ui/toast";
import { useI18n } from "@/lib/i18n";
import type { ApplicationDetail, ApplicationStage } from "@/lib/types";
import { stageLabel } from "./admission-labels";
import { AdmitDialog, MoveStageDialog, type ApplicationRef } from "./application-dialogs";
import { StageMenu } from "./stage-menu";

/**
 * The "Move" menu of one application with the dialogs it opens: a note (and the offer dates)
 * for a stage change, or the admission form for "Admit as student".
 */
export function StageActions({
  application,
  nextStages,
  onChanged,
  size = "sm",
}: {
  application: ApplicationRef;
  nextStages: ApplicationStage[];
  onChanged: (updated: ApplicationDetail) => void;
  size?: "sm" | "md";
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [to, setTo] = useState<ApplicationStage | null>(null);
  // The new student is shown in the dialog first; the board refreshes once it is closed.
  const [admitted, setAdmitted] = useState<ApplicationDetail | null>(null);

  return (
    <>
      <StageMenu childName={application.childName} nextStages={nextStages} onPick={setTo} size={size} />
      {to !== null && to !== "ADMITTED" ? (
        <MoveStageDialog
          application={application}
          to={to}
          onClose={() => setTo(null)}
          onMoved={(updated) => {
            setTo(null);
            toast(t("admissions.moved", { name: updated.childName, stage: stageLabel(t, updated.stage) }));
            onChanged(updated);
          }}
        />
      ) : null}
      {to === "ADMITTED" ? (
        <AdmitDialog
          open
          application={application}
          onClose={() => {
            setTo(null);
            if (admitted) {
              setAdmitted(null);
              onChanged(admitted);
            }
          }}
          onAdmitted={setAdmitted}
        />
      ) : null}
    </>
  );
}
