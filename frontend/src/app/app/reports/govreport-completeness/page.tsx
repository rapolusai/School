import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { GovCompletenessReportView } from "@/components/views/reports/govreport-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Government fields completeness" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <GovCompletenessReportView />
    </RequirePermission>
  );
}
