import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AccountsOverview } from "@/components/views/accounts/overview-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Accounts" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <AccountsOverview />
    </RequirePermission>
  );
}
