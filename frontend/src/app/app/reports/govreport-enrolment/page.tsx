import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { GovEnrolmentReportView } from "@/components/views/reports/govreport-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Enrolment by category" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <GovEnrolmentReportView />
    </RequirePermission>
  );
}
