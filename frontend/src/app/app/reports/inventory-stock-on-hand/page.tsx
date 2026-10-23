import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StockOnHandReport } from "@/components/views/inventory/inventory-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Stock on hand" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <StockOnHandReport />
    </RequirePermission>
  );
}
