import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { InventorySetupView } from "@/components/views/inventory/setup-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Inventory setup" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryManage}>
      <InventorySetupView />
    </RequirePermission>
  );
}
