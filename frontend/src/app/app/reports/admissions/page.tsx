import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FunnelReportView } from "@/components/views/reports/funnel-report";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Admissions funnel" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.admissionsRead}>
      <FunnelReportView />
    </RequirePermission>
  );
}
