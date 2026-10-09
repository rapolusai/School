import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FeeSettingsView } from "@/components/views/fees/settings-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Fee settings" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.feesRead}>
      <FeeSettingsView />
    </RequirePermission>
  );
}
