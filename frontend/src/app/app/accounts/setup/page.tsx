import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SetupView } from "@/components/views/accounts/setup-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Accounts setup" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <SetupView />
    </RequirePermission>
  );
}
