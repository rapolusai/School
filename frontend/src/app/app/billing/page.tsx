import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SchoolBillingView } from "@/components/views/billing/school-billing-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Billing" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.billingRead}>
      <SchoolBillingView />
    </RequirePermission>
  );
}
