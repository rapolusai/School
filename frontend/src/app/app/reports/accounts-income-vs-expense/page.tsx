import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { IncomeVsExpenseReport } from "@/components/views/accounts/accounts-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Income vs expense" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <IncomeVsExpenseReport />
    </RequirePermission>
  );
}
