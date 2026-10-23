import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HealthInfirmaryReportView } from "@/components/views/reports/health-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Infirmary visits report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.healthRead}>
      <HealthInfirmaryReportView />
    </RequirePermission>
  );
}
