import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HealthAllergiesReportView } from "@/components/views/reports/health-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Allergies by class" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.healthRead}>
      <HealthAllergiesReportView />
    </RequirePermission>
  );
}
