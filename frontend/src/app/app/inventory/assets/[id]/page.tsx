import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AssetDetailView } from "@/components/views/inventory/asset-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Asset" };

export default async function Page({ params }: PageProps<"/app/inventory/assets/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <AssetDetailView key={id} id={id} />
    </RequirePermission>
  );
}
