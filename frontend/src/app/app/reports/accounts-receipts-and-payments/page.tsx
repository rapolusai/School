import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ReceiptsAndPaymentsReport } from "@/components/views/accounts/accounts-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Receipts and payments" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <ReceiptsAndPaymentsReport />
    </RequirePermission>
  );
}
