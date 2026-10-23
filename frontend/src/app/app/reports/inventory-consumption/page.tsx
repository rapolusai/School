import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ConsumptionReport } from "@/components/views/inventory/inventory-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Consumption by department" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <ConsumptionReport />
    </RequirePermission>
  );
}
