import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PettyCashView } from "@/components/views/accounts/petty-cash-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Petty cash" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <PettyCashView />
    </RequirePermission>
  );
}
