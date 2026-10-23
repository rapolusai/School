import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RequisitionDetailView } from "@/components/views/inventory/requisition-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Requisition" };

export default async function Page({ params }: PageProps<"/app/inventory/requisitions/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <RequisitionDetailView key={id} id={id} />
    </RequirePermission>
  );
}
