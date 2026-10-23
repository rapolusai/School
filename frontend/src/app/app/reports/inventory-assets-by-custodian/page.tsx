import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AssetsByCustodianReport } from "@/components/views/inventory/inventory-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Assets by custodian" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <AssetsByCustodianReport />
    </RequirePermission>
  );
}
