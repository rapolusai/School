import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { OrderDetailView } from "@/components/views/inventory/order-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Purchase order" };

export default async function Page({ params }: PageProps<"/app/inventory/purchase-orders/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <OrderDetailView key={id} id={id} />
    </RequirePermission>
  );
}
