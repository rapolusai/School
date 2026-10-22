import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FrontOfficePassesReportView } from "@/components/views/reports/frontoffice-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Gate passes report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <FrontOfficePassesReportView />
    </RequirePermission>
  );
}
