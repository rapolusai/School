import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { DashboardView } from "@/components/views/dashboard-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Dashboard" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <DashboardView />
    </RequirePermission>
  );
}
