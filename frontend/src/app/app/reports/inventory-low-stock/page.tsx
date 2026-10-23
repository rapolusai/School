import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LowStockReport } from "@/components/views/inventory/inventory-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Low stock" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <LowStockReport />
    </RequirePermission>
  );
}
