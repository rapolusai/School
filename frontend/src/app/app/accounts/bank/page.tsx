import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { BankView } from "@/components/views/accounts/bank-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Bank and cash" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <BankView />
    </RequirePermission>
  );
}
