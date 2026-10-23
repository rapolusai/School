import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StockTakeDetailView } from "@/components/views/inventory/stock-takes-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Stock take" };

export default async function Page({ params }: PageProps<"/app/inventory/stock-takes/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryManage}>
      <StockTakeDetailView key={id} id={id} />
    </RequirePermission>
  );
}
