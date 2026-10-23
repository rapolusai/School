import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AssistSettingsView } from "@/components/views/assist/settings-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Assistance settings" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.assistManage}>
      <AssistSettingsView />
    </RequirePermission>
  );
}
