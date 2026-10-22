import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FrontOfficeFollowUpsReportView } from "@/components/views/reports/frontoffice-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Follow-ups report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <FrontOfficeFollowUpsReportView />
    </RequirePermission>
  );
}
