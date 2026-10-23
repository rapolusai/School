import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { GovSchemesReportView } from "@/components/views/reports/govreport-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Scheme enrolments" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <GovSchemesReportView />
    </RequirePermission>
  );
}
