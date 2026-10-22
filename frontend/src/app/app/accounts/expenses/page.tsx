import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ExpensesView } from "@/components/views/accounts/expenses-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Expenses" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <ExpensesView />
    </RequirePermission>
  );
}
