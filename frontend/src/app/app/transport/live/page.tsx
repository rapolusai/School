import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LiveView } from "@/components/views/transport/live-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Buses today" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <LiveView />
    </RequirePermission>
  );
}
