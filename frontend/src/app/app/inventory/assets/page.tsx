import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AssetsView } from "@/components/views/inventory/assets-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Assets" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <AssetsView />
    </RequirePermission>
  );
}
