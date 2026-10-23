import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AssetRegisterReport } from "@/components/views/inventory/inventory-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Asset register" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <AssetRegisterReport />
    </RequirePermission>
  );
}
