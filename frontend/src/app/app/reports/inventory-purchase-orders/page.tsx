import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PurchaseOrdersReport } from "@/components/views/inventory/inventory-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Purchase orders by status and vendor" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <PurchaseOrdersReport />
    </RequirePermission>
  );
}
