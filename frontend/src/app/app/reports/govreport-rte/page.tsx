import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { GovRteReportView } from "@/components/views/reports/govreport-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "RTE seats and allotments" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <GovRteReportView />
    </RequirePermission>
  );
}
