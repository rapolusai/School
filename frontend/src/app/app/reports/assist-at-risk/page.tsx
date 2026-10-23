import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AtRiskReportView } from "@/components/views/assist/assist-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "At-risk students" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.assistUse}>
      <AtRiskReportView />
    </RequirePermission>
  );
}
