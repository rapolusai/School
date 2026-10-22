import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { VendorLedgerView } from "@/components/views/accounts/vendors-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Vendor ledger" };

export default async function Page({ params }: PageProps<"/app/accounts/vendors/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <VendorLedgerView key={id} id={id} />
    </RequirePermission>
  );
}
