import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { TransportSettingsView } from "@/components/views/transport/settings-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Transport settings" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <TransportSettingsView />
    </RequirePermission>
  );
}
