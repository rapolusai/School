import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ExpenseView } from "@/components/views/accounts/expense-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Expense" };

export default async function Page({ params }: PageProps<"/app/accounts/expenses/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <ExpenseView key={id} id={id} />
    </RequirePermission>
  );
}
