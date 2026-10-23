import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HealthDrillsReportView } from "@/components/views/reports/health-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Drills report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.safetyManage}>
      <HealthDrillsReportView />
    </RequirePermission>
  );
}
