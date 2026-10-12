import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { VendorsView } from "@/components/views/accounts/vendors-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Vendors" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <VendorsView />
    </RequirePermission>
  );
}
