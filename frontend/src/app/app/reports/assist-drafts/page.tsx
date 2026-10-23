import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { DraftsAcceptedReportView } from "@/components/views/assist/assist-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Drafts accepted" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.assistUse}>
      <DraftsAcceptedReportView />
    </RequirePermission>
  );
}
