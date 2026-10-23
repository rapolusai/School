import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HealthVerificationsReportView } from "@/components/views/reports/health-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Verifications due" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.safetyManage}>
      <HealthVerificationsReportView />
    </RequirePermission>
  );
}
