import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FrontOfficeVisitorsReportView } from "@/components/views/reports/frontoffice-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Visitors report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <FrontOfficeVisitorsReportView />
    </RequirePermission>
  );
}
