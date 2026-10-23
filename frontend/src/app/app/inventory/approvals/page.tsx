import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { InventoryApprovalsView } from "@/components/views/inventory/approvals-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Purchase approvals" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.purchaseApprove}>
      <InventoryApprovalsView />
    </RequirePermission>
  );
}
