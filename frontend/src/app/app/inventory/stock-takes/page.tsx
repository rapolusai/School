import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StockTakesView } from "@/components/views/inventory/stock-takes-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Stock takes" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryManage}>
      <StockTakesView />
    </RequirePermission>
  );
}
