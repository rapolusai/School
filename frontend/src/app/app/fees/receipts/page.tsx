import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ReceiptsView } from "@/components/views/fees/receipts-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Receipts" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.feesRead}>
      <ReceiptsView />
    </RequirePermission>
  );
}
