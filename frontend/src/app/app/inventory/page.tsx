import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { InventoryOverview } from "@/components/views/inventory/overview-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Inventory" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <InventoryOverview />
    </RequirePermission>
  );
}
