import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyGovReportView } from "@/components/views/govreport/family-govreport";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Government details" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <FamilyGovReportView />
    </RequirePermission>
  );
}
