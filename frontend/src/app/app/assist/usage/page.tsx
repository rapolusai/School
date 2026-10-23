import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AssistUsageView } from "@/components/views/assist/usage-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Assistance usage" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.assistManage}>
      <AssistUsageView />
    </RequirePermission>
  );
}
