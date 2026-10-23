import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { VerificationRoundsView } from "@/components/views/inventory/verification-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Asset verification" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.inventoryManage}>
      <VerificationRoundsView />
    </RequirePermission>
  );
}
