import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { IncomeView } from "@/components/views/accounts/income-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Other income" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <IncomeView />
    </RequirePermission>
  );
}
