import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ApprovalsView } from "@/components/views/accounts/approvals-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Expense approvals" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsApprove}>
      <ApprovalsView />
    </RequirePermission>
  );
}
