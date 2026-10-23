import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RemarksView } from "@/components/views/assist/remarks-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Remark drafts" };

/** Report card remark drafts (docs/api/phase-3-assist.md): simulated drafts a teacher edits and accepts. */
export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.assistUse}>
      <RemarksView />
    </RequirePermission>
  );
}
