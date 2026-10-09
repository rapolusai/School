import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RenewalsView } from "@/components/views/billing/renewals-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Plans & billing" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.platformAdmin}>
      <RenewalsView />
    </RequirePermission>
  );
}
