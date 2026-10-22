import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FrontOfficeEnquiriesReportView } from "@/components/views/reports/frontoffice-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Enquiries report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <FrontOfficeEnquiriesReportView />
    </RequirePermission>
  );
}
