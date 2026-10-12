import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { BudgetVsActualReport } from "@/components/views/accounts/accounts-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Budget vs actual" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <BudgetVsActualReport />
    </RequirePermission>
  );
}
