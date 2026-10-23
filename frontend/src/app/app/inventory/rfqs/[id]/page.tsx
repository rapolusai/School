import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RfqDetailView } from "@/components/views/inventory/rfqs-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Quotation" };

export default async function Page({ params }: PageProps<"/app/inventory/rfqs/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryManage}>
      <RfqDetailView key={id} id={id} />
    </RequirePermission>
  );
}
