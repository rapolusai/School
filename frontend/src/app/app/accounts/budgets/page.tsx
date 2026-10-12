import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { BudgetsView } from "@/components/views/accounts/budgets-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Budgets" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <BudgetsView />
    </RequirePermission>
  );
}
