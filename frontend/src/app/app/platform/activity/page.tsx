import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ActivityView } from "@/components/views/billing/activity-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Activity" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.platformAdmin}>
      <ActivityView />
    </RequirePermission>
  );
}
