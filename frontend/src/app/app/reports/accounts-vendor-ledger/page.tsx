import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { VendorLedgerReport } from "@/components/views/accounts/accounts-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Vendor ledger" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <VendorLedgerReport />
    </RequirePermission>
  );
}
