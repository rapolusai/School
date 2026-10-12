import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HeadWiseReport } from "@/components/views/accounts/accounts-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Head-wise expense" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <HeadWiseReport />
    </RequirePermission>
  );
}
