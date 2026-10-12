import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { GstSummaryReport } from "@/components/views/accounts/accounts-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "GST summary" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <GstSummaryReport />
    </RequirePermission>
  );
}
